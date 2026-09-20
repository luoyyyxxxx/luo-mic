package com.luomic.pc;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * luo mic 电脑端入口。
 *
 * <p>命令行参数：
 * <ul>
 *   <li>无参数：打开图形界面</li>
 *   <li>{@code --minimized}：启动后最小化（开机自启用）</li>
 *   <li>{@code --install}：放行防火墙 + 设置开机自启，然后退出</li>
 *   <li>{@code --uninstall}：撤销上面的设置，然后退出</li>
 *   <li>{@code --list-devices}：列出所有音频输出设备（排查问题用）</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        boolean minimized = false;
        for (String a : args) {
            if ("--minimized".equalsIgnoreCase(a) || "-m".equalsIgnoreCase(a)) {
                minimized = true;
            } else if ("--install".equalsIgnoreCase(a)) {
                runInstall();
                return;
            } else if ("--uninstall".equalsIgnoreCase(a)) {
                runUninstall();
                return;
            } else if ("--list-devices".equalsIgnoreCase(a)) {
                listDevices();
                return;
            } else if ("--help".equalsIgnoreCase(a) || "-h".equalsIgnoreCase(a)) {
                System.out.println("luo mic — 手机麦克风接入电脑\n"
                        + "用法：java -jar luo-mic.jar [--minimized|--install|--uninstall|--list-devices]");
                return;
            }
        }

        if (!Win.isWindows()) {
            System.out.println("提示：luo mic 的电脑端为 Windows 设计，当前系统是 "
                    + System.getProperty("os.name") + "，音频部分可能不可用。");
        }

        MainWindow.applyLookAndFeel();
        final boolean startMinimized = minimized;
        SwingUtilities.invokeLater(() -> {
            MainWindow w = new MainWindow();
            if (startMinimized) {
                w.setState(java.awt.Frame.ICONIFIED);
            }
            w.setVisible(true);
            if (Win.isWindows() && !Win.firewallRulesExist()) {
                w.log("检测到防火墙尚未放行：点右下角“放行防火墙”可一键放行（需要管理员确认）。");
            }
        });
    }

    private static void runInstall() {
        System.out.println("luo mic 安装助手");
        boolean fw = Win.addFirewallRules();
        System.out.println(fw ? "[完成] 防火墙已放行 UDP 47777 / TCP 47778-47779"
                : "[失败] 防火墙放行失败（未同意 UAC 提权？）");
        boolean run = Win.enableAutostart();
        System.out.println(run ? "[完成] 已设置开机自动启动（可在界面里取消）"
                : "[失败] 开机自启设置失败");
        if (!Win.isWindows()) {
            System.out.println("[跳过] 当前不是 Windows 系统");
        }
    }

    private static void runUninstall() {
        System.out.println("luo mic 卸载助手");
        boolean fw = Win.removeFirewallRules();
        System.out.println(fw ? "[完成] 已删除防火墙规则" : "[失败] 删除防火墙规则失败");
        boolean run = Win.disableAutostart();
        System.out.println(run ? "[完成] 已取消开机自启" : "[失败] 取消开机自启失败");
    }

    private static void listDevices() {
        System.out.println("可用的音频输出设备：");
        int i = 0;
        for (AudioPlayer.Device d : AudioPlayer.listDevices()) {
            System.out.println("  [" + (i++) + "] " + d.name
                    + (AudioPlayer.looksVirtual(d.name) ? "   <-- 虚拟声卡" : ""));
        }
        if (i == 0) {
            System.out.println("  （没有找到可播放 48kHz 单声道 PCM 的设备）");
        }
        System.out.println("\n在界面“播放到”下拉框里选择对应设备即可。");
    }

    /** 供界面弹出的简单错误框。 */
    static void fatal(String msg) {
        JOptionPane.showMessageDialog(null, msg, "luo mic 启动失败", JOptionPane.ERROR_MESSAGE);
    }
}
