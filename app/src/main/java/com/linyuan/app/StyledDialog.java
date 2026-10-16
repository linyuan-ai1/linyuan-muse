package com.linyuan.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class StyledDialog {

    private StyledDialog() { }

    /** 主题判定：与 MainActivity 同一个 prefs 键（默认深色）。
     *  用户 2026-10-16 定：这一家族的弹窗（确认框/信息框/……）都要跟随主题，
     *  否则深色下白卡里铺着主题色的内容，一眼就是"没换肤"。 */
    private static boolean dark(Activity act) {
        return act.getSharedPreferences("voice_companion", android.content.Context.MODE_PRIVATE)
                .getBoolean("darkTheme", true);
    }

    public static LinearLayout card(Activity act) {
        final boolean dark = dark(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF141A28 : 0xFFFFFFFF);
        bg.setCornerRadius(dp(act, 20));
        bg.setStroke(dp(act, 1), dark ? 0xFF273349 : 0xFFE7E1D6);
        box.setBackground(bg);
        box.setPadding(dp(act, 18), dp(act, 16), dp(act, 18), dp(act, 14));
        return box;
    }

    public static TextView title(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextColor(dark(act) ? 0xFFEAF0FB : 0xFF202632);
        tv.setTextSize(16);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    public static TextView body(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextColor(dark(act) ? 0xFF9AACC6 : 0xFF6B7280);
        tv.setTextSize(13);
        tv.setLineSpacing(dp(act, 3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    public static LinearLayout chip(Activity act, String text) {
        LinearLayout chip = new LinearLayout(act);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        final boolean dark = dark(act);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF16233C : 0xFFE6EEFF);
        bg.setCornerRadius(dp(act, 8));
        bg.setStroke(dp(act, 1), dark ? 0x404C8DFF : 0x402F6BE6);
        chip.setBackground(bg);
        chip.setPadding(dp(act, 9), dp(act, 6), dp(act, 9), dp(act, 6));
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextColor(dark ? 0xFF6AA4FF : 0xFF2F6BE6);
        tv.setTextSize(12);
        tv.setLineSpacing(dp(act, 2), 1f);
        chip.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return chip;
    }

    public static Button primaryBtn(Activity act, String text) {
        return styleBtn(act, text, R.drawable.btn_primary, 0xFFFFFFFF);
    }

    public static Button ghostBtn(Activity act, String text) {
        return styleBtn(act, text, R.drawable.btn_ghost, dark(act) ? 0xFF6AA4FF : 0xFF2F6BE6);
    }

    public static Button dangerBtn(Activity act, String text) {
        return styleBtn(act, text, R.drawable.btn_danger, dark(act) ? 0xFFFF6B6B : 0xFFDC2626);
    }

    private static Button styleBtn(Activity act, String text, int bgRes, int textColor) {
        Button b = new Button(act);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setBackgroundResource(bgRes);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(act, 14), dp(act, 9), dp(act, 14), dp(act, 9));
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    public static LinearLayout buttonRows(Activity act, Button... btns) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.topMargin = dp(act, 14);
        col.setLayoutParams(colLp);
        for (int i = 0; i < btns.length; i += 2) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);
            if (i > 0) {
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(act, 8);
                row.setLayoutParams(rlp);
            }

            LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            btns[i].setLayoutParams(lp0);
            row.addView(btns[i]);
            if (i + 1 < btns.length) {
                LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp1.setMarginStart(dp(act, 8));
                btns[i + 1].setLayoutParams(lp1);
                row.addView(btns[i + 1]);
            }
            col.addView(row);
        }
        return col;
    }

    public static Dialog show(Activity act, View content) {
        return show(act, content, 0.88f);
    }

    public static Dialog show(Activity act, View content, float widthRatio) {
        Dialog dlg = new Dialog(act);
        dlg.setContentView(content);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (dm.widthPixels * widthRatio);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }
        dlg.show();
        return dlg;
    }

    public static Dialog showFixedHeight(Activity act, View content, float widthRatio, float heightRatio) {
        Dialog dlg = new Dialog(act);
        dlg.setContentView(content);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (dm.widthPixels * widthRatio);
            lp.height = (int) (dm.heightPixels * heightRatio);
            w.setAttributes(lp);
        }
        dlg.show();
        return dlg;
    }

    public static Dialog confirm(Activity act, String titleText, String message,
                                 String confirmLabel, boolean danger, Runnable onConfirm) {
        LinearLayout box = card(act);
        box.addView(title(act, titleText));
        if (message != null && !message.isEmpty()) box.addView(body(act, message));
        Button cancel = ghostBtn(act, "取消");
        Button ok = danger ? dangerBtn(act, confirmLabel) : primaryBtn(act, confirmLabel);
        box.addView(buttonRows(act, cancel, ok));
        final Dialog dlg = show(act, box);
        cancel.setOnClickListener(v -> dlg.dismiss());
        ok.setOnClickListener(v -> {
            dlg.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        return dlg;
    }

    public static Dialog info(Activity act, String titleText, String message) {
        LinearLayout box = card(act);
        box.addView(title(act, titleText));
        if (message != null && !message.isEmpty()) box.addView(body(act, message));
        Button ok = primaryBtn(act, "知道了");
        box.addView(buttonRows(act, ok));
        final Dialog dlg = show(act, box);
        ok.setOnClickListener(v -> dlg.dismiss());
        return dlg;
    }

    static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }
}
