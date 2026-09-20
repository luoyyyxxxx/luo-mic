"""luo mic 电脑端核心（Python 实现）。

与 Java 版、Android 端完全同协议（见 docs/PROTOCOL.md）：

* UDP 47777：广播在线宣告 + 回应手机探测
* TCP 47778：控制通道（行分隔 JSON：HELLO / START / STOP / PING / PONG / STAT）
* TCP 47779：音频通道（4 字节大端长度前缀 + 16bit 小端单声道 PCM）

依赖：``pip install sounddevice``（Windows 上 wheel 自带 PortAudio）。
没装 sounddevice 也能跑，只是不出声，便于先排查网络/防火墙问题。
"""

from __future__ import annotations

import base64
import json
import math
import socket
import threading
import time
from typing import Callable, List, Optional

# ---------------------------------------------------------------- 协议常量

DISCOVERY_PORT = 47777
CONTROL_PORT = 47778
AUDIO_PORT = 47779

MAGIC = "LUOMIC/1"
MSG_DISCOVER = MAGIC + " DISCOVER"
MSG_PROBE = MAGIC + " PROBE"
MSG_HERE_PREFIX = MAGIC + " HERE "

PROTOCOL_VERSION = 1
CODEC = "pcm_s16le"

ANNOUNCE_INTERVAL = 2.0
PING_INTERVAL = 2.0
CLIENT_TIMEOUT = 12.0
MAX_FRAME_BYTES = 8192
AUDIO_HANDSHAKE_LEN = 32
MAX_LINE_BYTES = 4096

INPUT_SAMPLE_RATE = 48000
FRAME_MS = 20

__all__ = [
    "DISCOVERY_PORT", "CONTROL_PORT", "AUDIO_PORT", "MAGIC", "MSG_DISCOVER", "MSG_PROBE",
    "MSG_HERE_PREFIX", "PROTOCOL_VERSION", "CODEC", "INPUT_SAMPLE_RATE", "FRAME_MS",
    "AudioOutput", "Server", "Session", "SocketReader", "host_name", "local_ipv4_list",
    "broadcast_targets", "compute_db", "looks_virtual",
]


def host_name() -> str:
    """电脑名（与 Java 版展示形式一致）。"""
    try:
        return socket.gethostname() + " 的电脑"
    except Exception:  # pragma: no cover
        return "电脑"


def local_ipv4_list() -> List[str]:
    """本机所有非回环 IPv4 地址。"""
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except Exception:
        pass
    try:  # 兜底：看本机默认出口 IP（UDP connect 不会真的发包）
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 53))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


def broadcast_targets() -> List[str]:
    """广播地址：受限广播 + 各网段的定向广播。"""
    targets = ["255.255.255.255"]
    for ip in local_ipv4_list():
        if ip.count(".") == 3:
            targets.append(ip.rsplit(".", 1)[0] + ".255")
    return targets


def compute_db(pcm: bytes) -> float:
    """峰值电平（dBFS，-120 表示静音）。"""
    peak = 0
    for i in range(0, len(pcm) - 1, 2):
        v = int.from_bytes(pcm[i:i + 2], "little", signed=True)
        a = -v if v < 0 else v
        if a > peak:
            peak = a
    if peak <= 0:
        return -120.0
    return max(-120.0, 20.0 * math.log10(peak / 32768.0))


def looks_virtual(name: str) -> bool:
    """设备名看起来像虚拟声卡。"""
    n = (name or "").lower()
    return any(k in n for k in ("cable", "virtual", "vb-audio", "voicemeeter", "loopback", "虚拟"))


# ---------------------------------------------------------------- socket 工具


class SocketReader:
    """给带超时的 socket 做缓冲读取。

    ``socket.makefile()`` 一旦超时就会抛 "cannot read from timed out object" 并永久失效，
    所以这里自己维护缓冲区：超时（``TimeoutError``）时保留半包，下次继续读。
    """

    def __init__(self, sock: socket.socket) -> None:
        self.sock = sock
        self.buf = bytearray()

    def read_exact(self, n: int) -> Optional[bytes]:
        """读满 n 字节；对端关闭返回 None。"""
        while len(self.buf) < n:
            try:
                chunk = self.sock.recv(max(4096, n - len(self.buf)))
            except (socket.timeout, TimeoutError):
                continue
            except OSError:
                return None
            if not chunk:
                return None  # 对端已关闭
            self.buf.extend(chunk)
        out = bytes(self.buf[:n])
        del self.buf[:n]
        return out

    def read_line(self) -> Optional[str]:
        """读一行（不含换行）；超时返回空串，对端关闭返回 None。"""
        while True:
            idx = self.buf.find(b"\n")
            if idx >= 0:
                line = bytes(self.buf[:idx]).decode("utf-8", "replace").strip()
                del self.buf[:idx + 1]
                return line
            if len(self.buf) > MAX_LINE_BYTES:
                raise RuntimeError("控制报文过长")
            try:
                chunk = self.sock.recv(4096)
            except (socket.timeout, TimeoutError):
                return ""
            except OSError:
                return None
            if not chunk:
                return None
            self.buf.extend(chunk)


