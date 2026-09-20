package com.luomic.pc;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows 平台集成：防火墙放行、开机自启、VB-CABLE 虚拟声卡安装/卸载。
 *
 * <p>所有需要管理员权限的操作都会通过 PowerShell 的 {@code Start-Process -Verb RunAs}
 * 触发一次 UAC 确认；用户拒绝提权时返回 {@code false}，程序继续以普通权限运行。
 */
public final class Win {

    private Win() {
    }

    public static final String FW_RULE_UDP = "luo mic UDP 47777";
    public static final String FW_RULE_TCP = "luo mic TCP 47778-47779";
    public static final String AUTORUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    public static final String AUTORUN_NAME = "luo mic";

    /** 是否 Windows。 */
    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    /* ================= 防火墙 ================= */

    /** 放行 luo mic 需要的端口（需要管理员权限）。 */
    public static boolean addFirewallRules() {
        List<String> cmds = new ArrayList<>();
        cmds.add("netsh advfirewall firewall delete rule name=\"" + FW_RULE_UDP + "\" >nul 2>&1");
        cmds.add("netsh advfirewall firewall delete rule name=\"" + FW_RULE_TCP + "\" >nul 2>&1");
        cmds.add("netsh advfirewall firewall add rule name=\"" + FW_RULE_UDP
                + "\" dir=in action=allow protocol=UDP localport=47777");
        cmds.add("netsh advfirewall firewall add rule name=\"" + FW_RULE_TCP
                + "\" dir=in action=allow protocol=TCP localport=47778-47779");
        cmds.add("netsh advfirewall firewall add rule name=\"" + FW_RULE_UDP + " out\""
                + " dir=out action=allow protocol=UDP localport=47777");
        return runElevated(cmds);
    }

    /** 删除上面添加的规则（需要管理员权限）。 */
    public static boolean removeFirewallRules() {
        List<String> cmds = new ArrayList<>();
        cmds.add("netsh advfirewall firewall delete rule name=\"" + FW_RULE_UDP + "\"");
        cmds.add("netsh advfirewall firewall delete rule name=\"" + FW_RULE_TCP + "\"");
        cmds.add("netsh advfirewall firewall delete rule name=\"" + FW_RULE_UDP + " out\"");
        return runElevated(cmds);
    }

    /** 检查防火墙规则是否已存在（无需管理员权限）。 */
    public static boolean firewallRulesExist() {
        String out = runCapture(new String[]{"netsh", "advfirewall", "firewall", "show", "rule",
                "name=" + FW_RULE_TCP});
        return out != null && out.contains(FW_RULE_TCP);
    }

    /* ================= 开机自启 ================= */

    /** 写入 HKCU Run 实现开机自启（无需管理员权限）。 */
    public static boolean enableAutostart() {
        String cmd = launchCommand();
        if (cmd == null) {
            return false;
        }
        String line = "reg add \"" + AUTORUN_KEY + "\" /v \"" + AUTORUN_NAME
                + "\" /t REG_SZ /d \"\\\"" + cmd.replace("\"", "\\\"") + "\\\" --minimized\" /f";
        return runPlain(new String[]{"cmd", "/c", line});
    }

    public static boolean disableAutostart() {
        return runPlain(new String[]{"cmd", "/c", "reg delete \"" + AUTORUN_KEY
                + "\" /v \"" + AUTORUN_NAME + "\" /f"});
    }

    public static boolean autostartEnabled() {
        String out = runCapture(new String[]{"cmd", "/c", "reg query \"" + AUTORUN_KEY
                + "\" /v \"" + AUTORUN_NAME + "\""});
        return out != null && out.contains(AUTORUN_NAME);
    }

    /**
     * 计算“用来启动本程序”的命令行。
     *
     * @return 例如 {@code javaw.exe -jar C:\luo-mic\luo-mic.jar}，无法判断时返回 null
     */
    public static String launchCommand() {
        String javaHome = System.getProperty("java.home");
        String exe = new File(javaHome, "bin" + File.separator + "javaw.exe").getAbsolutePath();
        if (!new File(exe).exists()) {
            exe = new File(javaHome, "bin" + File.separator + "java.exe").getAbsolutePath();
        }
        if (!new File(exe).exists()) {
            exe = "javaw.exe";
        }
        String self = selfPath();
        if (self == null) {
            return null;
        }
        if (self.toLowerCase().endsWith(".jar")) {
            return exe + " -jar \"" + self + "\"";
        }
        return exe + " -cp \"" + self + "\" com.luomic.pc.Main";
    }

