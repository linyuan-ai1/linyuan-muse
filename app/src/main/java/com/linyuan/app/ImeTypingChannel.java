package com.linyuan.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public final class ImeTypingChannel {

    private ImeTypingChannel() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static String type(Context ctx, AppSettings settings, String input, int speed, boolean wantSend) {
        if (ctx == null || input == null || input.isEmpty()) return null;

        if (!LinYuanImeService.isEnabledInSystem(ctx)) return null;

        final boolean canSilent = ImeSwitcher.canSwitchSilently(ctx);
        final boolean alreadyOurs = LinYuanImeService.isCurrentInSystem(ctx);

        if (canSilent) {

            if (!alreadyOurs && !ImeSwitcher.switchToOurs(ctx)) return null;
        } else if (!alreadyOurs) {

            if (ctx instanceof android.app.Activity) {
                MAIN.post(() -> ImeSwitcher.showPicker(ctx));
                return err("This input field cannot be read (apps like 微信 block accessibility reading). The IME picker has been opened for you - "
                        + "tap \"lin渊打字通道\", then let me try again. "
                        + "(To make this fully automatic with no popup: connect the phone to a computer and run the one-time adb authorization; see the instructions on the settings page)");
            }
            return null;
        }

        if (!alreadyOurs) {
            try { Thread.sleep(700); } catch (InterruptedException ignored) { }
        }

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] result = new int[]{-1};
        final String[] reason = new String[]{null};

        final java.util.concurrent.CountDownLatch sendLatch =
                wantSend ? new java.util.concurrent.CountDownLatch(1) : null;
        final boolean[] sendOk = new boolean[]{false};
        final String[] sendDetail = new String[]{null};
        if (wantSend) {
            LinYuanImeService.setSendCallback((sent, detail) -> {
                sendOk[0] = sent;
                sendDetail[0] = detail;
                sendLatch.countDown();
            });
        }

        MAIN.post(() -> {
            LinYuanImeService svc = LinYuanImeService.instance();
            if (svc == null) {
                latch.countDown();
                return;
            }
            svc.typeTextAsync(input, speed, wantSend, (d, r) -> {
                result[0] = d;
                reason[0] = r;
                latch.countDown();
            });
        });

        boolean finished;
        try {

            finished = latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            finished = false;
        }

        if (!finished) return err("The typing channel timed out (no response in 15s); please retry");

        boolean typedAll = result[0] >= 0 && result[0] == input.length();
        if (!typedAll) {
            if (result[0] > 0) {
                return err("Only the first " + result[0] + "/" + input.length() + " characters got typed ("
                        + (reason[0] == null ? "the input field interrupted" : reason[0]) + ")");
            }
            return err("The typing channel could not type either (" + (reason[0] == null
                    ? "no typeable input field - tap the input field first so the cursor appears" : reason[0]) + ")");
        }

        if (wantSend) {
            boolean gotSend;
            try {
                gotSend = sendLatch.await(8, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                gotSend = false;
            }
            if (sendOk[0]) {
                return ok("Typed character by character and **sent successfully**: " + input
                        + (canSilent ? " (your IME was automatically restored after typing)" : ""));
            }

            String d = sendDetail[0] != null ? sendDetail[0]
                    : (gotSend ? "the input field does not accept the send action" : "send verification timed out");
            String extra = (d.contains("发送按钮在应用自己手里"))
                    ? "-> This app keeps the send button in its own hands (微信 does this); the only way is to tap the on-screen send button, "
                      + "or let the user press it. The text has already been typed in - you can locate the send button by screen coordinates and tap it."
                    : "";
            return err("The text was typed in, but **sending failed**: " + d + extra);
        }

        return ok("Typed character by character: " + input + (canSilent ? " (your IME was automatically restored after typing)" : ""));
    }

    private static String ok(String msg) {
        try { return new org.json.JSONObject().put("success", true).put("message", msg).toString(); }
        catch (Exception e) { return "{\"success\":true}"; }
    }

    private static String err(String msg) {
        try { return new org.json.JSONObject().put("success", false).put("error", msg).toString(); }
        catch (Exception e) { return "{\"success\":false}"; }
    }
}
