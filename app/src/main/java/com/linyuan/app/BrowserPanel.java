package com.linyuan.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class BrowserPanel {

    private static final String TAG = "VC_BROWSER";

    public static void dlogStatic(android.content.Context ctx, String msg) {
        try { android.util.Log.i(TAG, msg); } catch (Throwable ignored) { }
    }

    private void dlog(String msg) {
        dlogStatic(activity, msg);
    }

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout panel;
    private WebView web;
    private ProgressBar progress;
    private TextView statusText;

    private BrowserIslandView island;
    private volatile boolean visible = false;

    private volatile boolean pageLoading = false;

    private volatile String lastTitle = "";
    private volatile String lastUrl = "";

    private boolean barWasVisible = false;

    private static final long PACE_MIN_MS = 2200;
    private static final long PACE_JITTER_MS = 1800;

    private long lastActionAt = 0L;

    private long paceGate() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            dlog("paceGate skipped: 在主线程上不等待（避免 ANR）");
            return 0L;
        }
        long wait;
        synchronized (this) {
            long want = PACE_MIN_MS + (long) (Math.random() * PACE_JITTER_MS);
            long since = System.currentTimeMillis() - lastActionAt;
            wait = lastActionAt == 0L ? 0L : Math.max(0L, want - since);

            lastActionAt = System.currentTimeMillis() + wait;
        }
        if (wait > 0) {
            dlog("paceGate: 等 " + wait + "ms（人类节奏，防反爬）");
            try { Thread.sleep(wait); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        return wait;
    }

    public BrowserPanel(Activity activity) {
        this.activity = activity;
    }

    private int color(int resId) {
        try {
            return activity.getResources().getColor(resId, activity.getTheme());
        } catch (Throwable t) {
            return 0xFF888888;
        }
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    public boolean isVisible() {
        return visible;
    }

    public BrowserIslandView island() {
        return island;
    }

    public boolean hasPage() {
        return web != null && (!lastUrl.isEmpty() || !lastTitle.isEmpty());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensurePanel() {
        if (panel != null) {
            dlog("ensurePanel: reuse panel, parent=" + panel.getParent()
                    + " alpha=" + panel.getAlpha());
            return;
        }
        dlog("ensurePanel: creating, act=" + System.identityHashCode(activity)
                + " panelObj=" + System.identityHashCode(this));

        panel = new PassthroughFrame(activity);

        panel.setFitsSystemWindows(true);
        panel.setClickable(true);
        panel.setFocusable(true);

        web = new WebView(activity);

        web.setBackgroundColor(0xFFFFFFFF);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(true);

        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setUserAgentString(ws.getUserAgentString());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String u = (req == null || req.getUrl() == null) ? "" : req.getUrl().toString();

                if (!u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
                    dlog("block non-http scheme: " + (u.length() > 120 ? u.substring(0, 120) : u));
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (progress != null) {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(5);
                }
                pageLoading = true;

                ui.post(() -> {
                    if (statusText != null) {
                        statusText.setText("正在加载 " + url + " …");
                        statusText.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, android.webkit.WebResourceError err) {

                if (req != null && req.isForMainFrame()) {
                    final String d = err == null ? "" : String.valueOf(err.getDescription());
                    pageLoading = false;
                    try {
                        NavWaiter w = navWaiter;
                        if (w != null) w.onDone(req.getUrl() == null ? "" : req.getUrl().toString(),
                                "页面打不开：" + d);
                    } catch (Throwable ignored) { }
                    ui.post(() -> {
                        if (statusText != null) {
                            statusText.setText("打不开这个网页（" + d + "）。");
                            statusText.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                try {
                    NavWaiter w = navWaiter;
                    if (w != null) w.onDone(url, "");
                } catch (Throwable ignored) { }
                dlog("onPageFinished url=" + url
                        + " panelAlpha=" + (panel == null ? -1 : panel.getAlpha())
                        + " webSize=" + v.getWidth() + "x" + v.getHeight());
                if (progress != null) progress.setVisibility(View.GONE);
                pageLoading = false;

                ui.post(() -> {
                    try {
                        if (web != null) {
                            web.requestLayout();
                            web.invalidate();
                        }
                    } catch (Throwable ignored) { }
                });
                lastUrl = url == null ? "" : url;
                try {
                    String t = v.getTitle();
                    lastTitle = t == null ? "" : t;
                } catch (Throwable ignored) { }
                ui.post(() -> {
                    if (statusText != null) statusText.setVisibility(View.GONE);
                });
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int newProgress) {
                if (progress == null) return;
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progress.setProgress(newProgress);
            }
        });
        panel.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        plp.gravity = Gravity.TOP;
        panel.addView(progress, plp);

        statusText = new TextView(activity);
        statusText.setTextSize(11.5f);
        statusText.setTextColor(color(R.color.text));
        statusText.setBackgroundColor(0xE6FFFFFF);
        statusText.setPadding(dp(14), dp(7), dp(14), dp(7));
        statusText.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.TOP;
        slp.topMargin = dp(3);
        panel.addView(statusText, slp);

        island = new BrowserIslandView(activity);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        ilp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        ilp.topMargin = dp(10);
        panel.addView(island, ilp);
        island.setVisibility(View.GONE);

        Button back = new Button(activity);
        back.setText("‹");
        back.setTextSize(20f);
        back.setAllCaps(false);
        back.setBackgroundResource(R.drawable.btn_secondary);
        back.setTextColor(color(R.color.text));
        back.setMinWidth(0);
        back.setMinimumWidth(0);
        back.setAlpha(0.72f);
        back.setPadding(0, 0, 0, dp(2));
        back.setOnClickListener(v -> goBackOrClose());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(42), dp(42));
        blp.gravity = Gravity.BOTTOM | Gravity.START;
        blp.setMargins(dp(10), 0, 0, dp(14));
        panel.addView(back, blp);

        ViewGroup rootContent = (ViewGroup) activity.findViewById(android.R.id.content);

        rootContent.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setAlpha(0f);
        panel.setVisibility(View.VISIBLE);
        setTouchable(false);
        dlog("ensurePanel done: idx=" + rootContent.indexOfChild(panel)
                + " sib=" + rootContent.getChildCount() + " parentOk=" + (panel.getParent() == rootContent));
    }

    public void openUrl(final String url) {
        openUrl(url, true);
    }

    public String openUrlAndWait(final String url, long timeoutMs) {
        if (TextUtils.isEmpty(url)) return "{\"ok\":false,\"error\":\"url is empty\"}";
        final String u = normalizeUrl(url);

        long paced = paceGate();

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] got = {"", ""};
        navWaiter = new NavWaiter() {
            @Override public void onDone(String finalUrl, String err) {
                got[0] = finalUrl == null ? "" : finalUrl;
                got[1] = err == null ? "" : err;
                latch.countDown();
            }
        };
        openUrl(u, true);
        boolean finished;
        try {
            finished = latch.await(Math.max(1000, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        navWaiter = null;
        String pacedNote = paced > 0 ? " (waited " + (paced / 100) / 10.0 + "s between actions to look human)" : "";
        if (finished && (got[1] == null || got[1].isEmpty())) {
            String t = lastTitle == null ? "" : lastTitle;
            return "{\"ok\":true,\"url\":" + jsonStr(got[0]) + ",\"title\":" + jsonStr(t)
                    + ",\"note\":" + jsonStr("Opened." + pacedNote) + "}";
        }
        if (!finished) {

            return "{\"ok\":false,\"pending\":true,\"url\":" + jsonStr(u)
                    + ",\"error\":\"Still not loaded after " + (timeoutMs / 1000) + "s (slow network or unresponsive site). "
                    + "Use browser_read to check the outcome.\"}";
        }
        return "{\"ok\":false,\"url\":" + jsonStr(got[0]) + ",\"error\":" + jsonStr(got[1]) + "}";
    }

    private static String jsonStr(String v) {
        if (v == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private interface NavWaiter {
        void onDone(String finalUrl, String error);
    }
    private volatile NavWaiter navWaiter;

    public void openUrl(final String url, final boolean show) {
        if (TextUtils.isEmpty(url)) return;
        dlog("openUrl(" + url + ", show=" + show
                + ") act=" + System.identityHashCode(activity)
                + " panelObj=" + System.identityHashCode(this)
                + " panelView=" + (panel == null ? "null" : System.identityHashCode(panel)));

        ui.post(() -> {
            ensurePanel();
            final String u = normalizeUrl(url);

            if (statusText != null) {
                statusText.setText("正在打开 " + u + " …");
                statusText.setVisibility(View.VISIBLE);
            }
            if (show) raisePanel();
            if (web == null) {
                return;
            }

            if (web.getWidth() == 0 || web.getHeight() == 0) {
                try {
                    panel.requestLayout();
                    web.requestLayout();
                } catch (Throwable ignored) { }
            }
            try {
                web.loadUrl(u);
            } catch (Throwable t) {
                Toast.makeText(activity, "打开失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void search(final String query) {
        if (TextUtils.isEmpty(query)) return;
        String engine = "https://www.bing.com/search?q=";
        try {
            String q = java.net.URLEncoder.encode(query.trim(), "UTF-8");
            openUrl(engine + q);
        } catch (Throwable t) {
            openUrl(engine + query.trim());
        }
    }

    public void openSmart(String input, boolean show) {
        if (input == null) return;
        String t = input.trim();
        if (t.isEmpty()) return;
        boolean looksUrl = t.matches("(?i)^(https?://).*")
                || t.matches("^[\\w-]+(\\.[\\w-]+)+(/.*)?$")
                || t.matches("^localhost(:\\d+)?(/.*)?$");
        if (looksUrl) openUrl(t, show);
        else {
            if (!show) { openUrl("https://www.bing.com/search?q=" + enc(t), false); return; }
            search(t);
        }
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Throwable t) { return s; }
    }

    private static String normalizeUrl(String url) {
        String u = url.trim();
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) u = "https://" + u;
        return u;
    }

    public void showPanel() {
        ui.post(this::raisePanel);
    }

    public void raisePanel() {
        ensurePanel();
        dlog("raisePanel: thread=" + Thread.currentThread().getName()
                + " parent=" + panel.getParent());
        View bar = activity.findViewById(R.id.settingsBackBar);

        if (!visible && bar != null) {
            barWasVisible = bar.getVisibility() == View.VISIBLE;
        }
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(1f);
        setTouchable(true);
        visible = true;

        if (island != null) {
            island.requestLayout();
            island.refreshNow();
        }
        dlog("raisePanel done: alpha=" + panel.getAlpha()
                + " vis=" + panel.getVisibility()
                + " webSize=" + (web == null ? "null" : web.getWidth() + "x" + web.getHeight())
                + " parent=" + panel.getParent());
        bringPanelToFront("raise+0ms");

        ui.postDelayed(() -> bringPanelToFront("raise+600ms"), 600);

        if (statusText != null && !hasPage()) {
            statusText.setText("还没有打开网页 —— 跟我说「搜一下 xxx」或「打开 xxx.com」就行。");
            statusText.setVisibility(View.VISIBLE);
        }
        try {
            if (web != null) {
                web.onResume();
                web.resumeTimers();
                web.requestLayout();
                web.invalidate();
            }
        } catch (Throwable ignored) { }

        if (bar != null) bar.setVisibility(View.GONE);
    }

    public void close() {
        ui.post(this::lowerPanel);
    }

    private void lowerPanel() {
        dlog("lowerPanel: stack=" + android.util.Log.getStackTraceString(new Throwable()));
        if (panel != null) {

            panel.setAlpha(0f);
            setTouchable(false);
        }
        visible = false;

        View bar = activity.findViewById(R.id.settingsBackBar);
        if (bar != null) bar.setVisibility(barWasVisible ? View.VISIBLE : View.GONE);
    }

    private void setTouchable(boolean on) {

        if (panel instanceof PassthroughFrame) ((PassthroughFrame) panel).setInterceptTouch(on);
        try {
            panel.setClickable(on);
            panel.setFocusable(on);
        } catch (Throwable ignored) { }
    }

    private void bringPanelToFront(String why) {
        if (panel == null) return;
        try {
            ViewGroup p = (ViewGroup) panel.getParent();
            if (p == null) { dlog("bringToFront(" + why + "): parent=null"); return; }
            int idx = p.indexOfChild(panel);
            int last = p.getChildCount() - 1;
            if (idx != last) {
                p.bringChildToFront(panel);
                dlog("bringToFront(" + why + "): " + idx + " -> " + p.indexOfChild(panel) + " (last=" + last + ")");
            } else {
                dlog("bringToFront(" + why + "): already top idx=" + idx);
            }
        } catch (Throwable t) {
            dlog("bringToFront(" + why + ") fail: " + t);
        }
    }

    private static class PassthroughFrame extends FrameLayout {
        private boolean interceptTouch = true;
        PassthroughFrame(android.content.Context c) { super(c); }
        void setInterceptTouch(boolean on) { interceptTouch = on; }
        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
            if (!interceptTouch) return false;
            return super.dispatchTouchEvent(ev);
        }
    }

    private void goBackOrClose() {
        if (web != null && web.canGoBack()) web.goBack();
        else close();
    }

    public boolean handleBack() {
        if (!visible) return false;

        ui.post(() -> {
            if (web != null && web.canGoBack()) web.goBack();
            else close();
        });
        return true;
    }

    public boolean goBackInPage() {
        final boolean[] ok = {false};

        paceGate();

        final String before = lastUrl == null ? "" : lastUrl;
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        ui.post(() -> {
            try {
                if (web != null && web.canGoBack()) {
                    web.goBack();
                    ok[0] = true;
                }
            } catch (Throwable ignored) { }
            l.countDown();
        });
        try { l.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        if (ok[0]) waitNav(before, 2500);
        return ok[0];
    }

    public String currentInfo() {
        String t = lastTitle == null ? "" : lastTitle;
        String u = lastUrl == null ? "" : lastUrl;
        if (t.isEmpty() && u.isEmpty()) return "The built-in browser has not opened any page yet.";
        return "Current page: " + (t.isEmpty() ? "(no title)" : t) + "\nURL: " + u;
    }

    public String readText(int maxChars) {

        if (web == null) return null;
        final int cap = maxChars <= 0 ? 6000 : Math.min(maxChars, 30000);
        String text = evalSync(
                "(function(){try{var t=document.body?document.body.innerText:'';"
                        + "return t.replace(/\\n{3,}/g,'\\n\\n').slice(0," + cap + ");}catch(e){return '';}})()",
                6000);
        if (text == null) return null;
        text = text.trim();
        return text.isEmpty() ? null : text;
    }

    public boolean canGoBack() {
        return web != null && web.canGoBack();
    }

    public boolean isLoading() {
        return pageLoading;
    }

    public String clickByText(String text) {
        if (web == null || TextUtils.isEmpty(text)) return null;

        paceGate();
        final String beforeUrl = lastUrl == null ? "" : lastUrl;
        final String js = "(function(){try{"
                + "var want=" + jsString(text) + ".toLowerCase();"
                + "function visible(el){if(!el)return false;var r=el.getBoundingClientRect();"
                + "if(r.width<1||r.height<1)return false;var s=getComputedStyle(el);"
                + "return s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';}"
                + "var cands=[];"
                + "var all=document.querySelectorAll('a,button,input[type=submit],input[type=button],"
                + "[role=button],[onclick],summary,label');"
                + "for(var i=0;i<all.length;i++){var el=all[i];if(!visible(el))continue;"
                + "var t=(el.innerText||el.value||el.getAttribute('aria-label')||el.title||'').trim();"
                + "if(!t)continue;cands.push({el:el,t:t,lt:t.toLowerCase()});}"
                + "function hit(c){return c.lt===want?0:(c.lt.indexOf(want)>=0?1:-1);}"

                + "function isLink(c){var n=c.el.tagName;return n==='A'||n==='BUTTON'"
                + "||(c.el.getAttribute&&c.el.getAttribute('role')==='button');}"
                + "var best=null,bi=-1;"
                + "for(var i=0;i<cands.length;i++){var k=hit(cands[i]);"
                + "if(k<0)continue;"
                + "if(best===null){best=cands[i];bi=i;continue;}"
                + "var kb=hit(best);"
                + "var better=false;"
                + "if(k<kb)better=true;"
                + "else if(k===kb){"
                + "  var cl=isLink(cands[i]),bl=isLink(best);"
                + "  if(cl&&!bl)better=true;"
                + "  else if(cl===bl&&cands[i].t.length<best.t.length)better=true;"
                + "}"
                + "if(better){best=cands[i];bi=i;}"
                + "if(bi<0)return JSON.stringify({ok:false,n:cands.length});"
                + "var el=cands[bi].el;"
                + "el.scrollIntoView({block:'center'});"
                + "el.click();"
                + "return JSON.stringify({ok:true,hit:cands[bi].t.slice(0,80),"
                + "href:(el.href||'').toString().slice(0,300),total:cands.length});"
                + "}catch(e){return JSON.stringify({ok:false,err:String(e)});}})()";
        String raw = evalSync(js, 5000);
        if (raw == null) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(raw);
            if (!o.optBoolean("ok")) {
                if (o.has("err")) return "Click failed: " + o.optString("err");
                return "No clickable \"" + text + "\" found on the page (the page has " + o.optInt("n")
                        + " clickable elements). Use browser_read to see what is actually on the page.";
            }
            String href = o.optString("href", "");
            String hit = o.optString("hit", text);

            String nav = waitNav(beforeUrl, 2500);

            String urlNow = lastUrl == null ? "" : lastUrl;
            if (urlNow.equals(beforeUrl) && !pageLoading
                    && href.startsWith("http") && !href.equals(beforeUrl)) {
                loadUrl(href);
                String nav2 = waitNav(beforeUrl, 4000);
                if (!(lastUrl == null ? "" : lastUrl).equals(beforeUrl)) {
                    nav = "The click did not trigger navigation, so its target URL was opened directly. " + nav2;
                } else {
                    nav = "Neither the click nor opening the URL directly caused navigation (the link may require login or open in-site via JS). "
                            + "Use browser_read to see the page's current state.";
                }
            }
            return "Clicked \"" + hit + "\"" + (href.isEmpty() ? " (in-page element)" : " -> " + href) + ". " + nav;
        } catch (Throwable t) {
            return null;
        }
    }

    private void loadUrl(String url) {
        if (url == null || url.isEmpty()) return;
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        ui.post(() -> {
            try {
                if (web != null) web.loadUrl(url);
            } catch (Throwable ignored) { }
            l.countDown();
        });
        try { l.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    private String waitNav(String beforeUrl, long maxMs) {
        long step = 100;
        long waited = 0;
        boolean sawLoading = false;
        while (waited < maxMs) {
            if (pageLoading) sawLoading = true;
            String now = lastUrl == null ? "" : lastUrl;
            if (!now.equals(beforeUrl) && !now.isEmpty()) {
                if (!pageLoading) return "The page has navigated to " + now + "; browser_read can now read the new content.";
                sawLoading = true;
            }
            try { Thread.sleep(step); } catch (InterruptedException ignored) { }
            waited += step;
        }
        if (pageLoading || sawLoading) {
            return "The page is still navigating; **wait a moment before browser_read** (reading now would return the old page).";
        }
        String now = lastUrl == null ? "" : lastUrl;
        if (!now.equals(beforeUrl) && !now.isEmpty()) {
            return "The page navigated to " + now + "; use browser_read for the new content.";
        }
        return "The page URL did not change (it may expand in-page or show a popup, or the element did nothing when clicked). "
                + "Use browser_read or browser_links to check the current state.";
    }

    public String listLinks(int limit) {
        if (web == null) return null;
        final int cap = limit <= 0 ? 40 : Math.min(limit, 120);
        final String js = "(function(){try{"
                + "function visible(el){var r=el.getBoundingClientRect();"
                + "if(r.width<1||r.height<1)return false;var s=getComputedStyle(el);"
                + "return s.visibility!=='hidden'&&s.display!=='none';}"
                + "var out=[],seen={};"
                + "var all=document.querySelectorAll('a,button,[role=button],input[type=submit]');"
                + "for(var i=0;i<all.length&&out.length<" + cap + ";i++){var el=all[i];"
                + "if(!visible(el))continue;"
                + "var t=(el.innerText||el.value||el.getAttribute('aria-label')||'').replace(/\\s+/g,' ').trim();"
                + "if(!t||t.length>60)continue;"
                + "var key=t+'|'+(el.href||'');if(seen[key])continue;seen[key]=1;"
                + "out.push(t+(el.href?(' -> '+String(el.href).slice(0,160)):''));}"
                + "return out.join('\\n');"
                + "}catch(e){return '';}})()";
        return evalSync(js, 5000);
    }

    private String evalSync(String js, long timeoutMs) {
        if (web == null) return null;
        final String[] out = new String[]{null};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        ui.post(() -> {
            try {
                web.evaluateJavascript(js, value -> {
                    try {
                        if (value != null && !"null".equals(value)) {

                            out[0] = new org.json.JSONArray("[" + value + "]").optString(0, "");
                        }
                    } catch (Throwable ignored) { }
                    latch.countDown();
                });
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) { }
        return out[0];
    }

    public String typeInto(String text, String into, boolean submit) {
        if (web == null || text == null) return null;

        paceGate();
        final String js = "(function(){try{"
                + "var want=" + jsString(text) + ","
                + "hint=" + jsString(into == null ? "" : into) + ","
                + "submit=" + (submit ? "true" : "false") + ";"
                + "function visible(el){var r=el.getBoundingClientRect();"
                + "if(r.width<1||r.height<1)return false;var s=getComputedStyle(el);"
                + "return s.visibility!=='hidden'&&s.display!=='none';}"
                + "function labelOf(el){return ((el.placeholder||'')+' '+(el.getAttribute('aria-label')||'')"
                + "+' '+(el.name||'')+' '+(el.id||'')).toLowerCase();}"
                + "var all=document.querySelectorAll('input,textarea,[contenteditable=true]'),cands=[];"
                + "for(var i=0;i<all.length;i++){var el=all[i];"
                + "if(!visible(el))continue;"
                + "var t=(el.type||'').toLowerCase();"
                + "if(t==='hidden'||t==='submit'||t==='button'||t==='checkbox'||t==='radio'"
                + "||t==='file'||t==='image'||el.disabled||el.readOnly)continue;"
                + "if(t==='password'){cands.push({el:el,pw:true});continue;}"
                + "cands.push({el:el,pw:false,l:labelOf(el)});}"
                + "if(!cands.length)return JSON.stringify({ok:false,why:'no_field'});"
                + "var pick=null;"
                + "if(hint){var hl=hint.toLowerCase();"
                + "for(var i=0;i<cands.length;i++){if(!cands[i].pw&&cands[i].l.indexOf(hl)>=0){pick=cands[i];break;}}}"
                + "if(!pick){var fields=cands.filter(function(c){return !c.pw;});"
                + "if(fields.length===1)pick=fields[0];"
                + "else if(fields.length>1)return JSON.stringify({ok:false,why:'ambiguous',n:fields.length});}"
                + "if(!pick){var pw=cands.filter(function(c){return c.pw;}).length;"
                + "if(pw)return JSON.stringify({ok:false,why:'password_only'});"
                + "return JSON.stringify({ok:false,why:'no_field'});}"
                + "var el=pick.el;"
                + "el.focus();"
                + "if(el.getAttribute('contenteditable')==='true'){el.textContent=want;}"
                + "else{var proto=(el.tagName==='TEXTAREA')?window.HTMLTextAreaElement.prototype"
                + ":window.HTMLInputElement.prototype;"
                + "var d=Object.getOwnPropertyDescriptor(proto,'value');"
                + "if(d&&d.set)d.set.call(el,want);else el.value=want;}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "var submitted=false;"
                + "if(submit){try{var f=el.form;"
                + "if(f&&f.requestSubmit){f.requestSubmit();submitted=true;}"
                + "else if(f&&f.submit){f.submit();submitted=true;}"
                + "}catch(e){}"
                + "if(!submitted){try{"
                + "['keydown','keypress','keyup'].forEach(function(ev){"
                + "el.dispatchEvent(new KeyboardEvent(ev,{key:'Enter',code:'Enter',"
                + "keyCode:13,which:13,bubbles:true,cancelable:true}));});"
                + "submitted=true;}catch(e){}}}"
                + "return JSON.stringify({ok:true,where:(el.placeholder||el.name||el.id||'').slice(0,60),"
                + "submitted:submitted});"
                + "}catch(e){return JSON.stringify({ok:false,why:'err',msg:String(e)});}})()";
        String raw = evalSync(js, 6000);
        if (raw == null) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(raw);
            if (!o.optBoolean("ok")) {
                String why = o.optString("why");
                if ("password_only".equals(why)) {
                    return "This page only has a password field (or a protected login box). **Never fill in passwords** - leave it to the user.";
                }
                if ("ambiguous".equals(why)) {
                    return "The page has " + o.optInt("n") + " input fields and it is unclear which one to fill. "
                            + "Specify which one via the into parameter (use the text or placeholder next to it, e.g. \"搜索\" or \"手机号\").";
                }
                if ("no_field".equals(why)) return "No typeable input field found on this page.";
                return "Input failed: " + o.optString("msg", why);
            }
            String where = o.optString("where", "");
            return "Typed \"" + text + "\" into " + (where.isEmpty() ? "the input field" : "the \"" + where + "\" field")
                    + (submit ? " and submitted it (the page should be refreshing/navigating)." : " (not submitted yet; click the search button if needed).");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '<': sb.append("\\u003c"); break;
                case '>': sb.append("\\u003e"); break;
                default: sb.append(c);
            }
        }
        return sb.append("'").toString();
    }

    public void destroy() {
        ui.post(() -> {
            try {
                if (web != null) {
                    web.stopLoading();
                    web.loadUrl("about:blank");
                    web.destroy();
                    web = null;
                }
            } catch (Throwable ignored) { }
            try {
                if (panel != null) {
                    ViewGroup p = (ViewGroup) panel.getParent();
                    if (p != null) p.removeView(panel);
                }
            } catch (Throwable ignored) { }
            panel = null;
        });
    }
}
