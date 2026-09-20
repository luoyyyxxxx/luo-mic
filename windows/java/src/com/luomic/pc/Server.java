package com.luomic.pc;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * luo mic 电脑端服务：
 * <ul>
 *   <li>UDP 47777：每 2 秒广播在线宣告（手机被动即可发现），并回应手机的主动探测；</li>
 *   <li>TCP 47778：控制通道（HELLO / START / STOP / PING / PONG / STAT）；</li>
 *   <li>TCP 47779：音频通道（长度前缀 + PCM）。</li>
 * </ul>
 * 同一时间只服务一台手机，新连接会顶掉旧连接（方便手机侧无感重连）。
 */
public class Server {

    /** 界面回调。 */
    public interface Listener {
        /** 日志。 */
        void onLog(String line);

        /** 是否已有手机连接。 */
        void onSession(boolean connected, String phoneName, String phoneIp);

        /** 每秒一次的运行状态。 */
        void onStats(String text, float levelDb, boolean streaming);

        /** 错误（界面可以弹提示）。 */
        void onError(String message);
    }

    private final Listener listener;
    private final AudioPlayer player;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Mixer.Info selectedMixer;
    private volatile String selectedName = "系统默认设备";
    private volatile boolean autoStart = true;

    private DatagramSocket discoverySocket;
    private ServerSocket controlServer;
    private ServerSocket audioServer;
    private Thread announceThread;
    private Thread controlAcceptThread;
    private Thread audioAcceptThread;
    private final Object sessionLock = new Object();
    private Session current;

    public Server(Listener listener, AudioPlayer player) {
        this.listener = listener;
        this.player = player;
    }

    /** 设置音频输出设备。 */
    public void setDevice(Mixer.Info mixer, String name) {
        this.selectedMixer = mixer;
        this.selectedName = name == null ? "系统默认设备" : name;
    }

    /**
     * 设置“手机连上后是否自动开始接收”。
     *
     * <p>{@code false} 时电脑不打开音频输出、也不让手机采集麦克风（省电省流量）。
     */
    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
        Session s = current;
        if (s != null && s.alive) {
            if (autoStart) {
                try {
                    s.startStreaming();
                } catch (Exception e) {
                    log("开始接收失败：" + e.getMessage());
                }
            } else {
                s.stopStreaming("用户暂停接收");
            }
        }
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    /** 当前是否正在接收并播放手机音频。 */
    public boolean isStreaming() {
        Session s = current;
        return s != null && s.alive && s.streaming;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 当前是否已有手机连接。 */
    public boolean hasClient() {
        Session s = current;
        return s != null && s.alive;
    }

    /* ================= 生命周期 ================= */

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            controlServer = new ServerSocket();
            controlServer.setReuseAddress(true);
            controlServer.bind(new InetSocketAddress(Proto.CONTROL_PORT));
            controlServer.setSoTimeout(1000);

            audioServer = new ServerSocket();
            audioServer.setReuseAddress(true);
            audioServer.bind(new InetSocketAddress(Proto.AUDIO_PORT));
            audioServer.setSoTimeout(1000);

            discoverySocket = new DatagramSocket(null);
            discoverySocket.setReuseAddress(true);
            discoverySocket.setBroadcast(true);
            discoverySocket.bind(new InetSocketAddress(Proto.DISCOVERY_PORT));
            discoverySocket.setSoTimeout(1000);
        } catch (IOException e) {
            stop();
            throw e;
        }

        announceThread = daemon("luomic-announce", this::announceLoop);
        controlAcceptThread = daemon("luomic-ctrl-accept", () -> acceptLoop(controlServer, true));
        audioAcceptThread = daemon("luomic-audio-accept", () -> acceptLoop(audioServer, false));
        announceThread.start();
        controlAcceptThread.start();
        audioAcceptThread.start();

