package com.luomic.pc;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JButton;
import javax.swing.border.EmptyBorder;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * luo mic 的界面外观：设计令牌 + 自绘控件。
 *
 * <p>设计取向：暖白底、单一青绿主色、圆角卡片、克制阴影、大留白。
 * 目标是在 Swing 里做出接近现代桌面应用（而不是"Java 默认界面"）的观感。
 */
public final class Ui {

    private Ui() {
    }

    /* ================= 设计令牌 ================= */

    /** 页面底色（暖白，不用纯白，屏幕上看更柔和）。 */
    public static final Color BG = new Color(0xFAFAF8);
    /** 卡片底色。 */
    public static final Color CARD = new Color(0xFFFFFF);
    /** 卡片描边。 */
    public static final Color BORDER = new Color(0xE3E2DC);
    /** 卡片底部柔和阴影色。 */
    public static final Color SHADOW = new Color(0xF0EFE9);
    /** 主色（青绿）。 */
    public static final Color ACCENT = new Color(0x0E9F6E);
    /** 主色按下态。 */
    public static final Color ACCENT_DARK = new Color(0x0B7F57);
    /** 主色浅底（用于徽章、选中态）。 */
    public static final Color ACCENT_SOFT = new Color(0xE6F6EF);
    /** 主文字。 */
    public static final Color INK = new Color(0x1C1D1B);
    /** 次级文字。 */
    public static final Color MUTED = new Color(0x6E7370);
    /** 更淡的说明文字。 */
    public static final Color FAINT = new Color(0x9AA09C);
    /** 警告色。 */
    public static final Color WARN = new Color(0xD97706);
    public static final Color WARN_SOFT = new Color(0xFDF3E3);
    /** 错误色。 */
    public static final Color DANGER = new Color(0xDC2626);
    public static final Color DANGER_SOFT = new Color(0xFDECEC);
    /** 分隔线。 */
    public static final Color DIVIDER = new Color(0xEFEEE9);

    /*
     * 字体：必须自己挑一个"能显示中文"的。
     *
     * 原来写死 Segoe UI / Consolas —— 这两个字体不含中文字形，
     * 界面里所有中文都会渲染成方块（豆腐块）。
     * 这里改成运行时从系统里挑：按偏好顺序取第一个"真的能显示中文"
     * 的字体，找不到就退回 Java 逻辑字体（逻辑字体自带 CJK 回退，不会出方块）。
     */
    public static final String SANS;
    public static final String MONO;

    static {
        java.util.Set<String> available = new java.util.HashSet<>();
        try {
            for (String n : GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames()) {
                available.add(n);
            }
        } catch (Throwable ignored) {
            // 取不到列表就退回逻辑字体
        }

        // 偏好顺序：先中英文都好看的，再纯中文的
        String[] prefer = {
            "Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑",
            "Noto Sans SC", "Source Han Sans SC", "思源黑体",
            "PingFang SC", "Microsoft JhengHei UI"
        };
        String picked = null;
        for (String name : prefer) {
            if (available.contains(name)) {
                picked = name;
                break;
            }
        }
        if (picked == null) {
            // 以上都没有：在系统里找一个能显示"手机麦"三个字的
            for (String name : available) {
                Font f = new Font(name, Font.PLAIN, 12);
                if (f.canDisplay('手') && f.canDisplay('机') && f.canDisplay('麦')) {
                    picked = name;
                    break;
                }
            }
        }
        SANS = (picked != null) ? picked : Font.SANS_SERIF;

        // 等宽字体只用于码率/帧数这类数字，但要保证它也能显示中文（设备名里有中文）
        Font m = new Font("Consolas", Font.PLAIN, 12);
        MONO = m.canDisplay('音') ? "Consolas" : SANS;
    }

    public static Font sans(int size) {
        return new Font(SANS, Font.PLAIN, size);
    }

    public static Font sansBold(int size) {
        return new Font(SANS, Font.BOLD, size);
    }

    public static Font mono(int size) {
        return new Font(MONO, Font.PLAIN, size);
    }

    /* ================= 通用绘制工具 ================= */

