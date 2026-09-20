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
        File installer = Win.findVbCableInstaller();
        if (installer == null) {
            showVirtualMicHelp();
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "将静默安装 VB-CABLE 虚拟声卡（来源：VB-Audio，安装后需要重启电脑）。\n\n安装包：" + installer.getName()
                        + "\n\n继续？",
                "安装虚拟声卡", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        log("正在安装 VB-CABLE：" + installer.getAbsolutePath());
        boolean ok = Win.installVbCable(installer);
        log(ok ? "安装命令已执行，请重启电脑，然后在上方设备列表中选择“CABLE Input”。"
                : "安装失败或被取消。");
    }

    private void showVirtualMicHelp() {
        String msg = "让电脑里的其它软件（微信 / QQ / Discord / 游戏 / 直播）把手机当麦克风：\n\n"
                + "1) 安装虚拟声卡 VB-CABLE（点左下角“安装虚拟声卡”，或到官网 vb-audio.com/Cable 下载）；\n"
                + "2) 重启电脑；\n"
                + "3) 本程序“播放到”选择 CABLE Input (VB-Audio Virtual Cable)；\n"
                + "4) 在目标软件里把“麦克风”设为 CABLE Output (VB-Audio Virtual Cable)。\n\n"
                + "想同时自己听到声音：Windows 声音设置 → 录制 → CABLE Output → 属性 → 侦听 → 勾选“侦听此设备”。\n\n"
                + "没装虚拟声卡也能用：直接选扬声器/耳机，手机声音会从电脑音箱放出来。";
        JTextArea area = new JTextArea(msg);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBackground(CARD);
        area.setForeground(TEXT);
        area.setBorder(new EmptyBorder(10, 10, 10, 10));
        area.setSize(460, 320);
        JOptionPane.showMessageDialog(this, area, "虚拟声卡帮助", JOptionPane.INFORMATION_MESSAGE);
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
