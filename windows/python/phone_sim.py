"""假手机模拟器（Python 版）：用同一套协议验证电脑端是否正常。

用法：
    python phone_sim.py                            # 自动发现电脑并推流 20 秒
    python phone_sim.py --host 127.0.0.1           # 直接连指定电脑（跳过发现）
    python phone_sim.py --seconds 8 --rate 16000   # 指定时长与采样率

它和 Android 端行为一致：控制通道 → HELLO 握手 → 音频通道 → 按 START/STOP 推流，
可用于在没有手机时验证电脑端、排查局域网/防火墙问题。
"""

from __future__ import annotations

import argparse
import json
import math
import socket
import struct
import sys
import time

from luomic import (AUDIO_PORT, CONTROL_PORT, DISCOVERY_PORT, FRAME_MS, MAGIC,
                    MSG_HERE_PREFIX, MSG_PROBE, PROTOCOL_VERSION, INPUT_SAMPLE_RATE,
                    SocketReader)


def log(msg: str) -> None:
    print(msg, flush=True)


def discover(target: str | None = None, timeout: float = 8.0) -> str | None:
    """绑 47777 端口（与真实手机一致），先等电脑广播，再主动发 PROBE。"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind(("0.0.0.0", DISCOVERY_PORT))
    except OSError as exc:
        log(f"[探测] 无法绑定 {DISCOVERY_PORT} 端口：{exc}")
        return None
    sock.settimeout(2.0)
    dest = target or "255.255.255.255"
    deadline = time.time() + timeout
    try:
        while time.time() < deadline:
            sock.sendto((MSG_PROBE + "\n").encode("ascii"), (dest, DISCOVERY_PORT))
            log(f"[探测] 已发送 PROBE 到 {dest}:{DISCOVERY_PORT}")
            try:
                data, addr = sock.recvfrom(512)
            except (socket.timeout, TimeoutError):
                continue
            msg = data.decode("ascii", "replace").strip()
            log(f"[探测] 收到 {msg}")
            if msg.startswith(MSG_HERE_PREFIX) or msg == MAGIC + " DISCOVER":
                return addr[0]
    finally:
        sock.close()
    return None


def run(host: str, seconds: float, rate: int = INPUT_SAMPLE_RATE) -> int:
    """连上电脑并推正弦波；返回 0 表示成功。"""
    ctrl = socket.create_connection((host, CONTROL_PORT), timeout=5)
    ctrl.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    ctrl.settimeout(0.6)
    reader = SocketReader(ctrl)
    log("[连接] 控制通道已建立")

    hello = (reader.read_line() or "")
    log(f"[收到] {hello}")
    if '"HELLO"' not in hello:
        log("[失败] 首包不是 HELLO")
        return 1
    info = parse(hello)
    rate = int(info.get("rate", rate))
    frame_ms = int(info.get("frame_ms", FRAME_MS))
    audio_port = int(info.get("audio_port", AUDIO_PORT))

    def send(obj: dict) -> None:
        ctrl.sendall((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))

    send({"t": "HELLO", "v": PROTOCOL_VERSION, "device": "PhoneSim(Python)",
          "android": 34, "codec": "pcm_s16le", "rate": rate, "channels": 1,
          "frame_ms": frame_ms})

    audio = socket.create_connection((host, audio_port), timeout=5)
    audio.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    handshake = bytearray(32)
    struct.pack_into(">i", handshake, 12, rate)
    struct.pack_into(">i", handshake, 16, 1)
    struct.pack_into(">i", handshake, 20, frame_ms)
    audio.sendall(bytes(handshake))
    log(f"[连接] 音频通道已建立（{rate}Hz / {frame_ms}ms）")

    # 等 START，中途回 PONG
    started = False
    wait_until = time.time() + 8
    while time.time() < wait_until and not started:
        line = reader.read_line()
        if line is None:
            log("[失败] 电脑关闭了控制连接")
            return 1
        if not line:
            continue
        log(f"[收到] {line}")
        if '"PING"' in line:
            send({"t": "PONG", "ts": int(parse(line).get("ts", 0))})
        if '"START"' in line:
            started = True
    if not started:
        log("[失败] 8 秒内没有收到 START")
        return 1

    frame_bytes = rate * frame_ms // 1000 * 2
    samples = frame_bytes // 2
    step = 2 * math.pi * 440.0 / rate
    phase = 0.0
    pcm = bytearray(frame_bytes)
    frames = 0
    deadline = time.time() + seconds
    stat_at = time.time()
    stat_frames = 0
    log(f"[推流] 开始推送 440Hz 正弦波 {seconds} 秒…")
    ctrl.settimeout(0.005)
    start = time.time()

    while time.time() < deadline:
        for s in range(samples):
            v = int(math.sin(phase) * 12000)
            phase += step
            pcm[s * 2:s * 2 + 2] = struct.pack("<h", v)
        try:
            audio.sendall(struct.pack(">I", frame_bytes) + pcm)
        except OSError as exc:
            log(f"[失败] 音频通道写入失败：{exc}")
            break
        frames += 1

        while True:
            line = reader.read_line()
            if line is None:
                log("[提示] 电脑关闭了控制连接")
                deadline = 0
                break
            if not line:
                break  # 本轮没有新报文（超时）
            log(f"[收到] {line}")
            if '"PING"' in line:
                send({"t": "PONG", "ts": int(parse(line).get("ts", 0))})
            if '"STOP"' in line:
                log("[提示] 电脑要求停止推流")
                deadline = 0

        now = time.time()
        if now - stat_at >= 2.0:
            secs = max(0.001, now - stat_at)
            send({"t": "STAT", "fps": int(round((frames - stat_frames) / secs)),
                  "kbps": int(round(rate * 16 / 1000)), "db": -18})
            stat_at, stat_frames = now, frames

        # 按实时节奏发送
        next_at = start + frames * frame_ms / 1000.0
        sleep = next_at - time.time()
        if sleep > 0:
            time.sleep(sleep)

    elapsed = max(0.001, time.time() - start)
    log(f"[完成] 共推送 {frames} 帧 / {frames * frame_bytes // 1024} KB"
        f"，实测 {frames / elapsed:.1f} 帧/秒（理论 {1000 / frame_ms:.0f}）")
    try:
        send({"t": "BYE"})
    except OSError:
        pass
    time.sleep(0.2)
    for closer in (audio, ctrl):
        try:
            closer.close()
        except OSError:
            pass
    return 0 if frames > seconds * 20 else 1


def parse(json_text: str) -> dict:
    try:
        return json.loads(json_text)
    except ValueError:
        return {}


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="luo mic 假手机模拟器（Python）")
    ap.add_argument("--host", help="电脑 IP，省略则自动发现")
    ap.add_argument("--probe-host", help="只在指定地址发探测（本机自测用）")
    ap.add_argument("--seconds", type=float, default=20)
    ap.add_argument("--rate", type=int, default=INPUT_SAMPLE_RATE)
    args = ap.parse_args(argv)

    host = args.host
    if not host:
        host = discover(args.probe_host)
        if not host:
            log("[失败] 没有发现电脑。请确认电脑端 luo mic 已启动服务。")
            return 1
        log(f"[发现] 电脑地址 {host}")
    return run(host, args.seconds, args.rate)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
