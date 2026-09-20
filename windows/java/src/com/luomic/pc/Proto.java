package com.luomic.pc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * luo mic 协议常量与工具（与 docs/PROTOCOL.md 严格对应）。
 */
public final class Proto {

    private Proto() {
    }

    /* ---------------- 端口 ---------------- */

    /** 设备发现 UDP 端口（固定）。 */
    public static final int DISCOVERY_PORT = 47777;
    /** 控制通道 TCP 端口。 */
    public static final int CONTROL_PORT = 47778;
    /** 音频通道 TCP 端口。 */
    public static final int AUDIO_PORT = 47779;

    /* ---------------- 发现报文 ---------------- */

    public static final String MAGIC = "LUOMIC/1";
    /** 电脑周期广播，宣告自己在线（手机被动即可发现）。 */
    public static final String MSG_DISCOVER = MAGIC + " DISCOVER";
    /** 手机主动探测。 */
    public static final String MSG_PROBE = MAGIC + " PROBE";
    /** 电脑对 PROBE 的单播回应。 */
    public static final String MSG_HERE_PREFIX = MAGIC + " HERE ";

    /** 电脑广播间隔（毫秒）。 */
    public static final long ANNOUNCE_INTERVAL_MS = 2000L;

    /* ---------------- 控制报文类型 ---------------- */

    public static final String T_HELLO = "HELLO";
    public static final String T_START = "START";
    public static final String T_STOP = "STOP";
    public static final String T_PING = "PING";
    public static final String T_PONG = "PONG";
    public static final String T_STAT = "STAT";
    public static final String T_BYE = "BYE";

    public static final int PROTOCOL_VERSION = 1;

    /** 控制通道心跳：每 2 秒 PING 一次；超过 12 秒没有手机的任何报文判定断线。 */
    public static final long PING_INTERVAL_MS = 2000L;
    public static final long CLIENT_TIMEOUT_MS = 12000L;

    /* ---------------- 音频 ---------------- */

    public static final String CODEC_PCM_S16LE = "pcm_s16le";
    public static final int AUDIO_HANDSHAKE_LEN = 32;
    /** 单帧 PCM 上限，超出视为协议错误。 */
    public static final int MAX_FRAME_BYTES = 8192;
    /** 抖动缓冲目标帧数（≈60ms @20ms 帧长）。 */
    public static final int JITTER_TARGET_FRAMES = 3;
    /** 抖动缓冲上限帧数（≈1s @20ms 帧长），超过则丢弃最旧帧。 */
    public static final int JITTER_MAX_FRAMES = 50;

    /** 音频输出设备的缓冲时长（毫秒），越小延迟越低但越容易爆音。 */
    public static final int OUTPUT_BUFFER_MS = 120;

    /* ---------------- 工具 ---------------- */

    public static int bytesPerFrame(int rate, int frameMs, int channels) {
        return rate * frameMs / 1000 * 2 * channels;
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Base64 编码（无填充、URL 安全字符集都不用，标准字母表即可）。 */
    public static String b64(String s) {
        return Base64.getEncoder().withoutPadding().encodeToString(utf8(s));
    }

    /** Base64 解码，失败返回 null。 */
    public static String unb64(String s) {
        if (s == null) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 极简 JSON 取值（不引入任何第三方库）。
     *
     * @return 字符串值或 {@code null}
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

    public static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    public static void writeIntBE(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
