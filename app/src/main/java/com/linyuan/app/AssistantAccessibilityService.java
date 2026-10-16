package com.linyuan.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;

public class AssistantAccessibilityService extends AccessibilityService {

    private static volatile AssistantAccessibilityService sInstance;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static boolean enabled = false;
    private static final AtomicReference<String> lastScreenText = new AtomicReference<>("");

    private static final AtomicReference<String> lastPackage = new AtomicReference<>("");
    private static final AtomicReference<String> lastActivity = new AtomicReference<>("");

    public static String foregroundPackage() {

        String fromRoot = activeWindowPackage();
        if (!fromRoot.isEmpty()) return fromRoot;
        String p = lastPackage.get();
        return p == null ? "" : p;
    }

    private static String activeWindowPackage() {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return "";
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return "";
            CharSequence p = root.getPackageName();
            try { root.recycle(); } catch (Throwable ignored) { }
            return p == null ? "" : p.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String foregroundActivity() {
        AssistantAccessibilityService svc = sInstance;

        try {
            if (svc != null) {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root != null) {
                    CharSequence c = root.getClassName();
                    try { root.recycle(); } catch (Throwable ignored) { }
                    if (c != null && c.length() > 0) return c.toString();
                }
            }
        } catch (Throwable ignored) { }
        String a = lastActivity.get();
        return a == null ? "" : a;
    }

    public static boolean isEnabled() {
        return sInstance != null && enabled;
    }

    public static AssistantAccessibilityService instance() {
        return sInstance;
    }

