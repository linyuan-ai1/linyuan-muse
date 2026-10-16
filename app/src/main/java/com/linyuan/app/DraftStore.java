package com.linyuan.app;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

public class DraftStore {

    private static final String PREFS = "gm_drafts";

    public static String load(Context ctx, String key) {
        if (ctx == null || key == null) return "";
        try {
            String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "");
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    public static void save(Context ctx, String key, String text) {
        if (ctx == null || key == null) return;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(key, text == null ? "" : text).apply();
        } catch (Exception ignored) { }
    }

    public static void bind(final EditText et, final Context ctx, final String key) {
        if (et == null || ctx == null || key == null) return;
        String d = load(ctx, key);
        if (!d.isEmpty()) {
            et.setText(d);
            et.setSelection(d.length());
        }
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                save(ctx, key, s == null ? "" : s.toString());
            }
        });
    }
}
