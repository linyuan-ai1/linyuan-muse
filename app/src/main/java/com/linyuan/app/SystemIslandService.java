package com.linyuan.app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;

public class SystemIslandService {

    private static SystemIslandService sInstance;

    private static final int CAPSULE_MIN_H_DP = 26;

    private static final int CAPSULE_PAD_Y_DP = 4;

    private static final int CAPSULE_MAX_H_DP = 34;

    private static final int IDLE_LOBE_DP = 34;

    private static final int NARROW_LOBE_DP = 18;

    private static final int WORK_LOBE_DP = 58;

    private int normalX = 0;

    private static final int GAP_SIDE_MARGIN_DP = 2;

    private final Context appCtx;
    private WindowManager wm;
    private BrowserIslandView island;
    private WindowManager.LayoutParams lp;
    private volatile boolean attached = false;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean stickyWanted = false;
    private volatile Runnable tapActionWanted = null;

    private final Runnable reattachSoon = new Runnable() {
        @Override
        public void run() {
            if (islandDetachIsFinal) return;
            try {
                android.util.Log.w("VC_ISLAND", "岛被摘掉 → 立即重挂");
                detach();
                if (attach() && island != null && (stickyWanted || desktopPersistentWanted)) {

                    island.setSticky(true);
                    if (desktopPersistentWanted) {
                        island.setDesktopPersistent(true);
                    }
                    applyTapActions();
                    setTouchable(desktopPersistentWanted);
                    island.refreshNow();
                }
            } catch (Throwable t) {
                android.util.Log.w("VC_ISLAND", "即时重挂失败: " + t);
            }
        }
    };

    private volatile boolean islandDetachIsFinal = false;

    private SystemIslandService(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public static synchronized SystemIslandService get(Context ctx) {
        if (sInstance == null) sInstance = new SystemIslandService(ctx);
        return sInstance;
    }

    public BrowserIslandView island() {
        return island;
    }

    public boolean isAttached() {
        return attached && island != null;
    }

    public boolean attach() {
        if (attached && island != null) {

            if (island.isAttachedToWindow()) return true;
            android.util.Log.w("VC_ISLAND", "岛窗口已不在屏幕上，清理后重挂");
            detach();
        }
        if (!canDrawOverlays()) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) {

            ui.post(this::attach);
            return false;
        }
        try {
            int type = overlayType();
            android.util.Log.i("VC_ISLAND", "开始挂载：类型=" + type);

            Context winCtx;
            AssistantAccessibilityService acc = AssistantAccessibilityService.instance();
            if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && acc != null) {
                winCtx = acc;
            } else {
                winCtx = appCtx;
            }
            wm = (WindowManager) winCtx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;
            island = new BrowserIslandView(winCtx);

            island.setDetachCallback(() -> {
                if (Looper.myLooper() == Looper.getMainLooper()) reattachSoon.run();
                else ui.post(reattachSoon);
            });

            int[] hole = cameraHoleRect(appCtx);
            int holeH = hole != null ? (hole[3] - hole[1]) : 0;
            int capH = holeH > 0
                    ? holeH + dp(CAPSULE_PAD_Y_DP) * 2
                    : Math.max(dp(CAPSULE_MIN_H_DP), cutoutBottom(appCtx) / 2);
            capH = Math.min(capH, dp(CAPSULE_MAX_H_DP));

            int y = 0;
            int camCy = cameraHoleCenterY(appCtx);
            if (camCy > 0) {
                y = Math.max(0, camCy - capH / 2);
                android.util.Log.i("VC_ISLAND", "摄像头开孔圆心 y=" + camCy + "px、孔高 " + holeH
                        + "px → 胶囊高 " + capH + "px，窗口 y=" + y + "px");
            } else {
                android.util.Log.i("VC_ISLAND", "拿不到开孔圆心，胶囊顶格显示（y=0）");
            }

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    capH,
                    type,

                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | (touchableMode ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.x = 0;
            lp.y = y;

            if (Build.VERSION.SDK_INT >= 30) {
                try { lp.setFitInsetsTypes(0); } catch (Throwable ignored) { }
            }
            wm.addView(island, lp);
            attached = true;

            Rect cr = cutoutRect(appCtx);
            if (cr != null && cr.width() > 0) {
                int gapW = cr.width() + dp(GAP_SIDE_MARGIN_DP) * 2;
                int[] lobes = lobeWidths(gapW);
                island.enableCameraGap(gapW, lobes[0], lobes[1], lobes[2]);
                alignGapToCutout(cr, gapW, lobes);
                android.util.Log.i("VC_ISLAND", "按挖孔开缝：孔宽 " + cr.width()
                        + "px → 缝宽 " + gapW + "px，瓣宽 桌面" + lobes[0]
                        + "/通话待命" + lobes[1] + "/干活" + lobes[2] + "px");
            } else {

                android.util.Log.i("VC_ISLAND", "没有挖孔几何，灵动岛按整块胶囊显示");
            }
            android.util.Log.i("VC_ISLAND", "系统灵动岛已挂载：胶囊高 " + capH + "px（孔高 "
                    + holeH + "px + 上下各 " + dp(CAPSULE_PAD_Y_DP) + "px），窗口 y=" + y + "px"
                    + "，类型=纯显示浮窗(不吃触摸)");
            return true;
        } catch (Throwable t) {
            android.util.Log.w("VC_ISLAND", "挂载失败: " + t);
            island = null;
            lp = null;
            attached = false;
            return false;
        }
    }

