package com.luomic.pc;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 音频输出：把手机推来的 PCM 帧写进选定的播放设备（真实声卡或虚拟声卡）。
 *
 * <p>延迟策略：队列里始终保留 {@link Proto#JITTER_TARGET_FRAMES} 帧左右作为抖动缓冲，
 * 队列空了写静音（避免爆音），队列过长丢弃最旧帧（避免延迟越积越大）。
 * 因此“开口说话”到“电脑出声”的端到端延迟 ≈ 一个帧长 + 目标缓冲（默认约 80ms）。
 */
public class AudioPlayer {

    /** 设备列表项。 */
    public static final class Device {
        public final Mixer.Info info;
        public final String name;
        public final int maxChannels;

        Device(Mixer.Info info, String name, int maxChannels) {
            this.info = info;
            this.name = name;
            this.maxChannels = maxChannels;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** 运行状态回调。 */
    public interface Listener {
        void onError(String message);
    }

    /**
     * 枚举所有能播放 48kHz 单声道 16bit PCM 的输出设备。
     *
     * <p>在没有可用声卡的机器（例如服务器、Linux 测试环境）上会返回一个“虚拟设备（丢弃输出）”，
     * 让整套流程仍可跑通与自检。
     *
     * @return 设备列表
     */
    public static List<Device> listDevices() {
        List<Device> out = new ArrayList<>();
        AudioFormat probe = new AudioFormat(48000f, 16, 1, true, false);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mi);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, probe, 4096);
                if (!mixer.isLineSupported(info)) {
                    continue;
                }
                int max = 1;
                try {
                    max = mixer.getMaxLines(info);
                } catch (Exception ignored) {
                    // 部分驱动不实现该查询，默认按 1 处理
                }
                out.add(new Device(mi, describe(mi), max));
            } catch (Throwable t) {
                // 某些设备枚举时会抛异常，跳过即可
            }
        }
        if (out.isEmpty()) {
            out.add(new Device(null, VIRTUAL_DEVICE_NAME, 1));
        }
        return out;
    }

    /** 没有真实声卡时使用的占位设备名。 */
    public static final String VIRTUAL_DEVICE_NAME = "虚拟设备（丢弃输出，仅自检用）";

    public static boolean isVirtualDevice(String name) {
        return VIRTUAL_DEVICE_NAME.equals(name);
    }

    private static String describe(Mixer.Info mi) {
        String name = mi.getName();
        String desc = mi.getDescription();
        if (desc == null || desc.isEmpty() || desc.equals(name)) {
            return name;
        }
        return name + "  ·  " + desc;
    }

    private final Listener listener;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();
    private final AtomicLong underruns = new AtomicLong();

    private volatile boolean running;
    private Thread writer;
    private SourceDataLine line;
    private volatile String openedDeviceName = "未打开";
    private int frameBytes;
    private volatile int sampleRate = 48000;
    private volatile float levelDb = -120f;
    private volatile long lastFrameAt;

    public AudioPlayer(Listener listener) {
        this.listener = listener;
    }

    /**
     * 打开设备并开始播放（队列为空时输出静音）。
     *
     * @param mixerInfo   目标设备，{@code null} 表示系统默认设备
     * @param rate        采样率
     * @param frameMs     帧长（毫秒）
     * @param deviceLabel 设备名，仅用于界面展示
     * @throws LineUnavailableException 设备被占用或不支持该格式
     */
    public void start(Mixer.Info mixerInfo, int rate, int frameMs, String deviceLabel)
            throws LineUnavailableException {
        stop();
        frameBytes = Proto.bytesPerFrame(rate, frameMs, 1);
        sampleRate = rate;
        AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
        int bufBytes = Math.max(frameBytes * 4, rate * 2 * Proto.OUTPUT_BUFFER_MS / 1000);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt, bufBytes);

        SourceDataLine l;
        boolean discard = VIRTUAL_DEVICE_NAME.equals(deviceLabel);
        if (discard) {
            l = null;
        } else if (mixerInfo == null) {
            l = (SourceDataLine) AudioSystem.getLine(info);
        } else {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            l = (SourceDataLine) mixer.getLine(info);
        }
        if (l != null) {
            l.open(fmt, bufBytes);
            l.start();
        }
        line = l;
        openedDeviceName = deviceLabel == null ? "系统默认设备" : deviceLabel;
        running = true;
        queue.clear();
        writer = new Thread(this::writeLoop, "luomic-audio-out");
        writer.setDaemon(true);
        writer.start();
    }

    /** 是否正在播放。 */
    public boolean isRunning() {
        return running;
    }

    /** 当前选用设备名（用于界面显示）。 */
    public String deviceName() {
        return openedDeviceName;
    }

    /** 收到一帧 PCM 数据（来自音频 socket）。 */
    public void push(byte[] pcm, int length) {
        if (!running) {
            return;
        }
        lastFrameAt = System.currentTimeMillis();
        levelDb = computeDb(pcm, length);
        if (queue.size() >= Proto.JITTER_MAX_FRAMES) {
            if (queue.poll() != null) {
                framesDropped.incrementAndGet();
            }
        }
        byte[] copy = new byte[length];
        System.arraycopy(pcm, 0, copy, 0, length);
        queue.offer(copy);
    }

    private void writeLoop() {
        SourceDataLine l = line;
        byte[] silence = new byte[frameBytes];
        if (l == null) {
            // 虚拟设备：按实时速度丢弃数据，保证统计信息与真实设备一致
            long deadline = System.nanoTime();
            while (running) {
                byte[] frame;
                try {
                    frame = queue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (frame == null) {
                    deadline = System.nanoTime();
                    continue;
                }
                framesWritten.incrementAndGet();
                deadline += frameBytes / 2L * 1_000_000_000L / sampleRate;
                long waitNanos = deadline - System.nanoTime();
                if (waitNanos > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(waitNanos);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else if (waitNanos < -200_000_000L) {
                    deadline = System.nanoTime();
                }
            }
            return;
        }
        while (running && l != null) {
            byte[] frame = null;
            try {
                frame = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (frame == null) {
                if (!queue.isEmpty()) {
                    continue;
                }
                // 手机没在推流：输出静音保持设备时钟
                l.write(silence, 0, silence.length);
                if (System.currentTimeMillis() - lastFrameAt > 1000) {
                    levelDb = -120f;
                }
                continue;
            }
            try {
                if (frame.length == frameBytes) {
                    l.write(frame, 0, frame.length);
                } else {
                    // 长度异常的帧用静音补齐，保证设备时钟连续
                    byte[] full = new byte[frameBytes];
                    System.arraycopy(frame, 0, full, 0, Math.min(frame.length, frameBytes));
                    l.write(full, 0, full.length);
                    underruns.incrementAndGet();
                }
                framesWritten.incrementAndGet();
            } catch (Exception e) {
                if (running && listener != null) {
                    listener.onError("音频输出失败：" + e.getMessage());
                }
                break;
            }
        }
        if (listener != null && running) {
            listener.onError("音频输出线程已退出");
        }
    }

    /** 统计快照：[已播放帧, 丢弃帧, 补静音帧]。 */
    public long[] stats() {
        return new long[]{framesWritten.get(), framesDropped.get(), underruns.get()};
    }

    /** 输入电平（dBFS）。 */
    public float levelDb() {
        return levelDb;
    }

    /** 最近一次收到音频数据的时间戳，0 表示从未收到。 */
    public long lastFrameAt() {
        return lastFrameAt;
    }

    public void stop() {
        running = false;
        Thread t = writer;
        writer = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try {
                l.stop();
            } catch (Exception ignored) {
                // 忽略
            }
            try {
                l.flush();
            } catch (Exception ignored) {
                // 忽略
            }
            l.close();
        }
        queue.clear();
        levelDb = -120f;
        lastFrameAt = 0L;
    }

    private static float computeDb(byte[] data, int len) {
        int peak = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int v = (data[i + 1] << 8) | (data[i] & 0xFF);
            int a = v < 0 ? -v : v;
            if (a > peak) {
                peak = a;
            }
        }
        if (peak <= 0) {
            return -120f;
        }
        return (float) Math.max(-120.0, 20.0 * Math.log10(peak / 32768.0));
    }

    /** 判断设备是否是虚拟声卡（用于界面提示“输出给其它软件用”）。 */
    public static boolean looksVirtual(String deviceName) {
        if (deviceName == null) {
            return false;
        }
        String n = deviceName.toLowerCase();
        return n.contains("cable") || n.contains("virtual") || n.contains("vb-audio")
                || n.contains("voicemeeter") || n.contains("虚拟") || n.contains("loopback");
    }

    /** 关闭时忽略异常的小工具。 */
    public static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
