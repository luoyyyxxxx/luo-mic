package com.luomic.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 局域网发现：手机侧。
 *
 * <p>两种模式同时工作：
 * <ul>
 *   <li><b>被动</b>：一直监听 47777 端口，电脑每 2 秒广播一次 {@code LUOMIC/1 DISCOVER}，
 *       收到即知道电脑存在（手机上线后无需等待探测就能连上）。</li>
 *   <li><b>主动</b>：每 2 秒广播 {@code LUOMIC/1 PROBE}，电脑会单播回
 *       {@code LUOMIC/1 HERE <name_b64> <ctrl_port> <audio_port>}。</li>
 * </ul>
 *
 * <p>发现结果通过 {@link Listener} 回调给上层。
 */
public class Discovery {

    /** 发现结果：一台电脑。 */
    public static final class ServerAddr {
        public final String host;
        public final int controlPort;
        public final int audioPort;
        public final String name;

        public ServerAddr(String host, int controlPort, int audioPort, String name) {
            this.host = host;
            this.controlPort = controlPort;
            this.audioPort = audioPort;
            this.name = name == null ? host : name;
        }

        public boolean sameAs(ServerAddr other) {
            return other != null && host.equals(other.host) && controlPort == other.controlPort;
        }

        @Override
        public String toString() {
            return host + ":" + controlPort;
        }
    }

    /** 发现回调。 */
    public interface Listener {
        /**
         * 收到电脑的在线宣告或探测应答。
         *
         * @param server 电脑地址信息
         */
        void onServerFound(ServerAddr server);
    }

    private static final String TAG = "luo-mic/discovery";

    private final Context appContext;
    private final Listener listener;

    private volatile boolean running;
    private Thread rxThread;
    private Thread txThread;
    private DatagramSocket socket;
    private WifiManager.MulticastLock multicastLock;
    private long lastProbeMs;

    public Discovery(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        try {
            WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                // 部分机型未持锁时收不到广播包
                multicastLock = wifi.createMulticastLock("luomic-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "multicast lock 获取失败：" + e.getMessage());
        }

        rxThread = new Thread(this::receiveLoop, "luomic-discovery-rx");
        rxThread.setDaemon(true);
        rxThread.start();

        txThread = new Thread(this::probeLoop, "luomic-discovery-tx");
        txThread.setDaemon(true);
        txThread.start();
        Log.i(TAG, "发现服务已启动，端口 " + Proto.DISCOVERY_PORT);
    }

    public synchronized void stop() {
        running = false;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            s.close();
        }
        if (rxThread != null) {
            rxThread.interrupt();
            rxThread = null;
        }
        if (txThread != null) {
            txThread.interrupt();
            txThread = null;
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
            } catch (Exception ignored) {
                // 释放失败不影响功能
            }
            multicastLock = null;
        }
        Log.i(TAG, "发现服务已停止");
    }

    /** 立刻发一次探测，用户点“重新扫描”时调用。 */
    public void probeNow() {
        lastProbeMs = 0L;
    }

    private void receiveLoop() {
        byte[] buf = new byte[512];
        while (running) {
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(Proto.DISCOVERY_PORT));
                s.setSoTimeout(1000);
                socket = s;
                Log.i(TAG, "已绑定 UDP " + Proto.DISCOVERY_PORT);

                while (running && !s.isClosed()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(pkt);
                    } catch (SocketTimeoutException te) {
                        continue;
                    }
                    String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.US_ASCII).trim();
                    ServerAddr addr = parse(msg, pkt.getAddress());
                    if (addr != null) {
                        Log.i(TAG, "发现电脑：" + addr.name + " @ " + addr);
                        listener.onServerFound(addr);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "发现监听异常，1 秒后重试：" + e);
                    sleepQuiet(1000);
                }
            } finally {
                DatagramSocket s = socket;
                socket = null;
                if (s != null && !s.isClosed()) {
                    s.close();
                }
            }
        }
    }

    private void probeLoop() {
        while (running) {
            try {
                if (System.currentTimeMillis() - lastProbeMs >= Proto.PROBE_INTERVAL_MS) {
                    lastProbeMs = System.currentTimeMillis();
                    sendProbe();
                }
                sleepQuiet(200);
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "探测发送异常：" + e);
                    sleepQuiet(1000);
                }
            }
        }
    }

    private void sendProbe() {
        DatagramSocket s = socket;
        if (s == null || s.isClosed()) {
            return;
        }
        byte[] data = (Proto.MSG_PROBE + "\n").getBytes(StandardCharsets.US_ASCII);
        int sent = 0;
        for (InetAddress bcast : broadcastAddresses()) {
            try {
                s.send(new DatagramPacket(data, data.length, bcast, Proto.DISCOVERY_PORT));
                sent++;
            } catch (Exception e) {
                Log.d(TAG, "向 " + bcast + " 广播失败：" + e.getMessage());
            }
        }
        if (sent > 0) {
            Log.d(TAG, "已发送 PROBE（" + sent + " 个广播地址）");
        }
    }

    /** 计算所有网段的广播地址：含 255.255.255.255 与各接口的定向广播地址。 */
    private List<InetAddress> broadcastAddresses() {
        List<InetAddress> list = new ArrayList<>();
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
            // 忽略：下面还有定向广播
        }
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && b instanceof Inet4Address && !list.contains(b)) {
                        list.add(b);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "枚举接口失败：" + e.getMessage());
        }
        return list;
    }

    /** 解析电脑报文，非本协议或格式错误返回 null。 */
    private ServerAddr parse(String msg, InetAddress from) {
        if (msg == null || from == null) {
            return null;
        }
        String host = from.getHostAddress();
        if (msg.equals(Proto.MSG_DISCOVER)) {
            return new ServerAddr(host, Proto.DEFAULT_CONTROL_PORT, Proto.DEFAULT_AUDIO_PORT, host);
        }
        if (!msg.startsWith(Proto.MSG_HERE_PREFIX)) {
            return null;
        }
        String[] parts = msg.substring(Proto.MSG_HERE_PREFIX.length()).trim().split("\\s+");
        String name = host;
        int ctrl = Proto.DEFAULT_CONTROL_PORT;
        int audio = Proto.DEFAULT_AUDIO_PORT;
        try {
            if (parts.length >= 1 && !parts[0].isEmpty()) {
                String decoded = Proto.base64Decode(parts[0]);
                if (decoded != null && !decoded.isEmpty()) {
                    name = decoded;
                }
            }
            if (parts.length >= 2) {
                ctrl = Integer.parseInt(parts[1]);
            }
            if (parts.length >= 3) {
                audio = Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "HERE 报文端口字段非法：" + msg);
        }
        return new ServerAddr(host, ctrl, audio, name);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 取本机在局域网中的 IPv4 地址，仅用于界面展示。 */
    public static String localIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String n = ni.getName();
                if (n != null && (n.startsWith("rmnet") || n.startsWith("dummy") || n.startsWith("tun"))) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // 返回未知即可
        }
        return "未知";
    }
}
