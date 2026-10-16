package com.linyuan.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 豆包（火山引擎）双向流式语音合成。
 *
 * 和旧版走 HTTP 单向的那条路相比，这条是 WebSocket：
 * 文本可以边给边合成，音频边合成边回，首字出声快得多。
 *
 * 协议要点（按官方「双向流式语音合成(WebSocket)」文档实现）：
 *   · 地址 wss://openspeech.bytedance.com/api/v3/tts/bidirection
 *   · 鉴权只需 X-Api-Key（新版控制台），另有 X-Api-Resource-Id 指模型
 *   · 事件流 StartConnection → StartSession → TaskRequest(可多次) → FinishSession
 *   · 每个 WebSocket 二进制帧里再套一层 4 字节头 + 事件号 + 会话号 + JSON/payload
 */
public class DoubaoStreamTts {

    public static final String HOST = "openspeech.bytedance.com";
    public static final String PATH = "/api/v3/tts/bidirection";
    public static final String RESOURCE_ID = "seed-tts-2.0";
    public static final String DEFAULT_SPEAKER = "zh_female_vv_uranus_bigtts";

    /** 单次 TextToVoice 请求的文本上限（中文按字算，保守取 1000）。 */
    private static final int MAX_CHARS_PER_SESSION = 1000;

    // ---- 协议常量 ----
    private static final int PROTO_VERSION = 0b0001;
    private static final int HEADER_SIZE = 0b0001;

    private static final int MT_FULL_CLIENT = 0b0001;
    private static final int MT_AUDIO_ONLY_SERVER = 0b1011;
    private static final int MT_FULL_SERVER = 0b1001;
    private static final int MT_ERROR = 0b1111;

    private static final int FLAG_WITH_EVENT = 0b0100;
    private static final int SER_JSON = 0b0001;

    // 事件号（官方 EventType 枚举值）
    private static final int EV_START_CONNECTION = 1;
    private static final int EV_FINISH_CONNECTION = 2;
    private static final int EV_CONNECTION_STARTED = 50;
    private static final int EV_START_SESSION = 100;
    private static final int EV_FINISH_SESSION = 102;
    private static final int EV_SESSION_STARTED = 150;
    private static final int EV_TASK_REQUEST = 200;
    private static final int EV_SESSION_FINISHED = 152;
    private static final int EV_TTS_ENDED = 359;

