package com.linyuan.app;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

public class PhoneNotificationListener extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) return;
            String pkg = sbn.getPackageName();
            if (TextUtils.isEmpty(pkg)) return;

            if (pkg.equals(getPackageName())) return;

            AutoReply.onNotification(this, pkg);

            if (!PhoneAwareness.enabled(this) || !PhoneAwareness.notifOn(this)) return;

            Notification n = sbn.getNotification();
            Bundle ex = n.extras;
            String title = ex == null ? null : str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text = ex == null ? null : str(ex.getCharSequence(Notification.EXTRA_TEXT));
            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) {
                text = ex == null ? null : str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            }
            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return;
            boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            String cat = n.category;
            PhoneAwareness.recordNotification(this, PhoneAwareness.appLabel(this, pkg),
                    title, text, cat, ongoing, sbn.getPostTime());
        } catch (Throwable ignored) { }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) { }

    public static volatile boolean connected = false;

    public static boolean granted(Context c) {
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            if (TextUtils.isEmpty(flat)) return false;
            String me = new ComponentName(c, PhoneNotificationListener.class).flattenToString();
            String me2 = new ComponentName(c, PhoneNotificationListener.class).flattenToShortString();
            return flat.contains(me) || flat.contains(me2) || flat.contains(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static void openSettings(Context c) {
        try {
            android.content.Intent i;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                i = new android.content.Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS");
                i.putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                        new ComponentName(c, PhoneNotificationListener.class).flattenToString());
            } else {
                i = new android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                android.content.Intent i = new android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable ignored) { }
        }
    }

    static java.util.List<android.media.session.MediaController> sessions(Context c) {
        android.media.session.MediaSessionManager msm =
                (android.media.session.MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return null;
        return msm.getActiveSessions(new ComponentName(c, PhoneNotificationListener.class));
    }

    public static android.media.session.MediaController activeController(Context c) {
        try {
            java.util.List<android.media.session.MediaController> list = sessions(c);
            if (list == null || list.isEmpty()) return null;
            for (android.media.session.MediaController mc : list) {
                android.media.session.PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) return mc;
            }
            return list.get(0);
        } catch (Throwable t) {
            android.util.Log.w("VC_MEDIA", "取媒体会话失败: " + t);
            return null;
        }
    }

    public static int sessionCount(Context c) {
        try {
            java.util.List<android.media.session.MediaController> list = sessions(c);
            return list == null ? -1 : list.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String nowPlaying(Context c) {
        android.media.session.MediaController mc = activeController(c);
        if (mc == null) return null;
        try {
            StringBuilder sb = new StringBuilder();
            CharSequence pkg = mc.getPackageName();
            sb.append(PhoneAwareness.appLabel(c, pkg == null ? null : pkg.toString()));
            android.media.MediaMetadata md = mc.getMetadata();
            if (md != null) {
                CharSequence title = md.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
                CharSequence artist = md.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                if (title != null) {
                    sb.append(" · ").append(title);
                    if (artist != null && artist.length() > 0) sb.append(" — ").append(artist);
                }
            }
            android.media.session.PlaybackState st = mc.getPlaybackState();
            sb.append("（").append(playStateText(st == null ? -1 : st.getState())).append("）");
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static String control(Context c, String action) {
        String a = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.isEmpty()) return "要指定动作：play / pause / toggle / next / prev / stop。";
        android.media.session.MediaController mc;
        int cnt;
        try {
            cnt = sessionCount(c);
            mc = activeController(c);
        } catch (Throwable t) {
            return "拿媒体会话时被系统拒绝：" + t.getMessage()
                    + "（媒体控制需要「通知使用权」，设置 → 手机感知 → 通知使用权）";
        }
        if (mc == null) {
            if (cnt < 0) {
                return "系统没让我看媒体会话（可能「通知使用权」被关了或系统刚重启）。"
                        + "去「设置 → 手机感知 → 通知使用权」确认一下，再让我试。";
            }
            return "系统现在没有任何可控制的媒体会话（我这边能看到 0 个）——"
                    + "也就是没有音乐/视频在播放，或者那个 App 没把会话开放给通知监听。"
                    + (granted(c) ? "" : "另外「通知使用权」还没给。");
        }
        try {
            android.media.session.MediaController.TransportControls tc = mc.getTransportControls();
            String what;
            switch (a) {
                case "play": tc.play(); what = "已播放"; break;
                case "pause": tc.pause(); what = "已暂停"; break;
                case "toggle": tc.play(); what = "已切换播放"; break;
                case "next": tc.skipToNext(); what = "已切到下一首"; break;
                case "prev": case "previous": tc.skipToPrevious(); what = "已回到上一首"; break;
                case "stop": tc.stop(); what = "已停止"; break;
                case "forward": tc.fastForward(); what = "已快进"; break;
                case "rewind": tc.rewind(); what = "已快退"; break;
                default: return "不认识的动作：" + action + "（可用 play/pause/toggle/next/prev/stop/forward/rewind）";
            }
            return what + " —— " + (nowPlaying(c) == null ? "" : nowPlaying(c));
        } catch (Throwable t) {
            return "控制失败：" + t.getMessage();
        }
    }

    private static String playStateText(int state) {
        switch (state) {
            case android.media.session.PlaybackState.STATE_PLAYING: return "正在播放";
            case android.media.session.PlaybackState.STATE_PAUSED: return "已暂停";
            case android.media.session.PlaybackState.STATE_STOPPED: return "已停止";
            case android.media.session.PlaybackState.STATE_BUFFERING: return "缓冲中";
            default: return "空闲";
        }
    }

    private static String str(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim().replace('\n', ' ');
        return s.isEmpty() ? null : s;
    }
}
