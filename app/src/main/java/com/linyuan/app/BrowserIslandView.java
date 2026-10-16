package com.linyuan.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public class BrowserIslandView extends LinearLayout {

    private static final int ST_IDLE = 0;
    private static final int ST_STATUS = 1;
    private static final int ST_THINK = 2;
    private static final int ST_TOOL = 3;
    private static final int ST_STREAM = 4;
    private static final int ST_DONE = 5;

    private static final long TICK_MS = 80;
    private static final long TICK_FAST_MS = 33;
    private static final long TICK_TEXT_MS = 50;

    private static final long TICK_IDLE_MS = 400;
    private static final long HIDE_AFTER_MS = 9000;
    private static final long DONE_HIDE_MS = 2200;
    private static final long USER_DECAY_MS = 650;
    private static final float USER_ON_LEVEL = 0.07f;
    private static final int TAIL_CHARS = 26;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final TextView label;

    private TextView labelRight;

    private final WaveBarsView bars;

    private WaveBarsView barsRight;
    private final Object lock = new Object();

    private boolean splitMode = false;

    private int narrowLobeW = 0, idleLobeW = 0, workLobeW = 0, splitGapW = 0;

    private int appliedLobeW = 0;

    private int labelLW = 0, labelRW = 0;

    private String spokenText = null;
    private volatile int spokenIdx = 0;

    private boolean spokenActive = false;

    private volatile long lastSpokenAt = 0L;

    private static final long SPOKEN_STALE_MS = 700;

    private int shownEnd = 0;

    private int charsLeft = 12, charsRight = 12;

    private String lastLeft = "", lastRight = "";

    private String lastMarqueeSrc = null;

    private String lastSpokenSrc = null;

    private int lastMarqueeLen = 0;

    private static final int SPOKEN_LEAD_CHARS = 6;

    private final android.widget.FrameLayout leftSlot;
    private final android.widget.FrameLayout rightSlot;
    private final View gapSpacer;
    private CapsuleBg capsuleBg;

    private volatile long lastEventAt = 0L;
    private volatile float level = 0f;
    private volatile long lastLevelAt = 0L;
    private volatile boolean tickerOn = false;
    private volatile boolean dirty = true;

    private int mode = ST_IDLE;
    private String statusText = "";
    private String toolText = "";
    private final StringBuilder streamBuf = new StringBuilder();

    private boolean shown = false;

    private boolean wavesVisibleNow = false;

    private volatile boolean sticky = false;

    private volatile boolean desktopPersistent = false;

    public void setDesktopPersistent(boolean on) {
        desktopPersistent = on;
        ui.post(() -> {
            if (!on && !sticky) {

                hideIsland();
            } else {
                touchEvent();
            }
        });
    }

    private volatile boolean inCall = false;

    public void setInCall(boolean on) {
        inCall = on;
        ui.post(this::touchEvent);
    }

    private volatile boolean aiSpeaking = false;

    public void setAiSpeaking(boolean on) {
        aiSpeaking = on;
        ui.post(this::touchEvent);
    }

    public void keepSpokenAlive() {
        if (spokenActive) lastSpokenAt = System.currentTimeMillis();
    }

    public BrowserIslandView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        capsuleBg = new CapsuleBg(0xF0121216, 0x33FFFFFF, dp(1));
        setBackground(capsuleBg);
        setElevation(dp(6));
        setPadding(dp(13), 0, dp(13), 0);
        setMinimumHeight(dp(34));
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);

        leftSlot = new android.widget.FrameLayout(context);
        LayoutParams llp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        addView(leftSlot, llp);
        bars = new WaveBarsView(context);
        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                dp(30), dp(16));
        blp.gravity = Gravity.CENTER;
        blp.rightMargin = dp(6);
        leftSlot.addView(bars, blp);
        bars.setVisibility(GONE);

        gapSpacer = new View(context);
        LayoutParams glp = new LayoutParams(0, 1);
        glp.weight = 0;
        addView(gapSpacer, glp);
        gapSpacer.setVisibility(GONE);

        rightSlot = new android.widget.FrameLayout(context);
        LayoutParams rlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        addView(rightSlot, rlp);
        label = new TextView(context);
        setLabelLook(label);
        android.widget.FrameLayout.LayoutParams tlp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        rightSlot.addView(label, tlp);
    }

    private void ensureSplitViews() {
        if (labelRight != null) return;
        Context ctx = getContext();

        ((android.view.ViewGroup) label.getParent()).removeView(label);

        label.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        android.widget.FrameLayout.LayoutParams tlp =
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        leftSlot.addView(label, tlp);

        labelRight = new TextView(ctx);
        setLabelLook(labelRight);
        labelRight.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        android.widget.FrameLayout.LayoutParams tlp2 = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp2.gravity = Gravity.CENTER;
        rightSlot.addView(labelRight, tlp2);

        barsRight = new WaveBarsView(ctx);

        barsRight.setPhaseBase(5f);
        android.widget.FrameLayout.LayoutParams bp2 = new android.widget.FrameLayout.LayoutParams(
                dp(30), dp(14));
        bp2.gravity = Gravity.CENTER;
        rightSlot.addView(barsRight, bp2);
        barsRight.setVisibility(GONE);
    }

    private void setLabelLook(TextView tv) {
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(12);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.66f));
    }

    public void setCompactMode(boolean compact) {
        if (splitMode) return;
        ui.post(() -> {
            try {
                android.widget.FrameLayout lbl = (labelSlot != null) ? labelSlot : rightSlot;
                android.widget.FrameLayout spr = (spareSlot != null) ? spareSlot : leftSlot;
                lbl.setVisibility(VISIBLE);
                spr.setVisibility(compact ? GONE : VISIBLE);
                if (compact) {
                    gapSpacer.setVisibility(GONE);
                } else if (savedGapW > 0) {
                    gapSpacer.setVisibility(VISIBLE);
                }

                android.widget.FrameLayout.LayoutParams tlp =
                        (android.widget.FrameLayout.LayoutParams) label.getLayoutParams();
                tlp.width = compact ? android.widget.FrameLayout.LayoutParams.WRAP_CONTENT : labelFixedW;
                label.setLayoutParams(tlp);
                setPadding(compact ? dp(13) : 0, 0, compact ? dp(13) : 0, 0);
                capsuleBg.setGap(compact ? 0 : savedGapW, savedGapCenter);
                requestLayout();
            } catch (Throwable ignored) { }
        });
    }

    private int savedGapW = 0;
    private int savedGapCenter = 0;
    private int labelFixedW = 0;

    private android.widget.FrameLayout labelSlot;
    private android.widget.FrameLayout spareSlot;

    public void enableCameraGap(int gapWidthPx, int narrowLobePx, int idleLobePx, int workLobePx) {
        if (gapWidthPx <= 0 || idleLobePx <= 0) return;
        splitMode = true;
        splitGapW = gapWidthPx;
        idleLobeW = idleLobePx;
        narrowLobeW = narrowLobePx > 0 ? Math.min(narrowLobePx, idleLobePx) : idleLobePx;
        workLobeW = Math.max(idleLobePx, workLobePx);
        ensureSplitViews();

        LayoutParams glp = (LayoutParams) gapSpacer.getLayoutParams();
        glp.width = gapWidthPx;
        glp.height = 1;
        gapSpacer.setLayoutParams(glp);
        gapSpacer.setVisibility(VISIBLE);

        int labelW = Math.max(dp(16), workLobeW);
        labelLW = labelW;
        labelRW = labelW;
        android.widget.FrameLayout.LayoutParams tlp =
                (android.widget.FrameLayout.LayoutParams) label.getLayoutParams();
        tlp.width = labelLW;
        tlp.gravity = Gravity.CENTER;
        label.setLayoutParams(tlp);
        label.setMaxWidth(Integer.MAX_VALUE);
        android.widget.FrameLayout.LayoutParams tlp2 =
                (android.widget.FrameLayout.LayoutParams) labelRight.getLayoutParams();
        tlp2.width = labelRW;
        tlp2.gravity = Gravity.CENTER;
        labelRight.setLayoutParams(tlp2);
        labelRight.setMaxWidth(Integer.MAX_VALUE);

        labelFixedW = 0;
        savedGapW = gapWidthPx;
        savedGapCenter = idleLobeW + gapWidthPx / 2;
        capsuleBg.setGap(gapWidthPx, savedGapCenter);

        applyLobeWidth(idleLobeW);

        charsLeft = Math.max(2, (int) (labelLW / measureHanPx()));
        charsRight = charsLeft;

        setPadding(0, 0, 0, 0);
        android.util.Log.i("VC_ISLAND", "灵动岛已开中缝：让位 " + gapWidthPx
                + "px，瓣宽 桌面待命" + narrowLobeW + "/通话待命" + idleLobeW
                + "/干活" + workLobeW + "px");
    }

    private void applyLobeWidth(int lobe) {
        if (lobe <= 0 || lobe == appliedLobeW) return;
        appliedLobeW = lobe;
        LayoutParams llp = (LayoutParams) leftSlot.getLayoutParams();
        llp.width = lobe;
        leftSlot.setLayoutParams(llp);
        LayoutParams rlp = (LayoutParams) rightSlot.getLayoutParams();
        rlp.width = lobe;
        rightSlot.setLayoutParams(rlp);

        int bw = Math.min(dp(56), Math.max(dp(20), lobe * 2 / 3));
        android.widget.FrameLayout.LayoutParams blp =
                (android.widget.FrameLayout.LayoutParams) bars.getLayoutParams();
        blp.width = bw;
        blp.height = dp(14);
        blp.rightMargin = 0;
        blp.gravity = Gravity.CENTER;
        bars.setLayoutParams(blp);
        if (barsRight != null) {
            android.widget.FrameLayout.LayoutParams bp =
                    (android.widget.FrameLayout.LayoutParams) barsRight.getLayoutParams();
            bp.width = bw;
            bp.height = dp(14);
            bp.gravity = Gravity.CENTER;
            barsRight.setLayoutParams(bp);
        }
        requestLayout();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float hanPx = 0f;
    private float measureHanPx() {
        if (hanPx > 0) return hanPx;
        try {
            hanPx = label.getPaint().measureText("汉");
        } catch (Throwable ignored) { }
        if (hanPx <= 0) hanPx = getResources().getDisplayMetrics().scaledDensity * 12f;
        return hanPx;
    }

    public void pushReset() {
        synchronized (lock) {
            mode = ST_IDLE;
            statusText = "";
            toolText = "";
            streamBuf.setLength(0);
            spokenActive = false;
        }
        shownEnd = 0;
        lastLeft = "";
        lastRight = "";
        marqueeInit = false;
        marqueeDone = false;
        marqueeFrac = 0f;
        lastMarqueeSrc = null;
        lastSpokenSrc = null;
        lastMarqueeLen = 0;
        touchEvent();
    }

    public void pushStatus(String s) {
        synchronized (lock) {
            mode = ST_STATUS;
            statusText = s == null ? "" : s;
        }
        unlockMarquee();
        touchEvent();
    }

    public void pushThink() {
        synchronized (lock) {
            mode = ST_THINK;
        }
        unlockMarquee();
        touchEvent();
    }

    public void pushTool(String name, String argSummary) {
        synchronized (lock) {
            mode = ST_TOOL;
            String a = argSummary == null ? "" : argSummary.replace("\n", " ").trim();
            if (a.length() > 30) a = a.substring(0, 30) + "…";
            toolText = (name == null ? "" : name) + (a.isEmpty() ? "" : "  " + a);
        }
        unlockMarquee();
        touchEvent();
    }

    public void pushToolDone(String name) {
        synchronized (lock) {
            mode = ST_TOOL;
            toolText = (name == null ? "" : name) + " 已返回";
        }
        unlockMarquee();
        touchEvent();
    }

    public void pushStream(String delta) {
        if (delta == null || delta.isEmpty()) return;
        synchronized (lock) {
            mode = ST_STREAM;
            streamBuf.append(delta);

            if (streamBuf.length() > 400) streamBuf.delete(0, streamBuf.length() - 400);
        }
        unlockMarquee();
        touchEvent();
    }

    public void setStream(String full) {
        synchronized (lock) {
            mode = ST_STREAM;
            streamBuf.setLength(0);
            if (full != null) streamBuf.append(full);
        }
        unlockMarquee();
        touchEvent();
    }

    public void pushDone() {
        synchronized (lock) {
            mode = ST_DONE;
        }
        unlockMarquee();
        touchEvent();
    }

    public void pushSpoken(String full, int idx) {
        if (full == null || full.isEmpty()) return;
        synchronized (lock) {
            mode = ST_STREAM;
            spokenText = full;
            spokenActive = true;
            spokenIdx = Math.max(0, Math.min(full.length(), idx));
        }

        unlockMarquee();
        lastSpokenAt = System.currentTimeMillis();
        touchEvent();
    }

    public void clearSpoken() {
        synchronized (lock) {
            spokenActive = false;
            spokenText = null;
        }
        touchEvent();
    }

    public void pushLevel(float lv) {
        level = Math.max(0f, Math.min(1f, lv));
        lastLevelAt = System.currentTimeMillis();
        ensureTicker();
    }

    public void setSticky(boolean on) {
        sticky = on;
        ui.post(() -> {
            if (!on) return;
            dirty = true;
            ensureTicker();
        });
    }

    public void setDoubleTapAction(Runnable r) {
        ui.post(() -> {
            doubleTapAction = r;
            tappable = (r != null);
            setClickable(tappable);
            setFocusable(tappable);
        });
    }

    private volatile Runnable doubleTapAction = null;
    private volatile boolean tappable = false;
    private long lastTapDownAt = 0L;

    private static final long DOUBLE_TAP_MS = 400L;

    @Deprecated
    public void setTapAction(Runnable r) {
        ui.post(() -> setClickable(false));
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {

        if (!tappable) return false;
        int action = ev.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();
            if (now - lastTapDownAt <= DOUBLE_TAP_MS) {
                lastTapDownAt = 0L;
                Runnable r = doubleTapAction;
                if (r != null) {
                    try { r.run(); } catch (Throwable t) {
                        android.util.Log.w("VC_ISLAND", "双击动作失败: " + t);
                    }
                }
            } else {
                lastTapDownAt = now;
            }
            return true;
        }

        return action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_MOVE
                || action == android.view.MotionEvent.ACTION_CANCEL;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        Runnable r = detachCallback;
        if (r != null) r.run();
    }

    private volatile Runnable detachCallback = null;
    public void setDetachCallback(Runnable r) { this.detachCallback = r; }

    public void refreshNow() {
        dirty = true;
        ensureTicker();
    }

    private void touchEvent() {
        lastEventAt = System.currentTimeMillis();
        dirty = true;
        ensureTicker();
    }

    private void ensureTicker() {
        ui.post(kick);
    }

    private final Runnable kick = new Runnable() {
        @Override
        public void run() {
            if (tickerOn) return;
            tickerOn = true;
            tick();
        }
    };

    private final Runnable cont = new Runnable() {
        @Override
        public void run() {
            if (!tickerOn) return;
            tick();
        }
    };

    private boolean tick() {
        long now = System.currentTimeMillis();

        boolean userSpeaking = !aiSpeaking
                && (now - lastLevelAt) < USER_DECAY_MS && level >= USER_ON_LEVEL;

        int curMode;
        String curText;
        boolean spoken;
        synchronized (lock) {
            curMode = mode;

            if (spokenActive && System.currentTimeMillis() - lastSpokenAt > SPOKEN_STALE_MS) {
                spokenActive = false;
                mode = ST_DONE;
                lastEventAt = System.currentTimeMillis();
            }
            spoken = spokenActive && spokenText != null && !spokenText.isEmpty();
            if (spoken) {

                curText = spokenText;
            } else {
                curText = renderText(curMode);

                if (curMode == ST_STREAM) curText = streamBuf.toString();
            }
        }

        boolean hasContent = curMode != ST_IDLE
                && !(curMode == ST_STATUS && (statusText == null || statusText.trim().isEmpty()));

        if (!userSpeaking) {
            long idleFor = now - lastEventAt;
            if (sticky || desktopPersistent) {

                if (curMode == ST_DONE && idleFor > DONE_HIDE_MS) resetToIdle();
            } else {
                long hideAfter = curMode == ST_DONE ? DONE_HIDE_MS : HIDE_AFTER_MS;
                if (curMode == ST_IDLE || idleFor > hideAfter) {
                    hideIsland();
                    return false;
                }
            }
        }

        showIsland();

        if (splitMode) {

            if (userSpeaking) {

                applyLobeWidth(workLobeW);
                renderSplitWaves(true);
            } else if (hasContent && !marqueeDone) {

                applyLobeWidth(workLobeW);
                String text = curText;
                if (curMode == ST_THINK) text = "💭 正在思考" + dots(now, 3);
                renderSplitText(text, spoken);
            } else if (marqueeDone && curMode == ST_STREAM && !spoken) {

                applyLobeWidth(inCall ? idleLobeW : narrowLobeW);
                renderSplitIdleBar();
            } else {

                if (inCall) {
                    applyLobeWidth(idleLobeW);
                    renderSplitWaves(false);
                } else {
                    applyLobeWidth(narrowLobeW);
                    renderSplitIdleBar();
                }
            }
        } else {

            if (userSpeaking) {
                if (bars.getVisibility() != VISIBLE) {
                    bars.setVisibility(VISIBLE);
                    label.setVisibility(GONE);
                }
                bars.setLevel(level);
            } else {
                if (bars.getVisibility() == VISIBLE) {
                    bars.setVisibility(GONE);
                    label.setVisibility(VISIBLE);
                    dirty = true;
                }
                if (curMode == ST_THINK) {
                    label.setText("💭 正在思考" + dots(now, 3));
                } else if (dirty) {
                    label.setText(tail(curText, TAIL_CHARS));
                }
            }
        }
        dirty = false;

        long delay = TICK_MS;
        if (splitMode ? wavesVisibleNow : (bars.getVisibility() == VISIBLE)) {
            delay = TICK_FAST_MS;
        } else if (splitMode && idleStaticNow) {

            delay = TICK_IDLE_MS;
        } else if (curMode == ST_STREAM || curMode == ST_THINK || spoken) {
            delay = TICK_TEXT_MS;
        }
        ui.postDelayed(cont, delay);
        return true;
    }

    private void renderSplitWaves(boolean userSpeaking) {
        wavesVisibleNow = true;
        idleStaticNow = false;
        if (bars.getVisibility() != VISIBLE) {
            bars.setVisibility(VISIBLE);
            label.setVisibility(GONE);
        }
        if (labelRight != null && labelRight.getVisibility() == VISIBLE) {
            labelRight.setVisibility(GONE);
        }
        if (barsRight != null && barsRight.getVisibility() != VISIBLE) {
            barsRight.setVisibility(VISIBLE);
        }
        bars.setLevel(userSpeaking ? level : 0f);
        if (barsRight != null) barsRight.setLevel(userSpeaking ? level : 0f);
    }

    private boolean idleStaticNow = false;

    private void renderSplitIdleBar() {
        wavesVisibleNow = false;
        idleStaticNow = true;

        if (bars.getVisibility() == VISIBLE) bars.setVisibility(GONE);
        if (barsRight != null && barsRight.getVisibility() == VISIBLE) {
            barsRight.setVisibility(GONE);
        }
        if (label.getVisibility() == VISIBLE) label.setVisibility(GONE);
        if (labelRight != null && labelRight.getVisibility() == VISIBLE) {
            labelRight.setVisibility(GONE);
        }
    }

    private void renderSplitText(String full, boolean spoken) {
        wavesVisibleNow = false;

        idleStaticNow = false;
        if (label.getVisibility() != VISIBLE) {
            label.setVisibility(VISIBLE);
            if (labelRight != null) labelRight.setVisibility(VISIBLE);
            bars.setVisibility(GONE);
            if (barsRight != null) barsRight.setVisibility(GONE);
        }
        if (full == null) full = "";
        int len = full.length();
        int totalChars = Math.max(4, charsLeft + charsRight);

        int hardMax = len + totalChars;
        if (shownEnd > hardMax) shownEnd = hardMax;
        if (shownEnd < 0) shownEnd = 0;

        boolean spokenChanged = spoken
                && (lastSpokenSrc == null || !full.equals(lastSpokenSrc));
        if (spokenChanged) lastSpokenSrc = full;
        else if (!spoken) lastSpokenSrc = null;
        if (spokenChanged
                || lastMarqueeSrc == null
                || (!full.startsWith(lastMarqueeSrc) && !lastMarqueeSrc.startsWith(full))) {
            shownEnd = 0;
            marqueeInit = false;
            marqueeFrac = 0f;
            marqueeDone = false;
        }
        lastMarqueeSrc = full;

        boolean growing = len > lastMarqueeLen;
        lastMarqueeLen = len;
        advanceMarquee(len, totalChars, spoken, growing);
        int start = Math.max(0, shownEnd - totalChars);

        if (start > 0 && start < len
                && Character.isLowSurrogate(full.charAt(start))
                && Character.isHighSurrogate(full.charAt(start - 1))) {
            start++;
        }
        int end = Math.min(len, Math.max(start, shownEnd));
        String body = full.substring(start, end);

        int blankBefore = Math.max(0, totalChars - shownEnd);
        int blankAfter = Math.max(0, shownEnd - len);
        String win;
        if (blankBefore == 0 && blankAfter == 0) {
            win = body;
        } else {

            StringBuilder b = new StringBuilder(totalChars + 2);
            for (int i = 0; i < blankBefore; i++) b.append('\u3000');
            b.append(body);
            for (int i = 0; i < blankAfter; i++) b.append('\u3000');
            win = b.toString();
        }
        int cut = Math.max(0, win.length() - charsRight);
        cut = safeCut(win, cut);
        String L = win.substring(0, cut);
        String R = win.substring(cut);
        if (!L.equals(lastLeft)) {
            label.setText(L);
            lastLeft = L;
        }
        if (labelRight != null && !R.equals(lastRight)) {
            labelRight.setText(R);
            lastRight = R;
        }
    }

    private static int safeCut(String s, int cut) {
        if (cut <= 0 || cut >= s.length()) return cut;
        if (Character.isHighSurrogate(s.charAt(cut - 1)) && Character.isLowSurrogate(s.charAt(cut))) {
            return cut - 1;
        }
        return cut;
    }

    private static final float SCROLL_CPS_CALM = 5.5f;
    private static final float SCROLL_CPS_FAST = 11.0f;
    private static final float SCROLL_CPS_CATCHUP = 30f;

    private static final int MARQUEE_LAG_FAST = 12;
    private static final int MARQUEE_LAG_CATCHUP = 34;

    private long marqueeAt = 0L;

    private float marqueeFrac = 0f;

    private boolean marqueeInit = false;

    private void advanceMarquee(int len, int totalChars, boolean spoken, boolean growing) {
        long now = System.currentTimeMillis();

        if (!marqueeInit) {
            marqueeInit = true;
            marqueeAt = now;
            marqueeFrac = 0f;
            return;
        }
        long dt = now - marqueeAt;
        marqueeAt = now;

        if (dt > 200L) dt = 200L;
        if (dt <= 0L) return;

        int target = spoken
                ? Math.min(len, spokenIdx + SPOKEN_LEAD_CHARS)
                : len + totalChars;
        int lag = target - shownEnd;

        if (growing && lag > MARQUEE_LAG_CATCHUP) {
            shownEnd = Math.max(totalChars, target - totalChars);
            marqueeFrac = 0f;
            return;
        }

        if (lag <= 0) {
            marqueeFrac = 0f;
            if (!spoken && shownEnd >= len + totalChars) marqueeDone = true;
            return;
        }

        float cps = SCROLL_CPS_CALM;
        if (len > 60) cps = SCROLL_CPS_FAST;
        if (growing && lag > MARQUEE_LAG_FAST) cps = Math.max(cps, SCROLL_CPS_FAST);
        if (growing && lag > MARQUEE_LAG_FAST * 3) cps = SCROLL_CPS_CATCHUP;
        float step = dt / 1000f * cps + marqueeFrac;
        int whole = (int) step;
        marqueeFrac = step - whole;
        if (whole <= 0) return;
        shownEnd = Math.min(target, shownEnd + whole);
    }

    private volatile boolean marqueeDone = false;

    public boolean isMarqueeDone() {
        return marqueeDone;
    }

    private void unlockMarquee() {
        marqueeDone = false;
    }

    private void resetToIdle() {
        synchronized (lock) {
            mode = ST_IDLE;
            statusText = "";
            toolText = "";
            streamBuf.setLength(0);
            spokenActive = false;
        }
        shownEnd = 0;
        lastLeft = "";
        lastRight = "";
        marqueeInit = false;
        marqueeDone = false;
        marqueeFrac = 0f;
        lastMarqueeSrc = null;
        lastSpokenSrc = null;
        lastMarqueeLen = 0;
    }

    private void stopTicker() {
        tickerOn = false;
    }

    private String renderText(int m) {
        switch (m) {

            case ST_STATUS: return statusText == null ? "" : statusText;
            case ST_THINK:  return "💭 正在思考";
            case ST_TOOL:   return "🔧 " + toolText;
            case ST_STREAM: return tail(streamBuf.toString(), TAIL_CHARS);
            case ST_DONE:   return "✅ 完成";
            default:        return "";
        }
    }

    private void showIsland() {
        if (shown && getVisibility() == VISIBLE) return;
        shown = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(140).start();
    }

    private void hideIsland() {
        stopTicker();
        shown = false;
        animate().alpha(0f).setDuration(180).withEndAction(() -> {
            setVisibility(GONE);
            label.setText("");
            lastLeft = "";
            if (labelRight != null) labelRight.setText("");
            lastRight = "";
            bars.setVisibility(GONE);
            if (barsRight != null) barsRight.setVisibility(GONE);
        }).start();
        synchronized (lock) {
            mode = ST_IDLE;
            toolText = "";
            streamBuf.setLength(0);
            spokenActive = false;
        }
    }

    private static String dots(long now, int max) {
        int n = (int) ((now / 400) % (max + 1));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append("·");
        return b.toString();
    }

    private static String tail(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        s = s.replace("\n", " ").trim();
        if (s.length() <= max) return s;
        int start = s.length() - max;

        if (start > 0 && start < s.length()
                && Character.isLowSurrogate(s.charAt(start))
                && Character.isHighSurrogate(s.charAt(start - 1))) {
            start--;
        }
        return "…" + s.substring(start);
    }

    private static class CapsuleBg extends android.graphics.drawable.Drawable {
        private final int fillColor;
        private final int strokeColor;
        private final int strokeWidth;
        private final android.graphics.Paint fill = new android.graphics.Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint stroke = new android.graphics.Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path outer = new android.graphics.Path();
        private final android.graphics.RectF rect = new android.graphics.RectF();

        private int gapWidth = 0;

        CapsuleBg(int fillColor, int strokeColor, int strokeWidth) {
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
            this.strokeWidth = strokeWidth;
            fill.setColor(fillColor);
            fill.setStyle(android.graphics.Paint.Style.FILL);
            stroke.setColor(strokeColor);
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeWidth(strokeWidth);
        }

        void setGap(int w, int centerInView) {
            gapWidth = Math.max(0, w);
            invalidateSelf();
        }

        @Override
        public void draw(android.graphics.Canvas cv) {
            float r = getBounds().height() / 2f;
            rect.set(getBounds());
            float inset = strokeWidth / 2f;
            rect.inset(inset, inset);

            outer.reset();
            outer.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW);
            cv.drawPath(outer, fill);
            if (strokeWidth > 0 && strokeColor != 0) cv.drawPath(outer, stroke);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); stroke.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            fill.setColorFilter(cf); stroke.setColorFilter(cf);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static class WaveBarsView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float level = 0f;

        private float phaseBase = 0f;

        WaveBarsView(Context c) {
            super(c);
            p.setColor(0xFFFFFFFF);
        }

        void setLevel(float v) {
            level = v;
            invalidate();
        }

        void setPhaseBase(float base) {
            phaseBase = base;
        }

        @Override
        protected void onDraw(Canvas cv) {
            int n = 5;
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float bw = w / (n * 2f - 1f);
            long now = System.currentTimeMillis();
            for (int i = 0; i < n; i++) {
                float phase = (float) Math.sin(now / 130.0 + (phaseBase + i) * 1.25);

                float amp = 0.3f + 0.7f * Math.min(1f, level * 2.2f);
                float f = 0.32f + 0.68f * amp * (0.6f + 0.4f * phase);
                float bh = Math.max(bw, Math.min(h, h * f));
                float x = i * bw * 2f;
                p.setAlpha((int) (150 + 105 * Math.min(1f, level * 3f)));
                cv.drawRoundRect(x, (h - bh) / 2f, x + bw, (h + bh) / 2f, bw / 2f, bw / 2f, p);
            }
        }
    }
}