    /** 开启抗锯齿 + 文字微调，让自绘内容不毛糙。 */
    public static Graphics2D prepare(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    /** 圆角矩形（填充 + 可选描边）。 */
    public static void roundRect(Graphics2D g2, int x, int y, int w, int h, int r, Color fill, Color stroke) {
        if (fill != null) {
            g2.setColor(fill);
            g2.fillRoundRect(x, y, w, h, r, r);
        }
        if (stroke != null) {
            g2.setColor(stroke);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(x, y, w - 1, h - 1, r, r);
        }
    }


    /* ================= 布局 ================= */

    /**
     * 纵向布局：前几个组件按首选高度排列，最后 {@code stretchCount} 个吃掉剩余空间。
     *
     * <p>为什么要自己写：BoxLayout 在空间不足时会按组件的 maximumSize 压缩，
     * 卡片被压扁后文字会互相覆盖（实测踩过）。这里明确按首选高度排，
     * 空间不够就给滚动条，而不是把内容压变形。
     */
    public static class StackLayout implements java.awt.LayoutManager {
        private final int gap;
        private final int stretchCount;

        /**
         * @param gap          组件间距
         * @param stretchCount 末尾几个组件吸收剩余高度（通常是日志区）
         */
        public StackLayout(int gap, int stretchCount) {
            this.gap = gap;
            this.stretchCount = stretchCount;
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int h = in.top + in.bottom;
            int w = 0;
            Component[] cs = parent.getComponents();
            for (int i = 0; i < cs.length; i++) {
                Dimension d = cs[i].getPreferredSize();
                h += d.height;
                if (i > 0) {
                    h += gap;
                }
                w = Math.max(w, d.width);
            }
            return new Dimension(w + in.left + in.right, h);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int h = in.top + in.bottom;
            int w = 0;
            Component[] cs = parent.getComponents();
            for (int i = 0; i < cs.length; i++) {
                Dimension d = cs[i].getMinimumSize();
                h += Math.min(d.height, cs[i].getPreferredSize().height);
                if (i > 0) {
                    h += gap;
                }
                w = Math.max(w, d.width);
            }
            return new Dimension(w + in.left + in.right, h);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            Component[] cs = parent.getComponents();
            int n = cs.length;
            if (n == 0) {
                return;
            }
            int avail = parent.getHeight() - in.top - in.bottom - gap * (n - 1);
            int fixedTotal = 0;
            for (int i = 0; i < n; i++) {
                if (i < n - stretchCount) {
                    fixedTotal += cs[i].getPreferredSize().height;
                }
            }
            int stretch = Math.max(0, avail - fixedTotal);
            int stretchEach = stretchCount > 0 ? stretch / stretchCount : 0;
            int y = in.top;
            int width = parent.getWidth() - in.left - in.right;
            for (int i = 0; i < n; i++) {
                int h;
                if (i < n - stretchCount) {
                    h = cs[i].getPreferredSize().height;
                } else {
                    h = Math.max(cs[i].getMinimumSize().height, stretchEach);
                }
                cs[i].setBounds(in.left, y, width, h);
                y += h + gap;
            }
        }
    }

    /* ================= 卡片 ================= */

    /** 白色圆角卡片容器。 */
    public static class Card extends JPanel {
        private final int radius;
        private final int pad;

        public Card(int pad) {
            this(pad, 14);
        }

        public Card(int pad, int radius) {
            this.pad = pad;
            this.radius = radius;
            setOpaque(false);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(pad, pad + 2, pad, pad + 2));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prepare(g);
            // 先画一层错位 1px 的浅色充当柔和阴影，再画白卡本身（含描边）
            roundRect(g2, 1, 2, getWidth() - 2, getHeight() - 2, radius, SHADOW, null);
            roundRect(g2, 0, 0, getWidth() - 2, getHeight() - 3, radius, CARD, null);
            g2.setColor(BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, getWidth() - 3, getHeight() - 3, radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /* ================= 按钮 ================= */

    /** 自绘圆角按钮：主要（实心青绿）/ 次要（白底描边）两种。 */
    public static class Button extends JButton {
        private final boolean primary;
        private boolean hover;
        private boolean down;

        public Button(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(sansBold(13));
            setForeground(primary ? Color.WHITE : INK);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    down = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    down = true;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    down = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prepare(g);
            Color fill;
            Color stroke = null;
            if (primary) {
                fill = !isEnabled() ? new Color(0xC9CFCC) : (down ? ACCENT_DARK : (hover ? ACCENT_DARK : ACCENT));
            } else {
                fill = !isEnabled() ? new Color(0xF3F3F0) : (hover ? new Color(0xF6F6F4) : CARD);
                stroke = BORDER;
            }
            roundRect(g2, 0, 0, getWidth(), getHeight(), 10, fill, stroke);
            g2.dispose();
            super.paintComponent(g);
        }
    }


    /* ================= 下拉框 ================= */

    /**
     * 自绘下拉框：不受系统主题影响，任何平台都是同一套浅色外观。
     *
     * <p>为什么不用默认 JComboBox：Linux/GTK 或部分 Windows 主题下，
     * 默认下拉框会渲染成深色，和整体浅色设计打架（实测踩过）。
     */
    public static class Combo<T> extends javax.swing.JComboBox<T> {
        private boolean hover;

        public Combo() {
            setOpaque(false);
            setFocusable(false);
            setFont(Ui.sans(12));
            setForeground(INK);
            setBorder(new EmptyBorder(0, 0, 0, 0));
            setPreferredSize(new Dimension(300, 34));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRenderer(new javax.swing.DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                        int index, boolean selected, boolean focus) {
                    JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                    l.setFont(Ui.sans(12));
                    l.setBorder(new EmptyBorder(7, 12, 7, 12));
                    l.setBackground(selected ? ACCENT_SOFT : Color.WHITE);
                    l.setForeground(INK);
                    return l;
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prepare(g);
            int w = getWidth();
            int h = getHeight();
            roundRect(g2, 0, 0, w, h, 10, hover ? new Color(0xFAFAF8) : CARD, BORDER);
            // 右侧箭头
            int ax = w - 22;
            int ay = h / 2 - 2;
            g2.setColor(MUTED);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(ax - 4, ay, ax, ay + 4);
            g2.drawLine(ax, ay + 4, ax + 4, ay);
            g2.dispose();

            // 文字自己画，避免 L&F 的默认渲染把颜色改掉
            Object sel = getSelectedItem();
            String text = sel == null ? "" : sel.toString();
            Graphics2D g3 = prepare(g);
            g3.setFont(getFont());
            g3.setColor(INK);
            FontMetrics fm = g3.getFontMetrics();
            java.awt.Shape old = g3.getClip();
            g3.clipRect(12, 0, w - 40, h);
            g3.drawString(text, 12, (h + fm.getAscent() - fm.getDescent()) / 2);
            g3.setClip(old);
            g3.dispose();
        }
    }

    /* ================= 状态圆点 ================= */

    /** 会呼吸的状态圆点（连接中/传输中时轻微脉动）。 */
    public static class Dot extends JComponent {
        private Color color = FAINT;
        private boolean pulse;
        private float phase;
        private final Timer timer;

        public Dot() {
            setPreferredSize(new Dimension(10, 10));
            timer = new Timer(60, e -> {
                if (pulse) {
                    phase += 0.12f;
                    repaint();
                }
            });
            timer.start();
        }

        public void setColor(Color c) {
            this.color = c;
            repaint();
        }

        public void setPulse(boolean p) {
            this.pulse = p;
            if (!p) {
                phase = 0;
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prepare(g);
            int size = getHeight();
            float alpha = pulse ? (float) (0.55 + 0.45 * Math.abs(Math.sin(phase))) : 1f;
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (255 * alpha)));
            g2.fillOval(0, 0, size, size);
            g2.dispose();
        }
    }

    /* ================= 电平表 ================= */

    /** 竖向条状电平表（0~100），带渐变与峰值保持。 */
    public static class LevelMeter extends JComponent {
        private int value;
        private int peak;
        private long peakAt;
        private final Timer timer;

        public LevelMeter() {
            setPreferredSize(new Dimension(10, 26));
            timer = new Timer(400, e -> {
                if (peak > 0 && System.currentTimeMillis() - peakAt > 900) {
                    peak = 0;
                    repaint();
                }
            });
            timer.start();
        }

        public void setValue(int v) {
            v = Math.max(0, Math.min(100, v));
            if (v > peak) {
                peak = v;
                peakAt = System.currentTimeMillis();
            }
            if (Math.abs(v - value) >= 1) {
                value = v;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prepare(g);
            int w = getWidth();
            int h = getHeight();
            roundRect(g2, 0, 0, w, h, 6, new Color(0xF1F1EE), null);
            if (value > 0) {
                int fw = Math.max(3, (int) (w * value / 100.0));
                g2.setColor(ACCENT);
                g2.fillRoundRect(0, 0, fw, h, 6, 6);
            }
            if (peak > 0) {
                int px = Math.max(2, (int) (w * peak / 100.0)) - 2;
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 120));
                g2.fillRect(px, 0, 2, h);
            }
            g2.dispose();
        }
    }

    /* ================= 徽章 ================= */

    /** 小圆角标签，用于「已连接」「虚拟声卡」之类的状态标注。 */
    public static class Badge extends JComponent {
        private String text = "";
        private Color fg = MUTED;
        private Color bg = new Color(0xF2F2EF);

        public Badge() {
            setFont(sansBold(11));
        }

        public void set(String text, Color fg, Color bg) {
            this.text = text == null ? "" : text;
            this.fg = fg;
            this.bg = bg;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + 18, 22);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (text.isEmpty()) {
                return;
            }
            Graphics2D g2 = prepare(g);
            roundRect(g2, 0, 0, getWidth(), getHeight(), 11, bg, null);
            g2.setFont(getFont());
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 9, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }
}