# ---------------------------------------------------------------- 音频输出


class AudioOutput:
    """把 PCM 写进指定播放设备；没有 sounddevice 时静默丢弃（仍统计）。"""

    def __init__(self, log: Callable[[str], None]) -> None:
        self.log = log
        self._stream = None
        self._sd = None
        self._lock = threading.Lock()
        self._buf = bytearray()
        self._target = 0
        self._underruns = 0
        self._played = 0
        self._level_db = -120.0
        self._last_frame_at = 0.0
        self.device_name = "未打开"
        self.sample_rate = INPUT_SAMPLE_RATE
        try:
            import sounddevice as sd  # type: ignore

            self._sd = sd
        except Exception as exc:  # pragma: no cover
            self.log(f"未安装 sounddevice（{exc}），将不播放声音（仅测试网络）。")
            self.log("安装命令：pip install sounddevice")

    # -------------------------------------------------- 设备

    def list_devices(self) -> List[dict]:
        """可用的输出设备列表。"""
        if self._sd is None:
            return []
        out: List[dict] = []
        try:
            for idx, dev in enumerate(self._sd.query_devices()):
                if dev.get("max_output_channels", 0) < 1:
                    continue
                name = str(dev.get("name", f"设备 {idx}"))
                out.append({
                    "index": idx,
                    "name": name,
                    "samplerate": int(dev.get("default_samplerate", 48000)),
                    "virtual": looks_virtual(name),
                })
        except Exception as exc:  # pragma: no cover
            self.log(f"枚举音频设备失败：{exc}")
        return out

    @staticmethod
    def default_device_name() -> str:
        try:
            import sounddevice as sd  # type: ignore

            return str(sd.query_devices(kind="output").get("name", "系统默认设备"))
        except Exception:
            return "系统默认设备"

    # -------------------------------------------------- 播放

    def start(self, device_index: Optional[int] = None, sample_rate: int = INPUT_SAMPLE_RATE,
              device_name: Optional[str] = None) -> bool:
        """打开输出设备，返回是否真的出声。"""
        self.stop()
        self.sample_rate = sample_rate
        frame_bytes = sample_rate * FRAME_MS // 1000 * 2
        self._target = frame_bytes * 3  # 目标缓冲 ≈60ms
        self.device_name = device_name or (
            self.default_device_name() if device_index is None else f"设备 #{device_index}")
        if self._sd is None:
            return False
        try:
            self._stream = self._sd.RawOutputStream(
                samplerate=sample_rate,
                blocksize=frame_bytes // 2,
                channels=1,
                dtype="int16",
                device=device_index,
                callback=self._callback,
            )
            self._stream.start()
            if device_index is not None:
                try:
                    self.device_name = str(self._sd.query_devices(device_index)["name"])
                except Exception:
                    pass
            self.log(f"音频输出已打开：{self.device_name}（{sample_rate}Hz）")
            return True
        except Exception as exc:
            self._stream = None
            self.log(f"打开音频设备失败：{exc}")
            return False

    def _callback(self, outdata, frames, time_info, status) -> None:  # pragma: no cover
        need = frames * 2
        with self._lock:
            take = min(need, len(self._buf))
            if take:
                outdata[:take] = bytes(self._buf[:take])
                del self._buf[:take]
                self._played += 1
            else:
                self._underruns += 1
            if take < need:
                outdata[take:need] = b"\x00" * (need - take)

    def push(self, pcm: bytes) -> None:
        """收到一帧 PCM。"""
        self._last_frame_at = time.time()
        self._level_db = compute_db(pcm)
        with self._lock:
            self._buf.extend(pcm)
            limit = self._target * 4  # 最多积压 ~240ms，避免延迟越滚越大
            if len(self._buf) > limit:
                del self._buf[: len(self._buf) - self._target]

    def stop(self) -> None:
        stream, self._stream = self._stream, None
        if stream is not None:
            try:
                stream.stop()
                stream.close()
            except Exception:
                pass
        with self._lock:
            self._buf.clear()
        self._level_db = -120.0
        self._last_frame_at = 0.0

    # -------------------------------------------------- 状态

    @property
    def is_running(self) -> bool:
        return self._stream is not None

    @property
    def level_db(self) -> float:
        return self._level_db

    @property
    def last_frame_at(self) -> float:
        return self._last_frame_at

    def stats(self) -> dict:
        with self._lock:
            buffered = len(self._buf)
        return {
            "played": self._played,
            "underruns": self._underruns,
            "buffered_ms": buffered // 2 * 1000 // max(1, self.sample_rate),
        }


