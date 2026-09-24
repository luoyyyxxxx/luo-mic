package com.luomic.pc;

import javax.sound.sampled.Mixer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.File;
import java.net.URI;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * luo mic 电脑端主界面。
 *
 * <p>外观由 {@link Ui} 提供（暖白底、青绿主色、圆角卡片、自绘按钮与电平表），
 * 结构是自上而下的卡片流：连接状态 → 音频设置 → 运行日志。
 */
public class MainWindow extends JFrame implements Server.Listener {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AudioPlayer player;
    private final Server server;

    /* ---- 控件 ---- */
    private final Ui.Dot statusDot = new Ui.Dot();
    private final JLabel stateText = new JLabel("未启动");
    private final JLabel stateSub = new JLabel("点下方按钮开始，手机打开 luo mic 会自动连上来");
    private final Ui.Badge connBadge = new Ui.Badge();

    private final JLabel phoneValue = new JLabel("还没有手机连接");
    private final JLabel ipValue = new JLabel("—");
    private final Ui.Badge deviceBadge = new Ui.Badge();

    private final Ui.Combo<AudioPlayer.Device> deviceCombo = new Ui.Combo<>();
    private final Ui.LevelMeter meter = new Ui.LevelMeter();
    private final JLabel statsText = new JLabel(" ");
    private final Ui.Badge levelBadge = new Ui.Badge();

    private final JTextArea logArea = new JTextArea();
    private final Ui.Button mainButton = new Ui.Button("启动服务", true);
    private final javax.swing.JCheckBox autostartCheck = new javax.swing.JCheckBox("开机自动启动");

    private boolean running;

