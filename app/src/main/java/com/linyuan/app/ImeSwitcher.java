package com.linyuan.app;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

public final class ImeSwitcher {

    private static final String TAG = "VC_IME_SWITCH";

    private ImeSwitcher() { }

    public static boolean canSwitchSilently(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static String currentImeId(Context ctx) {
        try {
            return Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void rememberUserIme(Context ctx) {
        String cur = currentImeId(ctx);
        if (cur == null || cur.startsWith(ctx.getPackageName())) return;
        AppSettings.setSavedUserIme(ctx, cur);
        LinYuanImeService.rememberUserIme(cur);
        Log.i(TAG, "已记住用户输入法: " + cur);
    }

    public static boolean switchToOurs(Context ctx) {
        if (!canSwitchSilently(ctx)) return false;
        String ours = LinYuanImeService.componentName(ctx);
        if (!LinYuanImeService.isEnabledInSystem(ctx)) {
            Log.w(TAG, "本输入法尚未在系统里启用，无法切换");
            return false;
        }
        if (ours.equals(currentImeId(ctx))) return true;
        rememberUserIme(ctx);
        try {
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, ours);
            boolean ok = ours.equals(currentImeId(ctx));
            Log.i(TAG, "静默切换到 lin渊输入法: " + ok);
            if (ok) LinYuanImeService.noteAiSwitch();
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "静默切换失败: " + t.getMessage());
            return false;
        }
    }

    public static boolean restore(Context ctx) {
        String saved = AppSettings.getSavedUserIme(ctx);
        if (saved == null || saved.isEmpty()) return false;
        if (!LinYuanImeService.isCurrentInSystem(ctx)) return true;
        if (!canSwitchSilently(ctx)) return false;
        try {
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, saved);
            boolean ok = saved.equals(currentImeId(ctx));
            Log.i(TAG, "已切回用户输入法 " + saved + ": " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "切回失败: " + t.getMessage());
            return false;
        }
    }

    public static void showPicker(Context ctx) {
        try {
            InputMethodManager imm = (InputMethodManager)
                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        } catch (Throwable t) {
            Log.w(TAG, "弹输入法选择器失败: " + t.getMessage());
        }
    }

    public static void openImeSettings(Context ctx) {
        try {
            ctx.startActivity(new android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Log.w(TAG, "打开输入法设置失败: " + t.getMessage());
        }
    }
}