    /**
     * 本程序的位置：jar 文件路径，或 class 文件所在根目录（开发时运行）。
     *
     * @return 路径，无法判断时返回 null
     */
    public static String selfPath() {
        try {
            File f = new File(Win.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                return f.getAbsolutePath();
            }
            // 开发环境：指向 classes 根目录，便于 -cp 启动
            String cp = System.getProperty("java.class.path", "");
            for (String part : cp.split(File.pathSeparator)) {
                if (part.toLowerCase().endsWith(".jar") && new File(part).isFile()) {
                    return new File(part).getAbsolutePath();
                }
            }
            if (f.isDirectory()) {
                return f.getAbsolutePath();
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // 忽略
        }
        return null;
    }

    /* ================= VB-CABLE 虚拟声卡 ================= */

    /** 在 tools 目录里寻找 VB-CABLE 安装包。 */
    public static File findVbCableInstaller() {
        List<File> dirs = new ArrayList<>();
        String self = selfPath();
        if (self != null) {
            File base = new File(self).isDirectory() ? new File(self) : new File(self).getParentFile();
            if (base != null) {
                dirs.add(new File(base, "tools"));
                dirs.add(new File(base.getParentFile() == null ? base : base.getParentFile(), "tools"));
                File up = base.getParentFile();
                if (up != null && up.getParentFile() != null) {
                    dirs.add(new File(up.getParentFile(), "tools"));
                }
            }
        }
        dirs.add(new File("tools"));
        dirs.add(new File(".." + File.separator + "tools"));
        for (File dir : dirs) {
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (n.startsWith("vbcable") && n.endsWith(".exe")) {
                    return f;
                }
            }
        }
        return null;
    }

    /** 静默安装 VB-CABLE（需要管理员权限，安装后需重启电脑）。 */
    public static boolean installVbCable(File installer) {
        if (installer == null || !installer.isFile()) {
            return false;
        }
        List<String> cmds = new ArrayList<>();
        cmds.add("\"" + installer.getAbsolutePath() + "\" -i -h");
        return runElevated(cmds);
    }

    /** 静默卸载 VB-CABLE（需要管理员权限，卸载后需重启电脑）。 */
    public static boolean uninstallVbCable(File installer) {
        if (installer == null || !installer.isFile()) {
            return false;
        }
        List<String> cmds = new ArrayList<>();
        cmds.add("\"" + installer.getAbsolutePath() + "\" -u -h");
        return runElevated(cmds);
    }

    /* ================= 进程执行 ================= */

    /** 生成一个临时 bat，用管理员权限执行。 */
    private static boolean runElevated(List<String> commands) {
        File bat = null;
        try {
            bat = File.createTempFile("luomic-elev-", ".bat");
            StringBuilder sb = new StringBuilder();
            sb.append("@echo off\r\n");
            sb.append("chcp 65001 >nul\r\n");
            for (String c : commands) {
                sb.append(c).append("\r\n");
            }
            sb.append("exit /b 0\r\n");
            java.nio.file.Files.write(bat.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        // Start-Process -Verb RunAs 会弹出 UAC；-Wait 保证命令执行完再返回
        String ps = "Start-Process -FilePath '" + bat.getAbsolutePath().replace("'", "''")
                + "' -Verb RunAs -WindowStyle Hidden -Wait";
        boolean ok = runPlain(new String[]{"powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps});
        // 临时脚本延迟删除（此时可能仍被占用）
        bat.deleteOnExit();
        return ok;
    }

    /** 执行命令并等待结束，返回是否成功。 */
    public static boolean runPlain(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** 执行命令并捕获输出（用于查询状态）。 */
    public static String runCapture(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            return new String(out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
