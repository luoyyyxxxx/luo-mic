package com.luomic.test;

import com.luomic.pc.AudioPlayer;
import com.luomic.pc.Server;

/**
 * 无界面自检：启动电脑端服务并统计收到的音频，用于在没有声卡的机器（CI/服务器）上验证协议。
 *
 * <p>用法：{@code java -cp out com.luomic.test.HeadlessServer [运行秒数]}
 */
public final class HeadlessServer {

    private HeadlessServer() {
    }

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 15;
        AudioPlayer player = new AudioPlayer(msg -> System.out.println("[player] " + msg));
        Server server = new Server(new Server.Listener() {
            @Override
            public void onLog(String line) {
                System.out.println("[server] " + line);
            }

            @Override
            public void onSession(boolean connected, String phoneName, String phoneIp) {
                System.out.println("[session] " + (connected ? "手机已连接 " + phoneName + " @" + phoneIp : "手机已断开"));
            }

            @Override
            public void onStats(String text, float levelDb, boolean streaming) {
                System.out.println("[stats] " + text + " | level=" + Math.round(levelDb) + "dB | streaming=" + streaming);
            }

            @Override
            public void onError(String message) {
                System.out.println("[error] " + message);
            }
        }, player);

        server.setAutoStart(true);
        server.setDevice(null, AudioPlayer.VIRTUAL_DEVICE_NAME);
        server.start();
        System.out.println("[main] 服务已启动，等待 " + seconds + " 秒…");

        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        long[] st = player.stats();
        System.out.println("[main] 播放帧数=" + st[0] + " 丢弃=" + st[1] + " 补静音=" + st[2]);
        server.stop();
        System.out.println("[main] 自检结束");
        System.exit(0);
    }
}
