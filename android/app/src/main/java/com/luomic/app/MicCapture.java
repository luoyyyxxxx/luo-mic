package com.luomic.app;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * 麦克风采集：把 {@link AudioRecord} 的 PCM 按固定帧长读出。
 *
 * <p>使用 {@link MediaRecorder.AudioSource#VOICE_RECOGNITION}：它不做通话降噪/AGC，
 * 保留原始音色，适合当电脑麦克风；若机型不支持会自动回退到 {@code MIC}。
 */
public class MicCapture {

    private static final String TAG = "luo-mic/capture";

    /** 采集回调，运行在采集线程上，实现方不要做耗时操作。 */
    public interface Sink {
        /**
         * @param data    PCM 数据（调用方复用缓冲区，需要留存请自行拷贝）
         * @param offset 起始偏移
         * @param length 有效长度
         */
        void onPcm(byte[] data, int offset, int length);
    }

    private final int rate;
    private final int frameMs;
    private final Sink sink;

    private volatile boolean running;
    private AudioRecord record;
    private Thread thread;
    private volatile float lastDb = -120f;

    public MicCapture(int rate, int frameMs, Sink sink) {
        this.rate = rate;
        this.frameMs = frameMs;
        this.sink = sink;
    }

    public int rate() {
        return rate;
    }

    public int frameMs() {
        return frameMs;
    }

    /** 最近一帧的电平（dBFS，-120 表示静音）。 */
    public float lastDb() {
        return lastDb;
    }

    /** 打开麦克风并启动采集线程。 */
    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (running) {
            return;
        }
        int frameBytes = rate * frameMs / 1000 * 2;
        int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            throw new IllegalStateException("设备不支持 " + rate + "Hz 单声道录音（AudioRecord 初始化失败）");
        }
        // 缓冲给足，避免采集线程被调度抖动导致丢帧
        int bufSize = Math.max(minBuf * 2, frameBytes * 8);

        AudioRecord rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, bufSize);
        if (rec == null) {
            Log.w(TAG, "VOICE_RECOGNITION 不可用，回退到 MIC");
            rec = open(MediaRecorder.AudioSource.MIC, rate, bufSize);
        }
        if (rec == null) {
            throw new IllegalStateException("无法打开麦克风，请检查录音权限是否已授予");
        }
        record = rec;
        running = true;
        rec.startRecording();
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            running = false;
            rec.release();
            record = null;
            throw new IllegalStateException("麦克风启动失败（可能被其他应用占用）");
        }

        thread = new Thread(() -> loop(frameBytes), "luomic-capture");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "采集已开始：" + rate + "Hz / " + frameMs + "ms / " + frameBytes + "B 每帧");
    }

    private AudioRecord open(int source, int rate, int bufSize) {
        try {
            AudioRecord rec = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return null;
            }
            return rec;
        } catch (Exception e) {
            Log.w(TAG, "AudioRecord 打开失败(" + source + ")：" + e);
            return null;
        }
    }

    private void loop(int frameBytes) {
        byte[] buf = new byte[frameBytes];
        while (running) {
            AudioRecord rec = record;
            if (rec == null) {
                break;
            }
            int n = rec.read(buf, 0, frameBytes);
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                    Log.w(TAG, "read 返回错误码 " + n + "，停止采集");
                    break;
                }
                continue;
            }
            lastDb = computeDb(buf, n);
            try {
                sink.onPcm(buf, 0, n);
            } catch (Exception e) {
                Log.w(TAG, "发送帧失败：" + e);
                break;
            }
        }
        Log.i(TAG, "采集线程结束");
    }

    /** 峰值电平转 dBFS，返回 -120 ~ 0。 */
    private static float computeDb(byte[] data, int len) {
        int peak = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int lo = data[i] & 0xFF;
            int hi = data[i + 1];
            int v = (hi << 8) | lo;
            int a = v < 0 ? -v : v;
            if (a > peak) {
                peak = a;
            }
        }
        if (peak <= 0) {
            return -120f;
        }
        double db = 20.0 * Math.log10(peak / 32768.0);
        return (float) Math.max(-120.0, db);
    }

    public synchronized void stop() {
        running = false;
        AudioRecord rec = record;
        record = null;
        if (rec != null) {
            try {
                if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop();
                }
            } catch (Exception ignored) {
                // 停止失败直接释放
            }
            rec.release();
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastDb = -120f;
        Log.i(TAG, "采集已停止");
    }
}
