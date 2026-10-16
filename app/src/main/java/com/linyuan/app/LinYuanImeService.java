package com.linyuan.app;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputConnection;

public class LinYuanImeService extends InputMethodService {

    private static final String TAG = "VC_IME";

    public static final String ACTION_TYPE = "com.linyuan.app.IME_TYPE";

    public static final String ACTION_CLEAR = "com.linyuan.app.IME_CLEAR";

    public static final String ACTION_RESULT = "com.linyuan.app.IME_RESULT";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MSG = "msg";
    public static final String EXTRA_DONE = "done";

    public static final String EXTRA_SEND = "send";

    public static final String ACTION_SEND = "com.linyuan.app.IME_SEND";

    public interface TypeCallback {
        void onDone(int done, String reason);
    }

    private static volatile boolean sUserHoldsUs;

    private static volatile long sAiSwitchAt;

    public static boolean isHeldByUser() {
        return sUserHoldsUs;
    }

    public static void setHeldByUser(boolean held) {
        sUserHoldsUs = held;
        Log.i(TAG, "保持状态: " + (held ? "✅ 用户手动持有（打完不切回）" : "AI 临时使用（打完切回）"));
    }

    public static void noteAiSwitch() {
        sAiSwitchAt = android.os.SystemClock.uptimeMillis();
        sUserHoldsUs = false;
        Log.i(TAG, "AI 主动切换（10 秒内不视为用户持有）");
    }

    public boolean isBusyTyping() {
        return pendingText != null;
    }

    public interface SendCallback {
        void onSendResult(boolean sent, String detail);
    }

    private static volatile SendCallback sSendCb;

    public static void setSendCallback(SendCallback cb) {
        sSendCb = cb;
    }

    private static volatile Boolean sLastSendOk;
    private static volatile String sLastSendDetail;

    public static Boolean lastSendResult() {
        return sLastSendOk;
    }

    public static String lastSendDetail() {
        return sLastSendDetail;
    }

    private static volatile LinYuanImeService sInstance;

    private static volatile String sFallbackUserIme;

    public static void rememberUserIme(String imeId) {
        if (imeId == null || imeId.isEmpty()) return;
        sFallbackUserIme = imeId;
        Log.i(TAG, "已记住用户输入法: " + imeId);
    }

    private static volatile boolean sConnected = false;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Runnable pendingChar;
    private TypeCallback pendingCb;
    private String pendingText;
    private int pendingIndex;
    private int pendingDelay;
    private boolean pendingCancelled;

    private boolean pendingSend;
    private android.content.BroadcastReceiver rx;

    public static LinYuanImeService instance() {
        return sInstance;
    }

    public static boolean isReady() {
        return sInstance != null;
    }

    public static boolean hasInputTarget() {
        return sConnected;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        rememberOtherImeIfAny();

        rx = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, android.content.Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (ACTION_TYPE.equals(action)) {
                    String text = intent.getStringExtra(EXTRA_TEXT);
                    int speed = intent.getIntExtra(EXTRA_SPEED, 0);
                    final boolean send = intent.getBooleanExtra(EXTRA_SEND, false);
                    typeTextAsync(text, speed, send, new TypeCallback() {
                        @Override
                        public void onDone(int done, String reason) {
                            broadcastResult(done, reason, text == null ? 0 : text.length());
                        }
                    });
                } else if (ACTION_SEND.equals(action)) {

                    if (!triggerSendAction(null)) broadcastSendResult(false);
                } else if (ACTION_CLEAR.equals(action)) {
                    cancelTyping();
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(ACTION_TYPE);
        f.addAction(ACTION_SEND);
        f.addAction(ACTION_CLEAR);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(rx, f);
        }
        Log.i(TAG, "lin渊输入法已就绪（无界面打字通道）");
    }

