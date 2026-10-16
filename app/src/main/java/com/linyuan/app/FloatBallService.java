package com.linyuan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class FloatBallService extends Service {

    private static final int NOTIFY_ID = 9201;
    private static final String CHANNEL_ID = "float_ball";
    private static final float BALL_SIZE_DP = 52f;
    private static final float BALL_MARGIN_DP = 18f;

    private WindowManager windowManager;
    private View ballView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean dragMoved = false;

    private static volatile BallWaveView sBallWave;

    private static volatile FloatBallService sInstance;

    private static volatile boolean sAppInForeground = true;

    public static void hideBallForForeground() {
        sAppInForeground = true;
        FloatBallService inst = sInstance;
        if (inst != null) inst.mainHandler.post(inst::removeBall);
    }

    public static void showBallForBackground() {
        sAppInForeground = false;
        FloatBallService inst = sInstance;
        if (inst != null) inst.mainHandler.post(inst::removeBall);
    }

    public static void postLevel(float level) {
        BallWaveView v = sBallWave;
        if (v != null) v.setLevel(level);
    }

    public static void postLoading(boolean loading) {
        BallWaveView v = sBallWave;
        if (v != null) v.setLoading(loading);
    }

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, FloatBallService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {

        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, FloatBallService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
        try {
            startForeground(NOTIFY_ID, buildNotification());
        } catch (Exception e) {

            sInstance = null;
            stopSelf();
            return;
        }

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sBallWave = null;
        sInstance = null;
        removeBall();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFY_ID);
        super.onDestroy();
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this);
    }

    private void addBall() {

        removeBall();
    }

    private void removeBall() {
        if (ballView != null && windowManager != null) {
            try {
                windowManager.removeView(ballView);
            } catch (Exception ignored) {
            }
        }
        ballView = null;
    }

    private View buildBallView() {
        FrameLayout ball = new FrameLayout(this);

        ball.setElevation(dp(8));

        BallWaveView wave = new BallWaveView(this);
        FrameLayout.LayoutParams waveParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        wave.setLayoutParams(waveParams);
        ball.addView(wave);
        sBallWave = wave;

        final WindowManager.LayoutParams[] holder = new WindowManager.LayoutParams[1];
        ball.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragMoved = false;
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    if (holder[0] == null && windowManager != null) {
                        holder[0] = (WindowManager.LayoutParams) ball.getLayoutParams();
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (holder[0] != null) {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                            dragMoved = true;

                            holder[0].x -= (int) dx;
                            holder[0].y += (int) dy;
                            downRawX = event.getRawX();
                            downRawY = event.getRawY();
                            try {
                                windowManager.updateViewLayout(ball, holder[0]);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragMoved && holder[0] != null) {

                        int screenW = getResources().getDisplayMetrics().widthPixels;

                        float distRight = holder[0].x;
                        float distLeft = screenW - holder[0].x - dp(BALL_SIZE_DP);
                        if (distRight <= distLeft) {
                            holder[0].x = dp(BALL_MARGIN_DP);
                        } else {
                            holder[0].x = screenW - dp(BALL_SIZE_DP) - dp(BALL_MARGIN_DP);
                        }
                        try {
                            windowManager.updateViewLayout(ball, holder[0]);
                        } catch (Exception ignored) {
                        }
                        return true;
                    }
                    if (!dragMoved) {

                        v.performClick();
                    }
                    return true;
            }
            return false;
        });
        ball.setOnClickListener(v -> openApp());
        ball.setContentDescription("lin渊悬浮球，点按打开");
        return ball;
    }

    private float downRawX = 0, downRawY = 0;

    private void openApp() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("lin渊正在后台运行")
                .setContentText("通话中退出软件也能继续听和说；点这条通知可回到 App")
                .setContentIntent(pi)
                .setOngoing(true);
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "后台运行", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台保持运行（通话不中断）");
            nm.createNotificationChannel(channel);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
