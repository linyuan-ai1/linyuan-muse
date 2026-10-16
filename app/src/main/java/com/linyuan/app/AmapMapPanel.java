package com.linyuan.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.AMapNaviViewListener;
import com.amap.api.navi.AMapNaviViewOptions;
import com.amap.api.navi.AmapPageType;
import com.amap.api.navi.SimpleNaviListener;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapNaviPath;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.geocoder.GeocodeQuery;
import com.amap.api.services.geocoder.GeocodeResult;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.RegeocodeResult;
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;
import com.amap.api.services.weather.LocalDayWeatherForecast;
import com.amap.api.services.weather.LocalWeatherForecastResult;
import com.amap.api.services.weather.LocalWeatherLive;
import com.amap.api.services.weather.LocalWeatherLiveResult;
import com.amap.api.services.weather.WeatherSearch;
import com.amap.api.services.weather.WeatherSearchQuery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AmapMapPanel {

    private static final String TAG = "VC_AMAP";

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout panel;
    private FrameLayout mapHost;
    private MapView mapView;
    private AMap aMap;
    private AMapNaviView naviView;
    private TextView titleText;
    private TextView statusText;
    private LinearLayout listBox;

    private ScrollView listScroll;

    private LinearLayout toolBar;
    private Button trafficBtn, satelliteBtn, locateBtn;

    private boolean trafficOn = false;
    private boolean satelliteOn = false;

    private boolean visible = false;
    private boolean barWasVisible = false;
    private boolean naviMode = false;

    private AMapNavi navi;
    private boolean naviReady = false;
    private Runnable pendingAction;
    private String apiKey = "";

    private double lastLat = 0, lastLon = 0;

    private Double routeFromLat = null, routeFromLon = null;
    private Double routeToLat = null, routeToLon = null;

    private java.util.function.Consumer<String> statusSink;
    private String pendingMode = "drive";
    private java.util.function.Consumer<String> pendingNaviResult;
    private java.util.function.Consumer<String> pendingRouteResult;
    private AMapLocationClient locClient;

    private AMapLocationClient continuousClient;
    private com.amap.api.maps.LocationSource locSource;
    private com.amap.api.maps.LocationSource.OnLocationChangedListener locListener;

    public AmapMapPanel(Activity activity) {
        this.activity = activity;
    }

    public void setStatusSink(java.util.function.Consumer<String> sink) { this.statusSink = sink; }

    public void setApiKey(String key) {
        this.apiKey = key == null ? "" : key.trim();
        AmapHolder.init(activity, this.apiKey);
    }

    public boolean isVisible() { return visible; }
    public boolean isNaviMode() { return naviMode; }
    public double getLastLat() { return lastLat; }
    public double getLastLon() { return lastLon; }

    private void dlog(String msg) {
        try { android.util.Log.i(TAG, msg); } catch (Throwable ignored) { }
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private int color(int id) {
        try { return activity.getResources().getColor(id); } catch (Throwable t) { return Color.WHITE; }
    }

    private static final int ON_DARK_BAR = 0xFFEAF0FB;

    private static String nz(String s) { return s == null ? "" : s; }

    private String amapSearchHint(int code) {
        switch (code) {
            case 1001: return "（签名错误，" + AmapHolder.signingHint(activity) + "，需与高德控制台登记的一致）";
            case 1002: return "（Key 无效，检查设置里的高德 Key）";
            case 1008: return "（签名校验没过：高德控制台里这把 Key 需要登记本机签名 SHA1 —— "
                    + AmapHolder.signingHint(activity) + "；也可以先用「纬度,经度」坐标代替地名）";
            case 1009: return "（这把 Key 的平台类型不对，要用 Android 平台 Key）";
            case 1003: return "（该服务未开通/不可用）";
            case 1004: return "（今日查询次数已达上限）";
            case 1005: return "（访问过于频繁，稍后再试）";
            default: return "";
        }
    }

    private String amapRouteHint(int code) {
        switch (code) {
            case 22: return "（签名安全码没过：高德控制台里这把 Key 要登记本机签名 SHA1 —— "
                    + AmapHolder.signingHint(activity) + "）";
            case 23: return "（起点或终点坐标不合法）";
            case 24: return "（途经点数量超限）";
            default: return "";
        }
    }

    static String toolErr(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg).toString(); }
        catch (Throwable t) { return "{\"ok\":false}"; }
    }

    private void ensurePanel() {
        if (panel != null) return;
        AmapHolder.init(activity, apiKey);

        panel = new PassthroughFrame(activity);
        panel.setBackgroundColor(0xFF0E1116);

        mapHost = new FrameLayout(activity);
        panel.addView(mapHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundResource(R.drawable.bg_panel);
        scroll.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.setVisibility(View.GONE);
        listBox = new LinearLayout(activity);
        listBox.setOrientation(LinearLayout.VERTICAL);

        scroll.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        this.listScroll = scroll;
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(330));
        llp.gravity = Gravity.BOTTOM;
        llp.setMargins(dp(8), 0, dp(8), dp(96));
        panel.addView(scroll, llp);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xE6121419);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        titleText = new TextView(activity);
        titleText.setTextColor(ON_DARK_BAR);
        titleText.setTextSize(14);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(titleText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP;
        panel.addView(bar, blp);

        statusText = new TextView(activity);
        statusText.setTextColor(ON_DARK_BAR);
        statusText.setTextSize(13);
        statusText.setBackgroundColor(0xE6121419);
        statusText.setPadding(dp(12), dp(8), dp(12), dp(8));
        statusText.setVisibility(View.GONE);

        LinearLayout bottomWrap = new LinearLayout(activity);
        bottomWrap.setOrientation(LinearLayout.VERTICAL);
        bottomWrap.setPadding(dp(8), dp(6), dp(8), dp(10));

        LinearLayout rowBar = new LinearLayout(activity);
        rowBar.setOrientation(LinearLayout.HORIZONTAL);
        rowBar.setGravity(Gravity.CENTER_VERTICAL);

        Button backBtn = mkSmallBtn("‹");
        backBtn.setTextSize(20f);
        backBtn.setOnClickListener(v -> goBackOrClose());
        LinearLayout.LayoutParams bklp = new LinearLayout.LayoutParams(dp(42), dp(42));
        bklp.setMargins(0, 0, dp(6), 0);
        rowBar.addView(backBtn, bklp);

        toolBar = new LinearLayout(activity);
        toolBar.setOrientation(LinearLayout.HORIZONTAL);
        toolBar.setGravity(Gravity.CENTER_VERTICAL);
        trafficBtn = mkToolBtn("🚦 路况", v -> toggleTraffic());
        satelliteBtn = mkToolBtn("🛰 卫星", v -> toggleSatellite());
        locateBtn = mkToolBtn("◎ 我的位置", v -> locateMe(null));
        toolBar.addView(trafficBtn);
        toolBar.addView(satelliteBtn);
        toolBar.addView(locateBtn);
        rowBar.addView(toolBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bottomWrap.addView(rowBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.setMargins(0, dp(6), 0, 0);
        bottomWrap.addView(statusText, stlp);

        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.gravity = Gravity.BOTTOM;
        panel.addView(bottomWrap, wlp);
        refreshToolButtons();

        ViewGroup rootContent = (ViewGroup) activity.findViewById(android.R.id.content);
        rootContent.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setAlpha(0f);
        panel.setVisibility(View.VISIBLE);
        setTouchable(false);

        mapView = new MapView(activity);
        mapHost.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mapView.onCreate(new Bundle());
        try {
            aMap = mapView.getMap();
            aMap.getUiSettings().setZoomControlsEnabled(false);
            aMap.getUiSettings().setRotateGesturesEnabled(true);
            aMap.getUiSettings().setTiltGesturesEnabled(false);
            aMap.getUiSettings().setCompassEnabled(false);

            aMap.getUiSettings().setScaleControlsEnabled(true);

            setupLocationLayer();

            if (!visible) stopLocationLayer();
        } catch (Throwable t) {
            dlog("地图初始化失败: " + t);
            setStatus("地图初始化失败：" + t.getMessage());
        }
        dlog("ensurePanel done idx=" + rootContent.indexOfChild(panel));
    }

    private void setupLocationLayer() {
        if (aMap == null) return;
        try {
            if (!hasLocationPermission()) return;
            if (locSource == null) {
                locSource = new com.amap.api.maps.LocationSource() {
                    @Override public void activate(com.amap.api.maps.LocationSource.OnLocationChangedListener listener) {

                        if (!visible) { dlog("activate 被调用但面板不可见 → 不启动定位"); return; }
                        locListener = listener;
                        try {
                            if (continuousClient == null) {
                                continuousClient = new AMapLocationClient(activity.getApplicationContext());
                            }
                            AMapLocationClientOption o = new AMapLocationClientOption();
                            o.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
                            o.setOnceLocation(false);
                            o.setInterval(3000);
                            o.setNeedAddress(false);
                            continuousClient.setLocationOption(o);
                            continuousClient.setLocationListener(loc -> {
                                if (loc == null || loc.getErrorCode() != 0) return;

                                try {
                                    android.location.Location l = new android.location.Location("amap");
                                    l.setLatitude(loc.getLatitude());
                                    l.setLongitude(loc.getLongitude());
                                    l.setAccuracy(loc.getAccuracy());
                                    l.setTime(System.currentTimeMillis());
                                    if (locListener != null) locListener.onLocationChanged(l);
                                    lastLat = loc.getLatitude();
                                    lastLon = loc.getLongitude();
                                    String ct = nz(loc.getCity());
                                    if (!ct.isEmpty()) lastCity = ct;
                                } catch (Throwable ignored) { }
                            });
                            continuousClient.startLocation();
                        } catch (Throwable t) { dlog("蓝点定位启动失败: " + t); }
                    }
                    @Override public void deactivate() {
                        locListener = null;
                        try { if (continuousClient != null) continuousClient.stopLocation(); }
                        catch (Throwable ignored) { }
                    }
                };
                aMap.setLocationSource(locSource);
            }
            com.amap.api.maps.model.MyLocationStyle st = new com.amap.api.maps.model.MyLocationStyle();

            st.myLocationType(com.amap.api.maps.model.MyLocationStyle.LOCATION_TYPE_LOCATE);
            st.showMyLocation(true);
            st.interval(3000);
            aMap.setMyLocationStyle(st);
            aMap.setMyLocationEnabled(true);
            dlog("蓝点已启用");
        } catch (Throwable t) {
            dlog("蓝点启用失败: " + t);
        }
    }

    private void stopLocationLayer() {
        try {
            if (continuousClient != null) continuousClient.stopLocation();
        } catch (Throwable ignored) { }
        locListener = null;
    }

    private void stopLocalClient() {
        try {
            if (locClient != null) locClient.stopLocation();
        } catch (Throwable ignored) { }
    }

    private Button mkSmallBtn(String text) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color(R.color.text));
        try { b.setBackgroundResource(R.drawable.btn_secondary); } catch (Throwable ignored) { }
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(36)));
        return b;
    }

    private Button mkToolBtn(String text, View.OnClickListener onClick) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color(R.color.text));
        b.setTextSize(12);
        try { b.setBackgroundResource(R.drawable.btn_secondary); } catch (Throwable ignored) { }
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.setMargins(0, 0, dp(6), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(onClick);
        return b;
    }

    private void paintToggle(Button b, boolean on) {
        if (b == null) return;
        try {
            b.setBackgroundResource(on ? R.drawable.btn_primary : R.drawable.btn_secondary);
        } catch (Throwable ignored) { }
        b.setTextColor(on ? color(R.color.onAccent) : color(R.color.text));
    }

    private void refreshToolButtons() {
        paintToggle(trafficBtn, trafficOn);
        paintToggle(satelliteBtn, satelliteOn);
    }

    public void toggleTraffic() { setTraffic(!trafficOn); }

    public void toggleSatellite() { setSatellite(!satelliteOn); }

    public void setTraffic(final boolean on) {
        runOnUiBlocking(() -> {
            ensurePanel();
            raisePanel();
            if (aMap == null) return;
            trafficOn = on;
            try {
                aMap.setTrafficEnabled(on);
                setStatus(on ? "已开启实时路况" : "已关闭实时路况");
            } catch (Throwable t) {
                setStatus("路况开关失败：" + t.getMessage());
            }
            refreshToolButtons();
        });
    }

    public void setSatellite(final boolean on) {
        runOnUiBlocking(() -> {
            ensurePanel();
            raisePanel();
            if (aMap == null) return;
            satelliteOn = on;
            try {
                aMap.setMapType(on ? AMap.MAP_TYPE_SATELLITE : AMap.MAP_TYPE_NORMAL);
                setStatus(on ? "已切换到卫星图" : "已切回普通地图");
            } catch (Throwable t) {
                setStatus("卫星图切换失败：" + t.getMessage());
            }
            refreshToolButtons();
        });
    }

    private void runOnUiBlocking(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        ui.post(() -> {
            try { r.run(); } finally { latch.countDown(); }
        });
        try { latch.await(8, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { }
    }

    public String layerState() {
        return new StringBuilder()
                .append("traffic=").append(trafficOn ? "on" : "off")
                .append(", mapType=").append(satelliteOn ? "satellite" : "normal").toString();
    }

    public boolean isTrafficOn() { return trafficOn; }
    public boolean isSatelliteOn() { return satelliteOn; }

    private void setTouchable(boolean on) {
        if (panel == null) return;

        if (panel instanceof PassthroughFrame) ((PassthroughFrame) panel).setInterceptTouch(on);
        panel.setClickable(on);
        panel.setFocusable(on);
        panel.setAlpha(on ? 1f : 0f);
        visible = on;

        if (!on) stopLocationLayer();
        else if (locSource != null) {
            try { aMap.setLocationSource(null); aMap.setLocationSource(locSource); } catch (Throwable ignored) { }
        }
    }

    static class PassthroughFrame extends FrameLayout {
        private boolean interceptTouch = true;
        PassthroughFrame(android.content.Context c) { super(c); }
        void setInterceptTouch(boolean on) { interceptTouch = on; }
        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
            if (!interceptTouch) return false;
            return super.dispatchTouchEvent(ev);
        }
    }

    public void showPanel() { ui.post(this::raisePanel); }

    private void raisePanel() {
        ensurePanel();
        View backBar = activity.findViewById(R.id.settingsBackBar);
        if (!visible && backBar != null) barWasVisible = backBar.getVisibility() == View.VISIBLE;
        if (backBar != null) backBar.setVisibility(View.GONE);
        panel.setAlpha(1f);
        setTouchable(true);
        if (mapView != null) { try { mapView.onResume(); } catch (Throwable ignored) { } }
        if (naviView != null) { try { naviView.onResume(); } catch (Throwable ignored) { } }
        ui.postDelayed(this::resizeMap, 60);
    }

    private void resizeMap() {
        if (mapView == null || panel == null) return;
        int w = panel.getWidth(), h = panel.getHeight();
        if (w > 0 && h > 0) {
            mapView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            mapView.layout(0, 0, w, h);
        }
    }

    public void hidePanel() {
        ui.post(() -> {
            if (panel == null) return;
            if (barWasVisible) {
                View backBar = activity.findViewById(R.id.settingsBackBar);
                if (backBar != null) backBar.setVisibility(View.VISIBLE);
            }
            panel.setAlpha(0f);

            setTouchable(false);
            if (mapView != null) { try { mapView.onPause(); } catch (Throwable ignored) { } }
            if (naviView != null) { try { naviView.onPause(); } catch (Throwable ignored) { } }
        });
    }

    public void goBackOrClose() {

        if (naviMode) { stopNavi("已退出导航"); return; }

        if (listScroll != null && listScroll.getVisibility() == View.VISIBLE) {
            listScroll.setVisibility(View.GONE);
            return;
        }
        hidePanel();
    }

    public boolean handleBack() {
        if (!visible) return false;
        goBackOrClose();
        return true;
    }

    public void onResume() {

        if (!visible) return;
        if (mapView != null) try { mapView.onResume(); } catch (Throwable ignored) { }
        if (naviView != null) try { naviView.onResume(); } catch (Throwable ignored) { }
    }

    public void onPause() {

        if (!visible) return;
        if (mapView != null) try { mapView.onPause(); } catch (Throwable ignored) { }
        if (naviView != null) try { naviView.onPause(); } catch (Throwable ignored) { }
    }

    public void onDestroy() {
        try { if (navi != null) { navi.stopNavi(); navi.removeAMapNaviListener(naviListener); } }
        catch (Throwable ignored) { }

        navi = null;
        naviReady = false;
        try { if (naviView != null) { naviView.onDestroy(); naviView = null; } } catch (Throwable ignored) { }
        try { if (mapView != null) { mapView.onDestroy(); mapView = null; } } catch (Throwable ignored) { }
        try { if (locClient != null) { locClient.onDestroy(); locClient = null; } } catch (Throwable ignored) { }
        panel = null;
    }

    private void setTitle(String s) {
        String t = nz(s);
        if (titleText != null) titleText.setText(t.isEmpty() ? "地图" : t);
    }

    private void setStatus(String s) {
        if (statusText == null) return;

        if (naviMode) return;
        if (s == null || s.trim().isEmpty()) { statusText.setVisibility(View.GONE); return; }
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(s);
    }

    private void pushStatus(String s) {
        java.util.function.Consumer<String> sink = statusSink;
        if (sink != null) { try { sink.accept(s); } catch (Throwable ignored) { } }
    }

    public void showPoint(final double lat, final double lon, final String title, String sub) {
        showPoint(lat, lon, title, sub, 16f);
    }

    public void showPoint(final double lat, final double lon, final String title, String sub, float zoom) {
        final float z = (zoom < 3f || zoom > 19f) ? 16f : zoom;
        ui.post(() -> {
            ensurePanel();
            raisePanel();
            if (aMap == null) { setStatus("地图还没准备好"); return; }
            LatLng ll = new LatLng(lat, lon);
            aMap.clear();
            aMap.addMarker(new MarkerOptions().position(ll).title(nz(title)).snippet(nz(sub)));
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(ll, z));
            lastLat = lat; lastLon = lon;
            setTitle(title);
            setStatus(sub);
        });
    }

    public String showPointBlocking(final double lat, final double lon,
                                    final String title, final String sub,
                                    float zoom, long timeoutMs) {
        final float z = (zoom < 3f || zoom > 19f) ? 16f : zoom;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] out = new String[1];
        ui.post(() -> {
            try {
                ensurePanel();
                raisePanel();
                if (aMap == null) { out[0] = toolErr("The built-in map failed to initialize (possibly a Key or network issue)"); return; }
                LatLng ll = new LatLng(lat, lon);
                aMap.clear();
                aMap.addMarker(new MarkerOptions().position(ll).title(nz(title)).snippet(nz(sub)));
                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(ll, z));
                lastLat = lat; lastLon = lon;
                setTitle(title);
                setStatus(sub);
            } catch (Throwable t) {
                out[0] = toolErr("Failed to show the location: " + t);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return toolErr("Showing the location timed out (the main thread did not respond)");
            }
        } catch (InterruptedException ignored) { }
        return out[0];
    }

    public void search(final String keyword, final String city,
                       final java.util.function.Consumer<String> onResult) {
        search(keyword, city, null, null, onResult);
    }

    public void search(final String keyword, final String city,
                       final Double nearLat, final Double nearLon,
                       final java.util.function.Consumer<String> onResult) {
        ui.post(() -> {
            ensurePanel();
            raisePanel();
            boolean nearby = (nearLat != null && nearLon != null);
            setTitle(nearby ? ("附近：" + keyword) : ("搜索：" + keyword));
            setStatus(nearby ? "正在搜附近…" : "正在搜索…");
            AmapHolder.init(activity, apiKey);
            try {
                PoiSearch.Query q = new PoiSearch.Query(keyword, "", city == null ? "" : city);
                q.setPageSize(10);
                q.setPageNum(1);
                PoiSearch poiSearch = new PoiSearch(activity, q);
                if (nearby) {
                    LatLonPoint center = new LatLonPoint(nearLat, nearLon);
                    q.setLocation(center);
                    q.setDistanceSort(true);
                    poiSearch.setBound(new PoiSearch.SearchBound(center, 3000, true));
                }
                poiSearch.setOnPoiSearchListener(new PoiSearch.OnPoiSearchListener() {
                    @Override public void onPoiSearched(PoiResult result, int code) {
                        ui.post(() -> handlePoiResult(result, code, keyword, onResult, nearby));
                    }
                    @Override public void onPoiItemSearched(PoiItem item, int code) { }
                });
                poiSearch.searchPOIAsyn();
            } catch (Throwable t) {
                String msg = "搜索失败：" + t.getMessage();
                setStatus(msg);
                if (onResult != null) onResult.accept(toolErr(msg));
            }
        });
    }

    private void handlePoiResult(PoiResult result, int code, String keyword,
                                 java.util.function.Consumer<String> onResult) {
        handlePoiResult(result, code, keyword, onResult, false);
    }

    private void handlePoiResult(PoiResult result, int code, String keyword,
                                 java.util.function.Consumer<String> onResult, boolean nearby) {
        try {
            List<PoiItem> items = (result == null) ? null : result.getPois();
            if (code != 1000 || items == null || items.isEmpty()) {

                suggest(keyword, tips -> {
                    String msg = "没有搜到「" + keyword + "」" + amapSearchHint(code);
                    if (tips != null && tips.length() > 0) {
                        msg += "。是不是想说：" + tips.optString(0)
                                + (tips.length() > 1 ? "、" + tips.optString(1) : "") + "？";
                    }
                    setStatus(msg + (code == 1000 ? "" : "（错误码 " + code + "）"));
                    if (onResult != null) {
                        JSONObject o = new JSONObject();
                        try {
                            o.put("ok", false).put("error", msg);
                            if (tips != null && tips.length() > 0) o.put("suggestions", tips);
                        } catch (Throwable ignored) { }
                        onResult.accept(o.toString());
                    }
                });
                return;
            }
            listBox.removeAllViews();
            JSONArray arr = new JSONArray();
            int shown = 0;
            for (int i = 0; i < items.size(); i++) {
                final PoiItem it = items.get(i);
                if (it.getLatLonPoint() == null) continue;
                shown++;
                final int idx = shown;
                double la = it.getLatLonPoint().getLatitude();
                double lo = it.getLatLonPoint().getLongitude();

                String dStart = "", dEnd = "", dHere = "";
                if (routeFromLat != null && routeFromLon != null) {
                    dStart = fmtDist((int) Math.round(distanceMeters(routeFromLat, routeFromLon, la, lo)));
                }
                if (routeToLat != null && routeToLon != null) {
                    dEnd = fmtDist((int) Math.round(distanceMeters(routeToLat, routeToLon, la, lo)));
                }
                if (dStart.isEmpty() && dEnd.isEmpty()) {
                    if (it.getDistance() > 0) dHere = fmtDist(it.getDistance());
                    else if (lastLat != 0 || lastLon != 0) {
                        dHere = fmtDist((int) Math.round(distanceMeters(lastLat, lastLon, la, lo)));
                    }
                }

                JSONObject o = new JSONObject();
                o.put("index", idx);
                o.put("name", nz(it.getTitle()));
                o.put("address", nz(it.getSnippet()));
                o.put("lat", la);
                o.put("lon", lo);
                if (!dStart.isEmpty()) o.put("distanceFromStart", dStart);
                if (!dEnd.isEmpty()) o.put("distanceToDestination", dEnd);
                if (!dHere.isEmpty()) o.put("distance", dHere);
                arr.put(o);

                listBox.addView(buildPoiCard(idx, it, dStart, dEnd, dHere,
                        () -> {
                            if (listScroll != null) listScroll.setVisibility(View.GONE);
                            setTitle(it.getTitle());
                            setStatus("第 " + idx + " 条：" + nz(it.getSnippet()));
                            lastLat = la; lastLon = lo;
                            if (aMap != null) {
                                aMap.clear();
                                aMap.addMarker(new MarkerOptions()
                                        .position(new LatLng(la, lo)).title(it.getTitle()));
                                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(la, lo), 16f));
                            }
                        }));
            }
            if (listScroll != null) listScroll.setVisibility(View.VISIBLE);
            setStatus("找到 " + arr.length() + " 个「" + keyword + "」，点一条即可定位");
            if (onResult != null) {
                onResult.accept(new JSONObject().put("ok", true)
                        .put("count", arr.length()).put("places", arr)
                        .put("note", "Already displayed in the built-in map; the user can tap a result directly. The coordinates can be passed straight to map_navigate.").toString());
            }
        } catch (Throwable t) {
            String msg = "搜索出错：" + t.getMessage();
            setStatus(msg);
            if (onResult != null) onResult.accept(toolErr(msg));
        }
    }

    private View buildPoiCard(final int idx, final PoiItem it,
                              final String dStart, final String dEnd, final String dHere,
                              final Runnable onClick) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.card);
        card.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, dp(7));
        card.setLayoutParams(clp);
        card.setOnClickListener(v -> onClick.run());

        TextView badge = new TextView(activity);
        badge.setText(String.valueOf(idx));
        badge.setTextColor(color(R.color.onAccent));
        badge.setTextSize(12);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundResource(R.drawable.btn_primary);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(22), dp(22));
        blp.setMargins(0, dp(1), dp(9), 0);
        card.addView(badge, blp);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout nameRow = new LinearLayout(activity);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(activity);
        name.setText(nz(it.getTitle()));
        name.setTextColor(color(R.color.text));
        name.setTextSize(14.5f);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameRow.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        StringBuilder dTxt = new StringBuilder();
        if (!dStart.isEmpty()) dTxt.append("距起点 ").append(dStart);
        if (!dEnd.isEmpty()) {
            if (dTxt.length() > 0) dTxt.append(" · ");
            dTxt.append("距终点 ").append(dEnd);
        }
        if (dTxt.length() == 0 && !dHere.isEmpty()) dTxt.append(dHere);
        if (dTxt.length() > 0) {
            TextView dist = new TextView(activity);
            dist.setText(dTxt.toString());
            dist.setTextColor(color(R.color.accent));
            dist.setTextSize(11.5f);
            dist.setSingleLine(true);
            nameRow.addView(dist, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        col.addView(nameRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String addr = nz(it.getSnippet());
        if (!addr.isEmpty()) {
            TextView sub = new TextView(activity);
            sub.setText(addr);
            sub.setTextColor(color(R.color.muted));
            sub.setTextSize(12);
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp2.setMargins(0, dp(3), 0, 0);
            col.addView(sub, slp2);
        }
        return card;
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private void suggest(String keyword, java.util.function.Consumer<JSONArray> cb) {
        ui.post(() -> {
            try {
                AmapHolder.init(activity, apiKey);
                com.amap.api.services.help.InputtipsQuery q =
                        new com.amap.api.services.help.InputtipsQuery(keyword, "");
                q.setType("");
                com.amap.api.services.help.Inputtips it =
                        new com.amap.api.services.help.Inputtips(activity, q);
                it.setInputtipsListener((list, code) -> ui.post(() -> {
                    JSONArray out = new JSONArray();
                    try {
                        if (code == 1000 && list != null) {
                            for (com.amap.api.services.help.Tip t : list) {
                                if (t == null) continue;
                                String n = nz(t.getName());
                                if (n.isEmpty()) continue;
                                out.put(n);
                                if (out.length() >= 3) break;
                            }
                        }
                    } catch (Throwable ignored) { }
                    if (cb != null) cb.accept(out);
                }));
                it.requestInputtipsAsyn();
            } catch (Throwable t) {
                if (cb != null) cb.accept(new JSONArray());
            }
        });
    }

    public void weather(final String city, final java.util.function.Consumer<String> onResult) {
        ui.post(() -> {
            ensurePanel();
            raisePanel();
            setTitle("天气" + (nz(city).isEmpty() ? "" : "：" + city));
            setStatus("正在查天气…");
            try {
                AmapHolder.init(activity, apiKey);
                String c = nz(city).trim();
                if (c.isEmpty()) c = lastCity;
                if (c.isEmpty()) {
                    String msg = "还不知道你在哪个城市，先定位一次（或告诉我要查哪个城市）";
                    setStatus(msg);
                    if (onResult != null) onResult.accept(toolErr(msg));
                    return;
                }
                final String cityFinal = c;
                final JSONObject out = new JSONObject();
                final String[] liveErr = new String[1];

                WeatherSearchQuery liveQ = new WeatherSearchQuery(
                        cityFinal, WeatherSearchQuery.WEATHER_TYPE_LIVE);
                WeatherSearch liveSearch = new WeatherSearch(activity);
                liveSearch.setQuery(liveQ);
                liveSearch.setOnWeatherSearchListener(new WeatherSearch.OnWeatherSearchListener() {
                    @Override public void onWeatherLiveSearched(LocalWeatherLiveResult result, int code) {
                        if (code == 1000 && result != null && result.getLiveResult() != null) {
                            LocalWeatherLive w = result.getLiveResult();
                            try {
                                out.put("city", nz(w.getCity()));
                                out.put("weather", nz(w.getWeather()));
                                out.put("temperature", nz(w.getTemperature()) + "°C");
                                out.put("windDirection", nz(w.getWindDirection()));
                                out.put("windPower", nz(w.getWindPower()) + "级");
                                out.put("humidity", nz(w.getHumidity()) + "%");
                                out.put("reportTime", nz(w.getReportTime()));
                            } catch (Throwable ignored) { }
                        } else {
                            liveErr[0] = "天气实况查询失败" + amapSearchHint(code) + "（code=" + code + "）";
                        }

                        ui.post(() -> forecast(cityFinal, out, liveErr[0], onResult));
                    }

                    @Override public void onWeatherForecastSearched(LocalWeatherForecastResult r, int c) { }
                });
                liveSearch.searchWeatherAsyn();
            } catch (Throwable t) {
                String msg = "天气查询失败：" + t.getMessage();
                setStatus(msg);
                if (onResult != null) onResult.accept(toolErr(msg));
            }
        });
    }

    private void forecast(String city, JSONObject out, String liveErr,
                          java.util.function.Consumer<String> onResult) {
        try {
            WeatherSearchQuery fq = new WeatherSearchQuery(
                    city, WeatherSearchQuery.WEATHER_TYPE_FORECAST);
            WeatherSearch fs = new WeatherSearch(activity);
            fs.setQuery(fq);
            fs.setOnWeatherSearchListener(new WeatherSearch.OnWeatherSearchListener() {
                @Override public void onWeatherLiveSearched(LocalWeatherLiveResult r, int c) { }

                @Override public void onWeatherForecastSearched(LocalWeatherForecastResult result, int code) {
                    ui.post(() -> {
                        JSONArray days = new JSONArray();
                        if (code == 1000 && result != null && result.getForecastResult() != null
                                && result.getForecastResult().getWeatherForecast() != null) {
                            List<LocalDayWeatherForecast> list =
                                    result.getForecastResult().getWeatherForecast();
                            for (LocalDayWeatherForecast d : list) {
                                try {
                                    JSONObject o = new JSONObject();
                                    o.put("date", nz(d.getDate()));
                                    o.put("week", nz(d.getWeek()));
                                    o.put("dayWeather", nz(d.getDayWeather()));
                                    o.put("nightWeather", nz(d.getNightWeather()));
                                    o.put("dayTemp", nz(d.getDayTemp()) + "°C");
                                    o.put("nightTemp", nz(d.getNightTemp()) + "°C");
                                    o.put("dayWind", nz(d.getDayWindDirection()) + nz(d.getDayWindPower()) + "级");
                                    days.put(o);
                                } catch (Throwable ignored) { }
                            }
                        }
                        try {
                            if (days.length() > 0) out.put("forecast", days);
                        } catch (Throwable ignored) { }

                        boolean hasLive = out.has("weather");
                        if (!hasLive && days.length() == 0) {
                            String msg = liveErr != null ? liveErr : "没查到天气数据（可能是城市名不对）";
                            setStatus(msg);
                            if (onResult != null) onResult.accept(toolErr(msg));
                            return;
                        }

                        StringBuilder sb = new StringBuilder();
                        if (out.has("city")) sb.append(out.optString("city")).append(" ");
                        if (hasLive) {
                            sb.append(out.optString("weather")).append(" ")
                              .append(out.optString("temperature")).append("  ")
                              .append(out.optString("windDirection")).append(out.optString("windPower"))
                              .append("  湿度 ").append(out.optString("humidity"));
                        }
                        if (days.length() > 0) {
                            JSONObject d0 = days.optJSONObject(0);
                            if (d0 != null) {
                                sb.append(days.length() > 1 ? "  |  明天 " : "  |  ")
                                  .append(d0.optString("dayWeather")).append(" ")
                                  .append(d0.optString("nightTemp")).append("~")
                                  .append(d0.optString("dayTemp"));
                            }
                        }
                        final String human = sb.toString().trim();
                        setStatus(human);
                        try {
                            out.put("ok", true).put("summary", human);
                        } catch (Throwable ignored) { }
                        if (onResult != null) onResult.accept(out.toString());
                    });
                }
            });
            fs.searchWeatherAsyn();
        } catch (Throwable t) {
            String msg = "天气预报查询失败：" + t.getMessage();
            setStatus(msg);
            if (onResult != null) onResult.accept(toolErr(msg));
        }
    }

    private String lastCity = "";

    private void ensureNavi() throws Exception {
        AmapHolder.init(activity, apiKey);
        if (navi == null) {
            navi = AMapNavi.getInstance(activity.getApplicationContext());
            navi.addAMapNaviListener(naviListener);
            naviReady = false;
        }
    }

    private void runWhenReady(Runnable r) {
        if (naviReady) { r.run(); return; }
        pendingAction = r;
        ui.postDelayed(() -> {
            if (pendingAction == r) {

                pendingAction = null;
                String msg = "导航引擎还没准备好（可能是网络或 Key 配置问题），请稍后再试。";
                if (pendingNaviResult != null) { pendingNaviResult.accept(toolErr(msg)); pendingNaviResult = null; }
                if (pendingRouteResult != null) { pendingRouteResult.accept(toolErr(msg)); pendingRouteResult = null; }
                setStatus(msg);
            }
        }, 15000);
    }

    public void route(final double fromLat, final double fromLon,
                      final double toLat, final double toLon, final String mode,
                      final java.util.function.Consumer<String> onResult) {

        if ("bus".equalsIgnoreCase(mode) || "transit".equalsIgnoreCase(mode)) {
            routeBus(fromLat, fromLon, toLat, toLon, onResult);
            return;
        }
        ui.post(() -> {
            ensurePanel();
            raisePanel();
            setTitle("路线规划");
            setStatus("正在算路…");
            try {
                ensureNavi();
                pendingMode = mode == null ? "drive" : mode.toLowerCase();
                pendingRouteResult = onResult;
                pendingNaviResult = null;
                runWhenReady(() -> calcRoute(fromLat, fromLon, toLat, toLon));
            } catch (Throwable t) {
                String msg = "算路失败：" + t.getMessage();
                setStatus(msg);
                if (onResult != null) onResult.accept(toolErr(msg));
            }
        });
    }

    public void navigateTo(final double fromLat, final double fromLon,
                           final double toLat, final double toLon, final String mode,
                           final java.util.function.Consumer<String> onResult) {
        ui.post(() -> {
            ensurePanel();
            raisePanel();
            setTitle("导航");
            setStatus("正在规划路线…");
            try {
                ensureNavi();
                pendingMode = mode == null ? "drive" : mode.toLowerCase();
                pendingNaviResult = onResult;
                pendingRouteResult = null;
                runWhenReady(() -> calcRoute(fromLat, fromLon, toLat, toLon));
            } catch (Throwable t) {
                String msg = "导航失败：" + t.getMessage();
                setStatus(msg);
                if (onResult != null) onResult.accept(toolErr(msg));
            }
        });
    }

    private void calcRoute(double fromLat, double fromLon, double toLat, double toLon) {
        try {
            lastLat = toLat; lastLon = toLon;

            routeFromLat = fromLat; routeFromLon = fromLon;
            routeToLat = toLat; routeToLon = toLon;
            boolean ok;
            String m = pendingMode == null ? "drive" : pendingMode;
            if ("walk".equals(m)) {
                ok = navi.calculateWalkRoute(new NaviLatLng(fromLat, fromLon), new NaviLatLng(toLat, toLon));
            } else if ("ride".equals(m)) {
                ok = navi.calculateRideRoute(new NaviLatLng(fromLat, fromLon), new NaviLatLng(toLat, toLon));
            } else {
                List<NaviLatLng> from = new ArrayList<>();
                from.add(new NaviLatLng(fromLat, fromLon));
                List<NaviLatLng> to = new ArrayList<>();
                to.add(new NaviLatLng(toLat, toLon));
                ok = navi.calculateDriveRoute(from, to, null, 0 );
            }
            if (!ok) {
                String msg = "算路请求没发出去（起点/终点或网络有问题）";
                setStatus(msg);
                failPending(msg);
            }
        } catch (Throwable t) {
            String msg = "算路失败：" + t.getMessage();
            setStatus(msg);
            failPending(msg);
        }
    }

    private void failPending(String msg) {
        if (pendingNaviResult != null) { pendingNaviResult.accept(toolErr(msg)); pendingNaviResult = null; }
        if (pendingRouteResult != null) { pendingRouteResult.accept(toolErr(msg)); pendingRouteResult = null; }
    }

    public void routeBus(final double fromLat, final double fromLon,
                         final double toLat, final double toLon,
                         final java.util.function.Consumer<String> onResult) {
        ui.post(() -> {
            ensurePanel();
            raisePanel();
            setTitle("公交路线");
            setStatus("正在查公交…");
            try {
                AmapHolder.init(activity, apiKey);
                com.amap.api.services.route.RouteSearch rs =
                        new com.amap.api.services.route.RouteSearch(activity);
                com.amap.api.services.route.RouteSearch.FromAndTo fromTo =
                        new com.amap.api.services.route.RouteSearch.FromAndTo(
                                new LatLonPoint(fromLat, fromLon), new LatLonPoint(toLat, toLon));

                com.amap.api.services.route.RouteSearch.BusRouteQuery q =
                        new com.amap.api.services.route.RouteSearch.BusRouteQuery(
                                fromTo, com.amap.api.services.route.RouteSearch.BusDefault, "", 0);
                q.setCityd("");
                rs.setRouteSearchListener(new com.amap.api.services.route.RouteSearch.OnRouteSearchListener() {
                    @Override public void onBusRouteSearched(
                            com.amap.api.services.route.BusRouteResult result, int code) {
                        ui.post(() -> handleBusResult(result, code, onResult));
                    }
                    @Override public void onDriveRouteSearched(
                            com.amap.api.services.route.DriveRouteResult r, int c) { }
                    @Override public void onWalkRouteSearched(
                            com.amap.api.services.route.WalkRouteResult r, int c) { }
                    @Override public void onRideRouteSearched(
                            com.amap.api.services.route.RideRouteResult r, int c) { }
                });
                rs.calculateBusRouteAsyn(q);
            } catch (Throwable t) {
                String msg = "公交查询失败：" + t.getMessage();
                setStatus(msg);
                if (onResult != null) onResult.accept(toolErr(msg));
            }
        });
    }

    private void handleBusResult(com.amap.api.services.route.BusRouteResult result, int code,
                                 java.util.function.Consumer<String> onResult) {
        try {
            List<com.amap.api.services.route.BusPath> paths =
                    (result == null) ? null : result.getPaths();
            if (code != 1000 || paths == null || paths.isEmpty()) {
                String msg = "没查到公交方案" + amapSearchHint(code);
                setStatus(msg + (code == 1000 ? "" : "（错误码 " + code + "）"));
                if (onResult != null) onResult.accept(toolErr(msg + "（code=" + code + "）"));
                return;
            }

            com.amap.api.services.route.BusPath p = paths.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("公交全程 ").append(fmtDist(Math.round(p.getDistance())))
              .append("，约 ").append(Math.max(1, p.getDuration() / 60)).append(" 分钟");
            if (p.getCost() > 0) sb.append("，票价约 ").append((int) p.getCost()).append(" 元");
            sb.append("。");

            JSONArray segs = new JSONArray();
            int lineCount = 0;

            List<LatLng> poly = new ArrayList<>();
            List<com.amap.api.services.route.BusStep> steps = p.getSteps();
            if (steps != null) {
                for (com.amap.api.services.route.BusStep st : steps) {
                    if (st == null) continue;

                    com.amap.api.services.route.RouteBusWalkItem walk = st.getWalk();
                    if (walk != null && walk.getDistance() > 1) {
                        String line = "步行 " + fmtDist(Math.round(walk.getDistance()));
                        sb.append(" ").append(line).append("→");
                        collectPoly(walk.getPolyline(), poly);
                        try { segs.put(new JSONObject().put("type", "walk")
                                .put("distance", fmtDist(Math.round(walk.getDistance())))); } catch (Throwable ignored) { }
                    }

                    List<com.amap.api.services.route.RouteBusLineItem> lines = st.getBusLines();
                    if (lines == null || lines.isEmpty()) continue;
                    com.amap.api.services.route.RouteBusLineItem ln = lines.get(0);
                    String name = nz(ln.getBusLineName());
                    int i = name.indexOf('(');
                    if (i > 0) name = name.substring(0, i);
                    String dep = ln.getDepartureBusStation() == null ? ""
                            : nz(ln.getDepartureBusStation().getBusStationName());
                    String arr = ln.getArrivalBusStation() == null ? ""
                            : nz(ln.getArrivalBusStation().getBusStationName());
                    lineCount++;
                    sb.append("乘 ").append(name)
                      .append("（").append(dep).append(" → ").append(arr)
                      .append("，").append(ln.getPassStationNum()).append(" 站）→");
                    collectPoly(ln.getPolyline(), poly);
                    try {
                        JSONObject o = new JSONObject();
                        o.put("type", "bus");
                        o.put("line", name);
                        o.put("from", dep);
                        o.put("to", arr);
                        o.put("stops", ln.getPassStationNum());
                        segs.put(o);
                    } catch (Throwable ignored) { }
                }
            }
            String summary = sb.toString();
            if (summary.endsWith("→")) summary = summary.substring(0, summary.length() - 1);

            final String fs = summary;
            ui.post(() -> {
                if (aMap != null && !poly.isEmpty()) {
                    aMap.clear();
                    aMap.addPolyline(new PolylineOptions().addAll(poly)
                            .width(14f).color(0xFF2F6BE6));

                    aMap.addMarker(new MarkerOptions().position(poly.get(0)).title("起点"));
                    aMap.addMarker(new MarkerOptions().position(poly.get(poly.size() - 1)).title("终点"));

                    com.amap.api.maps.model.LatLngBounds.Builder bb =
                            new com.amap.api.maps.model.LatLngBounds.Builder();
                    for (LatLng ll : poly) bb.include(ll);
                    try {
                        aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bb.build(), dp(60)));
                    } catch (Throwable ignored) {
                        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(poly.get(0), 12f));
                    }
                }
                setStatus(fs);
            });

            if (onResult != null) {
                onResult.accept(new JSONObject().put("ok", true)
                        .put("mode", "bus")
                        .put("route", fs)
                        .put("busLines", lineCount)
                        .put("segments", segs)
                        .put("note", "The bus route has been drawn on the built-in map (blue line); the user can see it directly.").toString());
            }
        } catch (Throwable t) {
            String msg = "公交结果解析失败：" + t.getMessage();
            setStatus(msg);
            if (onResult != null) onResult.accept(toolErr(msg));
        }
    }

    private void collectPoly(List<LatLonPoint> src, List<LatLng> out) {
        if (src == null) return;
        for (LatLonPoint q : src) {
            if (q != null) out.add(new LatLng(q.getLatitude(), q.getLongitude()));
        }
    }

    private void enterNaviView() {
        ensurePanel();
        if (naviView == null) {
            naviView = new AMapNaviView(activity);

            panel.addView(naviView, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            naviView.onCreate(new Bundle());
            try {
                AMapNaviViewOptions opt = naviView.getViewOptions();

                opt.setAutoLockCar(true);
                try { naviView.setNaviMode(AMapNaviView.CAR_UP_MODE); }
                catch (Throwable ignored) { }
                opt.setAutoChangeZoom(false);
                opt.setLayoutVisible(true);
                opt.setTrafficBarEnabled(true);
                opt.setSettingMenuEnabled(false);

                try { opt.setRealCrossDisplayShow(true); } catch (Throwable ignored) { }
                try { opt.setLaneInfoShow(true); } catch (Throwable ignored) { }
                try { opt.setCompassEnabled(true); } catch (Throwable ignored) { }
            } catch (Throwable t) {
                dlog("导航视图选项设置失败: " + t);
            }
            naviView.setAMapNaviViewListener(naviViewListener);
        }
        naviView.setVisibility(View.VISIBLE);
        naviMode = true;
        if (statusText != null) statusText.setVisibility(View.GONE);
        if (listScroll != null) listScroll.setVisibility(View.GONE);
        try { naviView.onResume(); } catch (Throwable ignored) { }
    }

    private void exitNaviView() {
        naviMode = false;
        if (naviView != null) {
            try { naviView.onPause(); } catch (Throwable ignored) { }
            naviView.setVisibility(View.GONE);
        }
    }

    public void stopNavi(String note) {
        ui.post(() -> {
            try { if (navi != null) navi.stopNavi(); } catch (Throwable ignored) { }
            exitNaviView();
            setTitle("地图");
            setStatus(note);
            pushStatus(null);
        });
    }

    private void drawPath(AMapNaviPath path) {
        if (aMap == null || path == null) return;
        try {
            List<NaviLatLng> coords = path.getCoordList();
            if (coords == null || coords.isEmpty()) return;
            List<LatLng> pts = new ArrayList<>(coords.size());
            for (NaviLatLng n : coords) pts.add(new LatLng(n.getLatitude(), n.getLongitude()));
            aMap.clear();
            aMap.addPolyline(new PolylineOptions().addAll(pts).width(14f).color(0xFF2F6BE6));
            NaviLatLng s = path.getStartPoint(), e = path.getEndPoint();
            if (s != null) aMap.addMarker(new MarkerOptions()
                    .position(new LatLng(s.getLatitude(), s.getLongitude())).title("起点"));
            if (e != null) aMap.addMarker(new MarkerOptions()
                    .position(new LatLng(e.getLatitude(), e.getLongitude())).title("终点")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            LatLng mid = new LatLng((pts.get(0).latitude + pts.get(pts.size() - 1).latitude) / 2,
                    (pts.get(0).longitude + pts.get(pts.size() - 1).longitude) / 2);
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(mid, 12f));
        } catch (Throwable t) {
            dlog("画路线失败: " + t);
        }
    }

    private static String fmtDist(int meters) {
        if (meters < 1000) return meters + " 米";
        return String.format(java.util.Locale.CHINA, "%.1f 公里", meters / 1000.0);
    }

    private String pathSummary(AMapNaviPath path) {
        if (path == null) return "";
        String modeCn;
        if ("walk".equals(pendingMode)) modeCn = "步行";
        else if ("ride".equals(pendingMode)) modeCn = "骑行";
        else modeCn = "驾车";
        return String.format(java.util.Locale.CHINA, "%s全程 %s，约 %d 分钟",
                modeCn, fmtDist(path.getAllLength()), Math.max(1, path.getAllTime() / 60));
    }

    private final SimpleNaviListener naviListener = new SimpleNaviListener() {
        @Override public void onInitNaviSuccess() {
            naviReady = true;
            dlog("导航引擎就绪");
            Runnable r = pendingAction;
            pendingAction = null;
            if (r != null) ui.post(r);
        }

        @Override public void onInitNaviFailure() {
            naviReady = false;
            dlog("导航引擎初始化失败");
            String msg = "导航引擎初始化失败（检查高德 Key 是否 Android 平台 Key、包名/SHA1 是否匹配）";
            setStatus(msg);
            failPending(msg);
        }

        @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result) {
            ui.post(() -> onRouteOk());
        }

        @Override public void onCalculateRouteFailure(AMapCalcRouteResult result) {
            ui.post(() -> {
                int code = result == null ? 0 : result.getErrorCode();
                String msg = "算路失败"
                        + (result == null ? "" : "（code=" + code
                        + " " + nz(result.getErrorDescription()) + "）")
                        + amapRouteHint(code);
                setStatus(msg);
                failPending(msg);
            });
        }

        @Override public void onNaviInfoUpdate(NaviInfo info) {
            if (info == null) return;

            pushStatus(String.format(java.util.Locale.CHINA, "%s → %s，剩 %s（%d 分钟）",
                    nz(info.getCurrentRoadName()), nz(info.getNextRoadName()),
                    fmtDist(info.getPathRetainDistance()), Math.max(0, info.getPathRetainTime() / 60)));
        }

        @Override public void onArriveDestination() {
            ui.post(() -> {
                exitNaviView();
                setTitle("地图");
                setStatus("已到达目的地");
                pushStatus(null);
            });
        }

        @Override public void onEndEmulatorNavi() {
            ui.post(() -> {
                exitNaviView();
                setStatus("模拟导航结束");
                pushStatus(null);
            });
        }
    };

    private void onRouteOk() {
        try {
            AMapNaviPath path = (navi == null) ? null : navi.getNaviPath();
            String summary = pathSummary(path);

            JSONArray steps = buildStepList(path);
            boolean wantNavi = pendingNaviResult != null;
            String m = pendingMode == null ? "drive" : pendingMode;

            if (wantNavi && "drive".equals(m)) {
                enterNaviView();
                try { navi.startNavi(NaviType.GPS); }
                catch (Throwable t) { setStatus("启动导航失败：" + t.getMessage()); }
                if (pendingNaviResult != null) {
                    pendingNaviResult.accept(new JSONObject()
                            .put("ok", true).put("mode", "drive").put("route", summary)
                            .put("steps", steps)
                            .put("note", "Turn-by-turn navigation has started in the built-in map (with voice guidance); the user can see the navigation screen directly.").toString());
                    pendingNaviResult = null;
                }
                pushStatus(summary.isEmpty() ? "导航中" : summary);
            } else if (wantNavi) {

                exitNaviView();
                drawPath(path);
                setTitle(("walk".equals(m) ? "步行" : "骑行") + "路线");
                setStatus(summary);
                if (pendingNaviResult != null) {
                    pendingNaviResult.accept(new JSONObject()
                            .put("ok", true).put("mode", m).put("route", summary)
                            .put("steps", steps)
                            .put("note", "The route has been drawn on the built-in map (start / end / full route); the user can see it directly. "
                                    + "Note: the app's built-in turn-by-turn navigation only supports driving; for walking/cycling this shows the route with distance and time, not turn-by-turn guidance.").toString());
                    pendingNaviResult = null;
                }
            } else {
                exitNaviView();
                drawPath(path);
                setStatus(summary);
                if (pendingRouteResult != null) {
                    pendingRouteResult.accept(new JSONObject()
                            .put("ok", true).put("mode", m).put("route", summary)
                            .put("steps", steps)
                            .put("toll", path == null ? 0 : path.getTollCost())
                            .put("note", "The route has been drawn on the built-in map. To start turn-by-turn navigation (driving only), call map_navigate with the same destination.").toString());
                    pendingRouteResult = null;
                }
            }
        } catch (Throwable t) {
            String msg = "算路结果处理出错：" + t.getMessage();
            setStatus(msg);
            failPending(msg);
        }
    }

    private JSONArray buildStepList(AMapNaviPath path) {
        JSONArray arr = new JSONArray();
        if (path == null) return arr;
        try {
            List<com.amap.api.navi.model.AMapNaviStep> steps = path.getSteps();
            if (steps == null) return arr;
            int shown = 0;
            for (com.amap.api.navi.model.AMapNaviStep st : steps) {
                if (st == null || shown >= 12) break;

                StringBuilder roads = new StringBuilder();
                List<com.amap.api.navi.model.AMapNaviLink> links = st.getLinks();
                if (links != null) {
                    for (com.amap.api.navi.model.AMapNaviLink lk : links) {
                        if (lk == null) continue;
                        String rn = nz(lk.getRoadName()).trim();
                        if (rn.isEmpty()) continue;
                        if (roads.indexOf(rn) >= 0) continue;
                        if (roads.length() > 0) roads.append("/");
                        roads.append(rn);
                    }
                }
                JSONObject o = new JSONObject();
                String road = roads.toString();
                o.put("road", road.isEmpty() ? "(unnamed road)" : road);
                o.put("distance", fmtDist(st.getLength()));
                o.put("time", Math.max(1, st.getTime() / 60) + " min");

                o.put("turn", turnText(st.getIconType()));
                arr.put(o);
                shown++;
            }
            if (steps.size() > 12) {
                arr.put(new JSONObject().put("note", "(" + (steps.size() - 12) + " more steps follow, omitted)"));
            }
        } catch (Throwable ignored) { }
        return arr;
    }

    private static String turnText(int iconType) {
        switch (iconType) {
            case 0: return "continue";
            case 1: return "follow the navigation";
            case 2: return "turn left";
            case 3: return "turn right";
            case 4: return "bear left";
            case 5: return "bear right";
            case 6: return "sharp left";
            case 7: return "sharp right";
            case 8: return "make a U-turn";
            case 9: return "go straight";
            case 10: return "arrived at waypoint";
            case 11: return "enter the roundabout";
            case 12: return "exit the roundabout";
            case 13: return "arrived at service area";
            case 14: return "arrived at toll station";
            case 15: return "arrived at destination";
            case 16: return "enter tunnel";
            case 19: return "make a U-turn";
            case 20: return "keep going straight";
            case 29: return "cross the pedestrian crossing";
            case 30: return "get on the overpass";
            case 31: return "take the underpass";
            case 38: return "take the passage";
            case 45: return "cross the bridge";
            case 46: return "take the ferry";
            case 47: return "take the subway";
            default: return "follow the navigation";
        }
    }

    public boolean hasLocationPermission() {
        try {
            return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public void requestLocationPermission() {
        try {

            activity.runOnUiThread(() -> {
                try {
                    activity.requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, 0x9A17);
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
    }

    public void locateMe(final java.util.function.Consumer<String> onResult) {
        ui.post(() -> {
            ensurePanel();
            raisePanel();
            if (!hasLocationPermission()) {
                setStatus("还没有定位权限，正在申请…");
                requestLocationPermission();
                if (onResult != null) onResult.accept(toolErr(
                        "No location permission yet. The request has been shown on screen; once the user taps 允许 (Allow), just ask again."));
                return;
            }
            setStatus("正在定位…");
            try {
                AmapHolder.init(activity, apiKey);
                if (locClient == null) locClient = new AMapLocationClient(activity.getApplicationContext());
                AMapLocationClientOption opt = new AMapLocationClientOption();
                opt.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
                opt.setOnceLocationLatest(true);
                opt.setNeedAddress(true);
                locClient.setLocationOption(opt);
                final boolean[] done = new boolean[1];
                locClient.setLocationListener(loc -> {
                    if (loc == null || done[0]) return;
                    done[0] = true;
                    stopLocalClient();
                    ui.post(() -> {
                        if (loc.getErrorCode() != 0) {
                            String msg = "定位失败（" + loc.getErrorCode() + "：" + nz(loc.getErrorInfo()) + "）";
                            setStatus(msg);
                            if (onResult != null) onResult.accept(toolErr(msg));
                            return;
                        }
                        double lat = loc.getLatitude(), lon = loc.getLongitude();
                        lastLat = lat; lastLon = lon;
                        if (aMap != null) {
                            LatLng ll = new LatLng(lat, lon);
                            aMap.clear();
                            aMap.addMarker(new MarkerOptions().position(ll).title("我在这里")
                                    .icon(BitmapDescriptorFactory.defaultMarker(
                                            BitmapDescriptorFactory.HUE_AZURE)));
                            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(ll, 16f));
                        }
                        setTitle("我的位置");

                        String ct = nz(loc.getCity());
                        if (!ct.isEmpty()) lastCity = ct;
                        setStatus(nz(loc.getAddress()));
                        if (onResult != null) {
                            try {
                                onResult.accept(new JSONObject().put("ok", true)
                                        .put("lat", lat).put("lon", lon)
                                        .put("address", nz(loc.getAddress()))
                                        .put("city", nz(loc.getCity()))
                                        .put("note", "The user's current location has been marked on the built-in map.").toString());
                            } catch (Throwable ignored) { }
                        }
                    });
                });
                locClient.startLocation();

                ui.postDelayed(() -> {
                    if (done[0]) return;
                    done[0] = true;

                    stopLocalClient();
                    String msg = "定位超时（15 秒没拿到结果）：可能室内信号弱或系统定位没开。";
                    setStatus(msg);
                    if (onResult != null) onResult.accept(toolErr(msg));
                }, 15000);
            } catch (Throwable t) {
                String msg = "定位失败：" + t.getMessage();
                setStatus(msg);
                if (onResult != null) onResult.accept(toolErr(msg));
            }
        });
    }

    public void geocodeTo(final String address, final String city,
                          final java.util.function.Consumer<double[]> onResult,
                          final java.util.function.Consumer<String> onFail) {
        ui.post(() -> {
            try {
                AmapHolder.init(activity, apiKey);
                GeocodeSearch gs = new GeocodeSearch(activity);
                gs.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener() {
                    @Override public void onRegeocodeSearched(RegeocodeResult r, int code) { }
                    @Override public void onGeocodeSearched(GeocodeResult r, int code) {
                        ui.post(() -> {
                            try {
                                if (code == 1000 && r != null && r.getGeocodeAddressList() != null
                                        && !r.getGeocodeAddressList().isEmpty()) {
                                    LatLonPoint p = r.getGeocodeAddressList().get(0).getLatLonPoint();
                                    if (onResult != null) onResult.accept(new double[]{p.getLatitude(), p.getLongitude()});
                                } else {
                                    if (onFail != null) onFail.accept("Cannot find \"" + address + "\" (code=" + code + ") "
                                            + amapSearchHint(code));
                                }
                            } catch (Throwable t) {
                                if (onFail != null) onFail.accept("Geocoding error: " + t);
                            }
                        });
                    }
                });
                gs.getFromLocationNameAsyn(new GeocodeQuery(address, city == null ? "" : city));
            } catch (Throwable t) {
                if (onFail != null) onFail.accept("Geocoding failed: " + t);
            }
        });
    }

    public void firstPlaceOf(final String keyword, final String city,
                             final java.util.function.Consumer<double[]> onResult,
                             final java.util.function.Consumer<String> onFail) {
        ui.post(() -> {
            try {
                AmapHolder.init(activity, apiKey);
                PoiSearch.Query q = new PoiSearch.Query(keyword, "", city == null ? "" : city);
                q.setPageSize(5);
                q.setPageNum(1);
                PoiSearch poiSearch = new PoiSearch(activity, q);
                poiSearch.setOnPoiSearchListener(new PoiSearch.OnPoiSearchListener() {
                    @Override public void onPoiSearched(PoiResult result, int code) {
                        ui.post(() -> {
                            try {
                                List<PoiItem> items = (result == null) ? null : result.getPois();
                                if (code == 1000 && items != null && !items.isEmpty()
                                        && items.get(0).getLatLonPoint() != null) {
                                    LatLonPoint p = items.get(0).getLatLonPoint();
                                    if (onResult != null) onResult.accept(new double[]{p.getLatitude(), p.getLongitude()});
                                } else {
                                    if (onFail != null) onFail.accept("No search results for \"" + keyword + "\" (code=" + code + ") "
                                            + amapSearchHint(code));
                                }
                            } catch (Throwable t) {
                                if (onFail != null) onFail.accept("Search error: " + t);
                            }
                        });
                    }
                    @Override public void onPoiItemSearched(PoiItem item, int code) { }
                });
                poiSearch.searchPOIAsyn();
            } catch (Throwable t) {
                if (onFail != null) onFail.accept("Search failed: " + t);
            }
        });
    }

    private final AMapNaviViewListener naviViewListener = new AMapNaviViewListener() {
        @Override public void onNaviSetting() { }
        @Override public void onNaviCancel() { stopNavi("已退出导航"); }
        @Override public boolean onNaviBackClick() { stopNavi("已退出导航"); return true; }
        @Override public void onNaviMapMode(int i) { }
        @Override public void onNaviTurnClick() { }
        @Override public void onNextRoadClick() { }
        @Override public void onScanViewButtonClick() { }
        @Override public void onLockMap(boolean b) { }
        @Override public void onNaviViewLoaded() { }
        @Override public void onMapTypeChanged(int i) { }
        @Override public void onNaviViewShowMode(int i) { }
        @Override public void onStopSpeaking() { }
        @Override public void onViewTypeChanged(AmapPageType t) { }
        @Override public void onAMapNaviViewExit() { stopNavi("已退出导航"); }
        @Override public void onStrategyChanged(int i) { }
        @Override public void onBroadcastModeChanged(int i) { }
        @Override public void onDayAndNightModeChanged(int i) { }
        @Override public void onScaleAutoChanged(boolean b) { }
        @Override public void onListenToVoiceDuringCallChanged(boolean b) { }
        @Override public void onControlMusicVolumeModeChanged(int i) { }
        @Override public void onEagleChanged(boolean b) { }
        @Override public void onNaviRouteHighlightChange(long l, int i) { }
    };
}
