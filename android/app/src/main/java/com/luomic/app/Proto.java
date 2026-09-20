package com.luomic.app;

/**
 * luo mic 协议常量（与 docs/PROTOCOL.md 严格对应）。
 *
 * <p>发现：UDP 47777 上的 ASCII 单行文本。
 * <p>控制：TCP 47778 上的行分隔 JSON。
 * <p>音频：TCP 47779 上的 12 字节帧头 + PCM。
 */
public final class Proto {

    private Proto() {
    }

    /* ---------------- 端口 ---------------- */

    public static final int DISCOVERY_PORT = 47777;
    public static final int DEFAULT_CONTROL_PORT = 47778;
    public static final int DEFAULT_AUDIO_PORT = 47779;

    /* ---------------- 发现（UDP） ---------------- */

    public static final String MAGIC = "LUOMIC/1";
    /** 电脑周期广播，宣告自己在线。 */
    public static final String MSG_DISCOVER = MAGIC + " DISCOVER";
    /** 手机主动广播，请求电脑立刻回应。 */
    public static final String MSG_PROBE = MAGIC + " PROBE";
    /** 电脑对 PROBE 的单播回应。 */
    public static final String MSG_HERE_PREFIX = MAGIC + " HERE ";

    /** 手机主动探测间隔（毫秒）。 */
    public static final long PROBE_INTERVAL_MS = 2000L;

    /* ---------------- 控制（TCP 行 JSON） ---------------- */

    public static final String T_HELLO = "HELLO";
    public static final String T_START = "START";
    public static final String T_STOP = "STOP";
    public static final String T_PING = "PING";
    public static final String T_PONG = "PONG";
    public static final String T_STAT = "STAT";
    public static final String T_BYE = "BYE";

    /** 协议版本。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 控制通道心跳超时：超过该时间没收到电脑任何报文即判定断线。 */
    public static final long HEARTBEAT_TIMEOUT_MS = 12000L;

    /* ---------------- 音频（TCP 帧） ---------------- */

    /** 帧头魔数 "LM"。 */
    public static final int FRAME_MAGIC = 0x4C4D;
    public static final int FRAME_VERSION = 1;
    public static final int FRAME_HEADER_LEN = 12;

    public static final String CODEC_PCM_S16LE = "pcm_s16le";

    /* ---------------- 音频参数可选值 ---------------- */

    /** 采样率：48 kHz 音质最好；16 kHz 省流量、抗弱网。 */
    public static final int[] RATES = {48000, 16000};
    /** 帧长（毫秒）。 */
    public static final int[] FRAME_MS = {20, 10};

    /* ---------------- 工具方法 ---------------- */

    public static int bytesPerFrame(int rate, int frameMs, int channels) {
        return rate * frameMs / 1000 * 2 * channels;
    }

    /**
     * 极简 JSON 取值：够用且不依赖任何第三方库。
     *
     * @return 找到的字符串值（已去引号），找不到返回 {@code null}
     */
    public static String jsonString(String json, String key) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return null;
        }
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
            j++;
        }
        if (j >= json.length()) {
            return null;
        }
        if (json.charAt(j) == '"') {
            int end = json.indexOf('"', j + 1);
            if (end < 0) {
                return null;
            }
            return json.substring(j + 1, end);
        }
        int end = j;
        while (end < json.length() && ",}\r\n \t".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        return json.substring(j, end);
    }

    /**
     * 极简 JSON 取整数。
     *
     * @return 解析出的整数，缺失或非法时返回 {@code def}
     */
    public static int jsonInt(String json, String key, int def) {
        String v = jsonString(json, key);
        if (v == null) {
            return def;
        }
        try {
            return (int) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 转义 JSON 字符串里的特殊字符。 */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    public static String base64Encode(String s) {
        return android.util.Base64.encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    public static String base64Decode(String s) {
        try {
            byte[] raw = android.util.Base64.decode(s, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