# ---------------------------------------------------------------- 一次会话


class Session:
    """一台手机的会话：控制通道 + 音频通道。"""

    def __init__(self, server: "Server", conn: socket.socket) -> None:
        self.server = server
        self.control = conn
        self.reader = SocketReader(conn)
        self.audio: Optional[socket.socket] = None
        self.audio_reader: Optional[SocketReader] = None
        self.alive = True
        self.streaming = False
        self.last_recv = time.time()
        self.phone_name = "手机"
        self.phone_ip = conn.getpeername()[0]
        self.rtt_ms = -1
        self.phone_kbps = 0
        self.phone_fps = 0
        self.audio_bytes = 0
        self._last_audio_bytes = 0
        self._last_stat = time.time()

    # -------------------------------------------------- 主流程

    def run(self) -> None:
        reason = "手机断开"
        try:
            self.control.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.control.settimeout(0.5)
            self.send_hello()
            if self.server.auto_start:
                self.start_streaming()
            else:
                self.server.log("当前为“暂停接收”，手机已连接但不会采集麦克风。")
            self.wait_for_audio()
            self.event_loop()
        except Exception as exc:
            reason = str(exc) or exc.__class__.__name__
        finally:
            self.alive = False
            self.streaming = False
            self.server.player.stop()
            self.close()
            self.server.on_session_end(self, reason)

    def send_hello(self) -> None:
        self.send({
            "t": "HELLO",
            "v": PROTOCOL_VERSION,
            "name": host_name(),
            "audio_port": AUDIO_PORT,
            "codec": CODEC,
            "rate": INPUT_SAMPLE_RATE,
            "channels": 1,
            "frame_ms": FRAME_MS,
        })

    def start_streaming(self) -> None:
        """打开音频输出并让手机开始推流（幂等）。"""
        if self.streaming and self.server.player.is_running:
            return
        self.streaming = True
        self.server.player.start(self.server.device_index, INPUT_SAMPLE_RATE, self.server.device_name)
        self.send({"t": "START"})
        self.server.log("已请求手机开始推流。")

    def stop_streaming(self, reason: str) -> None:
        """停流但保持控制连接。"""
        if not self.streaming:
            return
        self.streaming = False
        try:
            self.send({"t": "STOP"})
        except OSError:
            pass
        self.server.player.stop()
        self.server.log(f"已停止接收（{reason}）。")

    def wait_for_audio(self) -> None:
        deadline = time.time() + 10
        while self.audio is None and self.alive and time.time() < deadline:
            time.sleep(0.05)
        if self.audio is None:
            raise RuntimeError("手机没有建立音频通道（超时 10 秒）")

    def event_loop(self) -> None:
        last_ping = 0.0
        while self.alive and self.server.running:
            line = self.reader.read_line()
            if line is None:
                raise RuntimeError("手机关闭了控制连接")
            if line:
                self.last_recv = time.time()
                self.handle(line)
            now = time.time()
            if now - self.last_recv > CLIENT_TIMEOUT:
                raise RuntimeError(f"与手机的心跳超时（{int(CLIENT_TIMEOUT)} 秒无响应）")
            if now - last_ping >= PING_INTERVAL:
                last_ping = now
                self.send({"t": "PING", "ts": int(now * 1000)})
            self.report_stats(now)

    def handle(self, msg: str) -> None:
        try:
            data = json.loads(msg)
        except ValueError:
            self.server.log(f"收到无法解析的控制报文：{msg[:120]}")
            return
        t = data.get("t")
        if t == "HELLO":
            self.phone_name = str(data.get("device") or self.phone_name)
            self.server.log(f"手机已连接：{self.phone_name} @ {self.phone_ip}"
                            f"（Android {data.get('android')}）")
            self.server.on_session_start(self)
        elif t == "PONG":
            ts = int(data.get("ts") or 0)
            if ts:
                self.rtt_ms = max(0, min(9999, int(time.time() * 1000) - ts))
        elif t == "STAT":
            self.phone_fps = int(data.get("fps") or 0)
            self.phone_kbps = int(data.get("kbps") or 0)
        elif t == "BYE":
            self.server.log("手机主动断开连接")
            self.alive = False

    def report_stats(self, now: float) -> None:
        if now - self._last_stat < 1.0:
            return
        secs = max(0.001, now - self._last_stat)
        delta = self.audio_bytes - self._last_audio_bytes
        self._last_stat = now
        self._last_audio_bytes = self.audio_bytes
        kbps = int(round(delta * 8 / 1000.0 / secs))
        receiving = self.streaming and (now - self.server.player.last_frame_at) < 1.5
        st = self.server.player.stats()
        if not self.streaming:
            state_text = "已暂停接收"
        elif receiving:
            state_text = "接收中"
        elif kbps > 0:
            state_text = "收到数据（未出声）"
        else:
            state_text = "等待数据"
        text = (f"{state_text} | 手机 {self.phone_name} | {kbps} kbps"
                f" | {self.phone_fps} fps | 延迟 {self.rtt_ms if self.rtt_ms >= 0 else '—'}ms"
                f" | 播放 {st['played']} 帧 | 补静音 {st['underruns']} | 缓冲 {st['buffered_ms']}ms")
        self.server.on_stats(text, self.server.player.level_db if receiving else -120.0, receiving)

    # -------------------------------------------------- 音频通道

    def attach_audio(self, sock: socket.socket) -> None:
        """手机连上音频端口。"""
        if not self.alive:
            try:
                sock.close()
            except OSError:
                pass
            return
        old = self.audio
        if old is not None:
            try:
                old.close()
            except OSError:
                pass
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.settimeout(0.5)
        self.audio = sock
        self.audio_reader = SocketReader(sock)
        threading.Thread(target=self.audio_loop, args=(sock,), daemon=True).start()

    def audio_loop(self, sock: socket.socket) -> None:
        reader = self.audio_reader
        try:
            if reader is None:
                return
            handshake = reader.read_exact(AUDIO_HANDSHAKE_LEN)
            if handshake is None:
                raise RuntimeError("音频握手数据不完整")
            rate = int.from_bytes(handshake[12:16], "big")
            channels = int.from_bytes(handshake[16:20], "big")
            frame_ms = int.from_bytes(handshake[20:24], "big")
            self.server.log(f"音频握手：rate={rate} channels={channels} frame_ms={frame_ms}")

            while self.alive and self.server.running:
                header = reader.read_exact(4)
                if header is None:
                    break
                length = int.from_bytes(header, "big")
                if length <= 0 or length > MAX_FRAME_BYTES:
                    raise RuntimeError(f"音频帧长度非法：{length}")
                pcm = reader.read_exact(length)
                if pcm is None:
                    break
                self.audio_bytes += len(pcm)
                self.last_recv = time.time()
                if self.streaming and self.server.player.is_running:
                    self.server.player.push(pcm)
            self.server.log("音频通道已关闭")
        except Exception as exc:
            if self.alive and self.server.running:
                self.server.log(f"音频通道异常：{exc}")
                self.server.close_session("音频通道中断")

    # -------------------------------------------------- 工具

    def send(self, obj: dict) -> None:
        self.control.sendall((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))

    def close(self) -> None:
        for sock in (self.audio, self.control):
            try:
                if sock is not None:
                    sock.close()
            except OSError:
                pass
        self.audio = None


