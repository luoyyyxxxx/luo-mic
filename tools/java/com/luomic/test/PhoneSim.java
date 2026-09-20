package com.luomic.test;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 假手机模拟器：在没有安卓手机的情况下，用同一套协议验证电脑端是否正常。
 *
 * <p>用法：
 * <pre>
 *   java -cp out com.luomic.test.PhoneSim       # 自动发现局域网里的电脑并推流 20 秒
 *   java -cp out com.luomic.test.PhoneSim --host 127.0.0.1 --seconds 10
 * </pre>
 */
public final class PhoneSim {

    private static final int DISCOVERY_PORT = 47777;
    private static final int CONTROL_PORT = 47778;
    private static final int AUDIO_PORT = 47779;
    private static final String MAGIC = "LUOMIC/1";

    private PhoneSim() {
    }

    public static void main(String[] args) throws Exception {
        String host = null;
        String probeHost = null;
        int seconds = 20;
        int rate = 48000;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    host = args[++i];
                    break;
                case "--probe-host":
                    probeHost = args[++i];
                    break;
                case "--seconds":
                    seconds = Integer.parseInt(args[++i]);
                    break;
                case "--rate":
                    rate = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.out.println("未知参数：" + args[i]);
                    return;
            }
        }

        if (host == null) {
            host = discover(probeHost);
            if (host == null) {
                System.out.println("[失败] 没有发现电脑。请确认电脑端 luo mic 已点击“启动服务”。");
                return;
            }
        }
        System.out.println("[发现] 电脑地址 " + host);

        try (Socket control = new Socket()) {
            control.setTcpNoDelay(true);
            control.setSoTimeout(5000);
            control.connect(new InetSocketAddress(host, CONTROL_PORT), 5000);
            control.setSoTimeout(500);
            System.out.println("[连接] 控制通道已建立");

            InputStream in = new BufferedInputStream(control.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(control.getOutputStream(), 8192);

            String hello = readLine(in);
            System.out.println("[收到] " + hello);
            if (hello == null || !hello.contains("\"HELLO\"")) {
                System.out.println("[失败] 首包不是 HELLO");
                return;
            }
            int audioPort = intField(hello, "audio_port", AUDIO_PORT);
            int serverRate = intField(hello, "rate", rate);
            int frameMs = intField(hello, "frame_ms", 20);
            rate = serverRate;

            send(out, "{\"t\":\"HELLO\",\"v\":1,\"device\":\"PhoneSim(测试)\",\"android\":34,"
                    + "\"codec\":\"pcm_s16le\",\"rate\":" + rate + ",\"channels\":1,\"frame_ms\":" + frameMs + "}");

            // 音频通道 + 32 字节握手
            try (Socket audio = new Socket()) {
                audio.setTcpNoDelay(true);
                audio.setSoTimeout(0);
                audio.connect(new InetSocketAddress(host, audioPort), 5000);
                byte[] handshake = new byte[32];
                writeIntBE(handshake, 12, rate);
                writeIntBE(handshake, 16, 1);
                writeIntBE(handshake, 20, frameMs);
                OutputStream audioOut = new BufferedOutputStream(audio.getOutputStream(), 64 * 1024);
                audioOut.write(handshake);
                audioOut.flush();
                System.out.println("[连接] 音频通道已建立（" + rate + "Hz / " + frameMs + "ms）");

                // 等 START：期间处理 PING
                boolean started = false;
                long waitStart = System.currentTimeMillis();
                while (System.currentTimeMillis() - waitStart < 8000) {
                    String msg = tryReadLine(in);
                    if (msg == null) {
                        continue;
                    }
                    System.out.println("[收到] " + msg);
                    if (msg.contains("\"PING\"")) {
                        send(out, "{\"t\":\"PONG\",\"ts\":" + intField(msg, "ts", 0) + "}");
                    }
                    if (msg.contains("\"START\"")) {
                        started = true;
                        break;
                    }
                }
                if (!started) {
                    System.out.println("[失败] 8 秒内没有收到 START");
                    return;
                }

                // 推正弦波 440Hz
                int frameBytes = rate * frameMs / 1000 * 2;
                int samplesPerFrame = frameBytes / 2;
                long frameNanos = frameMs * 1_000_000L;
                long next = System.nanoTime();
                long deadline = System.currentTimeMillis() + seconds * 1000L;
                long frames = 0;
                long bytes = 0;
                long statAt = System.currentTimeMillis();
                long statFrames = 0;
                double phase = 0;
                double step = 2 * Math.PI * 440.0 / rate;
                byte[] pcm = new byte[frameBytes];
                byte[] header = new byte[4];

                // 控制通道用 5ms 超时做非阻塞轮询，否则每帧都会白等一次读超时
                control.setSoTimeout(5);
                System.out.println("[推流] 开始推送 440Hz 正弦波，持续 " + seconds + " 秒…");
                while (System.currentTimeMillis() < deadline) {
                    for (int s = 0; s < samplesPerFrame; s++) {
                        short v = (short) (Math.sin(phase) * 12000);
                        phase += step;
                        pcm[s * 2] = (byte) (v & 0xFF);
                        pcm[s * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                    }
                    writeIntBE(header, 0, frameBytes);
                    audioOut.write(header);
                    audioOut.write(pcm);
                    audioOut.flush();
                    frames++;
                    bytes += frameBytes;

                    // 处理控制报文
                    String msg;
                    while ((msg = tryReadLine(in)) != null) {
                        System.out.println("[收到] " + msg);
                        if (msg.contains("\"PING\"")) {
                            send(out, "{\"t\":\"PONG\",\"ts\":" + intField(msg, "ts", 0) + "}");
                        } else if (msg.contains("\"STOP\"")) {
                            System.out.println("[提示] 电脑要求停止推流");
                            deadline = System.currentTimeMillis();
                        }
                    }

                    long now = System.currentTimeMillis();
                    if (now - statAt >= 2000) {
                        double secs = Math.max(0.001, (now - statAt) / 1000.0);
                        int fps = (int) Math.round((frames - statFrames) / secs);
                        int kbps = (int) Math.round((rate * 16.0) / 1000.0);
                        statAt = now;
                        statFrames = frames;
                        send(out, "{\"t\":\"STAT\",\"fps\":" + fps + ",\"kbps\":" + kbps + ",\"db\":-18}");
                    }

                    next += frameNanos;
                    long sleep = next - System.nanoTime();
                    if (sleep > 0) {
                        Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                    }
                }
                System.out.println("[完成] 共推送 " + frames + " 帧 / " + (bytes / 1024) + " KB");
                send(out, "{\"t\":\"BYE\"}");
                Thread.sleep(200);
            }
        }
        System.out.println("[结束] 模拟器退出");
    }

    /**
     * 发现电脑：绑 47777 端口（与真实手机一致），先等电脑的周期广播，再主动发 PROBE。
     *
     * @param target 定向探测地址，{@code null} 表示广播 255.255.255.255
     * @return 电脑 IP，失败返回 {@code null}
     */
    private static String discover(String target) throws IOException {
        DatagramSocket sock = new DatagramSocket(null);
        try {
            sock.setReuseAddress(true);
            sock.setBroadcast(true);
            sock.bind(new InetSocketAddress(DISCOVERY_PORT));
        } catch (IOException e) {
            sock.close();
            System.out.println("[探测] 无法绑定 " + DISCOVERY_PORT + " 端口：" + e.getMessage());
            return null;
        }
        try {
            byte[] probe = (MAGIC + " PROBE\n").getBytes(StandardCharsets.US_ASCII);
            byte[] buf = new byte[512];
            InetAddress dest = InetAddress.getByName(target == null ? "255.255.255.255" : target);
            sock.setSoTimeout(2000);
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    sock.send(new DatagramPacket(probe, probe.length, dest, DISCOVERY_PORT));
                    System.out.println("[探测] 已发送 PROBE 到 " + dest.getHostAddress() + ":" + DISCOVERY_PORT);
                } catch (IOException e) {
                    System.out.println("[探测] 发送 PROBE 失败：" + e.getMessage());
                }
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(),
                            StandardCharsets.US_ASCII).trim();
                    System.out.println("[探测] 收到 " + msg);
                    if (msg.startsWith(MAGIC + " HERE ") || msg.equals(MAGIC + " DISCOVER")) {
                        String[] parts = msg.startsWith(MAGIC + " HERE ")
                                ? msg.substring((MAGIC + " HERE ").length()).split("\\s+")
                                : new String[0];
                        if (parts.length > 0) {
                            try {
                                System.out.println("[探测] 电脑名称："
                                        + new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8));
                            } catch (IllegalArgumentException ignored) {
                                // 名称解析失败不影响连接
                            }
                        }
                        return pkt.getAddress().getHostAddress();
                    }
                } catch (SocketTimeoutException te) {
                    // 再广播一次
                }
            }
        } finally {
            sock.close();
        }
        return null;
    }

    private static void send(OutputStream out, String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        while (true) {
            int c = in.read();
            if (c < 0) {
                return bos.size() == 0 ? null : bos.toString("UTF-8");
            }
            if (c == '\n') {
                return bos.toString("UTF-8");
            }
            if (c != '\r') {
                bos.write(c);
            }
        }
    }

    private static String tryReadLine(InputStream in) {
        try {
            return readLine(in);
        } catch (SocketTimeoutException te) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static int intField(String json, String key, int def) {
        if (json == null) {
            return def;
        }
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) {
            return def;
        }
        int colon = json.indexOf(':', i);
        if (colon < 0) {
            return def;
        }
        int j = colon + 1;
        StringBuilder sb = new StringBuilder();
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) {
            sb.append(json.charAt(j++));
        }
        try {
            return sb.length() == 0 ? def : Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void writeIntBE(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    /** 顺带打印本机音频设备，便于排查（未使用，保留给扩展）。 */
    static void dumpDevices() {
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            System.out.println("  " + mi.getName() + " | " + mi.getDescription());
        }
    }
}
