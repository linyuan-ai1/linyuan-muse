package com.linyuan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

public final class ChatNotifier {

    private ChatNotifier() { }

    public static final String CH_GROUP = "vc_gm_done";
    public static final String CH_CHAT = "vc_chat_reply";

    private static String channelIdFor(Context ctx, String baseId, boolean group) {
        Uri u = soundUri(ctx, group);
        if (u == null) return baseId;
        int h = u.toString().hashCode();
        return baseId + "_" + Integer.toHexString(h).replace("-", "n");
    }

    public static boolean isChannelBlocked(Context ctx, boolean group) {
        if (Build.VERSION.SDK_INT < 26) return false;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            String id = channelIdFor(ctx, group ? CH_GROUP : CH_CHAT, group);
            NotificationChannel ch = nm.getNotificationChannel(id);
            if (ch == null) return false;
            return ch.getImportance() == NotificationManager.IMPORTANCE_NONE;
        } catch (Exception e) {
            return false;
        }
    }

    public static final String KEY_GROUP_ENABLED = "groupNotifyEnabled";
    public static final String KEY_GROUP_SOUND = "groupNotifySound";
    public static final String KEY_CHAT_ENABLED = "chatNotifyEnabled";
    public static final String KEY_CHAT_SOUND = "chatNotifySound";

    public static boolean isEnabled(Context ctx, boolean group) {
        SharedPreferences sp = ctx.getSharedPreferences("voice_companion", Context.MODE_PRIVATE);
        return sp.getBoolean(group ? KEY_GROUP_ENABLED : KEY_CHAT_ENABLED, false);
    }

    public static final String KEY_GROUP_TONE = "groupNotifyToneIndex";
    public static final String KEY_CHAT_TONE = "chatNotifyToneIndex";

    public static int toneIndex(Context ctx, boolean group) {
        SharedPreferences sp = ctx.getSharedPreferences("voice_companion", Context.MODE_PRIVATE);
        int i = sp.getInt(group ? KEY_GROUP_TONE : KEY_CHAT_TONE, 0);
        return (i < 0 || i >= BUILTIN_TONES.length) ? 0 : i;
    }

    public static void setToneIndex(Context ctx, boolean group, int index) {
        int i = (index < 0 || index >= BUILTIN_TONES.length) ? 0 : index;
        ctx.getSharedPreferences("voice_companion", Context.MODE_PRIVATE)
                .edit().putInt(group ? KEY_GROUP_TONE : KEY_CHAT_TONE, i).apply();
    }

    public static String toneName(Context ctx, boolean group) {
        return builtinToneName(toneIndex(ctx, group));
    }

    @Deprecated
    public static Uri soundUri(Context ctx, boolean group) {
        SharedPreferences sp = ctx.getSharedPreferences("voice_companion", Context.MODE_PRIVATE);
        String s = sp.getString(group ? KEY_GROUP_SOUND : KEY_CHAT_SOUND, "");
        if (s == null || s.isEmpty()) return null;
        try {
            return Uri.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static Uri defaultSoundUri() {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    public static String soundName(Context ctx, Uri uri) {
        if (uri == null) return "跟随系统默认";
        try {
            Ringtone r = RingtoneManager.getRingtone(ctx, uri);
            String t = r == null ? null : r.getTitle(ctx);
            return (t == null || t.isEmpty()) ? "已选铃声" : t;
        } catch (Exception e) {
            return "已选铃声";
        }
    }

    public static void notifyGroupDone(Context ctx, long roomId, String title, String summary) {
        if (!isEnabled(ctx, true)) return;
        String t = (title == null || title.isEmpty()) ? "群聊" : title;
        String text = (summary == null || summary.trim().isEmpty()) ? "讨论已结束" : summary.trim();
        notify(ctx, CH_GROUP, "群聊讨论已完成", "💬 " + t + " · 讨论结束", text,
                (int) (roomId + 7000), groupSoundFor(ctx), true);
    }

    private static final int CHAT_NOTIFY_ID = 2_147_482_000;

    public static void notifyChatReply(Context ctx, String reply) {
        if (!isEnabled(ctx, false)) return;
        String text = reply == null ? "" : reply.trim();
        notify(ctx, CH_CHAT, "AI 回复", "🤖 lin渊", text,

                CHAT_NOTIFY_ID, groupSoundFor(ctx, false), false);
    }

    private static android.media.MediaPlayer tonePlayer;

    public static final String[][] BUILTIN_TONES = {
            {"叮（默认·清脆）",   "tone_ding",     "单音，最简洁"},
            {"叮咚（愉快）",       "tone_dingdong", "双音上行，轻快"},
            {"三音上行（正式）",    "tone_chime3",   "C-E-G，像提示音"},
            {"啵（轻量）",         "tone_pop",      "短促一声，几乎不打扰"},
            {"柔和（安静）",       "tone_soft",     "低沉温柔，适合夜间"},
            {"完成（四音上行）",    "tone_done",     "明确的\"事情做完了\""},
            {"提醒（两短一长）",    "tone_alert",    "有\"请看这里\"的意味"},
            {"极简（最低调）",      "tone_minimal",  "最轻的一声"},
    };

    public static int builtinToneRes(Context ctx, int index) {
        int i = (index < 0 || index >= BUILTIN_TONES.length) ? 0 : index;
        String name = BUILTIN_TONES[i][1];
        return ctx.getResources().getIdentifier(name, "raw", ctx.getPackageName());
    }

    public static String builtinToneName(int index) {
        int i = (index < 0 || index >= BUILTIN_TONES.length) ? 0 : index;
        return BUILTIN_TONES[i][0];
    }

    public static int builtinToneCount() {
        return BUILTIN_TONES.length;
    }

    public static void playAlertInApp(Context ctx, boolean group) {
        try {

            releaseTonePlayer();

            int resId = builtinToneRes(ctx, toneIndex(ctx, group));
            if (resId == 0) {
                android.util.Log.w("VC_NOTIFY", "自带音效资源缺失，跳过提示音");
                return;
            }
            tonePlayer = new android.media.MediaPlayer();
            tonePlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());

            android.content.res.AssetFileDescriptor toneAfd = ctx.getResources().openRawResourceFd(resId);
            if (toneAfd == null) {
                releaseTonePlayer();
                return;
            }
            try {
                tonePlayer.setDataSource(toneAfd);
            } finally {
                try { toneAfd.close(); } catch (Exception ignored) { }
            }
            tonePlayer.setOnCompletionListener(mp -> releaseTonePlayer());
            tonePlayer.setOnErrorListener((mp, what, extra) -> { releaseTonePlayer(); return true; });
            tonePlayer.prepare();
            tonePlayer.start();
            android.util.Log.i("VC_NOTIFY", "应用内提示音已播放（"
                    + (group ? "群聊" : "单聊") + "，音效=" + toneName(ctx, group) + "）");
        } catch (Exception e) {
            android.util.Log.w("VC_NOTIFY", "应用内提示音播放失败: " + e.getMessage());
            releaseTonePlayer();
        }
    }

    public static void releaseTonePlayer() {
        try {
            if (tonePlayer != null) {
                if (tonePlayer.isPlaying()) tonePlayer.stop();
                tonePlayer.release();
            }
        } catch (Exception ignored) {
        } finally {
            tonePlayer = null;
        }
    }

    public static void notifyGroupFailure(Context ctx, long roomId, String roomTitle,
                                          String roleName, String reason) {
        String t = (roomTitle == null || roomTitle.isEmpty()) ? "群聊" : roomTitle;
        String rn = (roleName == null || roleName.isEmpty()) ? "某个角色" : roleName;
        String why = (reason == null || reason.trim().isEmpty()) ? "请求失败" : reason.trim();
        String text = rn + " 请求失败，讨论已暂停。\n" + why
                + "\n\n回到群聊，卡片右下角点「🔄 重试」接着跑（会从它断掉的位置继续）。";
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("vc_gm_fail", "群聊请求失败",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("群聊里某个角色的模型请求失败（讨论被暂停）时提醒");
                ch.setSound(defaultSoundUri(),
                        new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                                .build());
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }

            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;

            PendingIntent pi = PendingIntent.getActivity(ctx, (int) (roomId + 7100), open, flags);

            Notification n = new Notification.Builder(ctx, "vc_gm_fail")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("⚠️ " + t + " · " + rn + " 请求失败")
                    .setContentText("讨论已暂停，点底部「▶ 继续」接着跑")
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify((int) (roomId + 7100), n);
        } catch (Exception e) {
            android.util.Log.w("VC_NOTIFY", "失败通知发送失败: " + e.getMessage());
        }
    }

    private static Uri groupSoundFor(Context ctx) { return soundUri(ctx, true); }
    private static Uri groupSoundFor(Context ctx, boolean group) { return soundUri(ctx, group); }

    private static void notify(Context ctx, String channelId, String channelName, String title,
                               String text, int id, Uri sound, boolean group) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= 26) {

                channelId = channelIdFor(ctx, channelId, group);
                NotificationChannel ch = new NotificationChannel(channelId, channelName,
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription(group ? "群聊讨论结束提醒" : "单聊回复提醒");

                Uri s = sound;
                if (s != null) {
                    android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    ch.setSound(s, attrs);
                } else {
                    ch.setSound(defaultSoundUri(),
                            new android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                                    .build());
                }
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }

            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, id, open, flags);

            String shortText = text.length() > 120 ? text.substring(0, 120) + "…" : text;
            Notification n = new Notification.Builder(ctx, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(shortText)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(id, n);

            if (Build.VERSION.SDK_INT < 26) {
                playSound(ctx, sound);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_NOTIFY", "通知失败: " + e.getMessage());
        }
    }

    public static void previewTone(Context ctx, int index) {
        try {
            releaseTonePlayer();
            int resId = builtinToneRes(ctx, index);
            if (resId == 0) return;
            tonePlayer = new android.media.MediaPlayer();
            tonePlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());

            android.content.res.AssetFileDescriptor toneAfd = ctx.getResources().openRawResourceFd(resId);
            if (toneAfd == null) {
                releaseTonePlayer();
                return;
            }
            try {
                tonePlayer.setDataSource(toneAfd);
            } finally {
                try { toneAfd.close(); } catch (Exception ignored) { }
            }
            tonePlayer.setOnCompletionListener(mp -> releaseTonePlayer());
            tonePlayer.setOnErrorListener((mp, what, extra) -> { releaseTonePlayer(); return true; });
            tonePlayer.prepare();
            tonePlayer.start();
        } catch (Exception e) {
            android.util.Log.w("VC_NOTIFY", "试听失败: " + e.getMessage());
            releaseTonePlayer();
        }
    }

    @Deprecated
    public static void playSound(Context ctx, Uri sound) {
        previewTone(ctx, toneIndex(ctx, false));
    }
}
