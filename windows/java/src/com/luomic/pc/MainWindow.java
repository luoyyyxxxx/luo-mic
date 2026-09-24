package com.luomic.pc;

import javax.sound.sampled.Mixer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.net.URI;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * luo mic 电脑端主界面：启动/停止服务、选择音频输出设备、查看连接状态与日志。
 */
public class MainWindow extends JFrame implements Server.Listener {

    private static final Color BG = new Color(0x0E1116);
    private static final Color CARD = new Color(0x171B22);
    private static final Color STROKE = new Color(0x262C36);
    private static final Color ACCENT = new Color(0x3DDC97);
    private static final Color WARN = new Color(0xFFB020);
    private static final Color TEXT = new Color(0xE9EDF2);
    private static final Color MUTED = new Color(0x9AA4B2);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AudioPlayer player;
    private final Server server;

    private final JLabel stateLabel = new JLabel("未启动");
    private final JLabel phoneLabel = new JLabel("—");
    private final JLabel ipLabel = new JLabel("—");
    private final JLabel deviceLabel = new JLabel("—");
    private final JLabel statsLabel = new JLabel(" ");
    private final JProgressBar levelBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();
    private final JComboBox<AudioPlayer.Device> deviceCombo = new JComboBox<>();
    private final JButton startButton = new JButton("启动服务");
    private final JCheckBox autostartCheck = new JCheckBox("开机自动启动");
    private final JRadioButton pushRadio = new JRadioButton("开始接收（推流）", true);
    private final JRadioButton muteRadio = new JRadioButton("暂停接收（省流量）");

    private final javax.swing.Timer timer;

    public MainWindow() {
        super("luo mic — 手机麦克风 → 电脑");
        this.player = new AudioPlayer(this::onError);
        this.server = new Server(this, player);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(880, 620));
        setSize(940, 660);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        refreshDevices();
        autostartCheck.setSelected(Win.isWindows() && Win.autostartEnabled());
        autostartCheck.setEnabled(Win.isWindows());
        log("欢迎使用 luo mic。手机与电脑需连接同一个 Wi-Fi / 局域网。");
        log("本机局域网地址：" + String.join("，", Server.localIpv4List()));

