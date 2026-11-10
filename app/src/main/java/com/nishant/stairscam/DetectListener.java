package com.nishant.stairscam;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Locale;

/**
 * Watches the Tapo app's notifications. When one is about the Stairs camera detecting something
 * (person / motion / ...), pops up the floating camera window.
 */
public class DetectListener extends NotificationListenerService {
    private static final String TAG = "StairsDetect";
    private static final String TAPO = "com.tplink.iot";
    private static final String[] DETECTION_WORDS = {
            "detect", "motion", "person", "people", "someone", "movement", "human",
            "pet", "vehicle", "crossing", "intrusion", "loiter"
    };
    private static final String[] IGNORE_WORDS = {"offline", "online", "firmware", "update", "storage", "sd card"};

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!TAPO.equals(sbn.getPackageName())) return;
        Bundle x = sbn.getNotification().extras;
        String text = (str(x.getCharSequence(Notification.EXTRA_TITLE)) + " | "
                + str(x.getCharSequence(Notification.EXTRA_TEXT)) + " | "
                + str(x.getCharSequence(Notification.EXTRA_BIG_TEXT)) + " | "
                + str(x.getCharSequence(Notification.EXTRA_SUB_TEXT)));
        Prefs.setLastTapoAlert(this, sbn.getNotification().getChannelId() + " :: " + text);
        Log.i(TAG, "Tapo notification: " + text);

        if (!Prefs.autoOpen(this) || !Prefs.configured(this)) return;
        if (!matches(text.toLowerCase(Locale.ROOT), Prefs.camName(this).toLowerCase(Locale.ROOT))) return;
        handleDetection(this);
    }

    /** Same path for a real Tapo alert and for the USB test trigger. */
    static void handleDetection(Context c) {
        if (!Settings.canDrawOverlays(c)) return;
        if (Prefs.isPaused(c)) {
            Log.i(TAG, "Detection ignored: paused until " + Prefs.clock(Prefs.pausedUntil(c)));
            return;
        }
        if (Prefs.inQuietHours(c)) {
            Log.i(TAG, "Detection ignored: quiet hours");
            return;
        }
        boolean asleep = !c.getSystemService(PowerManager.class).isInteractive();
        boolean locked = c.getSystemService(KeyguardManager.class).isKeyguardLocked();
        Log.i(TAG, "Detection: asleep=" + asleep + " locked=" + locked);
        if (asleep || locked) {
            if (!Prefs.lockScreen(c)) return;
            // Floating windows can't appear over the lock screen, and ColorOS blocks background
            // activity starts, so use a call-style full-screen notification to wake the screen.
            // Direct start works once ColorOS allows Stairs to open windows in the background;
            // the notification is the fallback (LockCamActivity cancels it when it opens).
            try {
                c.startActivity(new Intent(c, LockCamActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } catch (RuntimeException e) {
                Log.w(TAG, "Direct lock-screen start failed", e);
            }
            postLockScreenAlert(c);
            return;
        }
        try {
            c.startForegroundService(new Intent(c, FloatService.class)
                    .putExtra(FloatService.EXTRA_AUTO, true));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open floating window", e);
        }
    }

    static final String ALERT_CHANNEL = "stairs_alert"; // "detect" was stuck at default importance
    static final int ALERT_ID = 2;

    private static void postLockScreenAlert(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel("detect");
        NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL, "Movement on the stairs",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(false);
        nm.createNotificationChannel(ch);
        NotificationChannel actual = nm.getNotificationChannel(ALERT_CHANNEL);
        Log.i(TAG, "Alert channel importance=" + (actual == null ? -1 : actual.getImportance()));
        PendingIntent show = PendingIntent.getActivity(c, 0,
                new Intent(c, LockCamActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_cam_small)
                .setContentTitle("Movement on the stairs")
                .setContentText("Tap to watch the Stairs camera")
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(show)
                .setFullScreenIntent(show, true)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .build();
        try {
            nm.notify(ALERT_ID, n);
            Log.i(TAG, "Posted lock-screen alert");
        } catch (SecurityException e) {
            Log.w(TAG, "Notifications not allowed", e);
        }
    }

    static boolean matches(String text, String camName) {
        if (camName.isEmpty() || !text.contains(camName)) return false;
        for (String w : IGNORE_WORDS) if (text.contains(w)) return false;
        for (String w : DETECTION_WORDS) if (text.contains(w)) return true;
        return false;
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
