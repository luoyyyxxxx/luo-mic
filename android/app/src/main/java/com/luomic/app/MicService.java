package com.luomic.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * luo mic 前台服务：负责发现电脑、建立连接、推送麦克风音频，并在断线后自动重连。
 *
 * <p>状态机：见 docs/PROTOCOL.md 第 2.3 节。
 */
public class MicService extends Service implements Discovery.Listener {

    private static final String TAG = "luo-mic/service";

    /** 停止服务（通知栏按钮 / 界面按钮）。 */
    public static final String ACTION_STOP = "com.luomic.app.action.STOP";
    /** 立即重新扫描局域网。 */
    public static final String ACTION_RESCAN = "com.luomic.app.action.RESCAN";
    /** 服务向外广播状态的 action（界面监听它刷新 UI）。 */
    public static final String ACTION_STATE = "com.luomic.app.action.STATE";
    /** 界面通知服务切换“只扫描不推流”的省电模式。 */
    public static final String ACTION_SET_PUSH = "com.luomic.app.action.SET_PUSH";

    public static final String EXTRA_PUSH = "push";
    public static final String EXTRA_STATE = "state_json";

    private static final String PREFS = "luomic";
    private static final String KEY_HOST = "server_host";
    private static final String KEY_CTRL_PORT = "server_ctrl_port";
    private static final String KEY_AUDIO_PORT = "server_audio_port";
    private static final String KEY_NAME = "server_name";
    private static final String KEY_RATE = "rate";
    private static final String KEY_RANDOM_MIC = "random_mic";
    private static final String KEY_PUSH = "push_enabled";

    private static final int NOTIF_ID = 0x4C4D;
    private static final String CHANNEL_ID = "luomic-stream";

    /** 引擎线程与状态快照。 */
    public static final class State {
        public String phase = "idle";
        public String detail = "";
        public String serverName = "";
        public String serverIp = "";
        public String localIp = "";
        public int rate = 48000;
        public int frameMs = 20;
        public boolean connected;
        public boolean streaming;
        public float levelDb = -120f;
        public int fps;
        public int kbps;
        public int lossPercent;
        public int rttMs = -1;
        public long reconnects;

        public synchronized String toJson() {
            return "{\"phase\":\"" + Proto.escape(phase) + "\""
                    + ",\"detail\":\"" + Proto.escape(detail) + "\""
                    + ",\"serverName\":\"" + Proto.escape(serverName) + "\""
                    + ",\"serverIp\":\"" + Proto.escape(serverIp) + "\""
                    + ",\"localIp\":\"" + Proto.escape(localIp) + "\""
                    + ",\"rate\":" + rate
                    + ",\"frameMs\":" + frameMs
                    + ",\"connected\":" + connected
                    + ",\"streaming\":" + streaming
                    + ",\"levelDb\":" + levelDb
                    + ",\"fps\":" + fps
                    + ",\"kbps\":" + kbps
                    + ",\"lossPercent\":" + lossPercent
                    + ",\"rttMs\":" + rttMs
                    + ",\"reconnects\":" + reconnects + "}";
        }
    }

    private final State state = new State();

    private volatile boolean running;
    private Thread engineThread;
    private Discovery discovery;
    private MicCapture capture;
    private volatile OutputStream audioOut;
    private Socket controlSocket;
    private Socket audioSocket;

    private SharedPreferences prefs;
    private NotificationManager notifManager;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private Object audioFocusRequest;   // API 26+ 用 AudioFocusRequest，老版本为 null

    private volatile int rate = 48000;
    private volatile int frameMs = 20;
    private volatile boolean pushEnabled = true;
    private volatile Discovery.ServerAddr pendingServer;
    private final Object writeLock = new Object();