    public void setTouchable(final boolean touchable) {
        final Runnable job = () -> {
            try {

                boolean wantAcc = touchable && AssistantAccessibilityService.instance() != null;
                boolean curAcc = usedType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                touchableMode = touchable;
                if (attached && wantAcc != curAcc) {
                    android.util.Log.i("VC_ISLAND", "窗口类型需切换（可点=" + touchable
                            + "）→ 重挂：当前" + (curAcc ? "无障碍浮窗" : "普通浮窗"));
                    detach();
                    boolean ok = attach();
                    if (ok && island != null) {

                        island.setSticky(stickyWanted || desktopPersistentWanted);
                        if (desktopPersistentWanted) {
                            island.setDesktopPersistent(true);
                        }
                        applyTapActions();
                        island.refreshNow();
                    }
                    return;
                }
                if (!attached || lp == null) return;
                if (touchable) {
                    lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                } else {
                    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                }
                if (wm != null && island != null) wm.updateViewLayout(island, lp);
            } catch (Throwable t) {
                android.util.Log.w("VC_ISLAND", "切换可触摸失败: " + t);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) job.run();
        else ui.post(job);
    }

    private volatile boolean touchableMode = false;

    public void enterSticky() {
        final Runnable job = () -> {
            stickyWanted = true;
            tapActionWanted = null;
            startWatchdog();
            if (!attach()) return;
            try {
                if (island == null) return;
                island.setSticky(true);

                setTouchable(desktopPersistentWanted);
            } catch (Throwable t) {
                android.util.Log.w("VC_ISLAND", "进入通话常驻态失败: " + t);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) job.run();
        else ui.post(job);
    }

    public void exitStandby() {
        stickyWanted = false;
        tapActionWanted = null;

        if (!desktopPersistentWanted) stopWatchdog();
        final Runnable job = () -> {
            try {
                if (island != null) island.setSticky(false);

                setTouchable(desktopPersistentWanted);
            } catch (Throwable ignored) { }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) job.run();
        else ui.post(job);
    }

    public boolean isReady() {
        return attached && island != null;
    }

    private volatile boolean desktopPersistentWanted = false;
    private volatile Runnable doubleTapAction = null;

    /** 连点三下的动作（只暂停屏幕操作员）。存字段：重挂会换掉 View，必须能恢复。 */
    private volatile Runnable tripleTapAction = null;

    /** 双击/三连点动作统一从这里下发：重挂后的每条路径都调它，别各写各的。 */
    private void applyTapActions() {
        if (island == null) return;
        island.setDoubleTapAction(doubleTapAction);
        island.setTripleTapAction(tripleTapAction);
    }

    public boolean isDesktopPersistent() {
        return desktopPersistentWanted;
    }

    public void setDesktopPersistent(final boolean on, final Runnable onDoubleTap) {
        final Runnable job = () -> {
            try {
                desktopPersistentWanted = on;
                doubleTapAction = onDoubleTap;
                tripleTapAction = on ? (SystemIslandService.this::pauseScreenOperator) : null;
                if (on) {
                    if (!attach()) return;
                    if (island == null) return;

                    island.setSticky(true);
                    island.setDesktopPersistent(true);
                    applyTapActions();
                    setTouchable(true);
                    island.refreshNow();
                } else {
                    if (island != null) {
                        applyTapActions();
                        island.setDesktopPersistent(false);

                        if (stickyWanted) {
                            island.setSticky(true);
                        } else {
                            island.setSticky(false);
                        }
                    }
                    setTouchable(false);
                    if (!stickyWanted) stopWatchdog();
                }
            } catch (Throwable t) {
                android.util.Log.w("VC_ISLAND", "桌面常驻切换失败: " + t);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) job.run();
        else ui.post(job);
    }

    /**
     * 连点三下 = 只暂停屏幕操作员（用户 2026-10-16 定）。
     *
     * <p>屏幕操作员抢着用屏幕和输入的时候，用户来不及跟主助手说话去停它，所以给这个手势。
     * 只停带屏幕工具的活儿（accessibility / tap_script），别的子智能体一律不动。
     */
    private void pauseScreenOperator() {
        int n = 0;
        try { n = SubAgentRunner.stopJobs(null, true); } catch (Throwable ignored) { }
        String msg = n > 0 ? "已暂停屏幕操作员" : "现在没有屏幕操作员在跑";
        android.util.Log.i("VC_ISLAND", "三连点：" + msg);
        // 岛上直接显示这句话：走"助手说话"那条已经在用的渲染路径（后台 toast 会被系统吞掉）
        try {
            final BrowserIslandView isl = island;
            if (isl != null) {
                isl.pushSpoken(msg, msg.length());
                for (int i = 1; i <= 4; i++) ui.postDelayed(isl::keepSpokenAlive, i * 500L);
                ui.postDelayed(isl::clearSpoken, 2500L);
            }
        } catch (Throwable ignored) { }
        try {
            android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    private static final long WATCHDOG_MS = 30000L;
    private boolean watchdogOn = false;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!watchdogOn) return;
            try {

                boolean missing = island == null || !island.isAttachedToWindow();
                if (missing) {
                    android.util.Log.w("VC_ISLAND", "看门狗：岛不在屏幕上（island="
                            + (island == null ? "null" : "有") + "）→ 重挂");
                    if (island != null) detach();
                    if (attach() && island != null && stickyWanted) {
                        island.setSticky(true);
                        if (desktopPersistentWanted) {
                            island.setDesktopPersistent(true);
                        }
                        applyTapActions();
                        setTouchable(desktopPersistentWanted);
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("VC_ISLAND", "看门狗重挂失败: " + t);
            }
            ui.postDelayed(this, WATCHDOG_MS);
        }
    };

    private void startWatchdog() {
        if (watchdogOn) return;
        watchdogOn = true;
        ui.postDelayed(watchdog, WATCHDOG_MS);
    }

    private void stopWatchdog() {
        watchdogOn = false;
        ui.removeCallbacks(watchdog);
    }

    public void noteShouldBeVisible() {
        stickyWanted = true;
        startWatchdog();
    }

    public void detach() {
        if (!attached) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(this::detach);
            return;
        }

        islandDetachIsFinal = true;

        try {
            if (island != null) island.setDetachCallback(null);
        } catch (Throwable ignored) { }
        try {
            if (wm != null && island != null) wm.removeView(island);
        } catch (Throwable ignored) { }
        island = null;
        lp = null;
        attached = false;
        islandDetachIsFinal = false;
    }

    public void onHostDestroyed() {
        final Runnable job = this::detach;
        if (Looper.myLooper() == Looper.getMainLooper()) job.run();
        else ui.post(job);
    }

    private int usedType = 0;

    private int overlayType() {
        if (touchableMode) {
            AssistantAccessibilityService acc = AssistantAccessibilityService.instance();
            if (acc != null) {
                usedType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                return usedType;
            }

            android.util.Log.w("VC_ISLAND", "无障碍服务未连接 → 桌面常驻岛只能显示、双击无效。"
                    + "请到 设置→手机感知→无障碍设置 打开本应用的无障碍服务。"
                    + "（ColorOS 会在 App 更新/重装后撤销该授权，需重新打开）");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            usedType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            usedType = WindowManager.LayoutParams.TYPE_PHONE;
        }
        return usedType;
    }

    public boolean canTapInStatusBar() {
        return usedType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    }

    public void onAccessibilityReady() {
        final Runnable job = () -> {
            try {
                if (island == null) return;
                boolean upgraded = usedType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                if (island.isAttachedToWindow() && upgraded) return;
                android.util.Log.i("VC_ISLAND", "借无障碍就绪重挂/升级窗口（当前"
                        + (upgraded ? "无障碍浮窗" : "普通浮窗") + "，期望可点=" + touchableMode + "）");
                detach();
                if (!attach()) return;
                if (island == null) return;

                island.setSticky(stickyWanted || desktopPersistentWanted);
                if (desktopPersistentWanted) {
                    island.setDesktopPersistent(true);
                }
                applyTapActions();
                island.refreshNow();
                if (lp != null) {
                    if (touchableMode) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    try { if (wm != null) wm.updateViewLayout(island, lp); } catch (Throwable ignored) { }
                }
            } catch (Throwable t) {
                android.util.Log.w("VC_ISLAND", "岛重挂失败: " + t);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) job.run();
        else ui.post(job);
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            return android.provider.Settings.canDrawOverlays(appCtx);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int cutoutBottom(Context ctx) {
        int fallback = statusBarHeight(ctx);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    android.view.Display d = wm.getDefaultDisplay();
                    if (d != null) {
                        android.view.DisplayCutout cut = d.getCutout();
                        if (cut != null) {
                            java.util.List<Rect> rects = cut.getBoundingRects();
                            if (rects != null && !rects.isEmpty()) {
                                int bottom = 0;
                                for (Rect r : rects) {
                                    if (r != null && r.bottom > bottom) bottom = r.bottom;
                                }
                                if (bottom > 0) return bottom;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return fallback;
    }

    static int[] cameraHoleRect(Context ctx) {
        try {
            String[] files = {"/my_product/build.prop", "/odm/etc/build.prop",
                    "/vendor/build.prop", "/system/build.prop"};
            for (String path : files) {
                java.io.File f = new java.io.File(path);
                if (!f.canRead()) continue;
                java.io.BufferedReader br = null;
                try {
                    br = new java.io.BufferedReader(new java.io.InputStreamReader(
                            new java.io.FileInputStream(f), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.startsWith("ro.oplus.display.screenhole.positon=")) continue;
                        String v = line.substring(line.indexOf('=') + 1).trim();

                        int colon = v.indexOf(':');
                        if (colon <= 0) continue;
                        String[] a = v.substring(0, colon).split(",");
                        String[] b = v.substring(colon + 1).split(",");
                        if (a.length < 2 || b.length < 2) continue;
                        int left = Integer.parseInt(a[0].trim());
                        int top = Integer.parseInt(a[1].trim());
                        int right = Integer.parseInt(b[0].trim());
                        int bottom = Integer.parseInt(b[1].trim());
                        if (right > left && bottom > top) return new int[]{left, top, right, bottom};
                    }
                } finally {
                    if (br != null) try { br.close(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        Rect cr = cutoutRect(ctx);
        if (cr != null && cr.height() > 0) return new int[]{cr.left, cr.top, cr.right, cr.bottom};
        return null;
    }

    static int cameraHoleCenterY(Context ctx) {
        int[] r = cameraHoleRect(ctx);
        if (r != null) return (r[1] + r[3]) / 2;
        return 0;
    }

    static Rect cutoutRect(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    android.view.Display d = wm.getDefaultDisplay();
                    if (d != null) {
                        android.view.DisplayCutout cut = d.getCutout();
                        if (cut != null) {
                            java.util.List<Rect> rects = cut.getBoundingRects();
                            if (rects != null) {
                                Rect best = null;
                                for (Rect r : rects) {

                                    if (r != null && r.width() > 0 && r.height() > 0) {
                                        if (best == null || (long) r.width() * r.height()
                                                > (long) best.width() * best.height()) {
                                            best = r;
                                        }
                                    }
                                }
                                if (best != null) return best;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static int statusBarHeight(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                int h = ctx.getResources().getDimensionPixelSize(id);
                if (h > 0) return h;
            }
        } catch (Throwable ignored) { }
        return 110;
    }

    private int dp(int v) {
        return Math.round(v * appCtx.getResources().getDisplayMetrics().density);
    }

    private int[] lobeWidths(int gapW) {
        int w = screenWidthPx();
        int centerL = w / 2 - gapW / 2;
        int centerR = w / 2 + gapW / 2;

        int leftLimit = (int) (w * 0.157f);
        int rightLimit = (int) (w * 0.696f);
        int freeL = Math.max(0, centerL - leftLimit) - dp(6);
        int freeR = Math.max(0, rightLimit - centerR) - dp(6);
        int maxLobe = Math.min(freeL, freeR);
        int work = Math.min(dp(WORK_LOBE_DP), Math.max(dp(30), maxLobe));
        int idle = Math.min(dp(IDLE_LOBE_DP), work);
        int narrow = Math.min(dp(NARROW_LOBE_DP), idle);
        return new int[]{narrow, idle, work};
    }

    private void alignGapToCutout(Rect cr, int gapW, int[] lobes) {
        if (lp == null || island == null || wm == null) return;
        try {
            normalX = 0;
            lp.x = 0;
            wm.updateViewLayout(island, lp);
        } catch (Throwable t) {
            android.util.Log.w("VC_ISLAND", "中缝对位失败: " + t);
        }
    }

    private int screenWidthPx() {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            WindowManager wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
                if (dm.widthPixels > 0) return dm.widthPixels;
            }
        } catch (Throwable ignored) { }
        return appCtx.getResources().getDisplayMetrics().widthPixels;
    }
}