        // 1 秒刷新一次运行状态与电平
        timer = new javax.swing.Timer(1000, e -> tick());
        timer.start();
    }

    /* ================= 界面搭建 ================= */

    private JPanel buildHeader() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG);
        outer.setBorder(new EmptyBorder(16, 18, 8, 18));

        JPanel title = new JPanel();
        title.setLayout(new BoxLayout(title, BoxLayout.Y_AXIS));
        title.setBackground(BG);
        JLabel t = new JLabel("luo mic");
        t.setForeground(TEXT);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 26f));
        JLabel sub = new JLabel("把手机麦克风接入这台电脑 · 局域网自动发现 · 断线自动重连");
        sub.setForeground(MUTED);
        sub.setFont(sub.getFont().deriveFont(12f));
        title.add(t);
        title.add(Box.createVerticalStrut(4));
        title.add(sub);
        outer.add(title, BorderLayout.WEST);

        JPanel status = new JPanel(new GridLayout(4, 2, 8, 4));
        status.setBackground(CARD);
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(STROKE),
                new EmptyBorder(12, 16, 12, 16)));
        status.add(muted("服务状态"));
        stateLabel.setForeground(WARN);
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD, 14f));
        status.add(stateLabel);
        status.add(muted("已连接手机"));
        phoneLabel.setForeground(TEXT);
        status.add(phoneLabel);
        status.add(muted("本机 IP（手机同网段）"));
        ipLabel.setForeground(TEXT);
        ipLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        status.add(ipLabel);
        status.add(muted("音频输出设备"));
        deviceLabel.setForeground(TEXT);
        status.add(deviceLabel);

        outer.add(status, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildCenter() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        panel.setBorder(new EmptyBorder(8, 18, 8, 18));

        // 设备选择行
        JPanel devRow = new JPanel(new BorderLayout(10, 0));
        devRow.setBackground(BG);
        devRow.add(muted("播放到："), BorderLayout.WEST);
        deviceCombo.setBackground(CARD);
        deviceCombo.setForeground(TEXT);
        deviceCombo.setPreferredSize(new Dimension(420, 30));
        devRow.add(deviceCombo, BorderLayout.CENTER);

        JButton refresh = new JButton("刷新设备");
        refresh.addActionListener(e -> refreshDevices());
        JButton virtual = new JButton("虚拟声卡帮助");
        virtual.addActionListener(e -> showVirtualMicHelp());
        JPanel devButtons = new JPanel(new GridLayout(1, 2, 8, 0));
        devButtons.setBackground(BG);
        devButtons.add(refresh);
        devButtons.add(virtual);
        devRow.add(devButtons, BorderLayout.EAST);
        panel.add(devRow);
        panel.add(Box.createVerticalStrut(12));

        // 推流开关 + 开机自启
        JPanel options = new JPanel(new GridLayout(1, 3, 12, 0));
        options.setBackground(BG);
        ButtonGroup bg = new ButtonGroup();
        bg.add(pushRadio);
        bg.add(muteRadio);
        pushRadio.setBackground(BG);
        pushRadio.setForeground(TEXT);
        muteRadio.setBackground(BG);
        muteRadio.setForeground(TEXT);
        pushRadio.addActionListener(e -> applyPushMode());
        muteRadio.addActionListener(e -> applyPushMode());
        options.add(pushRadio);
        options.add(muteRadio);
        autostartCheck.setBackground(BG);
        autostartCheck.setForeground(TEXT);
        autostartCheck.addActionListener(e -> toggleAutostart());
        options.add(autostartCheck);
        panel.add(options);
        panel.add(Box.createVerticalStrut(12));

        // 电平条
        JPanel levelRow = new JPanel(new BorderLayout(10, 0));
        levelRow.setBackground(BG);
        levelRow.add(muted("麦克风电平："), BorderLayout.WEST);
        levelBar.setValue(0);
        levelBar.setForeground(ACCENT);
        levelBar.setBackground(CARD);
        levelBar.setBorderPainted(false);
        levelRow.add(levelBar, BorderLayout.CENTER);
        statsLabel.setForeground(MUTED);
        statsLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        levelRow.add(statsLabel, BorderLayout.SOUTH);
        panel.add(levelRow);
        panel.add(Box.createVerticalStrut(12));

        // 日志
        logArea.setEditable(false);
        logArea.setBackground(CARD);
        logArea.setForeground(TEXT);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBorder(new EmptyBorder(10, 12, 10, 12));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(STROKE));
        scroll.setPreferredSize(new Dimension(100, 240));
        panel.add(scroll);
        return panel;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.setBackground(BG);
        footer.setBorder(new EmptyBorder(8, 18, 16, 18));

        startButton.setFont(startButton.getFont().deriveFont(Font.BOLD, 15f));
        startButton.setPreferredSize(new Dimension(160, 42));
        startButton.setBackground(ACCENT);
        startButton.setForeground(new Color(0x08130E));
        startButton.addActionListener(e -> toggleServer());
        footer.add(startButton, BorderLayout.WEST);

        JPanel right = new JPanel(new GridLayout(1, 3, 8, 0));
        right.setBackground(BG);
        JButton fw = new JButton("放行防火墙");
        fw.addActionListener(e -> addFirewall());
        JButton vb = new JButton("安装虚拟声卡");
        vb.addActionListener(e -> installVbCable());
        JButton clear = new JButton("清空日志");
        clear.addActionListener(e -> logArea.setText(""));
        right.add(fw);
        right.add(vb);
        right.add(clear);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private JLabel muted(String s) {
        JLabel l = new JLabel(s);
        l.setForeground(MUTED);
        l.setFont(l.getFont().deriveFont(12f));
        return l;
    }

    /* ================= 交互逻辑 ================= */

    private void toggleServer() {
        if (server.isRunning()) {
            server.stop();
            startButton.setText("启动服务");
            stateLabel.setText("已停止");
            stateLabel.setForeground(WARN);
            levelBar.setValue(0);
            phoneLabel.setText("—");
            deviceLabel.setText("—");
            return;
        }
        applySelectionToServer();
        try {
            server.start();
            startButton.setText("停止服务");
            stateLabel.setText("等待手机连接…");
            stateLabel.setForeground(WARN);
            ipLabel.setText(String.join("  ", Server.localIpv4List()));
        } catch (Exception e) {
            stateLabel.setText("启动失败");
            stateLabel.setForeground(new Color(0xFF5A5A));
            errorDialog("服务启动失败：\n" + e.getMessage()
                    + "\n\n常见原因：\n"
                    + "1) 端口 47777/47778/47779 已被其它程序占用（可先关闭 luo mic 的另一个实例）；\n"
                    + "2) 防火墙拦住了监听（点右下角“放行防火墙”）。");
        }
    }

    private void applySelectionToServer() {
        AudioPlayer.Device d = (AudioPlayer.Device) deviceCombo.getSelectedItem();
        if (d == null) {
            server.setDevice(null, "系统默认设备");
        } else {
            Mixer.Info mi = d.info;
            server.setDevice(mi, d.name);
        }
        applyPushMode();
    }

    private void applyPushMode() {
        boolean receive = pushRadio.isSelected();
        server.setAutoStart(receive);
        log(receive ? "已切换到“开始接收”：手机连接后立即推流。"
                : "已切换到“暂停接收”：手机保持连接，但电脑不打开音频输出、也不采集麦克风。");
    }

    private void refreshDevices() {
        List<AudioPlayer.Device> devices = AudioPlayer.listDevices();
        DefaultComboBoxModel<AudioPlayer.Device> model = new DefaultComboBoxModel<>();
        AudioPlayer.Device defaultDevice = new AudioPlayer.Device(null, "系统默认播放设备", 1);
        model.addElement(defaultDevice);
        AudioPlayer.Device virtual = null;
        for (AudioPlayer.Device d : devices) {
            model.addElement(d);
            if (virtual == null && AudioPlayer.looksVirtual(d.name)) {
                virtual = d;
            }
        }
        AudioPlayer.Device previous = (AudioPlayer.Device) deviceCombo.getSelectedItem();
        deviceCombo.setModel(model);
        if (previous != null) {
            for (int i = 0; i < model.getSize(); i++) {
                if (model.getElementAt(i).name.equals(previous.name)) {
                    deviceCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        log("检测到 " + devices.size() + " 个可用播放设备"
                + (virtual != null ? "，发现虚拟声卡：" + virtual.name : "，未发现虚拟声卡（CABLE Input）"));
        String sel = selectedName();
        deviceLabel.setText(sel + (AudioPlayer.looksVirtual(sel) ? "（虚拟声卡）" : ""));
    }

    private String selectedName() {
        AudioPlayer.Device d = (AudioPlayer.Device) deviceCombo.getSelectedItem();
        return d == null ? "系统默认播放设备" : d.name;
    }

    private void toggleAutostart() {
        if (!Win.isWindows()) {
            autostartCheck.setSelected(false);
            log("开机自启仅支持 Windows。");
            return;
        }
        boolean want = autostartCheck.isSelected();
        boolean ok = want ? Win.enableAutostart() : Win.disableAutostart();
        log(ok ? (want ? "已设置开机自动启动。" : "已取消开机自动启动。")
                : "设置开机自启失败（可尝试以管理员身份运行）。");
    }

    private void addFirewall() {
        if (!Win.isWindows()) {
            log("防火墙设置仅支持 Windows。");
            return;
        }
        log("正在请求管理员权限放行防火墙端口…（如弹出 UAC 请选择“是”）");
        boolean ok = Win.addFirewallRules();
        log(ok ? "防火墙已放行 UDP 47777 / TCP 47778-47779。"
                : "防火墙放行失败或被取消；可手动执行 windows/tools/firewall.bat（右键以管理员身份运行）。");
    }

    private void installVbCable() {
        if (!Win.isWindows()) {
            log("虚拟声卡安装仅支持 Windows。");
            return;
        }
        // 1) 先用用户放进 tools 目录的安装包
        File installer = Win.findVbCableInstaller();
        if (installer == null) {
            // 2) 没有就自动从官方下载（VB-Audio 官网直链）
            int r = JOptionPane.showConfirmDialog(this,
                    "本机还没有 VB-CABLE 虚拟声卡。\n\n"
                            + "需要从 VB-Audio 官网下载驱动包（约 1MB）并安装，装完要重启电脑。\n"
                            + "这样微信/QQ/游戏里的麦克风就能选到手机的声音（和 WO Mic 效果一样）。\n\n"
                            + "现在自动下载并安装？",
                    "安装虚拟声卡", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return;
            }
            log("正在从 vb-audio.com 下载 VB-CABLE 驱动包（约 1MB，请稍候）…");
            File dir = Win.downloadVbCable(null);
            if (dir == null) {
                log("自动下载失败。请手动到 https://vb-audio.com/Cable/ 下载，"
                        + "把 VBCABLE_Setup_x64.exe 放进 windows\\tools\\ 后再点这个按钮。");
                openUri("https://vb-audio.com/Cable/");
                return;
            }
            installer = Win.findVbCableInstaller();
            if (installer == null) {
                File[] exes = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".exe"));
                if (exes != null && exes.length > 0) {
                    installer = exes[0];
                }
            }
            if (installer == null) {
                log("下载完成但没找到安装程序，请手动运行 " + dir.getAbsolutePath());
                return;
            }
            log("已下载：" + installer.getName());
        } else {
            int r = JOptionPane.showConfirmDialog(this,
                    "将静默安装 VB-CABLE 虚拟声卡（来源：VB-Audio，安装后需要重启电脑）。\n\n安装包："
                            + installer.getName() + "\n\n继续？",
                    "安装虚拟声卡", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return;
            }
        }

        log("正在安装 VB-CABLE（会弹出 UAC，请选“是”）…");
        boolean ok = Win.installVbCable(installer);
        if (ok) {
            log("安装命令已执行。请【重启电脑】，然后：");
            log("  1) “播放到”选择 CABLE Input (VB-Audio Virtual Cable)");
            log("  2) 微信/QQ/游戏里的麦克风选择 CABLE Output (VB-Audio Virtual Cable)");
            JOptionPane.showMessageDialog(this,
                    "安装完成！\n\n请重启电脑（驱动需要重启才生效）。\n"
                            + "重启后打开本程序：\n"
                            + "  · “播放到”选 CABLE Input (VB-Audio Virtual Cable)\n"
                            + "  · 微信/QQ/Discord 里的麦克风选 CABLE Output (VB-Audio Virtual Cable)\n\n"
                            + "这样对方听到的就是手机麦克风的声音 —— 和 WO Mic 效果一样。",
                    "安装完成", JOptionPane.INFORMATION_MESSAGE);
        } else {
            log("安装失败或被取消（UAC 被拒绝？）。可手动右键以管理员身份运行："
                    + installer.getAbsolutePath());
        }
    }

    private void showVirtualMicHelp() {
        // 先检测本机有没有虚拟声卡，据此给出不同的话术
        String virtualName = null;
        for (AudioPlayer.Device d : AudioPlayer.listDevices()) {
            if (AudioPlayer.looksVirtual(d.name)) {
                virtualName = d.name;
                break;
            }
        }

        JTextArea area = new JTextArea(virtualMicHelpText(virtualName));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBackground(CARD);
        area.setForeground(TEXT);
        area.setBorder(new EmptyBorder(10, 10, 10, 10));
        area.setSize(520, 360);

        Object[] options;
        if (virtualName == null) {
            options = new Object[]{"去下载 VB-CABLE", "我装好了，再检测一次", "关闭"};
        } else {
            options = new Object[]{"打开 Windows 麦克风设置", "再检测一次", "关闭"};
        }
        int r = JOptionPane.showOptionDialog(this, area,
                virtualName == null ? "还没有虚拟声卡" : "虚拟声卡已就绪",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);

        if (r == 0) {
            if (virtualName == null) {
                openUri("https://vb-audio.com/Cable/");
                log("已打开 VB-CABLE 下载页。下载解压后把 VBCABLE_Setup_x64.exe 放进 windows\\tools\\，"
                        + "点界面上的“安装虚拟声卡”即可（装完要重启电脑）。");
            } else {
                openMicSettings();
                log("已打开 Windows 麦克风设置。在微信/QQ/Discord 里把麦克风选成 CABLE Output 就能用了。");
            }
        } else if (r == 1) {
            refreshDevices();
            boolean found = false;
            for (AudioPlayer.Device d : AudioPlayer.listDevices()) {
                if (AudioPlayer.looksVirtual(d.name)) {
                    found = true;
                    log("检测到虚拟声卡：" + d.name + " —— 请把它选为“播放到”，然后点“启动服务”。");
                    break;
                }
            }
            if (!found) {
                log("仍然没有检测到虚拟声卡。如果你刚装完，请先重启电脑（驱动需要重启才生效）。");
            }
        }
    }

    /** 组装帮助文案（按是否已装虚拟声卡给不同内容）。 */
    private String virtualMicHelpText(String virtualName) {
        if (virtualName != null) {
            return "本机已检测到虚拟声卡：\n   " + virtualName + "\n\n"
                    + "想要 WO Mic 那种效果（麦克风列表里直接多一个设备选）：\n\n"
                    + "1) 上面「播放到」选 CABLE Input (VB-Audio Virtual Cable)\n"
                    + "2) 点「启动服务」，手机连上后点手机上的「开始」\n"
                    + "3) 在微信 / QQ / Discord / 游戏里，把「麦克风」选成\n"
                    + "   CABLE Output (VB-Audio Virtual Cable)\n\n"
                    + "这样对方听到的就是手机麦克风的声音 —— 和 WO Mic 效果一样。\n\n"
                    + "想让自己也听到（监听）：\n"
                    + "   Windows 声音设置 → 录制 → CABLE Output → 属性 → 侦听 → 勾选「侦听此设备」";
        }
        return "想要 WO Mic 那种效果（麦克风列表里直接多一个设备），需要一块虚拟声卡。\n\n"
                + "为什么必须装驱动：Windows 只允许内核级驱动注册「麦克风」设备，\n"
                + "WO Mic 之所以开箱即用，是因为它自带驱动。本程序用免费的 VB-CABLE 达到同样效果。\n\n"
                + "三步搞定（只需做一次）：\n"
                + "1) 点下面「去下载 VB-CABLE」→ 官网下 VB-CABLE 驱动包并解压\n"
                + "2) 把 VBCABLE_Setup_x64.exe 放到 windows\\tools\\ 目录，\n"
                + "   点本程序左下角「安装虚拟声卡」（会自动提权、静默安装）\n"
                + "3) 重启电脑 → 回来「播放到」选 CABLE Input → 目标软件麦克风选 CABLE Output\n\n"
                + "不想装驱动也能用：直接选扬声器/耳机，手机就变成电脑的无线扩音器（只是不能当麦克风输入）。";
    }

    /** 打开 Windows「声音 → 录制」设置页，方便直接选麦克风。 */
    private void openMicSettings() {
        // 优先用 ms-settings URI 直接跳到声音设置
        try {
            java.awt.Desktop.getDesktop().browse(new URI("ms-settings:sound"));
            return;
        } catch (Exception ignored) {
            // 回退到控制面板的录音设备
        }
        try {
            Win.runPlain(new String[]{"control", "mmsys.cpl", ",1"});
        } catch (Exception e) {
            log("打不开声音设置，请手动：Win+R 输入  mmsys.cpl  回车 → 录制 标签页");
        }
    }

    /* ================= 定时刷新 ================= */

    private void tick() {
        if (!server.isRunning()) {
            levelBar.setValue(0);
            return;
        }
        float db = player.levelDb();
        int v = (int) Math.max(0, Math.min(100, (db + 60f) / 60f * 100f));
        levelBar.setValue(v);
        long[] st = player.stats();
        statsLabel.setText("输入 " + Math.round(db) + " dBFS · 播放 " + st[0]
                + " 帧 · 丢弃 " + st[1] + " · 补静音 " + st[2]);
        deviceLabel.setText(server.hasClient() ? player.deviceName() : selectedName());
    }

    private void errorDialog(String msg) {
        JOptionPane.showMessageDialog(this, msg, "luo mic", JOptionPane.ERROR_MESSAGE);
    }

    /* ================= Server.Listener ================= */

    @Override
    public void onLog(String line) {
        log(line);
    }

    @Override
    public void onSession(boolean connected, String phoneName, String phoneIp) {
        SwingUtilities.invokeLater(() -> {
            if (connected) {
                stateLabel.setText("已连接，正在传输");
                stateLabel.setForeground(ACCENT);
                phoneLabel.setText(phoneName + " @ " + phoneIp);
            } else {
                stateLabel.setText(server.isRunning() ? "等待手机连接…" : "已停止");
                stateLabel.setForeground(WARN);
                phoneLabel.setText("—");
            }
        });
    }

    @Override
    public void onStats(String text, float levelDb, boolean streaming) {
        SwingUtilities.invokeLater(() -> statsLabel.setText(text));
    }

    @Override
    public void onError(String message) {
        SwingUtilities.invokeLater(() -> {
            log("错误：" + message);
            stateLabel.setText("输出异常");
            stateLabel.setForeground(new Color(0xFF5A5A));
        });
    }

    /** 追加一行日志（线程安全）。 */
    public void log(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + LocalTime.now().format(TS) + "] " + line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
            if (logArea.getLineCount() > 800) {
                try {
                    logArea.replaceRange("", 0, logArea.getLineStartOffset(200));
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        });
    }

    /** 打开项目文档（帮助菜单用）。 */
    public void openUri(String uri) {
        try {
            java.awt.Desktop.getDesktop().browse(new URI(uri));
        } catch (Exception e) {
            log("打开链接失败：" + e.getMessage());
        }
    }

    /** 设置系统外观，让 Swing 组件跟随 Windows 主题。 */
    static void applyLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 使用默认外观
        }
    }
}