    private final AtomicLong framesSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private long lastStatAt;
    private long lastStatFrames;
    private long lastStatBytes;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        notifManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        rate = prefs.getInt(KEY_RATE, 48000);
        frameMs = 20;
        pushEnabled = prefs.getBoolean(KEY_PUSH, true);
        state.rate = rate;
        state.frameMs = frameMs;
        state.localIp = Discovery.localIpv4();
        Log.i(TAG, "服务创建：本机 IP " + state.localIp + "，采样率 " + rate);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "收到停止指令");
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESCAN.equals(action)) {
            Log.i(TAG, "收到重新扫描指令");
            pendingServer = null;
            Discovery d = discovery;
            if (d != null) {
                d.probeNow();
            }
            return START_STICKY;
        }
        if (intent != null && intent.hasExtra(EXTRA_PUSH)) {
            pushEnabled = intent.getBooleanExtra(EXTRA_PUSH, true);
            prefs.edit().putBoolean(KEY_PUSH, pushEnabled).apply();
            Log.i(TAG, "推流开关：" + pushEnabled);
        }
        if (!running) {
            running = true;
            startForegroundSafe();
            acquireLocks();
            discovery = new Discovery(this, this);
            discovery.start();
            engineThread = new Thread(this::engineLoop, "luomic-engine");
            engineThread.setDaemon(true);
            engineThread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "服务销毁");
        stopEverything();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ================= 对外接口（给界面用） ================= */

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, MicService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, MicService.class).setAction(ACTION_STOP));
    }

    public static void rescan(Context ctx) {
        ctx.startService(new Intent(ctx, MicService.class).setAction(ACTION_RESCAN));
    }

    public static void setPush(Context ctx, boolean push) {
        ctx.startService(new Intent(ctx, MicService.class).setAction(ACTION_SET_PUSH).putExtra(EXTRA_PUSH, push));
    }

    public static String lastServerLabel(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        String host = p.getString(KEY_HOST, null);
        if (host == null) {
            return "";
        }
        return p.getString(KEY_NAME, host) + " (" + host + ")";
    }

    /* ================= 前台通知与锁 ================= */

    private void startForegroundSafe() {
        Notification n = buildNotification("正在扫描局域网中的电脑…");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // Android 14 起若未授予录音权限会抛异常；此时服务仍会继续运行，只是没有前台通知
            Log.w(TAG, "startForeground 失败（通常是缺少录音权限）：" + e);
        }
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26 && notifManager != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notif_channel_desc));
            ch.setShowBadge(false);
            notifManager.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                pendingFlags());

        Intent stopIntent = new Intent(this, MicService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stopIntent, pendingFlags());

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_action_stop), stopPi).build());
        if (Build.VERSION.SDK_INT >= 21) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return b.build();
    }

    private int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void updateNotification(String text) {
        try {
            if (notifManager != null) {
                notifManager.notify(NOTIF_ID, buildNotification(text));
            }
        } catch (Exception e) {
            Log.d(TAG, "更新通知失败：" + e.getMessage());
        }
    }

    /**
     * 申请音频焦点。
     *
     * <p>为什么要做：如果别的 App 正在放音乐/录音/接电话，而我们只管采集，
     * 采到的可能是被系统压低或截断的音频，表现为"电脑那边声音断断续续"，
     * 但日志里看不出任何异常。申请焦点后系统会明确通知我们被抢占了。
     */
    private void acquireAudioFocus() {
        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        // 不在播放，只是采集，所以不申请 AUDIOFOCUS_GAIN_TRANSIENT 之类的强占用
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(this::onAudioFocusChange)
                        .build();
                audioFocusRequest = req;
                int r = audioManager.requestAudioFocus(req);
                Log.i(TAG, "音频焦点申请结果：" + r);
            } else {
                int r = audioManager.requestAudioFocus(
                        this::onAudioFocusChange,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN);
                Log.i(TAG, "音频焦点申请结果（旧 API）：" + r);
            }
        } catch (Exception e) {
            Log.w(TAG, "音频焦点申请失败（不影响使用）：" + e.getMessage());
        }
    }

    /** 别的 App 抢走/归还音频焦点时，通知界面并（必要时）暂停采集。 */
    private void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Log.w(TAG, "麦克风被其他应用占用，暂停采集");
                // 采集线程会被系统自动静音，这里主动停掉，避免把静音数据推给电脑
                stopCapture();
                setPhase("retry", "麦克风被其他应用（通话/录音）占用，已暂停，稍后自动恢复");
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.i(TAG, "音频焦点已归还，恢复采集");
                if (state.connected && !state.streaming) {
                    try {
                        startCaptureIfNeeded();
                        setPhase("streaming", "正在把麦克风发送给 " + state.serverName);
                    } catch (Exception e) {
                        Log.w(TAG, "恢复采集失败：" + e.getMessage());
                    }
                }
                break;
            default:
                // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK 等情况：继续采集即可
                Log.i(TAG, "音频焦点变化：" + focusChange + "（继续采集）");
                break;
        }
    }

    private void releaseAudioFocus() {
        try {
            if (audioManager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest instanceof AudioFocusRequest) {
                audioManager.abandonAudioFocusRequest((AudioFocusRequest) audioFocusRequest);
            } else {
                audioManager.abandonAudioFocus(this::onAudioFocusChange);
            }
        } catch (Exception e) {
            Log.d(TAG, "释放音频焦点失败：" + e.getMessage());
        }
        audioFocusRequest = null;
    }

    private void acquireLocks() {
        acquireAudioFocus();
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "luomic-wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "WifiLock 获取失败：" + e.getMessage());
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "luomic:cpu");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock 获取失败：" + e.getMessage());
        }
    }

    private void releaseLocks() {
        releaseAudioFocus();
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
            } catch (Exception ignored) {
                // 忽略
            }
        }
        wifiLock = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
                // 忽略
            }
        }
        wakeLock = null;
    }

    /* ================= 主流程 ================= */

    private void stopEverything() {
        running = false;
        setPhase("idle", "已停止");
        stopCapture();
        closeSockets();
        Discovery d = discovery;
        discovery = null;
        if (d != null) {
            d.stop();
        }
        Thread t = engineThread;
        engineThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseLocks();
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        broadcastState();
    }

    private void engineLoop() {
        Discovery.ServerAddr cached = loadCachedServer();
        if (cached != null) {
            Log.i(TAG, "使用上次缓存的电脑地址：" + cached);
        }
        int failStreak = 0;
        while (running) {
            Discovery.ServerAddr target = pendingServer;
            if (target != null) {
                pendingServer = null;
                cached = target;
                failStreak = 0;
            }
            if (cached == null) {
                cached = loadCachedServer();
            }
            if (cached == null) {
                setPhase("scanning", "正在扫描局域网中的电脑…");
                sleepQuiet(500);
                continue;
            }
            try {
                runSession(cached);
                failStreak = 0;
            } catch (Exception e) {
                failStreak++;
                Log.w(TAG, "连接 " + cached + " 失败（第 " + failStreak + " 次）：" + e);
                setPhase("retry", "连接 " + cached.host + " 失败：" + shortMsg(e) + "，正在重试…");
                if (failStreak >= 3) {
                    // 电脑可能换了 IP，丢弃缓存重新扫描
                    Log.i(TAG, "连续失败 " + failStreak + " 次，清除缓存并重新扫描");
                    clearCachedServer();
                    cached = null;
                    failStreak = 0;
                }
            } finally {
                stopCapture();
                closeSockets();
            }
            long backoff = Math.min(10000L, 1000L * (1L << Math.min(failStreak, 4)));
            state.reconnects++;
            broadcastState();
            sleepQuiet(backoff);
        }
        Log.i(TAG, "引擎线程结束");
    }

    /**
     * 建立一次完整会话：控制连接 → 握手 → 音频连接 → 按 START/STOP 推流。
     *
     * <p>方法返回即表示本次会话结束（正常断开或异常）。
     */
    private void runSession(Discovery.ServerAddr server) throws IOException {
        setPhase("connecting", "正在连接 " + server.name + " (" + server.host + ")…");
        Socket ctrl = new Socket();
        ctrl.setTcpNoDelay(true);
        ctrl.setSoTimeout(5000);
        ctrl.connect(new InetSocketAddress(server.host, server.controlPort), 5000);
        controlSocket = ctrl;
        Log.i(TAG, "控制通道已连接 " + server.host + ":" + server.controlPort);

        OutputStream ctrlOut = ctrl.getOutputStream();
        InputStream ctrlIn = ctrl.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(ctrlIn, StandardCharsets.UTF_8));

        // 1) 先读电脑的 HELLO，拿到音频端口与编码参数
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("电脑在握手前关闭了连接");
        }
        Log.i(TAG, "收到： " + line);
        if (!Proto.T_HELLO.equals(Proto.jsonString(line, "t"))) {
            throw new IOException("协议不匹配，电脑首包不是 HELLO：" + line);
        }
        int audioPort = Proto.jsonInt(line, "audio_port", server.audioPort);
        String codec = Proto.jsonString(line, "codec");
        if (codec != null && !Proto.CODEC_PCM_S16LE.equals(codec)) {
            throw new IOException("电脑要求的编码不支持：" + codec);
        }
        int srvRate = Proto.jsonInt(line, "rate", rate);
        int srvChannels = Proto.jsonInt(line, "channels", 1);
        if (srvChannels != 1) {
            throw new IOException("仅支持单声道，电脑要求 " + srvChannels + " 声道");
        }
        rate = srvRate;
        frameMs = Proto.jsonInt(line, "frame_ms", 20);
        state.rate = rate;
        state.frameMs = frameMs;

        // 2) 回 HELLO
        String hello = "{\"t\":\"HELLO\",\"v\":" + Proto.PROTOCOL_VERSION
                + ",\"device\":\"" + Proto.escape(Build.MODEL) + "\""
                + ",\"android\":" + Build.VERSION.SDK_INT
                + ",\"codec\":\"" + Proto.CODEC_PCM_S16LE + "\""
                + ",\"rate\":" + rate
                + ",\"channels\":1"
                + ",\"frame_ms\":" + frameMs + "}\n";
        ctrlOut.write(hello.getBytes(StandardCharsets.UTF_8));
        ctrlOut.flush();

        ServerSession session = new ServerSession(server, ctrl, ctrlOut, reader);
        session.openAudio(server.host, audioPort);

        saveCachedServer(server);
        state.connected = true;
        state.serverIp = server.host;
        state.serverName = server.name;
        state.lossPercent = 0;
        state.rttMs = -1;
        setPhase("connected", "已连接 " + server.name + "，" + (pushEnabled ? "等待电脑开始接收…" : "推流已关闭"));

        // 3) 事件循环
        long lastRecv = System.currentTimeMillis();
        long lastPingSent = 0;
        ctrl.setSoTimeout(500);
        while (running) {
            try {
                String msg = reader.readLine();
                if (msg == null) {
                    throw new IOException("电脑关闭了控制连接");
                }
                lastRecv = System.currentTimeMillis();
                Log.d(TAG, "收到： " + msg);
                session.handle(msg);
            } catch (SocketTimeoutException te) {
                // 读超时属于正常情况，用于周期性检查心跳
            }
            long now = System.currentTimeMillis();
            if (now - lastRecv > Proto.HEARTBEAT_TIMEOUT_MS) {
                throw new IOException("与电脑的心跳超时（" + (Proto.HEARTBEAT_TIMEOUT_MS / 1000) + " 秒无响应）");
            }
            if (now - lastPingSent >= 2000) {
                lastPingSent = now;
                // 手机侧也主动上报状态，电脑界面据此显示电平/码率
                session.sendStat();
            }
        }
    }

    /** 一次会话的上下文：音频 socket、统计与 START/STOP 处理。 */
    private final class ServerSession {
        private final Discovery.ServerAddr server;
        private final Socket ctrl;
        private final OutputStream ctrlOut;
        private final BufferedReader ctrlReader;
        private long lastFrameAt;
        private int frameCountInWindow;
        private long windowStart;

        ServerSession(Discovery.ServerAddr server, Socket ctrl, OutputStream ctrlOut, BufferedReader reader) {
            this.server = server;
            this.ctrl = ctrl;
            this.ctrlOut = ctrlOut;
            this.ctrlReader = reader;
            this.windowStart = System.currentTimeMillis();
        }

        void openAudio(String host, int port) throws IOException {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.setSoTimeout(0);
            s.setSendBufferSize(256 * 1024);
            s.connect(new InetSocketAddress(host, port), 5000);
            byte[] handshake = new byte[32];
            // session 字段（偏移 4..12）预留为 0，rate/channels/frame_ms 按大端写入
            writeInt(handshake, 12, rate);
            writeInt(handshake, 16, 1);
            writeInt(handshake, 20, frameMs);
            OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream(), 32 * 1024);
            out.write(handshake);
            out.flush();
            audioSocket = s;
            audioOut = out;
            Log.i(TAG, "音频通道已连接 " + host + ":" + port + "（" + rate + "Hz/" + frameMs + "ms）");
        }

        void handle(String msg) throws IOException {
            String t = Proto.jsonString(msg, "t");
            if (t == null) {
                return;
            }
            switch (t) {
                case Proto.T_START:
                    if (!pushEnabled) {
                        Log.i(TAG, "电脑请求 START，但本机推流已关闭，忽略");
                        return;
                    }
                    startCaptureIfNeeded();
                    setPhase("streaming", "正在把麦克风发送给 " + server.name);
                    break;
                case Proto.T_STOP:
                    stopCapture();
                    setPhase("connected", "已连接 " + server.name + "（电脑暂停接收）");
                    break;
                case Proto.T_PING:
                    send("{\"t\":\"PONG\",\"ts\":" + Proto.jsonInt(msg, "ts", 0) + "}");
                    break;
                case Proto.T_BYE:
                    throw new IOException("电脑已退出");
                default:
                    Log.d(TAG, "忽略未知控制报文：" + msg);
            }
        }

        void sendStat() {
            long now = System.currentTimeMillis();
            long df = framesSent.get() - lastStatFrames;
            long db = bytesSent.get() - lastStatBytes;
            double secs = Math.max(0.001, (now - lastStatAt) / 1000.0);
            lastStatAt = now;
            lastStatFrames = framesSent.get();
            lastStatBytes = bytesSent.get();
            state.fps = (int) Math.round(df / secs);
            state.kbps = (int) Math.round(db * 8.0 / 1000.0 / secs);
            state.levelDb = capture == null ? -120f : capture.lastDb();
            try {
                send("{\"t\":\"STAT\",\"fps\":" + state.fps
                        + ",\"kbps\":" + state.kbps
                        + ",\"db\":" + Math.round(state.levelDb) + "}");
            } catch (IOException e) {
                Log.d(TAG, "上报状态失败：" + e.getMessage());
            }
        }

        void send(String json) throws IOException {
            synchronized (writeLock) {
                OutputStream out = ctrlOut;
                out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        boolean isAlive() {
            return !ctrl.isClosed() && ctrl.isConnected();
        }

        long lastFrameAt() {
            return lastFrameAt;
        }
    }

    /* ================= 采集与发送 ================= */

    private void startCaptureIfNeeded() {
        if (capture != null) {
            return;
        }
        MicCapture c = new MicCapture(rate, frameMs, this::onPcm);
        c.start();
        capture = c;
        framesSent.set(0);
        bytesSent.set(0);
        lastStatAt = System.currentTimeMillis();
        lastStatFrames = 0;
        lastStatBytes = 0;
        state.streaming = true;
        broadcastState();
    }

    /** 采集回调：把一帧 PCM 打上长度前缀后写进音频 socket。 */
    private void onPcm(byte[] data, int offset, int length) {
        OutputStream out = audioOut;
        if (out == null) {
            return;
        }
        byte[] frame = new byte[length + 4];
        frame[0] = (byte) (length >>> 24);
        frame[1] = (byte) (length >>> 16);
        frame[2] = (byte) (length >>> 8);
        frame[3] = (byte) length;
        System.arraycopy(data, offset, frame, 4, length);
        try {
            // 与 STAT/PONG 共用同一把写锁，保证控制报文不会被切进音频流中间
            synchronized (writeLock) {
                OutputStream o = audioOut;
                if (o == null) {
                    return;
                }
                o.write(frame);
            }
            framesSent.incrementAndGet();
            bytesSent.addAndGet(length);
        } catch (IOException e) {
            Log.w(TAG, "发送音频失败：" + e.getMessage());
            // 让控制通道尽快发现异常并重建会话
            closeSockets();
        }
    }

    private void stopCapture() {
        MicCapture c = capture;
        capture = null;
        if (c != null) {
            c.stop();
        }
        if (state.streaming) {
            state.streaming = false;
            state.fps = 0;
            state.kbps = 0;
            state.levelDb = -120f;
            broadcastState();
        }
    }

    private void closeSockets() {
        OutputStream out = audioOut;
        audioOut = null;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        Socket a = audioSocket;
        audioSocket = null;
        if (a != null) {
            try {
                a.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        Socket c = controlSocket;
        controlSocket = null;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        state.connected = false;
    }

    /* ================= 地址缓存（自动重连的关键） ================= */

    private Discovery.ServerAddr loadCachedServer() {
        String host = prefs.getString(KEY_HOST, null);
        if (host == null || host.isEmpty()) {
            return null;
        }
        return new Discovery.ServerAddr(host,
                prefs.getInt(KEY_CTRL_PORT, Proto.DEFAULT_CONTROL_PORT),
                prefs.getInt(KEY_AUDIO_PORT, Proto.DEFAULT_AUDIO_PORT),
                prefs.getString(KEY_NAME, host));
    }

    private void saveCachedServer(Discovery.ServerAddr s) {
        String old = prefs.getString(KEY_HOST, null);
        prefs.edit()
                .putString(KEY_HOST, s.host)
                .putInt(KEY_CTRL_PORT, s.controlPort)
                .putInt(KEY_AUDIO_PORT, s.audioPort)
                .putString(KEY_NAME, s.name)
                .apply();
        if (old == null || !old.equals(s.host)) {
            Log.i(TAG, "已记住电脑地址：" + s.host + "（下次开机自动连接）");
        }
    }

    private void clearCachedServer() {
        prefs.edit().remove(KEY_HOST).apply();
    }

    /* ================= 发现回调 ================= */

    @Override
    public void onServerFound(Discovery.ServerAddr server) {
        Discovery.ServerAddr cur = pendingServer;
        if (cur != null && cur.sameAs(server)) {
            return;
        }
        Discovery.ServerAddr cached = loadCachedServer();
        if (cached != null && cached.sameAs(server) && state.connected) {
            // 已连上同一台电脑，不必打断会话
            return;
        }
        pendingServer = server;
        state.serverName = server.name;
        state.serverIp = server.host;
        if (!state.connected) {
            setPhase("found", "发现电脑 " + server.name + "，正在连接…");
        } else {
            broadcastState();
        }
    }

    /* ================= 状态与广播 ================= */

    private synchronized void setPhase(String phase, String detail) {
        state.phase = phase;
        state.detail = detail;
        Log.i(TAG, "状态=" + phase + " " + detail);
        broadcastState();
        updateNotification(detail);
    }

    private void broadcastState() {
        try {
            Intent i = new Intent(ACTION_STATE);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_STATE, state.toJson());
            sendBroadcast(i);
        } catch (Exception e) {
            Log.d(TAG, "广播状态失败：" + e.getMessage());
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private static void writeInt(byte[] buf, int off, int value) {
        buf[off] = (byte) (value >>> 24);
        buf[off + 1] = (byte) (value >>> 16);
        buf[off + 2] = (byte) (value >>> 8);
        buf[off + 3] = (byte) value;
    }
}