# ---------------------------------------------------------------- 服务端


class Server:
    """发现 + 控制 + 音频三个监听器；同一时间只服务一台手机。"""

    def __init__(self, log: Callable[[str], None],
                 on_session: Callable[[bool, str, str], None],
                 on_stats: Callable[[str, float, bool], None],
                 player: AudioOutput) -> None:
        self.log = log
        self.on_session = on_session
        self.on_stats = on_stats
        self.player = player
        self.running = False
        self.auto_start = True
        self.device_index: Optional[int] = None
        self.device_name: Optional[str] = None
        self._session: Optional[Session] = None
        self._lock = threading.Lock()
        self._udp: Optional[socket.socket] = None
        self._ctrl_sock: Optional[socket.socket] = None
        self._audio_sock: Optional[socket.socket] = None
        self._threads: List[threading.Thread] = []

    # -------------------------------------------------- 生命周期

    def start(self) -> None:
        if self.running:
            return
        self.running = True
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        udp.bind(("0.0.0.0", DISCOVERY_PORT))
        udp.settimeout(0.5)

        ctrl = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        ctrl.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        ctrl.bind(("0.0.0.0", CONTROL_PORT))
        ctrl.listen(4)
        ctrl.settimeout(0.5)

        audio = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        audio.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        audio.bind(("0.0.0.0", AUDIO_PORT))
        audio.listen(4)
        audio.settimeout(0.5)

        self._udp, self._ctrl_sock, self._audio_sock = udp, ctrl, audio
        self._spawn(self._announce_loop)
        self._spawn(lambda: self._accept_loop(ctrl, True))
        self._spawn(lambda: self._accept_loop(audio, False))

        self.log(f"服务已启动：发现 UDP {DISCOVERY_PORT}，控制 TCP {CONTROL_PORT}，音频 TCP {AUDIO_PORT}")
        self.log("本机局域网地址：" + "，".join(local_ipv4_list()))

    def stop(self) -> None:
        if not self.running:
            return
        self.running = False
        self.close_session("服务停止")
        for sock in (self._udp, self._ctrl_sock, self._audio_sock):
            try:
                if sock is not None:
                    sock.close()
            except OSError:
                pass
        self._udp = self._ctrl_sock = self._audio_sock = None
        self.player.stop()
        self.log("服务已停止")

    def _spawn(self, target) -> None:
        t = threading.Thread(target=target, daemon=True)
        t.start()
        self._threads.append(t)

    # -------------------------------------------------- 发现

    def _announce_loop(self) -> None:
        payload = (MSG_DISCOVER + "\n").encode("ascii")
        name = base64.b64encode(host_name().encode("utf-8")).decode("ascii").rstrip("=")
        reply = f"{MSG_HERE_PREFIX}{name} {CONTROL_PORT} {AUDIO_PORT}\n".encode("ascii")
        last = 0.0
        while self.running:
            udp = self._udp
            if udp is None:
                break
            try:
                now = time.time()
                if now - last >= ANNOUNCE_INTERVAL:
                    last = now
                    for target in broadcast_targets():
                        try:
                            udp.sendto(payload, (target, DISCOVERY_PORT))
                        except OSError:
                            pass
                try:
                    data, addr = udp.recvfrom(512)
                except (socket.timeout, TimeoutError):
                    continue
                except OSError:
                    break
                if data.decode("ascii", "replace").strip() == MSG_PROBE:
                    udp.sendto(reply, (addr[0], DISCOVERY_PORT))
                    self.log(f"收到 {addr[0]} 的探测，已回应")
            except OSError as exc:
                if self.running:
                    self.log(f"发现服务异常：{exc}")
                    time.sleep(0.5)

    # -------------------------------------------------- 接受连接

    def _accept_loop(self, sock: socket.socket, control: bool) -> None:
        while self.running:
            try:
                conn, addr = sock.accept()
            except (socket.timeout, TimeoutError):
                continue
            except OSError:
                break
            if control:
                with self._lock:
                    old = self._session
                    if old is not None:
                        old.alive = False
                        old.close()
                    session = Session(self, conn)
                    self._session = session
                self.log(f"手机接入：{addr[0]}")
                threading.Thread(target=session.run, daemon=True).start()
            else:
                session = self._session
                if session is None or not session.alive:
                    self.log("收到无主的音频连接，已忽略")
                    try:
                        conn.close()
                    except OSError:
                        pass
                else:
                    session.attach_audio(conn)

    # -------------------------------------------------- 会话回调

    def on_session_start(self, session: Session) -> None:
        self.on_session(True, session.phone_name, session.phone_ip)

    def on_session_end(self, session: Session, reason: str) -> None:
        with self._lock:
            if self._session is session:
                self._session = None
                self.log(f"会话结束：{reason}")
        self.on_session(False, "", "")
        self.on_stats("已断开", -120.0, False)

    def close_session(self, reason: str) -> None:
        with self._lock:
            session, self._session = self._session, None
        if session is not None:
            session.alive = False
            session.close()
            self.log(f"关闭会话：{reason}")
            self.player.stop()
            self.on_session(False, "", "")

    def set_auto_start(self, value: bool) -> None:
        """界面上的“开始 / 暂停接收”。"""
        self.auto_start = value
        session = self._session
        if session is not None and session.alive:
            if value:
                session.start_streaming()
            else:
                session.stop_streaming("用户暂停接收")

    @property
    def has_client(self) -> bool:
        s = self._session
        return s is not None and s.alive
