package com.linyuan.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class ImageGen {

    private static final List<PoolKey> pool = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, Long> lastUsedAt = new ConcurrentHashMap<>();
    private static final AtomicInteger cursor = new AtomicInteger(0);
    private static volatile long cooldownMs = 0;

    public static final class PoolKey {
        public final String baseUrl;
        public final String apiKey;
        public PoolKey(String baseUrl, String apiKey) { this.baseUrl = baseUrl; this.apiKey = apiKey; }
    }

    public static final String MODEL_FAST = "image-gen-2.1-flash";
    public static final String MODEL_PRO  = "image-gen-2.5-flash";

    public static final String SIZE_SQUARE   = "1024x1024";
    public static final String SIZE_LANDSCAPE = "1792x1024";
    public static final String SIZE_PORTRAIT  = "1024x1792";

    public static void refreshPool(AppSettings settings) {
        pool.clear();
        if (settings == null) return;
        cooldownMs = Math.max(0, settings.imageGenPoolCooldownSec) * 1000L;

        List<String> lines = new ArrayList<>();
        String custom = settings.imageGenKeyPool;
        if (custom != null && !custom.trim().isEmpty()) {
            for (String l : custom.split("\n")) {
                String t = l.trim();
                if (!t.isEmpty() && !t.startsWith("#")) lines.add(t);
            }
        } else {
            String defBase = nz(settings.imageGenBaseUrl, "");
            while (defBase.endsWith("/")) defBase = defBase.substring(0, defBase.length() - 1);
            for (String k : AppSettings.BUILTIN_IMAGE_KEY_POOL) {
                String t = k.trim();
                if (!t.isEmpty()) lines.add(t);
            }
        }
        String defBase = nz(settings.imageGenBaseUrl, "");
        while (defBase.endsWith("/")) defBase = defBase.substring(0, defBase.length() - 1);

        for (String line : lines) {
            String base = defBase, key;
            int bar = line.indexOf('|');
            if (bar > 0) {
                base = line.substring(0, bar).trim();
                key = line.substring(bar + 1).trim();
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            } else {
                key = line;
            }
            if (!key.isEmpty()) pool.add(new PoolKey(base, key));
        }
        android.util.Log.i("VC_IMGGEN", "生图密钥池已加载 " + pool.size() + " 个密钥");
    }

    public static int poolSize() { return pool.size(); }

    private static PoolKey nextKey() {
        int n = pool.size();
        if (n == 0) return null;
        long now = System.currentTimeMillis();
        int start = Math.floorMod(cursor.getAndIncrement(), n);
        PoolKey earliest = null;
        long earliestAt = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            PoolKey k = pool.get((start + i) % n);
            Long last = lastUsedAt.get(k.apiKey);
            long usedAt = last == null ? 0L : last;
            if (now - usedAt >= cooldownMs) {
                lastUsedAt.put(k.apiKey, now);
                return k;
            }
            if (usedAt < earliestAt) { earliestAt = usedAt; earliest = k; }
        }
        if (earliest != null) lastUsedAt.put(earliest.apiKey, now);
        return earliest;
    }

    public static final class GenResult {
        public boolean ok;
        public String error;
        public String imageUrl;
        public String localPath;
        public String model;
        public String size;
        public String prompt;
        public long costMs;
    }

    public static GenResult generateOne(Context ctx, AppSettings settings, String prompt, String model, String size) {
        GenResult r = new GenResult();
        r.prompt = prompt;
        r.model = nz(model, nz(settings == null ? null : settings.imageGenModel, MODEL_FAST));
        r.size = nz(size, SIZE_SQUARE);
        long t0 = System.currentTimeMillis();

        if (prompt == null || prompt.trim().isEmpty()) {
            r.error = "提示词为空";
            return r;
        }
        PoolKey pk = nextKey();
        if (pk == null) {
            r.error = "生图密钥池为空（内置池没加载？）";
            return r;
        }

        try {
            String endpoint = pk.baseUrl + "/v1/images/generations";
            JSONObject body = new JSONObject();
            body.put("model", r.model);
            body.put("prompt", prompt.trim());
            body.put("size", r.size);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(timeout(settings, true));
                conn.setReadTimeout(timeout(settings, false));
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + pk.apiKey);

                OutputStream os = conn.getOutputStream();
                os.write(payload);
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String text = readAll(is);
                r.costMs = System.currentTimeMillis() - t0;

                if (code < 200 || code >= 300) {
                    r.error = "HTTP " + code + "：" + shortErr(text);
                    return r;
                }
                JSONObject j = new JSONObject(text);
                JSONArray data = j.optJSONArray("data");
                if (data == null || data.length() == 0) {
                    r.error = "返回里没有图片：" + shortErr(text);
                    return r;
                }
                JSONObject first = data.optJSONObject(0);
                String url = first == null ? "" : first.optString("url", "");
                if (url.isEmpty()) {

                    String b64 = first == null ? "" : first.optString("b64_json", "");
                    if (!b64.isEmpty()) {
                        r.imageUrl = "";
                        r.localPath = saveBase64(ctx, b64, r.model);
                        r.ok = r.localPath != null;
                        if (!r.ok) r.error = "base64 图片落盘失败";
                        return r;
                    }
                    r.error = "返回里没有 url：" + shortErr(text);
                    return r;
                }
                r.imageUrl = url;
                r.ok = true;
                return r;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            r.costMs = System.currentTimeMillis() - t0;
            r.error = "请求异常：" + e.getMessage();
            return r;
        }
    }

    public static String download(Context ctx, String imageUrl, String prompt, String model, String size) {
        if (imageUrl == null || imageUrl.isEmpty()) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl).openConnection();
            try {
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(120000);

                int code = conn.getResponseCode();
                if (code != 200) return null;
                InputStream is = conn.getInputStream();
                File f = newImageFile(ctx);
                FileOutputStream fos = new FileOutputStream(f);
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                fos.flush();
                fos.close();
                is.close();

                writeMeta(f, prompt, model, size, imageUrl);
                return f.getAbsolutePath();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            android.util.Log.w("VC_IMGGEN", "下载失败: " + e.getMessage());
            return null;
        }
    }

    public static GenResult generateAndSave(Context ctx, AppSettings settings, String prompt, String model, String size) {
        GenResult r = generateOne(ctx, settings, prompt, model, size);
        if (!r.ok) return r;
        if (r.localPath != null) return r;
        String p = download(ctx, r.imageUrl, prompt, r.model, r.size);
        if (p == null) {
            r.ok = false;
            r.error = "图片下到本地失败（URL 到手了但下载不成功）";
            return r;
        }
        r.localPath = p;
        return r;
    }

    private static int timeout(AppSettings s, boolean connect) {
        if (s == null) return connect ? 20000 : 180000;
        int v = connect ? s.connectTimeout : s.readTimeout;
        if (v <= 0) v = connect ? 20000 : 180000;
        if (v < 30000) v = 30000;
        return v;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    private static String shortErr(String text) {
        if (text == null) return "(空响应)";
        try {
            JSONObject j = new JSONObject(text);
            JSONObject err = j.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (!m.isEmpty()) return m;
            }
            String m = j.optString("message", "");
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) { }
        String t = text.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    public static File genDir(Context ctx) {
        File d = new File(ctx.getApplicationContext().getFilesDir(), AppSettings.IMAGE_GEN_DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File newImageFile(Context ctx) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.CHINA);
        File d = genDir(ctx);
        return new File(d, "img_" + f.format(new java.util.Date()) + ".png");
    }

    private static void writeMeta(File img, String prompt, String model, String size, String url) {
        try {
            JSONObject m = new JSONObject();
            m.put("prompt", prompt == null ? "" : prompt);
            m.put("model", model == null ? "" : model);
            m.put("size", size == null ? "" : size);
            m.put("url", url == null ? "" : url);
            m.put("createdAt", System.currentTimeMillis());
            File meta = new File(img.getAbsolutePath() + ".json");
            FileOutputStream fos = new FileOutputStream(meta);
            fos.write(m.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) { }
    }

    private static String saveBase64(Context ctx, String b64, String model) {
        try {
            byte[] data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            File f = newImageFile(ctx);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.close();
            writeMeta(f, "", model, "", "");
            return f.getAbsolutePath();
        } catch (Exception e) {
            android.util.Log.w("VC_IMGGEN", "base64 落盘失败: " + e.getMessage());
            return null;
        }
    }

    private static String nz(String v, String def) {
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }
}
