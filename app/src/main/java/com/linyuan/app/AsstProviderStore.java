package com.linyuan.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AsstProviderStore {

    private static final String KEY = "asst_providers";

    public static class Prov {
        public String id = "";
        public String name = "";
        public String baseUrl = "";
        public String apiKey = "";
        public String models = "";

        List<String> modelList() {
            return parseModels(models);
        }

        boolean usable() {
            return notBlank(name) && notBlank(baseUrl);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.trim().isEmpty();
        }
    }

    public static List<String> parseModels(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw.split("[,\n]")) {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public static String joinModels(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s == null || s.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(s.trim());
        }
        return sb.toString();
    }
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(SubAgentStore.PREFS, Context.MODE_PRIVATE);
    }

    public static List<Prov> list(Context c) {
        List<Prov> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, "");
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Prov p = new Prov();
                p.id = o.optString("id", "");
                p.name = o.optString("name", "");
                p.baseUrl = o.optString("baseUrl", "");
                p.apiKey = o.optString("apiKey", "");
                p.models = o.optString("models", "");
                if (!p.id.isEmpty()) out.add(p);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_ASSTPROV", "助手服务商读不出来，当成还没有: " + e.getMessage());
        }
        return out;
    }

    public static Prov byId(Context c, String id) {
        if (id == null) return null;
        for (Prov p : list(c)) if (id.equals(p.id)) return p;
        return null;
    }

    public static Prov byName(Context c, String name) {
        if (name == null) return null;
        String want = name.trim();
        for (Prov p : list(c)) {
            if (want.equalsIgnoreCase(p.name == null ? "" : p.name.trim())) return p;
        }
        return null;
    }

    public static Prov byBaseUrl(Context c, String baseUrl) {
        if (baseUrl == null) return null;
        String want = baseUrl.trim();
        for (Prov p : list(c)) {
            if (want.equals(p.baseUrl == null ? "" : p.baseUrl.trim())) return p;
        }
        return null;
    }

    public static void save(Context c, Prov p) {
        if (p == null) return;
        if (p.id == null || p.id.trim().isEmpty()) {
            p.id = java.util.UUID.randomUUID().toString().replace("-", "");
        }
        List<Prov> all = list(c);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(p.id)) { all.set(i, p); replaced = true; break; }
        }
        if (!replaced) all.add(p);
        write(c, all);
    }

    public static void delete(Context c, String id) {
        List<Prov> all = list(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) { all.remove(i); break; }
        }
        write(c, all);
    }

    public static String namesText(Context c) {
        StringBuilder sb = new StringBuilder();
        for (Prov p : list(c)) {
            if (sb.length() > 0) sb.append("、");
            sb.append(p.name);
            int n = p.modelList().size();
            if (n > 0) sb.append("（").append(n).append(" 个模型）");
        }
        return sb.toString();
    }

    private static void write(Context c, List<Prov> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Prov p : all) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("baseUrl", p.baseUrl);
                o.put("apiKey", p.apiKey);
                o.put("models", p.models);
                arr.put(o);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_ASSTPROV", "助手服务商写不进去: " + e.getMessage());
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }
}