    private void broadcastResult(int done, String reason, int total) {
        try {
            android.content.Intent r = new android.content.Intent(ACTION_RESULT);
            r.setPackage(getPackageName());
            boolean full = (done == total && reason == null && total > 0);
            r.putExtra(EXTRA_STATE, full ? "ok" : "fail");
            r.putExtra(EXTRA_DONE, done);
            if (full) {
                r.putExtra(EXTRA_MSG, "已逐字打进去 " + done + " 个字");
            } else if (done > 0) {
                r.putExtra(EXTRA_MSG, "只打进去前 " + done + "/" + total + " 个字就停了（" + reason + "）");
            } else {
                r.putExtra(EXTRA_MSG, "一个字都没打进去（" + reason + "）");
            }
            sendBroadcast(r);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        sConnected = false;
        cancelTyping();
        try {
            if (rx != null) unregisterReceiver(rx);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        return false;
    }

    @Override
    public View onCreateInputView() {
        return null;
    }

    @Override
    public View onCreateCandidatesView() {
        return null;
    }

    @Override
    public void onStartInput(android.view.inputmethod.EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        sConnected = true;
        currentEditor = attribute;
        Log.i(TAG, "onStartInput: actionId=" + (attribute == null ? -1 : attribute.actionId)
                + " imeOptions=0x" + Integer.toHexString(attribute == null ? 0 : attribute.imeOptions)
                + " pkg=" + (attribute == null ? "?" : attribute.packageName));

        boolean inAiWindow = (android.os.SystemClock.uptimeMillis() - sAiSwitchAt) < 10_000L;
        if (!inAiWindow && !isBusyTyping() && !sUserHoldsUs && isCurrentInSystem(this)) {
            setHeldByUser(true);
        }
    }

    private void rememberOtherImeIfAny() {
        try {
            String cur = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
            if (cur == null || cur.isEmpty()) return;
            if (cur.startsWith(getPackageName())) return;
            if (cur.equals(AppSettings.getSavedUserIme(this))) return;
            AppSettings.setSavedUserIme(this, cur);
            Log.i(TAG, "已记住用户输入法: " + cur);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onFinishInput() {
        sConnected = false;
        currentEditor = null;
        rememberOtherImeIfAny();
        super.onFinishInput();
    }

    private static volatile android.view.inputmethod.EditorInfo currentEditor;

    public static int currentActionId() {
        android.view.inputmethod.EditorInfo ei = currentEditor;
        if (ei == null) return 0;
        return ei.imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
    }

    public static String currentActionName() {
        switch (currentActionId()) {
            case android.view.inputmethod.EditorInfo.IME_ACTION_SEND: return "SEND(发送)";
            case android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH: return "SEARCH(搜索)";
            case android.view.inputmethod.EditorInfo.IME_ACTION_GO: return "GO(前往)";
            case android.view.inputmethod.EditorInfo.IME_ACTION_DONE: return "DONE(完成)";
            case android.view.inputmethod.EditorInfo.IME_ACTION_NEXT: return "NEXT(下一项)";
            case android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS: return "PREVIOUS(上一项)";
            case android.view.inputmethod.EditorInfo.IME_ACTION_NONE: return "NONE(不动作，回车=换行)";
            case android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED: return "UNSPECIFIED(未声明)";
            default: return "未知(" + currentActionId() + ")";
        }
    }

    public boolean triggerSendAction() {
        return triggerSendAction(null, null);
    }

    public boolean triggerSendAction(String justTyped) {
        return triggerSendAction(justTyped, null);
    }

    public boolean triggerSendAction(String justTyped, final VerifyCallback whenDone) {
        android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            Log.w(TAG, "发送失败：没有输入连接（输入框已失焦？）");
            return false;
        }
        int action = currentActionId();
        Log.i(TAG, "尝试触发发送动作: " + currentActionName());
        boolean acted = false;
        try {

            if (action != 0
                    && action != android.view.inputmethod.EditorInfo.IME_ACTION_NONE
                    && action != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                acted = ic.performEditorAction(action);
            }

            if (!acted) {
                acted = ic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
            }
        } catch (Throwable t) {
            Log.w(TAG, "performEditorAction 抛异常: " + t);
        }

        if (!acted && action != 0 && action != android.view.inputmethod.EditorInfo.IME_ACTION_NONE) {
            try {
                long now = android.os.SystemClock.uptimeMillis();
                boolean down = ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0));
                boolean up = ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0));
                acted = down || up;
            } catch (Throwable t) {
                Log.w(TAG, "sendKeyEvent 失败: " + t);
            }
        }
        Log.i(TAG, "动作已投递=" + acted + "，接下来核实是否真的发出去了");

        if (!acted) return false;

