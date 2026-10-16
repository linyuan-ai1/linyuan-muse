package com.linyuan.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class EmbeddingClient {

    public static float[] embed(AppSettings settings, String text) throws Exception {
        if (settings == null || isBlank(embedKey(settings))) {
            throw new RuntimeException("没有配置 API Key，无法生成向量");
        }
        String base = embedBase(settings);
        String endpoint = endpointFor(base, "/embeddings");
        String model = isBlank(settings.embeddingModel) ? "text-embedding-3-small" : settings.embeddingModel.trim();

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", text);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + embedKey(settings).trim());

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream output = connection.getOutputStream();
            output.write(payload);
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            JSONObject root = new JSONObject(responseText);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                throw new RuntimeException("Embedding 接口没有返回 data");
            }
            JSONArray embeddingArray = data.getJSONObject(0).optJSONArray("embedding");
            if (embeddingArray == null || embeddingArray.length() == 0) {
                throw new RuntimeException("Embedding 接口没有返回 embedding 向量");
            }
            float[] result = new float[embeddingArray.length()];
            for (int i = 0; i < embeddingArray.length(); i++) {
                result[i] = (float) embeddingArray.getDouble(i);
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static byte[] floatsToBytes(float[] floats) {
        if (floats == null) return null;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(floats.length * 4);
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        try {
            for (float f : floats) dos.writeFloat(f);
            dos.close();
        } catch (Exception e) {
            return null;
        }
        return baos.toByteArray();
    }

    public static float[] bytesToFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % 4 != 0) return null;
        float[] result = new float[bytes.length / 4];
        java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
        try {
            for (int i = 0; i < result.length; i++) result[i] = dis.readFloat();
            dis.close();
        } catch (Exception e) {
            return null;
        }
        return result;
    }

    private static String pick(String value, String fallback) { return isBlank(value) ? fallback : value; }
    private static String embedBase(AppSettings s) { return pick(s.embeddingBaseUrl, pick(s.chatBaseUrl, s.baseUrl)); }
    private static String embedKey(AppSettings s) { return pick(s.embeddingApiKey, pick(s.chatApiKey, s.apiKey)); }
    private static String endpointFor(String base, String suffix) {
        if (base.endsWith("/v1") || base.endsWith("/compatible-mode/v1")) return base + suffix;
        return base + "/v1" + suffix;
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
        }
        return builder.toString();
    }

    private static String safeSnippet(String text) {
        if (text == null) return "";
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
