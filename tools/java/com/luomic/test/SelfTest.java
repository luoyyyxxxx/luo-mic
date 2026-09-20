package com.luomic.test;

import com.luomic.pc.AudioPlayer;
import com.luomic.pc.Server;

import java.lang.reflect.Method;

/**
 * 一键自检：在同一个进程里启动电脑端服务，再用假手机走完整协议推流若干秒。
 *
 * <p>用法：{@code java -cp out com.luomic.test.SelfTest [秒数]}（默认 8 秒）
 *
 * <p>退出码 0 表示通过，1 表示失败。
 */
public final class SelfTest {

    private SelfTest() {
    }

    /** 从统计文本里取出“播放 N 帧”的 N。 */
    private static int playedFrames(String text) {
        if (text == null) {
            return 0;
        }
        int i = text.indexOf("播放 ");
        if (i < 0) {
            return 0;
        }
        int j = i + 3;
        StringBuilder sb = new StringBuilder();
        while (j < text.length() && Character.isDigit(text.charAt(j))) {
            sb.append(text.charAt(j++));
        }
        try {
            return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        AudioPlayer player = new AudioPlayer(msg -> System.out.println("[player] " + msg));
        final boolean[] everConnected = {false};
        final int[] maxPlayed = {0};

        Server server = new Server(new Server.Listener() {
            @Override
            public void onLog(String line) {
                System.out.println("[server] " + line);
            }

            @Override
            public void onSession(boolean c, String phoneName, String phoneIp) {
                if (c) {
                    everConnected[0] = true;
                }
                System.out.println("[session] " + c + " " + phoneName + " @" + phoneIp);
            }

            @Override
            public void onStats(String text, float levelDb, boolean streaming) {
                maxPlayed[0] = Math.max(maxPlayed[0], playedFrames(text));
                System.out.println("[stats] " + text + " | " + Math.round(levelDb) + "dB | streaming=" + streaming);
            }

            @Override
            public void onError(String message) {
                System.out.println("[error] " + message);
            }
        }, player);

        server.setAutoStart(true);
        server.setDevice(null, AudioPlayer.VIRTUAL_DEVICE_NAME);
        server.start();

        // 反射调用假手机，避免两个 main 相互干扰
        Thread sim = new Thread(() -> {
            try {
                Method m = PhoneSim.class.getDeclaredMethod("main", String[].class);
                m.setAccessible(true);
                m.invoke(null, (Object) new String[]{"--probe-host", "127.0.0.1", "--seconds", String.valueOf(seconds)});
            } catch (Exception e) {
                System.out.println("[sim] 异常：" + e.getCause());
            }
        }, "phone-sim");
        sim.start();
        sim.join();

        Thread.sleep(1500);
        long[] st = player.stats();
        server.stop();

        System.out.println();
        System.out.println("========== 自检结果 ==========");
        long played = Math.max(maxPlayed[0], st[0]);
        System.out.println("手机曾连接     : " + (everConnected[0] ? "是" : "否"));
        System.out.println("播放帧数       : " + played + "（会话内）/" + st[0] + "（累计）");
        System.out.println("缓冲丢弃帧数   : " + st[1]);
        System.out.println("补静音帧数     : " + st[2]);
        boolean ok = everConnected[0] && played > seconds * 25L;
        System.out.println("期望           : 连接成功且播放帧数 > " + (seconds * 25) + "（理论约 " + (seconds * 50) + " 帧）");
        System.out.println("结论           : " + (ok ? "通过 ✅" : "失败 ❌"));
        System.exit(ok ? 0 : 1);
    }
}
