package com.linyuan.app;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.text.method.PasswordTransformationMethod;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

final class MaskedFieldMenu {

    private static final int BTN_H_DP = 36;

    private MaskedFieldMenu() {
    }

    private static boolean isMasked(EditText et) {
        return et.getTransformationMethod() instanceof PasswordTransformationMethod;
    }

    static String eyeTag(EditText et) {
        return "pwEye_" + System.identityHashCode(et);
    }

    static String pasteTag(EditText et) {
        return "pwPaste_" + System.identityHashCode(et);
    }

    private static String rowTag(EditText et) {
        return "pwRow_" + System.identityHashCode(et);
    }

    private static android.widget.LinearLayout ensureButtonRow(EditText et, FrameLayout holder) {
        android.view.View v = holder.findViewWithTag(rowTag(et));
        if (v instanceof android.widget.LinearLayout) return (android.widget.LinearLayout) v;
        Context ctx = et.getContext();
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setTag(rowTag(et));
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dpx(ctx, BTN_H_DP));
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        holder.addView(row, lp);
        return row;
    }

    private static int dpx(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static int color(Context c, int resId) {
        try {
            return c.getResources().getColor(resId);
        } catch (Throwable t) {
            return 0xFF888888;
        }
    }

    static FrameLayout ensureHolder(EditText et) {
        if (et == null || et.getContext() == null) return null;
        ViewGroup parent = et.getParent() instanceof ViewGroup ? (ViewGroup) et.getParent() : null;
        if (parent == null) return null;

        if (parent instanceof FrameLayout) {
            FrameLayout fl = (FrameLayout) parent;
            if (fl.findViewWithTag(pasteTag(et)) != null) return fl;
            if (fl.findViewWithTag(eyeTag(et)) != null) return fl;
            if (fl.getChildCount() == 1) return fl;
        }

        int idx = parent.indexOfChild(et);
        if (idx < 0) return null;
        ViewGroup.LayoutParams origLp = et.getLayoutParams();
        final boolean fixedHeight = origLp != null && origLp.height > 0;
        FrameLayout box = new FrameLayout(et.getContext());
        box.setLayoutParams(origLp);

        box.setVisibility(et.getVisibility());
        parent.removeViewAt(idx);
        et.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                fixedHeight ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(et);
        parent.addView(box, idx);
        return box;
    }

    static void applyRightPadding(EditText et, FrameLayout holder) {
        if (et == null || holder == null) return;
        int n = 0;
        android.view.View row = holder.findViewWithTag(rowTag(et));
        if (row instanceof ViewGroup) n = ((ViewGroup) row).getChildCount();
        int right = n >= 2 ? 76 : (n == 1 ? 44 : 12);
        et.setPadding(dpx(et.getContext(), 12), et.getPaddingTop(),
                dpx(et.getContext(), right), et.getPaddingBottom());
    }

    static int attachMaskToggle(final EditText et) {
        if (et == null) return 0x81;

        final int MASKED = 0x81;
        final int VISIBLE = 0x01;

        final int baseType = et.getInputType() & ~0xF0;
        et.setInputType(baseType | MASKED);
        attach(et);

        FrameLayout holder = ensureHolder(et);
        if (holder == null) return MASKED;
        if (holder.findViewWithTag(eyeTag(et)) != null) {
            applyRightPadding(et, holder);
            return MASKED;
        }
        final Context ctx = et.getContext();
        final boolean[] shown = new boolean[]{false};
        TextView eye = new TextView(ctx);
        eye.setTag(eyeTag(et));
        eye.setText("\uD83D\uDC41");
        eye.setTextSize(15f);
        eye.setGravity(Gravity.CENTER);
        eye.setTextColor(color(ctx, R.color.muted));
        eye.setPadding(dpx(ctx, 8), 0, dpx(ctx, 10), 0);
        eye.setContentDescription("显示/隐藏密钥");
        eye.setOnClickListener(v -> {
            shown[0] = !shown[0];
            et.setInputType(baseType | (shown[0] ? VISIBLE : MASKED));

            et.setSelection(et.getText().length());
            eye.setTextColor(color(ctx, shown[0] ? R.color.accent : R.color.muted));
        });

        android.widget.LinearLayout row = ensureButtonRow(et, holder);
        row.addView(eye, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dpx(ctx, BTN_H_DP)));
        applyRightPadding(et, holder);
        return MASKED;
    }

    static void attachTree(ViewGroup root) {
        if (root == null) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            if (child instanceof EditText) {
                EditText et = (EditText) child;
                attach(et);
                addPasteButton(et);
            } else if (child instanceof ViewGroup) {
                attachTree((ViewGroup) child);
            }
        }
    }

    static boolean clipboardHasText(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            return cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static CharSequence readClipboardText(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;

            return clip.getItemAt(0).coerceToText(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean pasteInto(EditText et) {
        if (et == null || et.getContext() == null) return false;
        Context ctx = et.getContext();
        CharSequence text = readClipboardText(ctx);
        if (text == null || text.length() == 0) {
            Toast.makeText(ctx, "剪贴板是空的", Toast.LENGTH_SHORT).show();
            return false;
        }
        et.setText(text);
        et.setSelection(et.getText().length());
        Toast.makeText(ctx, "已粘贴 " + text.length() + " 个字符", Toast.LENGTH_SHORT).show();
        return true;
    }

    private static TextView makePasteButton(Context ctx, final EditText et) {
        TextView b = new TextView(ctx);
        b.setText("粘贴");
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(color(ctx, R.color.muted));
        b.setPadding(dpx(ctx, 8), 0, dpx(ctx, 8), 0);
        b.setContentDescription("粘贴剪贴板内容");
        b.setOnClickListener(v -> pasteInto(et));
        return b;
    }

    static void addPasteButton(EditText et) {
        if (et == null) return;
        FrameLayout holder = ensureHolder(et);
        if (holder == null) return;
        if (holder.findViewWithTag(pasteTag(et)) != null) return;
        android.widget.LinearLayout row = ensureButtonRow(et, holder);
        TextView b = makePasteButton(et.getContext(), et);
        b.setTag(pasteTag(et));

        row.addView(b, 0, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dpx(et.getContext(), BTN_H_DP)));
        applyRightPadding(et, holder);
    }

    static void attach(final EditText et) {        if (et == null) return;
        ActionMode.Callback cb = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                boolean masked = isMasked(et);

                if (menu.findItem(android.R.id.copy) == null) {
                    menu.add(Menu.NONE, android.R.id.copy, 5, "复制")
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                }
                if (menu.findItem(android.R.id.paste) == null && clipboardHasText(et.getContext())) {
                    menu.add(Menu.NONE, android.R.id.paste, 6, "粘贴")
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                }
                if (masked && menu.findItem(android.R.id.selectAll) == null) {

                    menu.add(Menu.NONE, android.R.id.selectAll, 8, "全选")
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                }
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (!isMasked(et)) {

                    return false;
                }
                int id = item.getItemId();
                if (id == android.R.id.selectAll) {

                    et.selectAll();
                    return true;
                }
                if (id == android.R.id.paste) {
                    pasteInto(et);
                    mode.finish();
                    return true;
                }
                if (id == android.R.id.copy) {
                    if (copyRealValue(et)) {
                        mode.finish();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }
        };
        et.setCustomSelectionActionModeCallback(cb);
        et.setCustomInsertionActionModeCallback(cb);
    }

    private static boolean copyRealValue(EditText et) {
        CharSequence raw = et.getText();
        if (raw == null || raw.length() == 0) {
            return false;
        }
        String real = raw.toString();
        int s = et.getSelectionStart();
        int e = et.getSelectionEnd();
        int from = Math.max(0, Math.min(s, e));
        int to = Math.min(real.length(), Math.max(s, e));
        String out = (to > from) ? real.substring(from, to) : real;

        Context ctx = et.getContext();
        if (ctx == null) {
            return false;
        }
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return false;
        }
        ClipData clip = ClipData.newPlainText("key", out);

        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);

        Toast.makeText(ctx, "已复制完整内容", Toast.LENGTH_SHORT).show();
        return true;
    }
}
