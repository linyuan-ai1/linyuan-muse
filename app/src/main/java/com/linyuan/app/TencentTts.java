package com.linyuan.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class TencentTts {

    public static final String DEFAULT_REGION = "ap-guangzhou";
    public static final String DEFAULT_VOICE = "101006";
    public static final String DEFAULT_ENDPOINT = "tts.tencentcloudapi.com";
    public static final String SERVICE = "tts";

    public static final String VERSION = "2019-08-23";
    public static final String ACTION = "TextToVoice";

    private static final int MAX_CHARS_PER_CALL = 148;

    private static final int SEGMENT_GAP_MS = 150;

    public static String synthesize(String secretId, String secretKey, String region,
                                    String voiceType, int speed, String text,
                                    File outputFile) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new RuntimeException("要合成的文本为空");
        }
        if (secretId == null || secretId.trim().isEmpty()
                || secretKey == null || secretKey.trim().isEmpty()) {
            throw new RuntimeException("腾讯云 TTS 缺少 SecretId / SecretKey");
        }
        String rg = (region == null || region.trim().isEmpty()) ? DEFAULT_REGION : region.trim();
        String voice = (voiceType == null || voiceType.trim().isEmpty()) ? DEFAULT_VOICE : voiceType.trim();

        java.util.List<String> segments = splitText(text.trim(), MAX_CHARS_PER_CALL);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        int okCount = 0;
        String firstError = null;

        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            try {
                byte[] mp3 = callOnce(secretId.trim(), secretKey.trim(), rg, voice, speed, seg);
                if (mp3 != null && mp3.length > 0) {

                    all.write(mp3);
                    okCount++;
                }
            } catch (Exception e) {
                if (firstError == null) firstError = e.getMessage();
                android.util.Log.w("VC_TTS_TC", "第 " + (i + 1) + "/" + segments.size()
                        + " 段合成失败: " + e.getMessage());
            }
        }

        if (okCount == 0) {

            throw new RuntimeException("腾讯云 TTS 全部片段合成失败"
                    + (firstError == null ? "" : "：" + firstError));
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(all.toByteArray());
        }
        android.util.Log.i("VC_TTS_TC", "腾讯云 TTS 完成：voice=" + voice
                + " 段数=" + okCount + "/" + segments.size()
                + " 字节=" + all.size());
        return outputFile.getAbsolutePath();
    }

    private static byte[] callOnce(String secretId, String secretKey, String region,
                                   String voiceType, int speed, String text) throws Exception {
        JSONObject body = new JSONObject();
        body.put("Text", text);

        body.put("SessionId", UUID.randomUUID().toString());
        try { body.put("VoiceType", Integer.parseInt(voiceType)); }
        catch (Exception e) { body.put("VoiceType", Integer.parseInt(DEFAULT_VOICE)); }
        body.put("Codec", "mp3");
        body.put("SampleRate", 16000);
        if (speed >= -2 && speed <= 6) body.put("Speed", speed);
        body.put("Volume", 5);
        body.put("PrimaryLanguage", 1);

        String payload = body.toString();
        String host = DEFAULT_ENDPOINT;
        String endpoint = "https://" + host;

        long nowSec = System.currentTimeMillis() / 1000L;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = sdf.format(new Date(nowSec * 1000L));
        String ts = String.valueOf(nowSec);

        String canonicalHeaders = "content-type:application/json; charset=utf-8\n"
                + "host:" + host + "\n"
                + "x-tc-action:" + ACTION.toLowerCase() + "\n";
        String signedHeaders = "content-type;host;x-tc-action";
        String hashedPayload = sha256Hex(payload);
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + hashedPayload;

        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + ts + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] secretDate = hmac("TC3" + secretKey, date);
        byte[] secretService = hmac(secretDate, SERVICE);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = bytesToHex(hmac(secretSigning, stringToSign));

        String authorization = "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", authorization);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Host", host);
            conn.setRequestProperty("X-TC-Action", ACTION);
            conn.setRequestProperty("X-TC-Version", VERSION);
            conn.setRequestProperty("X-TC-Timestamp", ts);
            conn.setRequestProperty("X-TC-Region", region);

            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String respText = readAll(in);
            if (code >= 400 && (respText == null || respText.trim().isEmpty())) {
                throw new RuntimeException("HTTP " + code + "（无响应体）");
            }

            JSONObject resp;
            try { resp = new JSONObject(respText); }
            catch (Exception e) {
                throw new RuntimeException("响应不是 JSON：" + snippet(respText));
            }
            JSONObject error = resp.optJSONObject("Response") == null
                    ? null : resp.getJSONObject("Response").optJSONObject("Error");
            if (error != null) {
                String ec = error.optString("Code", "");
                String em = error.optString("Message", "");

                throw new RuntimeException("腾讯云 TTS 错误 " + ec + "：" + em);
            }

            String audioB64 = resp.getJSONObject("Response").optString("Audio", "");
            if (audioB64 == null || audioB64.isEmpty()) {
                throw new RuntimeException("返回里没有 Audio 字段：" + snippet(respText));
            }
            byte[] raw = android.util.Base64.decode(audioB64, android.util.Base64.DEFAULT);
            if (raw == null || raw.length == 0) {
                throw new RuntimeException("返回音频为空");
            }
            return raw;
        } finally {
            conn.disconnect();
        }
    }

    public static java.util.List<String> splitText(String text, int limit) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String t = text.replace("\r\n", "\n").trim();
        int i = 0;
        while (i < t.length()) {
            int remain = t.length() - i;
            if (remain <= limit) {
                out.add(t.substring(i).trim());
                break;
            }
            int end = i + limit;

            int cut = -1;
            for (int k = end; k > end - 40 && k > i; k--) {
                char c = t.charAt(k - 1);
                if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '；'
                        || c == '.' || c == '!' || c == '?' || c == ';') {
                    cut = k;
                    break;
                }
            }
            if (cut <= i) {

                for (int k = end; k > end - 40 && k > i; k--) {
                    char c = t.charAt(k - 1);
                    if (c == '，' || c == '、' || c == ',' || c == ' ') {
                        cut = k;
                        break;
                    }
                }
            }
            if (cut <= i) cut = end;
            String seg = t.substring(i, cut).trim();
            if (!seg.isEmpty()) out.add(seg);
            i = cut;
        }
        return out;
    }

    private static byte[] hmac(String key, String msg) throws Exception {
        return hmac(key.getBytes(StandardCharsets.UTF_8), msg);
    }

    private static byte[] hmac(byte[] key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return bytesToHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    private static String snippet(String s) {
        if (s == null) return "(空)";
        String t = s.replace("\n", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