        log("服务已启动：发现 UDP " + Proto.DISCOVERY_PORT
                + "，控制 TCP " + Proto.CONTROL_PORT
                + "，音频 TCP " + Proto.AUDIO_PORT);
        log("本机局域网地址：" + String.join("，", localIpv4List()));
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeSession("服务停止");
        closeQuietly(controlServer);
        closeQuietly(audioServer);
        DatagramSocket d = discoverySocket;
        discoverySocket = null;
        if (d != null) {
            d.close();
        }
        controlServer = null;
        audioServer = null;
        joinQuietly(announceThread);
        joinQuietly(controlAcceptThread);
        joinQuietly(audioAcceptThread);
        announceThread = null;
        controlAcceptThread = null;
        audioAcceptThread = null;
        player.stop();
        log("服务已停止");
    }

    /* ================= 发现 ================= */

    private void announceLoop() {
        byte[] payload = (Proto.MSG_DISCOVER + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] buf = new byte[512];
        long lastAnnounce = 0;
        int ifaceRefresh = 0;
        List<InetAddress> targets = new ArrayList<>();
        while (running.get()) {
            DatagramSocket sock = discoverySocket;
            if (sock == null || sock.isClosed()) {
                break;
            }
            try {
                if (System.currentTimeMillis() - lastAnnounce >= Proto.ANNOUNCE_INTERVAL_MS) {
                    lastAnnounce = System.currentTimeMillis();
                    if (ifaceRefresh-- <= 0) {
                        ifaceRefresh = 15; // 每 30 秒重新枚举一次网卡，适应换网/插拔网线
                        targets = broadcastAddresses();
                    }
                    for (InetAddress t : targets) {
                        try {
                            sock.send(new DatagramPacket(payload, payload.length, t, Proto.DISCOVERY_PORT));
                        } catch (IOException ignored) {
                            // 单个广播地址失败不影响其它
                        }
                    }
                }
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(pkt);
                } catch (SocketTimeoutException te) {
                    continue;
                }
                String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(),
                        StandardCharsets.US_ASCII).trim();
                if (msg.equals(Proto.MSG_PROBE)) {
                    // 手机主动探测：立刻单播回应它的 47777 端口
                    InetAddress from = pkt.getAddress();
                    String reply = Proto.MSG_HERE_PREFIX + Proto.b64(hostName())
                            + " " + Proto.CONTROL_PORT + " " + Proto.AUDIO_PORT + "\n";
                    byte[] out = reply.getBytes(StandardCharsets.US_ASCII);
                    sock.send(new DatagramPacket(out, out.length, from, Proto.DISCOVERY_PORT));
                    log("收到 " + from.getHostAddress() + " 的探测，已回应");
                }
            } catch (Exception e) {
                if (running.get()) {
                    log("发现服务异常：" + e.getMessage());
                    sleep(500);
                }
            }
        }
    }

    private List<InetAddress> broadcastAddresses() {
        List<InetAddress> list = new ArrayList<>();
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
            // 继续枚举定向广播
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && b instanceof Inet4Address && !list.contains(b)) {
                        list.add(b);
                    }
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return list;
    }

    /* ================= 连接接受 ================= */

    private void acceptLoop(ServerSocket ss, boolean control) {
        while (running.get() && ss != null && !ss.isClosed()) {
            try {
                Socket s = ss.accept();
                if (control) {
                    handleControl(s);
                } else {
                    Session cur = current;
                    if (cur == null) {
                        // 没有控制会话时收到的音频连接是脏连接，直接丢掉
                        log("收到无主的音频连接，已忽略");
                        closeQuietly(s);
                    } else {
                        cur.attachAudio(s);
                    }
                }
            } catch (SocketTimeoutException te) {
                // 正常：用于检查 running
            } catch (IOException e) {
                if (running.get()) {
                    log((control ? "控制" : "音频") + "端口接受连接失败：" + e.getMessage());
                    sleep(300);
                }
            }
        }
    }

    private void handleControl(Socket socket) {
        synchronized (sessionLock) {
            Session old = current;
            if (old != null) {
                old.close("新手机接入，顶掉旧连接");
            }
            Session s = new Session(socket);
            current = s;
            s.start();
        }
    }

    private void closeSession(String reason) {
        synchronized (sessionLock) {
            Session s = current;
            current = null;
            if (s != null) {
                s.close(reason);
            }
        }
    }

    /** 清理已结束的会话（由会话自身在退出时调用）。 */
    private void onSessionEnded(Session s, String reason) {
        synchronized (sessionLock) {
            if (current == s) {
                current = null;
                log("会话结束：" + reason);
            }
        }
        listener.onSession(false, "", "");
    }

    /* ================= 一次手机会话 ================= */

    private final class Session {
        private final Socket control;
        private volatile Socket audio;
        private volatile OutputStream controlOut;
        private volatile InputStream controlIn;
        private volatile boolean alive = true;
        private volatile boolean streaming;
        private volatile long lastRecv = System.currentTimeMillis();
        private volatile String phoneName = "手机";
        private volatile String phoneIp;
        private volatile int rate = 48000;
        private volatile int frameMs = 20;
        private volatile int rttMs = -1;
        private volatile int phoneKbps;
        private volatile int phoneFps;
        private volatile int phoneDb = -120;
        private long audioBytes;
        private long lastStatAt;
        private long lastAudioBytes;
        private int audioGaps;

        Session(Socket control) {
            this.control = control;
            this.phoneIp = control.getInetAddress().getHostAddress();
        }

        void start() {
            Thread t = daemon("luomic-session", this::run);
            t.start();
        }

        private void run() {
            String endReason = "手机断开";
            try {
                control.setTcpNoDelay(true);
                control.setSoTimeout(1000);
                controlOut = control.getOutputStream();
                controlIn = new BufferedInputStream(control.getInputStream(), 8192);

                // 1) 先发 HELLO，手机据此决定音频参数
                sendHello();
                // 2) 打开音频输出设备并请求手机开始推流（可在界面上暂停）
                if (autoStart) {
                    startStreaming();
                } else {
                    log("当前为“暂停接收”，手机已连接但不会采集麦克风。");
                }
                // 3) 等手机把音频连接接上来
                waitForAudio();
                // 4) 事件循环
                eventLoop();
            } catch (Exception e) {
                endReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                alive = false;
                streaming = false;
                player.stop();
                closeQuietly(audio);
                closeQuietly(control);
                audio = null;
                onSessionEnded(this, endReason);
                listener.onStats("已断开", -120f, false);
            }
        }

        private void sendHello() throws IOException {
            String hello = "{\"t\":\"" + Proto.T_HELLO + "\",\"v\":" + Proto.PROTOCOL_VERSION
                    + ",\"name\":\"" + Proto.escape(hostName()) + "\""
                    + ",\"audio_port\":" + Proto.AUDIO_PORT
                    + ",\"codec\":\"" + Proto.CODEC_PCM_S16LE + "\""
                    + ",\"rate\":" + rate
                    + ",\"channels\":1"
                    + ",\"frame_ms\":" + frameMs + "}\n";
            send(hello);
        }

        /** 打开音频输出并让手机开始推流（幂等）。 */
        synchronized void startStreaming() throws LineUnavailableException, IOException {
            if (streaming && player.isRunning()) {
                return;
            }
            streaming = true;
            if (!player.isRunning()) {
                player.start(selectedMixer, rate, frameMs, selectedName);
                log("音频输出已打开：" + selectedName + "（" + rate + "Hz/" + frameMs + "ms）");
            }
            send("{\"t\":\"" + Proto.T_START + "\"}\n");
            log("已请求手机开始推流。");
        }

        /** 停流但保持控制连接（手机不再采集麦克风）。 */
        synchronized void stopStreaming(String reason) {
            if (!streaming) {
                return;
            }
            streaming = false;
            try {
                send("{\"t\":\"" + Proto.T_STOP + "\"}\n");
            } catch (IOException ignored) {
                // 手机可能已断开
            }
            player.stop();
            log("已停止接收（" + reason + "）。");
        }

        private void waitForAudio() throws IOException {
            long deadline = System.currentTimeMillis() + 10000;
            while (audio == null && alive && System.currentTimeMillis() < deadline) {
                sleep(100);
            }
            if (audio == null) {
                throw new IOException("手机没有建立音频通道（超时 10 秒）");
            }
        }

        /** 手机连上音频端口：读握手 → 起接收线程。 */
        void attachAudio(Socket s) {
            if (!alive) {
                closeQuietly(s);
                return;
            }
            Socket old = audio;
            if (old != null) {
                closeQuietly(old);
            }
            audio = s;
            Thread t = daemon("luomic-audio-in", () -> audioLoop(s));
            t.start();
        }

        private void audioLoop(Socket s) {
            try {
                s.setTcpNoDelay(true);
                s.setSoTimeout(500);
                s.setReceiveBufferSize(256 * 1024);
                InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
                byte[] handshake = new byte[Proto.AUDIO_HANDSHAKE_LEN];
                if (!readFully(in, handshake)) {
                    throw new IOException("音频握手数据不完整");
                }
                int phoneRate = Proto.readIntBE(handshake, 12);
                int phoneChannels = Proto.readIntBE(handshake, 16);
                int phoneFrame = Proto.readIntBE(handshake, 20);
                log("音频握手：rate=" + phoneRate + " channels=" + phoneChannels + " frame_ms=" + phoneFrame);
                if (phoneRate != rate) {
                    // 手机按我们 HELLO 里的参数采集，不一致说明手机端异常，按手机上报的重开设备
                    log("警告：手机采样率 " + phoneRate + " 与本地 " + rate + " 不一致，按手机参数重开输出");
                    rate = phoneRate;
                    player.stop();
                    player.start(selectedMixer, rate, frameMs, selectedName);
                }

                byte[] header = new byte[4];
                while (alive && running.get() && !s.isClosed()) {
                    if (!streaming) {
                        // 界面处于“暂停接收”：继续读并丢掉数据，保持连接
                        drain(in, header);
                        continue;
                    }
                    if (!player.isRunning()) {
                        sleep(20);
                        continue;
                    }
                    try {
                        if (!readFully(in, header)) {
                            break;
                        }
                    } catch (SocketTimeoutException te) {
                        continue;
                    }
                    int len = Proto.readIntBE(header, 0);
                    if (len <= 0 || len > Proto.MAX_FRAME_BYTES) {
                        throw new IOException("音频帧长度非法：" + len);
                    }
                    byte[] pcm = new byte[len];
                    if (!readFully(in, pcm)) {
                        break;
                    }
                    player.push(pcm, len);
                    audioBytes += len;
                    lastRecv = System.currentTimeMillis();
                }
                log("音频接收线程结束");
            } catch (Exception e) {
                if (alive && running.get()) {
                    log("音频通道异常：" + e.getMessage());
                    // 音频断了但控制通道还在：让手机重连，直接结束会话更快
                    closeSession("音频通道中断");
                }
            }
        }

        private void eventLoop() throws IOException {
            long lastPing = 0;
            while (alive && running.get()) {
                String line = null;
                try {
                    line = readControlLine();
                } catch (SocketTimeoutException te) {
                    // 正常
                }
                if (line != null) {
                    lastRecv = System.currentTimeMillis();
                    handle(line);
                }
                long now = System.currentTimeMillis();
                if (now - lastRecv > Proto.CLIENT_TIMEOUT_MS) {
                    throw new IOException("与手机的心跳超时（" + (Proto.CLIENT_TIMEOUT_MS / 1000) + " 秒无响应）");
                }
                if (now - lastPing >= Proto.PING_INTERVAL_MS) {
                    lastPing = now;
                    send("{\"t\":\"" + Proto.T_PING + "\",\"ts\":" + now + "}\n");
                }
                reportStats(now);
            }
        }

        private void handle(String msg) {
            String t = Proto.jsonString(msg, "t");
            if (t == null) {
                return;
            }
            switch (t) {
                case Proto.T_HELLO: {
                    String dev = Proto.jsonString(msg, "device");
                    if (dev != null && !dev.isEmpty()) {
                        phoneName = dev;
                    }
                    int phoneRate = Proto.jsonInt(msg, "rate", rate);
                    if (phoneRate != rate) {
                        rate = phoneRate;
                    }
                    log("手机已连接：" + phoneName + " @ " + phoneIp
                            + "（Android " + Proto.jsonString(msg, "android") + "）");
                    listener.onSession(true, phoneName, phoneIp);
                    break;
                }
                case Proto.T_PONG: {
                    int ts = Proto.jsonInt(msg, "ts", 0);
                    if (ts > 0) {
                        rttMs = (int) Math.min(9999, System.currentTimeMillis() - ts);
                    }
                    break;
                }
                case Proto.T_STAT: {
                    phoneFps = Proto.jsonInt(msg, "fps", 0);
                    phoneKbps = Proto.jsonInt(msg, "kbps", 0);
                    phoneDb = Proto.jsonInt(msg, "db", -120);
                    break;
                }
                case Proto.T_BYE:
                    log("手机主动断开连接");
                    alive = false;
                    break;
                default:
                    break;
            }
        }

        private void reportStats(long now) {
            if (now - lastStatAt < 1000) {
                return;
            }
            double secs = lastStatAt == 0 ? 1.0 : Math.max(0.001, (now - lastStatAt) / 1000.0);
            long delta = audioBytes - lastAudioBytes;
            lastStatAt = now;
            lastAudioBytes = audioBytes;
            int kbps = (int) Math.round(delta * 8.0 / 1000.0 / secs);
            boolean receiving = streaming && player.isRunning()
                    && System.currentTimeMillis() - player.lastFrameAt() < 1500;
            StringBuilder sb = new StringBuilder();
            sb.append(receiving ? "接收中" : "空闲");
            sb.append(" | 手机 ").append(phoneName);
            sb.append(" | ").append(kbps).append(" kbps");
            sb.append(" | ").append(phoneFps).append(" fps");
            sb.append(" | 丢帧 ").append(audioGaps);
            sb.append(" | 延迟 ").append(rttMs < 0 ? "—" : rttMs + "ms");
            long[] st = player.stats();
            sb.append(" | 播放 ").append(st[0]).append(" 帧");
            if (st[1] > 0) {
                sb.append("（丢弃 ").append(st[1]).append("）");
            }
            listener.onStats(sb.toString(), receiving ? player.levelDb() : -120f, receiving);
        }

        void send(String json) throws IOException {
            OutputStream out = controlOut;
            if (out == null) {
                throw new IOException("控制通道尚未就绪");
            }
            synchronized (this) {
                out.write(Proto.utf8(json));
                out.flush();
            }
        }

        /**
         * 按行读取控制报文。
         *
         * @return 一行 JSON（不含换行），对端关闭时返回 {@code null}
         */
        private String readControlLine() throws IOException {
            InputStream in = controlIn;
            if (in == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder(128);
            while (true) {
                int c = in.read();
                if (c < 0) {
                    return sb.length() > 0 ? sb.toString() : null;
                }
                if (c == '\n') {
                    return sb.toString();
                }
                if (c == '\r') {
                    continue;
                }
                sb.append((char) c);
                if (sb.length() > 4096) {
                    throw new IOException("控制报文过长（超过 4096 字节）");
                }
            }
        }

        void close(String reason) {
            alive = false;
            try {
                send("{\"t\":\"" + Proto.T_STOP + "\"}\n");
                send("{\"t\":\"" + Proto.T_BYE + "\"}\n");
            } catch (Exception ignored) {
                // 对方可能已经断开
            }
            closeQuietly(audio);
            closeQuietly(control);
            audio = null;
            log("关闭会话：" + reason);
            onSessionEnded(this, reason);
        }
    }

    /* ================= 工具 ================= */

    private Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private void log(String line) {
        listener.onLog(line);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    /** 严格读满 n 字节；返回 false 表示对端已关闭。 */
    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    /**
     * 丢弃一帧数据（“暂停接收”时使用），保持音频流对齐。
     *
     * @return 是否成功丢弃一帧
     */
    private static boolean drain(InputStream in, byte[] header) throws IOException {
        int off = 0;
        while (off < 4) {
            int n;
            try {
                n = in.read(header, off, 4 - off);
            } catch (SocketTimeoutException te) {
                return false;
            }
            if (n < 0) {
                return false;
            }
            off += n;
        }
        int len = Proto.readIntBE(header, 0);
        if (len <= 0 || len > Proto.MAX_FRAME_BYTES) {
            throw new IOException("音频帧长度非法：" + len);
        }
        int left = len;
        byte[] skip = new byte[Math.min(4096, len)];
        while (left > 0) {
            int n = in.read(skip, 0, Math.min(skip.length, left));
            if (n < 0) {
                return false;
            }
            left -= n;
        }
        return true;
    }

    private static String hostName() {
        String n = System.getenv("COMPUTERNAME");
        if (n == null || n.isEmpty()) {
            n = System.getProperty("user.name", "PC");
        }
        return n + " 的电脑";
    }

    /** 本机所有可用的局域网 IPv4 地址（用于界面提示连接哪个 IP）。 */
    public static List<String> localIpv4List() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        Collections.sort(out);
        return out;
    }
}