    /**
     * 合成整段文本，写成音频文件。
     *
     * @param apiKey   新版控制台的 API Key（不是 AppID/AccessToken）
     * @param speaker  音色 ID
     * @param text     要合成的文本
     * @param out      输出文件（mp3）
     * @param audioFormat 音频格式，流式推荐 pcm；这里默认用 mp3 便于直接播放
     * @return 输出路径
     */
    public static String synthesize(String apiKey, String speaker, String text, File out,
                                    String audioFormat, int sampleRate,
                                    int connectTimeoutMs, int readTimeoutMs) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new RuntimeException("要合成的文本为空");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new RuntimeException("豆包 TTS 缺少 API Key");
        }
        String fmt = (audioFormat == null || audioFormat.trim().isEmpty()) ? "mp3" : audioFormat.trim();
        String spk = (speaker == null || speaker.trim().isEmpty()) ? DEFAULT_SPEAKER : speaker.trim();
        String uid = "linyuan";

        java.util.List<String> segments = splitText(text.trim(), MAX_CHARS_PER_SESSION);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        String firstError = null;
        int okCount = 0;

        for (String seg : segments) {
            try {
                byte[] audio = oneSession(apiKey.trim(), spk, seg, fmt, sampleRate,
                        uid, connectTimeoutMs, readTimeoutMs);
                if (audio != null && audio.length > 0) {
                    all.write(audio, 0, audio.length);
                    okCount++;
                } else if (firstError == null) {
                    firstError = "服务端没有返回音频数据";
                }
            } catch (Exception e) {
                if (firstError == null) firstError = e.getMessage();
                android.util.Log.w("VC_DOUBAO_TTS", "双向流式分段失败：" + e.getMessage());
            }
        }

        if (all.size() == 0) {
            throw new RuntimeException(firstError != null ? firstError : "豆包双向流式没拿到音频");
        }
        try (FileOutputStream fo = new FileOutputStream(out)) {
            fo.write(all.toByteArray());
        }
        android.util.Log.i("VC_DOUBAO_TTS", "双向流式合成完成 " + all.size() + " 字节，"
                + okCount + "/" + segments.size() + " 段");
        return out.getAbsolutePath();
    }

    /** 一次完整的连接+会话，返回这段文本的音频字节。 */
    private static byte[] oneSession(String apiKey, String speaker, String text, String fmt,
                                     int sampleRate, String uid,
                                     int connectTimeoutMs, int readTimeoutMs) throws Exception {
        int ct = connectTimeoutMs > 0 ? connectTimeoutMs : 20000;
        int rt = readTimeoutMs > 0 ? readTimeoutMs : 60000;

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(HOST, 443), ct);
        raw.setSoTimeout(rt);
        javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket)
                ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                        .createSocket(raw, HOST, 443, true);

        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        try {
            ssl.startHandshake();
            OutputStream out = ssl.getOutputStream();
            InputStream in = ssl.getInputStream();

            String connectId = UUID.randomUUID().toString();
            StringBuilder hs = new StringBuilder();
            hs.append("GET ").append(PATH).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(HOST).append("\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: ").append(base64Key()).append("\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n")
                    .append("X-Api-Key: ").append(apiKey).append("\r\n")
                    .append("X-Api-Resource-Id: ").append(RESOURCE_ID).append("\r\n")
                    .append("X-Api-Connect-Id: ").append(connectId).append("\r\n")
                    .append("X-Control-Require-Usage-Tokens-Return: *\r\n\r\n");
            out.write(hs.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            String head = readHttpHead(in);
            if (!head.startsWith("HTTP/1.1 101")) {
                throw new RuntimeException("豆包 TTS 握手失败：" + head.replace("\r", " ").trim());
            }

            String sessionId = UUID.randomUUID().toString();

            sendEvent(out, EV_START_CONNECTION, null, null);
            sendEvent(out, EV_START_SESSION, null, sessionId, buildStartParams(speaker, fmt, sampleRate));
            sendEvent(out, EV_TASK_REQUEST, null, sessionId, buildTaskParams(text));
            sendEvent(out, EV_FINISH_SESSION, null, sessionId);

            long deadline = System.currentTimeMillis() + rt;
            boolean sessionFinished = false;
            while (System.currentTimeMillis() < deadline) {
                byte[] payload = readWsFrame(in);
                if (payload == null) break;
                if (payload.length < 4) continue;

                int mt = (payload[1] >> 4) & 0x0F;
                int headerSize = (payload[0] & 0x0F) * 4;
                int pos = headerSize;
                int flags = payload[1] & 0x0F;
                int event = -1;
                if ((flags & 0x04) != 0 && pos + 4 <= payload.length) {
                    event = ((payload[pos] & 0xFF) << 24) | ((payload[pos + 1] & 0xFF) << 16)
                            | ((payload[pos + 2] & 0xFF) << 8) | (payload[pos + 3] & 0xFF);
                    pos += 4;
                }
                if (event >= 100 && pos + 4 <= payload.length) {
                    int sl = ((payload[pos] & 0xFF) << 24) | ((payload[pos + 1] & 0xFF) << 16)
                            | ((payload[pos + 2] & 0xFF) << 8) | (payload[pos + 3] & 0xFF);
                    pos += 4 + sl;
                }
                if (pos + 4 > payload.length) continue;
                int size = ((payload[pos] & 0xFF) << 24) | ((payload[pos + 1] & 0xFF) << 16)
                        | ((payload[pos + 2] & 0xFF) << 8) | (payload[pos + 3] & 0xFF);
                pos += 4;
                int end = Math.min(pos + Math.max(size, 0), payload.length);
                byte[] body = java.util.Arrays.copyOfRange(payload, pos, end);

                if (mt == MT_AUDIO_ONLY_SERVER) {
                    audio.write(body, 0, body.length);
                } else if (mt == MT_ERROR) {
                    String msg = new String(body, StandardCharsets.UTF_8);
                    throw new RuntimeException("豆包 TTS 服务端错误：" + safeSnippet(msg));
                } else if (mt == MT_FULL_SERVER) {
                    if (event == EV_SESSION_FINISHED || event == EV_TTS_ENDED) {
                        sessionFinished = true;
                        // 服务端说会话结束了；音频可能还有几帧在路上，多收一会儿
                        break;
                    }
                    if (event == EV_CONNECTION_STARTED || event == EV_SESSION_STARTED) {
                        // 正常握手事件，忽略
                    }
                }
            }

            try {
                sendEvent(out, EV_FINISH_CONNECTION, null, null);
            } catch (Exception ignored) {
            }
            if (!sessionFinished && audio.size() == 0) {
                throw new RuntimeException("豆包 TTS 会话未完成且没有音频");
            }
            return audio.toByteArray();
        } finally {
            try { ssl.close(); } catch (Exception ignored) { }
        }
    }

    private static String buildStartParams(String speaker, String fmt, int sampleRate) {
        try {
            JSONObject req = new JSONObject();
            req.put("speaker", speaker);
            JSONObject ap = new JSONObject();
            ap.put("format", fmt);
            ap.put("sample_rate", sampleRate > 0 ? sampleRate : 24000);
            req.put("audio_params", ap);
            JSONObject o = new JSONObject();
            o.put("req_params", req);
            return o.toString();
        } catch (Exception e) {
            return "{\"req_params\":{\"speaker\":\"" + speaker + "\"}}";
        }
    }

    private static String buildTaskParams(String text) {
        try {
            JSONObject req = new JSONObject();
            req.put("text", text);
            JSONObject o = new JSONObject();
            o.put("req_params", req);
            return o.toString();
        } catch (Exception e) {
            return "{\"req_params\":{\"text\":\"\"}}";
        }
    }

    // ---- WebSocket 帧 ----

    private static void sendEvent(OutputStream out, int event, String payloadJson,
                                  String sessionId) throws Exception {
        sendEvent(out, event, null, sessionId, payloadJson);
    }

    /**
     * 发一个带事件号的控制帧。
     *
     * 注意：JSON 体里**必须**带 event（有会话时再带 session_id）——
     * 服务端会解析这个消息体，给空体会直接回
     * "unmarshal request: Syntax error no sources available, the input json is empty"。
     * 光在二进制头里写事件号不够。
     */
    private static void sendEvent(OutputStream out, int event, String unused,
                                  String sessionId, String paramsJson) throws Exception {
        // 组装消息体：event + session_id + 业务参数
        JSONObject msg = new JSONObject();
        msg.put("event", event);
        if (sessionId != null) msg.put("session_id", sessionId);
        if (paramsJson != null && !paramsJson.trim().isEmpty()) {
            JSONObject p = new JSONObject(paramsJson);
            java.util.Iterator<String> it = p.keys();
            while (it.hasNext()) {
                String k = it.next();
                msg.put(k, p.get(k));
            }
        }
        byte[] data = msg.toString().getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write((PROTO_VERSION << 4) | HEADER_SIZE);
        body.write((MT_FULL_CLIENT << 4) | FLAG_WITH_EVENT);
        body.write((SER_JSON << 4) | 0b0000);
        body.write(0b0000);
        // 事件号（二进制头里也带一份，服务端两边都认）
        int ev = event;
        body.write((ev >> 24) & 0xFF);
        body.write((ev >> 16) & 0xFF);
        body.write((ev >> 8) & 0xFF);
        body.write(ev & 0xFF);
        // 会话号（连接级事件没有）
        if (sessionId != null) {
            byte[] sid = sessionId.getBytes(StandardCharsets.UTF_8);
            body.write((sid.length >> 24) & 0xFF);
            body.write((sid.length >> 16) & 0xFF);
            body.write((sid.length >> 8) & 0xFF);
            body.write(sid.length & 0xFF);
            body.write(sid);
        }
        body.write((data.length >> 24) & 0xFF);
        body.write((data.length >> 16) & 0xFF);
        body.write((data.length >> 8) & 0xFF);
        body.write(data.length & 0xFF);
        body.write(data);
        writeWsFrame(out, body.toByteArray());
    }

    private static void writeWsFrame(OutputStream out, byte[] payload) throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x82); // FIN + binary
        int len = payload.length;
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len < 65536) {
            frame.write(0x80 | 126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) frame.write((int) (((long) len >> (8 * i)) & 0xFF));
        }
        frame.write(mask);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        frame.write(masked);
        out.write(frame.toByteArray());
        out.flush();
    }

    /** 读一个 WebSocket 帧的载荷；流结束返回 null。 */
    private static byte[] readWsFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        int len = b1 & 0x7F;
        if (len == 126) {
            int hi = in.read(), lo = in.read();
            if (hi < 0 || lo < 0) return null;
            len = (hi << 8) | lo;
        } else if (len == 127) {
            long l = 0;
            for (int i = 0; i < 8; i++) {
                int b = in.read();
                if (b < 0) return null;
                l = (l << 8) | b;
            }
            len = (int) l;
        }
        boolean masked = (b1 & 0x80) != 0;
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            for (int i = 0; i < 4; i++) {
                int b = in.read();
                if (b < 0) return null;
                mask[i] = (byte) b;
            }
        }
        if (len <= 0) return new byte[0];
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(data, off, len - off);
            if (r < 0) return null;
            off += r;
        }
        if (masked && mask != null) {
            for (int i = 0; i < len; i++) data[i] = (byte) (data[i] ^ mask[i & 3]);
        }
        return data;
    }

    private static String readHttpHead(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int b = in.read();
            if (b < 0) break;
            buf.write(b);
            if (b == '\r') {
                state = (state == 2) ? 3 : 1;
            } else if (b == '\n') {
                if (state == 3) { state = 4; break; }
                state = (state == 1) ? 2 : 0;
            } else {
                state = 0;
            }
            if (state == 4) break;
        }
        return buf.toString("UTF-8");
    }

    private static String base64Key() {
        byte[] r = new byte[16];
        new SecureRandom().nextBytes(r);
        return Base64.getEncoder().encodeToString(r);
    }

    // ---- 工具 ----

    /** 按标点/长度切段，尽量在句末断开，避免说到一半断气。 */
    static java.util.List<String> splitText(String text, int maxChars) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null) return out;
        String t = text.trim();
        if (t.isEmpty()) return out;
        int max = Math.max(80, maxChars);

        int i = 0;
        while (i < t.length()) {
            int end = Math.min(i + max, t.length());
            if (end < t.length()) {
                int cut = -1;
                for (int j = end - 1; j > i && j > end - 40; j--) {
                    char c = t.charAt(j);
                    if (c == '。' || c == '！' || c == '？' || c == '\n'
                            || c == '.' || c == '!' || c == '?' || c == '；' || c == ';') {
                        cut = j + 1;
                        break;
                    }
                }
                if (cut > i) end = cut;
            }
            out.add(t.substring(i, end));
            i = end;
        }
        return out;
    }

    private static String safeSnippet(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