    public static boolean canDispatch() {
        return isEnabled();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        enabled = true;
        android.util.Log.i("VC_ACCESS", "AccessibilityService connected");

        try { SystemIslandService.get(this).onAccessibilityReady(); } catch (Throwable ignored) { }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        try {
            CharSequence p = event.getPackageName();
            if (p != null && p.length() > 0) lastPackage.set(p.toString());
            CharSequence c = event.getClassName();
            if (c != null && c.length() > 0) lastActivity.set(c.toString());
        } catch (Throwable ignored) { }

        logPhoneAwarenessEvent(event);

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pendingRefresh != null) refreshHandler.removeCallbacks(pendingRefresh);
            pendingRefresh = this::refreshScreenText;
            refreshHandler.postDelayed(pendingRefresh, 400);
        }
    }

    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingRefresh;

    private void refreshScreenText() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                StringBuilder sb = new StringBuilder();
                collectText(root, sb, 0);
                if (sb.length() > 0) lastScreenText.set(sb.toString());
                root.recycle();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (pendingRefresh != null) refreshHandler.removeCallbacks(pendingRefresh);
        sInstance = null;
        enabled = false;
        super.onDestroy();
    }

    public static String getScreenText() {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return "";
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root != null) {
                StringBuilder sb = new StringBuilder();
                collectText(root, sb, 0);
                try { root.recycle(); } catch (Throwable ignored) {}
                if (sb.length() > 0) lastScreenText.set(sb.toString());
                return sb.toString();
            }
        } catch (Throwable ignored) {
        }
        return lastScreenText.get();
    }

    public static boolean tap(float xPercent, float yPercent) {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return false;
        float w = svc.getResources().getDisplayMetrics().widthPixels;
        float h = svc.getResources().getDisplayMetrics().heightPixels;
        float x = w * xPercent / 100f;
        float y = h * yPercent / 100f;
        return svc.performTap(x, y);
    }

    public static boolean swipe(float x1, float y1, float x2, float y2, int durationMs) {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return false;
        float w = svc.getResources().getDisplayMetrics().widthPixels;
        float h = svc.getResources().getDisplayMetrics().heightPixels;
        float sx = w * x1 / 100f;
        float sy = h * y1 / 100f;
        float ex = w * x2 / 100f;
        float ey = h * y2 / 100f;
        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(ex, ey);
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(200, durationMs)));
        return svc.dispatchGesture(gb.build(), null, null);
    }

    public static boolean goBack() {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return false;
        return svc.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public static boolean goHome() {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return false;
        return svc.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public static boolean openRecents() {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return false;
        return svc.performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    public static boolean openNotifications() {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return false;
        return svc.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    public static void copyToClipboard(String text) {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return;
        ClipboardManager cm = (ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("assistant", text));
    }

    public static String getClipboardText() {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return "";
        try {
            ClipboardManager cm = (ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                return t == null ? "" : t.toString();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private boolean performLongPress(float x, float y) {
        try {
            Path p1 = new Path();
            p1.moveTo(x, y);
            GestureDescription.Builder gb = new GestureDescription.Builder();
            gb.addStroke(new GestureDescription.StrokeDescription(p1, 0, 600, true));
            GestureDescription down = gb.build();
            return dispatchGesture(down, null, null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean paste() {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return false;
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo editable = findEditableNode(root, 0);
            boolean ok = false;
            if (editable != null) {

                editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                ok = editable.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                try { editable.recycle(); } catch (Throwable ignored) {}
            }
            try { root.recycle(); } catch (Throwable ignored) {}
            if (ok) return true;

            int[] center = svc.findEditableCenter();
            return center != null && svc.performLongPress(center[0], center[1]);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return null;
        if (node.isEditable() || node.isFocused()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findEditableNode(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static boolean typeText(String text) {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null || text == null || text.isEmpty()) return false;
        if (typeGradually(text, 0) > 0) return true;

        copyToClipboard(text);
        return paste();
    }

    public static int typeGradually(String text, int perCharMs) {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null || text == null || text.isEmpty()) return -1;

        String base = currentEditableText();
        if (base == null) base = "";

        int delay = perCharMs > 0
                ? perCharMs
                : Math.min(45, Math.max(8, (int) (2500L / Math.max(1, text.length()))));

        int done = 0;
        for (int i = 1; i <= text.length(); i++) {

            if (!setTextDirect(base + text.substring(0, i))) break;
            done = i;
            if (i < text.length()) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return done == 0 ? -1 : done;
    }

    private static String currentEditableText() {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return null;
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return null;
            AccessibilityNodeInfo editable = findEditableNode(root, 0);
            String t = null;
            if (editable != null) {
                CharSequence cs = editable.getText();
                t = cs == null ? "" : cs.toString();
                try { editable.recycle(); } catch (Throwable ignored) {}
            }
            try { root.recycle(); } catch (Throwable ignored) {}
            return t;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean setText(String text) {
        return setTextDirect(text);
    }

    private static boolean setTextDirect(String text) {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null || text == null) return false;
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo editable = findEditableNode(root, 0);
            boolean ok = false;
            if (editable != null) {
                android.os.Bundle b = new android.os.Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                try { editable.recycle(); } catch (Throwable ignored) {}
            }
            try { root.recycle(); } catch (Throwable ignored) {}
            return ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int[] findEditableCenter() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            AccessibilityNodeInfo editable = findEditableNode(root, 0);
            if (editable == null) { try { root.recycle(); } catch (Throwable ignored) {} return null; }
            Rect r = new Rect();
            editable.getBoundsInScreen(r);
            try { editable.recycle(); } catch (Throwable ignored) {}
            try { root.recycle(); } catch (Throwable ignored) {}
            return new int[]{r.centerX(), r.centerY()};
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean clickByText(String text) {
        AssistantAccessibilityService svc = sInstance;
        if (svc == null) return false;
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo target = findNodeByText(root, text, 0);
            if (target == null) { try { root.recycle(); } catch (Throwable ignored) {} return false; }
            Rect r = new Rect();
            target.getBoundsInScreen(r);
            try { target.recycle(); } catch (Throwable ignored) {}
            try { root.recycle(); } catch (Throwable ignored) {}
            return svc.performTap(r.centerX(), r.centerY());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean performTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0, 60));
        return dispatchGesture(gb.build(), null, null);
    }

    private static AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo node, String text, int depth) {
        if (node == null || depth > 40) return null;
        CharSequence t = node.getText();
        if (t != null && t.toString().contains(text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findNodeByText(child, text, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String collectText(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 60) return "";
        CharSequence t = node.getText();
        if (t != null && !t.toString().trim().isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectText(child, sb, depth + 1);
            }
        }
        return sb.toString();
    }

    public static boolean canScreenshot() {
        return sInstance != null && isEnabled() && android.os.Build.VERSION.SDK_INT >= 30;
    }

    public static java.io.File screenshotTo(java.io.File out) {
        final AssistantAccessibilityService svc = sInstance;
        if (svc == null || !isEnabled() || android.os.Build.VERSION.SDK_INT < 30) return null;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = new boolean[]{false};
        try {
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    java.util.concurrent.Executors.newSingleThreadExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult r) {
                            android.graphics.Bitmap bmp = null;
                            android.graphics.Bitmap soft = null;
                            try {
                                bmp = android.graphics.Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                                if (bmp != null) {
                                    soft = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                                }
                                if (soft != null) {
                                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                                    try { ok[0] = soft.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos); }
                                    finally { fos.close(); }
                                }
                            } catch (Throwable t) {
                                android.util.Log.w("VC_SHOT", "保存截图失败: " + t);
                            } finally {
                                if (soft != null) soft.recycle();
                                if (bmp != null) bmp.recycle();
                                try { r.getHardwareBuffer().close(); } catch (Throwable ignored) { }
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            android.util.Log.w("VC_SHOT", "系统拒绝截图，错误码 " + errorCode);
                            latch.countDown();
                        }
                    });
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            android.util.Log.w("VC_SHOT", "截图异常: " + t);
        }
        return ok[0] ? out : null;
    }

    private void logPhoneAwarenessEvent(AccessibilityEvent event) {
        try {
            if (!PhoneAwareness.enabled(this)) return;
            int t = event.getEventType();
            String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
            if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                PhoneAwareness.setForegroundPkg(pkg);
                CharSequence cls = event.getClassName();
                String win = cls == null ? "" : cls.toString();
                int dot = win.lastIndexOf('.');
                if (dot >= 0 && dot + 1 < win.length()) win = win.substring(dot + 1);
                String app = PhoneAwareness.appLabel(this, pkg);
                PhoneAwareness.noteForeground(this, app);
                PhoneAwareness.recordEvent(this, app, "switch", win);
            }

        } catch (Throwable ignored) { }
    }

    private String phoneEventText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        AccessibilityNodeInfo src = null;
        try {
            java.util.List<CharSequence> txt = event.getText();
            if (txt != null) {
                for (CharSequence cs : txt) {
                    if (cs == null) continue;
                    String x = cs.toString().trim();
                    if (x.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(x);
                    if (sb.length() >= 30) break;
                }
            }
            src = event.getSource();
            if (src != null && src.isPassword()) return null;
            if (sb.length() == 0 && src != null) {
                CharSequence cd = src.getContentDescription();
                if (cd != null) sb.append(cd.toString().trim());
                if (sb.length() == 0 && src.getText() != null) sb.append(src.getText().toString().trim());
            }
        } catch (Throwable ignored) {
        } finally {
            if (src != null) {
                try { src.recycle(); } catch (Throwable ignored) { }
            }
        }
        return sb.toString();
    }
}