    public MainWindow() {
        super("luo mic — 手机当电脑麦克风");
        this.player = new AudioPlayer(this::onError);
        this.server = new Server(this, player);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(700, 560));
        setSize(780, 720);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Ui.BG);
        setLayout(new BorderLayout());

        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        refreshDevices();
        autostartCheck.setSelected(Win.isWindows() && Win.autostartEnabled());
        autostartCheck.setEnabled(Win.isWindows());
        log("欢迎使用 luo mic —— 把手机麦克风接入这台电脑。");
        log("手机与电脑要在同一个 Wi-Fi / 局域网里。本机地址：" + String.join("，", Server.localIpv4List()));

        new javax.swing.Timer(1000, e -> tick()).start();
    }

    /* ================= 顶部标题 ================= */

    private JPanel buildHeader() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(Ui.BG);
        outer.setBorder(new EmptyBorder(22, 24, 14, 24));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBackground(Ui.BG);

        JLabel title = new JLabel("luo mic");
        title.setFont(Ui.sansBold(26));
        title.setForeground(Ui.INK);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel sub = new JLabel("把手机麦克风接入这台电脑 · 局域网自动发现 · 断线自动重连");
        sub.setFont(Ui.sans(12));
        sub.setForeground(Ui.FAINT);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);

        left.add(title);
        left.add(Box.createVerticalStrut(3));
        left.add(sub);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 6));
        right.setBackground(Ui.BG);
        deviceBadge.set("未选择设备", Ui.MUTED, new Color(0xF2F2EF));
        right.add(deviceBadge);

        outer.add(left, BorderLayout.WEST);
        outer.add(right, BorderLayout.EAST);
        return outer;
    }

    /* ================= 主体 ================= */

    private JPanel buildBody() {
        // 用自定义 StackLayout：卡片严格按首选高度排，日志区吸收剩余高度，
        // 避免 BoxLayout 在空间不足时把卡片压扁导致文字重叠
        JPanel body = new JPanel(new Ui.StackLayout(12, 1));
        body.setBackground(Ui.BG);
        body.setBorder(new EmptyBorder(0, 24, 0, 24));
        body.add(buildStatusCard());
        body.add(buildAudioCard());
        body.add(buildLogCard());
        return body;
    }

    /** 卡片 1：连接状态 */
    private JPanel buildStatusCard() {
        Ui.Card card = new Ui.Card(16);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        // 第一行：状态点 + 主状态文字 + 连接徽章
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        left.setOpaque(false);
        statusDot.setColor(Ui.FAINT);
        left.add(statusDot);
        left.add(Box.createHorizontalStrut(8));
        stateText.setFont(Ui.sansBold(15));
        stateText.setForeground(Ui.INK);
        left.add(stateText);
        row.add(left, BorderLayout.WEST);

        connBadge.set("等待启动", Ui.MUTED, new Color(0xF2F2EF));
        row.add(connBadge, BorderLayout.EAST);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(row);

        stateSub.setFont(Ui.sans(12));
        stateSub.setForeground(Ui.FAINT);
        stateSub.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(Box.createVerticalStrut(6));
        card.add(stateSub);

        card.add(Box.createVerticalStrut(12));
        card.add(divider());

        // 信息行：手机 / 本机 IP
        JPanel grid = new JPanel(new GridLayout(2, 1, 0, 8));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.add(infoRow("已连接手机", phoneValue));
        grid.add(infoRow("本机局域网 IP", mono(ipValue)));
        card.add(Box.createVerticalStrut(10));
        card.add(grid);
        card.setMinimumSize(new Dimension(200, 190));
        return card;
    }

    /** 卡片 2：音频设置 */
    private JPanel buildAudioCard() {
        Ui.Card card = new Ui.Card(16);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JLabel t = new JLabel("音频输出");
        t.setFont(Ui.sansBold(13));
        t.setForeground(Ui.INK);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(t);

        JLabel hint = new JLabel("想当麦克风用：选 CABLE Input；只想自己听：选耳机/扬声器");
        hint.setFont(Ui.sans(11));
        hint.setForeground(Ui.FAINT);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(Box.createVerticalStrut(2));
        card.add(hint);

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        deviceCombo.setPreferredSize(new Dimension(320, 34));
        row.add(deviceCombo, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.setOpaque(false);
        Ui.Button refresh = new Ui.Button("刷新", false);
        refresh.setPreferredSize(new Dimension(58, 30));
        refresh.addActionListener(e -> refreshDevices());
        Ui.Button help = new Ui.Button("当麦克风用", false);
        help.setPreferredSize(new Dimension(96, 30));
        help.addActionListener(e -> showVirtualMicHelp());
        btns.add(refresh);
        btns.add(help);
        row.add(btns, BorderLayout.EAST);
        card.add(Box.createVerticalStrut(10));
        card.add(row);

        // 电平 + 统计
        card.add(Box.createVerticalStrut(12));
        card.add(divider());
        card.add(Box.createVerticalStrut(12));

        JPanel levelRow = new JPanel(new BorderLayout(10, 0));
        levelRow.setOpaque(false);
        levelRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        levelRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        meter.setPreferredSize(new Dimension(240, 22));
        meter.setMaximumSize(new Dimension(240, 22));
        JPanel meterWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 3));
        meterWrap.setOpaque(false);
        meterWrap.add(meter);
        levelRow.add(meterWrap, BorderLayout.WEST);
        levelBadge.set("静音", Ui.MUTED, new Color(0xF2F2EF));
        JPanel badgeWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
        badgeWrap.setOpaque(false);
        badgeWrap.add(levelBadge);
        levelRow.add(badgeWrap, BorderLayout.EAST);
        card.add(levelRow);

        statsText.setFont(Ui.mono(11));
        statsText.setForeground(Ui.FAINT);
        statsText.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(Box.createVerticalStrut(8));
        card.add(statsText);
        card.setMinimumSize(new Dimension(200, 170));
        return card;
    }

    /** 卡片 3：日志 */
    private JPanel buildLogCard() {
        Ui.Card card = new Ui.Card(14);
        card.setLayout(new BorderLayout(0, 8));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JLabel t = new JLabel("运行日志");
        t.setFont(Ui.sansBold(13));
        t.setForeground(Ui.INK);
        head.add(t, BorderLayout.WEST);
        Ui.Button clear = new Ui.Button("清空", false);
        clear.setPreferredSize(new Dimension(56, 24));
        clear.setFont(Ui.sans(11));
        clear.addActionListener(e -> logArea.setText(""));
        head.add(clear, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setBackground(new Color(0xFBFBF9));
        logArea.setForeground(new Color(0x3A3D3B));
        logArea.setFont(Ui.mono(11));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(Ui.DIVIDER));
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getViewport().setBackground(new Color(0xFBFBF9));
        scroll.setPreferredSize(new Dimension(100, 120));
        card.add(scroll, BorderLayout.CENTER);
        card.setMinimumSize(new Dimension(200, 120));
        return card;
    }

    /* ================= 底部操作栏 ================= */

    private JPanel buildFooter() {
        JPanel outer = new JPanel(new BorderLayout(12, 0));
        outer.setBackground(Ui.BG);
        outer.setBorder(new EmptyBorder(14, 24, 20, 24));

        mainButton.setPreferredSize(new Dimension(150, 42));
        mainButton.setFont(Ui.sansBold(14));
        mainButton.addActionListener(e -> toggleServer());
        outer.add(mainButton, BorderLayout.WEST);

        JPanel opt = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 10));
        opt.setOpaque(false);
        autostartCheck.setOpaque(false);
        autostartCheck.setFont(Ui.sans(12));
        autostartCheck.setForeground(Ui.MUTED);
        autostartCheck.setFocusPainted(false);
        autostartCheck.addActionListener(e -> toggleAutostart());
        opt.add(autostartCheck);
        outer.add(opt, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        right.setOpaque(false);
        Ui.Button fw = new Ui.Button("放行防火墙", false);
        fw.setPreferredSize(new Dimension(100, 32));
        fw.setFont(Ui.sans(12));
        fw.addActionListener(e -> addFirewall());
        Ui.Button vb = new Ui.Button("安装虚拟声卡", false);
        vb.setPreferredSize(new Dimension(112, 32));
        vb.setFont(Ui.sans(12));
        vb.addActionListener(e -> installVbCable());
        right.add(fw);
        right.add(vb);
        outer.add(right, BorderLayout.EAST);
        return outer;
    }

    /* ================= 小工具 ================= */

    private JPanel divider() {
        JPanel p = new JPanel();
        p.setBackground(Ui.DIVIDER);
        p.setPreferredSize(new Dimension(10, 1));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JPanel infoRow(String key, JLabel value) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        JLabel k = new JLabel(key);
        k.setFont(Ui.sans(12));
        k.setForeground(Ui.MUTED);
        k.setPreferredSize(new Dimension(96, 20));
        row.add(k, BorderLayout.WEST);
        value.setFont(Ui.sans(12));
        value.setForeground(Ui.INK);
        row.add(value, BorderLayout.CENTER);
        return row;
    }

    private JLabel mono(JLabel l) {
        l.setFont(Ui.mono(12));
        return l;
    }

    /* ================= 交互逻辑 ================= */

    private void toggleServer() {
        if (server.isRunning()) {
            server.stop();
            running = false;
            mainButton.setText("启动服务");
            setState("已停止", "点下方按钮可重新启动", Ui.FAINT, "已停止", Ui.MUTED);
            connBadge.set("已停止", Ui.MUTED, new Color(0xF2F2EF));
            phoneValue.setText("还没有手机连接");
            meter.setValue(0);
            statsText.setText(" ");
            levelBadge.set("静音", Ui.MUTED, new Color(0xF2F2EF));
            return;
        }
        applySelectionToServer();
        try {
            server.start();
            running = true;
            mainButton.setText("停止服务");
            setState("等待手机连接…", "手机打开 luo mic 点「开始」就会自动连上", Ui.WARN, "扫描中", Ui.WARN_SOFT);
            connBadge.set("等待连接", Ui.WARN, Ui.WARN_SOFT);
            ipValue.setText(String.join("   ", Server.localIpv4List()));
        } catch (Exception e) {
            setState("启动失败", e.getMessage(), Ui.DANGER, "错误", Ui.DANGER_SOFT);
            connBadge.set("启动失败", Ui.DANGER, Ui.DANGER_SOFT);
            errorDialog("服务启动失败：\n" + e.getMessage()
                    + "\n\n常见原因：\n"
                    + "1) 端口 47777/47778/47779 被占用（是不是已经开了一个 luo mic？）\n"
                    + "2) 防火墙拦截了监听（点右下角「放行防火墙」）");
        }
    }

    private void setState(String text, String sub, Color dotColor, String badge, Color badgeColor) {
        stateText.setText(text);
        stateSub.setText(sub == null ? " " : sub);
        stateText.repaint();
        statusDot.setColor(dotColor);
        statusDot.setPulse(dotColor.equals(Ui.ACCENT) || dotColor.equals(Ui.WARN));
        if (badge != null) {
            connBadge.set(badge, badgeColor, tint(badgeColor));
        }
    }

    private static Color tint(Color c) {
        if (c.equals(Ui.ACCENT)) {
            return Ui.ACCENT_SOFT;
        }
        if (c.equals(Ui.WARN)) {
            return Ui.WARN_SOFT;
        }
        if (c.equals(Ui.DANGER)) {
            return Ui.DANGER_SOFT;
        }
        return new Color(0xF2F2EF);
    }

    private void applySelectionToServer() {
        AudioPlayer.Device d = (AudioPlayer.Device) deviceCombo.getSelectedItem();
        if (d == null) {
            server.setDevice(null, "系统默认设备");
        } else {
            Mixer.Info mi = d.info;
            server.setDevice(mi, d.name);
        }
        String name = d == null ? "系统默认设备" : d.name;
        deviceBadge.set(name.length() > 30 ? name.substring(0, 28) + "…" : name,
                AudioPlayer.looksVirtual(name) ? Ui.ACCENT : Ui.MUTED,
                AudioPlayer.looksVirtual(name) ? Ui.ACCENT_SOFT : new Color(0xF2F2EF));
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
        applySelectionToServer();
        if (virtual == null) {
            log("检测到 " + devices.size() + " 个播放设备，但没有虚拟声卡（CABLE Input）。"
                    + "想让微信/QQ/游戏把手机当麦克风，点右下角「安装虚拟声卡」。");
        } else {
            log("检测到 " + devices.size() + " 个播放设备，发现虚拟声卡：" + virtual.name
                    + " —— 选它就能当麦克风用。");
        }
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
        log("正在请求管理员权限放行防火墙端口…（如弹出 UAC 请选择「是」）");
        boolean ok = Win.addFirewallRules();
        log(ok ? "防火墙已放行 UDP 47777 / TCP 47778-47779。"
                : "防火墙放行失败或被取消；可手动执行 windows\\tools\\firewall.bat（右键以管理员身份运行）。");
    }

    /* ================= 虚拟声卡 ================= */

    private void installVbCable() {
        if (!Win.isWindows()) {
            log("虚拟声卡安装仅支持 Windows。");
            return;
        }
        File installer = Win.findVbCableInstaller();
        if (installer == null) {
            int r = JOptionPane.showConfirmDialog(this,
                    "要让微信 / QQ / 游戏把手机当麦克风，需要一块虚拟声卡。\n\n"
                            + "程序会从 VB-Audio 官网下载 VB-CABLE 驱动（约 1MB）并安装。\n"
                            + "装完需要重启一次电脑。\n\n现在开始？",
                    "安装虚拟声卡", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return;
            }
            log("正在从 vb-audio.com 下载 VB-CABLE 驱动包…");
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
                    "将静默安装 VB-CABLE 虚拟声卡（来源：VB-Audio，安装后需要重启电脑）。\n\n"
                            + "安装包：" + installer.getName() + "\n\n继续？",
                    "安装虚拟声卡", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return;
            }
        }

        log("正在安装（会弹出 UAC，请选「是」）…");
        boolean ok = Win.installVbCable(installer);
        if (ok) {
            log("安装完成，请重启电脑。");
            JOptionPane.showMessageDialog(this,
                    "安装完成！\n\n请重启电脑（驱动需要重启才生效）。\n\n"
                            + "重启后：\n"
                            + "  1) luo mic 的「播放到」选 CABLE Input\n"
                            + "  2) 微信 / QQ / Discord 里的麦克风选 CABLE Output\n\n"
                            + "这样对方听到的就是手机麦克风的声音 —— 和 WO Mic 效果一样。",
                    "安装完成", JOptionPane.INFORMATION_MESSAGE);
        } else {
            log("安装失败或被取消。可手动右键以管理员身份运行：" + installer.getAbsolutePath());
        }
    }

    private void showVirtualMicHelp() {
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
        area.setFont(Ui.sans(12));
        area.setBackground(Color.WHITE);
        area.setForeground(Ui.INK);
        area.setBorder(new EmptyBorder(10, 10, 10, 10));
        area.setSize(520, 340);

        Object[] options = virtualName == null
                ? new Object[]{"去下载 VB-CABLE", "我已装好，重新检测", "关闭"}
                : new Object[]{"打开 Windows 麦克风设置", "重新检测", "关闭"};
        int r = JOptionPane.showOptionDialog(this, area,
                virtualName == null ? "还没有虚拟声卡" : "虚拟声卡已就绪",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);

        if (r == 0) {
            if (virtualName == null) {
                openUri("https://vb-audio.com/Cable/");
                log("已打开 VB-CABLE 下载页。下载解压后把 VBCABLE_Setup_x64.exe 放进 windows\\tools\\，"
                        + "点「安装虚拟声卡」即可。");
            } else {
                openMicSettings();
                log("已打开 Windows 声音设置。在微信/QQ/Discord 里把麦克风选成 CABLE Output 就能用了。");
            }
        } else if (r == 1) {
            refreshDevices();
            boolean found = false;
            for (AudioPlayer.Device d : AudioPlayer.listDevices()) {
                if (AudioPlayer.looksVirtual(d.name)) {
                    found = true;
                    log("检测到虚拟声卡：" + d.name + " —— 请把它选为「播放到」，然后点「启动服务」。");
                    break;
                }
            }
            if (!found) {
                log("仍然没有检测到虚拟声卡。如果刚装完，请先重启电脑（驱动要重启才生效）。");
            }
        }
    }

    private String virtualMicHelpText(String virtualName) {
        if (virtualName != null) {
            return "本机已检测到虚拟声卡：\n   " + virtualName + "\n\n"
                    + "想要 WO Mic 那种效果（麦克风列表里直接多一个设备）：\n\n"
                    + "1) 「播放到」选 CABLE Input (VB-Audio Virtual Cable)\n"
                    + "2) 点「启动服务」，手机打开 luo mic 点「开始」\n"
                    + "3) 微信 / QQ / Discord / 游戏里，把「麦克风」选成\n"
                    + "   CABLE Output (VB-Audio Virtual Cable)\n\n"
                    + "这样对方听到的就是手机麦克风的声音。\n\n"
                    + "想让自己也听到：Windows 声音设置 → 录制 → CABLE Output →\n"
                    + "属性 → 侦听 → 勾选「侦听此设备」";
        }
        return "想要 WO Mic 那种效果，需要一块虚拟声卡。\n\n"
                + "为什么必须装驱动：Windows 只允许内核级驱动注册「麦克风」设备。\n"
                + "WO Mic 开箱即用是因为它自带驱动；本程序用免费的 VB-CABLE 达到同样效果。\n\n"
                + "三步搞定（只需一次）：\n"
                + "1) 点下面「去下载 VB-CABLE」→ 官网下载驱动包\n"
                + "2) 把 VBCABLE_Setup_x64.exe 放进 windows\\tools\\，\n"
                + "   点界面上的「安装虚拟声卡」（会自动提权安装）\n"
                + "3) 重启电脑 → 「播放到」选 CABLE Input → 目标软件麦克风选 CABLE Output\n\n"
                + "不想装驱动也能用：直接选扬声器/耳机，手机变成电脑的无线扩音器。";
    }

    private void openMicSettings() {
        try {
            Desktop.getDesktop().browse(new URI("ms-settings:sound"));
            return;
        } catch (Exception ignored) {
            // 回退到控制面板
        }
        Win.runPlain(new String[]{"control", "mmsys.cpl", ",1"});
    }

    void openUri(String uri) {
        try {
            Desktop.getDesktop().browse(new URI(uri));
        } catch (Exception e) {
            log("打开链接失败：" + e.getMessage());
        }
    }

    /* ================= 定时刷新 ================= */

    private void tick() {
        if (!server.isRunning()) {
            meter.setValue(0);
            return;
        }
        float db = player.levelDb();
        int v = (int) Math.max(0, Math.min(100, (db + 60f) / 60f * 100f));
        meter.setValue(v);
        if (player.isRunning() && db > -60f) {
            levelBadge.set(Math.round(db) + " dB", Ui.ACCENT, Ui.ACCENT_SOFT);
        } else if (server.hasClient()) {
            levelBadge.set("手机没在采集", Ui.WARN, Ui.WARN_SOFT);
        } else {
            levelBadge.set("静音", Ui.MUTED, new Color(0xF2F2EF));
        }
        long[] st = player.stats();
        statsText.setText("播放 " + st[0] + " 帧 · 丢弃 " + st[1] + " · 补静音 " + st[2]);
        if (server.hasClient()) {
            deviceBadge.set(player.deviceName() + (AudioPlayer.looksVirtual(player.deviceName()) ? "（虚拟声卡）" : ""),
                    AudioPlayer.looksVirtual(player.deviceName()) ? Ui.ACCENT : Ui.MUTED,
                    AudioPlayer.looksVirtual(player.deviceName()) ? Ui.ACCENT_SOFT : new Color(0xF2F2EF));
        }
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
                setState("已连接，正在传输", "手机那边应该显示「正在传输」了", Ui.ACCENT, "已连接", Ui.ACCENT);
                phoneValue.setText(phoneName + "  ·  " + phoneIp);
            } else {
                if (server.isRunning()) {
                    setState("等待手机连接…", "手机打开 luo mic 点「开始」就会自动连上", Ui.WARN, "等待连接", Ui.WARN);
                }
                phoneValue.setText("还没有手机连接");
                meter.setValue(0);
            }
        });
    }

    @Override
    public void onStats(String text, float levelDb, boolean streaming) {
        SwingUtilities.invokeLater(() -> statsText.setText(text));
    }

    @Override
    public void onError(String message) {
        SwingUtilities.invokeLater(() -> {
            log("错误：" + message);
            setState("输出异常", message, Ui.DANGER, "异常", Ui.DANGER);
        });
    }

    /** 追加一行日志（线程安全）。 */
    public void log(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + LocalTime.now().format(TS) + "]  " + line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
            if (logArea.getLineCount() > 600) {
                try {
                    logArea.replaceRange("", 0, logArea.getLineStartOffset(150));
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        });
    }

    /** 设置系统外观（保留系统字体渲染，颜色由我们自己控制）。 */
    static void applyLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            UIManager.put("Panel.background", Ui.BG);
            UIManager.put("OptionPane.background", Color.WHITE);
            UIManager.put("OptionPane.messageForeground", Ui.INK);
        } catch (Exception ignored) {
            // 使用默认外观
        }
    }
}