        final boolean readbackOk = canReadBack(justTyped);
        scheduleVerify(600, readbackOk, whenDone);
        return true;
    }

    private boolean canReadBack() {
        return canReadBack(null);
    }

    private boolean canReadBack(String justTyped) {
        try {
            android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
            if (ic == null) return false;
            CharSequence before = ic.getTextBeforeCursor(200, 0);
            CharSequence after = ic.getTextAfterCursor(200, 0);
            String got = (before == null ? "" : before.toString()) + (after == null ? "" : after.toString());
            boolean ok = got.length() > 0;

            if (ok && justTyped != null && !justTyped.isEmpty()) {
                String head = justTyped.length() > 4 ? justTyped.substring(0, 4) : justTyped;
                ok = got.contains(head) || got.contains(justTyped);
            }
            Log.i(TAG, "读回能力检测：读到 " + got.length() + " 字 → readback=" + ok
                    + (justTyped == null ? "" : " 期望包含「" + justTyped + "」"));
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private void scheduleVerify(int delayMs, final boolean readbackOk, final VerifyCallback whenDone) {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!readbackOk) {

                    Log.i(TAG, "核实：这个输入框读不回内容，无法判断是否已发出");
                    sLastSendOk = null;
                    sLastSendDetail = "已投递发送动作，但这个输入框读不回内容，无法确认是否真的发出去了";
                    if (whenDone != null) whenDone.onVerified(false);
                    SendCallback cb = sSendCb;
                    sSendCb = null;
                    if (cb != null) {
                        try { cb.onSendResult(false, sLastSendDetail); } catch (Throwable ignored) { }
                    }
                    return;
                }
                boolean sent = checkInputCleared();
                Log.i(TAG, "核实发送结果: " + (sent ? "已发出" : "没发出去（文字还在输入框里）"));
                if (whenDone != null) whenDone.onVerified(sent);
            }
        }, delayMs);
    }

    public interface VerifyCallback {
        void onVerified(boolean sent);
    }

    private boolean checkInputCleared() {
        try {
            android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                Log.i(TAG, "核实：输入连接已消失 → 判定为已发出");
                return true;
            }

            CharSequence before = ic.getTextBeforeCursor(200, 0);
            CharSequence after = ic.getTextAfterCursor(200, 0);
            int n = (before == null ? 0 : before.length()) + (after == null ? 0 : after.length());
            boolean stillThere = n > 0;
            Log.i(TAG, "核实：输入框残留 " + n + " 字（前 " + (before == null ? 0 : before.length())
                    + " / 后 " + (after == null ? 0 : after.length()) + "）→ "
                    + (stillThere ? "没发出去" : "已发出"));
            return !stillThere;
        } catch (Throwable t) {
            Log.w(TAG, "核实发送结果失败: " + t);
            return false;
        }
    }

    private void broadcastSendResult(boolean ok) {
        String detail;
        if (ok) {
            detail = "已触发发送并核实成功（" + currentActionName() + "）";
        } else {
            detail = "没能发送：这个输入框不接受发送动作（它声明的是 " + currentActionName()
                    + "，发送按钮在应用自己手里，只能点击屏幕上的发送按钮）";
        }
        sLastSendOk = ok;
        sLastSendDetail = detail;
        SendCallback cb = sSendCb;
        sSendCb = null;
        if (cb != null) {
            try { cb.onSendResult(ok, detail); } catch (Throwable ignored) { }
        }
        try {
            android.content.Intent r = new android.content.Intent(ACTION_RESULT);
            r.setPackage(getPackageName());
            r.putExtra(EXTRA_STATE, ok ? "ok" : "fail");
            r.putExtra(EXTRA_MSG, detail);
            sendBroadcast(r);
        } catch (Throwable ignored) {
        }
    }

    public void typeTextAsync(String text, int perCharMs, TypeCallback cb) {
        typeTextAsync(text, perCharMs, false, cb);
    }

    public void typeTextAsync(String text, int perCharMs, boolean sendAfter, TypeCallback cb) {
        if (TextUtils.isEmpty(text)) return;
        cancelTyping();

        pendingSend = sendAfter;
        pendingText = text;
        pendingIndex = 0;
        pendingCb = cb;
        pendingCancelled = false;
        pendingDelay = perCharMs > 0
                ? perCharMs
                : Math.min(45, Math.max(8, (int) (2500L / Math.max(1, text.length()))));

        pendingChar = new Runnable() {
            @Override
            public void run() {
                step();
            }
        };
        ui.post(pendingChar);
    }

    public void cancelTyping() {
        if (pendingChar != null) {
            ui.removeCallbacks(pendingChar);
            pendingChar = null;
        }
        pendingCancelled = true;
        pendingCb = null;
        pendingText = null;
        pendingIndex = 0;
    }

    private void step() {
        if (pendingCancelled || pendingText == null) return;

        if (pendingIndex >= pendingText.length()) {
            finishTyping(pendingIndex, null);
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            finishTyping(pendingIndex, "当前没有正在等待输入的输入框（请先点一下要输入的框，让光标出现）");
            return;
        }

        String chunk = pendingText.substring(pendingIndex, pendingIndex + 1);
        boolean ok;
        try {
            ok = ic.commitText(chunk, 1);
        } catch (Throwable e) {
            ok = false;
            finishTyping(pendingIndex, "提交失败：" + e.getMessage());
            return;
        }
        if (!ok) {
            finishTyping(pendingIndex, "输入框拒绝了这次提交");
            return;
        }

        pendingIndex++;
        if (pendingIndex >= pendingText.length()) {
            finishTyping(pendingIndex, null);
            return;
        }
        if (pendingChar != null) ui.postDelayed(pendingChar, pendingDelay);
    }

    private void finishTyping(int done, String reason) {
        TypeCallback cb = pendingCb;
        String text = pendingText;
        boolean wantSend = pendingSend;
        pendingCb = null;
        pendingText = null;
        pendingChar = null;
        pendingSend = false;
        Log.i(TAG, "打字结果: done=" + done + "/" + (text == null ? 0 : text.length())
                + " reason=" + reason + " 需要发送=" + wantSend);

        final boolean wantSendFinal = wantSend && done > 0 && text != null && done == text.length();

        if (cb != null) cb.onDone(done, reason);

        if (done > 0) {
            if (wantSendFinal) {

                boolean acted = triggerSendAction(text, new VerifyCallback() {
                    @Override
                    public void onVerified(boolean sent) {
                        switchBackLater(0);
                    }
                });
                if (!acted) {
                    broadcastSendResult(false);
                    switchBackLater(0);
                }
            } else {
                switchBackLater(700);
            }
        }
    }

    public boolean switchToUserImeNow() {
        setHeldByUser(false);
        return restoreUserIme();
    }

    private boolean shouldRestoreAfterTyping() {

        if (sUserHoldsUs) {
            Log.i(TAG, "用户手动持有输入法 → 打完不切回（要切回请对我说「切回我的输入法」）");
            return false;
        }

        try {
            return AppSettings.isImeRestoreAfterTyping(this);
        } catch (Throwable t) {
            return true;
        }
    }

    private void switchBackLater(int delayMs) {
        if (!shouldRestoreAfterTyping()) {
            Log.i(TAG, "设置要求保留打字通道输入法，不切回");
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!restoreUserIme()) {
                    Log.i(TAG, "未能自动切回（未记录用户输入法）");
                }
            }
        }, delayMs);
    }

    private boolean restoreUserIme() {
        String saved = AppSettings.getSavedUserIme(this);
        if (saved == null || saved.isEmpty()) {
            saved = sFallbackUserIme;
        }
        if (saved == null || saved.isEmpty() || saved.startsWith(getPackageName())) {

            saved = pickOtherEnabledIme();
        }
        if (saved == null || saved.isEmpty()) return false;
        try {
            switchInputMethod(saved);
            Log.i(TAG, "已切回用户输入法: " + saved);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "切回输入法失败: " + t);
            return false;
        }
    }

    private String pickOtherEnabledIme() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return null;
            java.util.List<android.view.inputmethod.InputMethodInfo> list = imm.getEnabledInputMethodList();
            if (list == null) return null;
            for (android.view.inputmethod.InputMethodInfo info : list) {
                if (info == null) continue;
                String id = info.getId();
                if (id == null || id.isEmpty()) continue;
                if (id.startsWith(getPackageName())) continue;
                return id;
            }
        } catch (Throwable t) {
            Log.w(TAG, "列出已启用输入法失败: " + t.getMessage());
        }
        return null;
    }

    public static boolean isEnabledInSystem(Context ctx) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return false;
            java.util.List<android.view.inputmethod.InputMethodInfo> list = imm.getEnabledInputMethodList();
            if (list == null) return false;
            for (android.view.inputmethod.InputMethodInfo info : list) {
                if (info != null && info.getId() != null && info.getId().startsWith(ctx.getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isCurrentInSystem(Context ctx) {
        try {
            String cur = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
            return cur != null && cur.startsWith(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static String componentName(Context ctx) {
        return ctx.getPackageName() + "/" + LinYuanImeService.class.getName();
    }
}
