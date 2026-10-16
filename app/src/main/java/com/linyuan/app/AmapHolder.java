package com.linyuan.app;

import android.content.Context;

public final class AmapHolder {

    private static final String TAG = "VC_AMAP";

    private static boolean inited = false;
    private static String initedKey = null;

    private AmapHolder() { }

    public static synchronized void init(Context ctx, String apiKey) {
        if (ctx == null) return;
        String key = apiKey == null ? "" : apiKey.trim();
        if (inited && key.equals(initedKey)) return;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;

        try {
            com.amap.api.maps.MapsInitializer.updatePrivacyShow(app, true, true);
            com.amap.api.maps.MapsInitializer.updatePrivacyAgree(app, true);
        } catch (Throwable t) { dlog("地图隐私合规失败: " + t); }

        try {
            com.amap.api.navi.NaviSetting.updatePrivacyShow(app, true, true);
            com.amap.api.navi.NaviSetting.updatePrivacyAgree(app, true);
        } catch (Throwable t) { dlog("导航隐私合规失败: " + t); }

        try {
            com.amap.api.location.AMapLocationClient.updatePrivacyShow(app, true, true);
            com.amap.api.location.AMapLocationClient.updatePrivacyAgree(app, true);
        } catch (Throwable t) { dlog("定位隐私合规失败: " + t); }

        try {
            com.amap.api.services.core.ServiceSettings.updatePrivacyShow(app, true, true);
            com.amap.api.services.core.ServiceSettings.updatePrivacyAgree(app, true);
        } catch (Throwable t) { dlog("搜索隐私合规失败: " + t); }

        if (!key.isEmpty()) {
            try { com.amap.api.maps.MapsInitializer.setApiKey(key); }
            catch (Throwable t) { dlog("地图 Key 设置失败: " + t); }
            try { com.amap.api.navi.AMapNavi.setApiKey(app, key); }
            catch (Throwable t) { dlog("导航 Key 设置失败: " + t); }
            try { com.amap.api.location.AMapLocationClient.setApiKey(key); }
            catch (Throwable t) { dlog("定位 Key 设置失败: " + t); }
            try { com.amap.api.services.core.ServiceSettings.getInstance().setApiKey(key); }
            catch (Throwable t) { dlog("搜索 Key 设置失败: " + t); }
        }

        inited = true;
        initedKey = key;
        dlog("高德 SDK 初始化完成（Key " + (key.isEmpty() ? "空" : "已设置") + "）");
    }

    public static synchronized void ensurePrivacy(Context ctx) {
        if (!inited) init(ctx, null);
    }

    public static boolean isConfigured() {
        return inited && initedKey != null && !initedKey.isEmpty();
    }

    private static String cachedSha1 = null;

    public static String signingSha1(Context ctx) {
        if (cachedSha1 != null) return cachedSha1;
        if (ctx == null) return null;
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.Signature[] sigs = null;
            if (pi.signingInfo != null) {
                sigs = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
            }
            if (sigs != null && sigs.length > 0) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                byte[] d = md.digest(sigs[0].toByteArray());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < d.length; i++) {
                    if (i > 0) sb.append(':');
                    sb.append(String.format("%02X", d[i]));
                }
                cachedSha1 = sb.toString();
                return cachedSha1;
            }
        } catch (Throwable t) {
            dlog("读签名失败: " + t);
        }
        return null;
    }

    public static String signingHint(Context ctx) {
        String sha1 = signingSha1(ctx);
        if (sha1 == null) return "本机签名 SHA1 见设置页「内置地图」一栏（可点一下复制）";
        return "本机签名 SHA1 是 " + sha1 + "（设置页可点一下复制）";
    }

    private static void dlog(String msg) {
        try { android.util.Log.i(TAG, msg); } catch (Throwable ignored) { }
    }
}
