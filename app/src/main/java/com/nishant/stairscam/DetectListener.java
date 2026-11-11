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
    public void onListenerConnected() {
        // Android (re)binds us after a reboot or update: a good moment to revive the camera watcher.
        if (Prefs.watchOn(this) && !WatchService.running) WatchService.sync(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!TAPO.equals(sbn.getPackageName())) return;
        Bundle x = sbn.getNotification().extras;
        String text = (str(x.getCharSequence(Notification.EXTRA_TITLE)) + " | "
                + str(x.getCharSequence(Notification.EXTRA_TEXT)) + " | "
                + str(x.getCharSequence(Notification.EXTRA_BIG_TEXT)) + " | "
                + str(x.getCharSequence(Notification.EXTRA_SUB_TEXT)));
        Prefs.setLastTapoAlert(this, sbn.getNotification().getChannelId() + " :: " + text);
        for (String k : x.keySet()) {
            Object v = x.get(k);
            String s = String.valueOf(v);
            Log.i(TAG, "  extra " + k + " (" + (v == null ? "null" : v.getClass().getSimpleName()) + ") = "
                    + (s.length() > 300 ? s.substring(0, 300) + "..." : s));
        }
        Log.i(TAG, "  category=" + sbn.getNotification().category + " group=" + sbn.getNotification().getGroup()
                + " tag=" + sbn.getTag() + " id=" + sbn.getId() + " key=" + sbn.getKey());
        Log.i(TAG, "Tapo notification: " + text);

        if (!Prefs.autoOpen(this) || !Prefs.configured(this)) return;
        if (!matches(text.toLowerCase(Locale.ROOT), Prefs.camName(this).toLowerCase(Locale.ROOT))) return;
        boolean person = isPersonAlert(text.toLowerCase(Locale.ROOT));
        int kind = Prefs.alertKind(this);
        if ((person && kind == Prefs.KIND_MOTION) || (!person && kind == Prefs.KIND_PERSON)) {
            Log.i(TAG, "Detection ignored: " + (person ? "person" : "motion") + " alert not selected");
            EventLog.add(this, "TAPO " + (person ? "person" : "motion") + " alert -> ignored (not selected)");
            return;
        }
        handleDetection(this, "TAPO " + (person ? "person" : "motion") + " alert");
    }

    /** Tapo's person-detection alerts say "person"; plain motion-zone alerts say "motion". */
    static boolean isPersonAlert(String lowerText) {
        return lowerText.contains("person") || lowerText.contains("people") || lowerText.contains("human");
    }

    /** Same path for a real Tapo alert and for the USB test trigger. */
    static void handleDetection(Context c) {
        handleDetection(c, "test trigger");
    }

    static void handleDetection(Context c, String source) {
        EventLog.add(c, source + " -> " + decide(c));
    }

    private static String decide(Context c) {
        if (!Settings.canDrawOverlays(c)) return "ignored (no overlay permission)";
        if (Prefs.isPaused(c)) {
            Log.i(TAG, "Detection ignored: paused until " + Prefs.clock(Prefs.pausedUntil(c)));
            return "ignored (paused until " + Prefs.clock(Prefs.pausedUntil(c)) + ")";
        }
        if (Prefs.inQuietHours(c)) {
            Log.i(TAG, "Detection ignored: quiet hours");
            return "ignored (quiet hours)";
        }
        // Cool-down only applies while no pop-up is open; an open one is simply kept alive.
        boolean popupOpen = FloatService.running || LockCamActivity.showing;
        if (!popupOpen && Prefs.inCooldown(c)) {
            Log.i(TAG, "Detection ignored: cool-down until " + Prefs.clock(Prefs.coolUntil(c)));
            return "ignored (cool-down until " + Prefs.clock(Prefs.coolUntil(c)) + ")";
        }
        boolean asleep = !c.getSystemService(PowerManager.class).isInteractive();
        boolean locked = c.getSystemService(KeyguardManager.class).isKeyguardLocked();
        Log.i(TAG, "Detection: asleep=" + asleep + " locked=" + locked);
        if (asleep || locked) {
            if (!Prefs.lockScreen(c)) return "ignored (lock-screen pop-ups off)";
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
            if (!popupOpen) Chime.playIfOn(c);
            return (popupOpen ? "kept open" : "popup") + " (lock screen)";
        }
        try {
            c.startForegroundService(new Intent(c, FloatService.class)
                    .putExtra(FloatService.EXTRA_AUTO, true));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open floating window", e);
            return "failed to open the floating window";
        }
        if (!popupOpen) Chime.playIfOn(c);
        return (popupOpen ? "kept open" : "popup") + " (floating)";
    }

    static final String ALERT_CHANNEL = "stairs_alert"; // "detect" was stuck at default importance
    static final int ALERT_ID = 2;

    private static void postLockScreenAlert(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel("detect");
        nm.deleteNotificationChannel("stairs_alert_quiet"); // a short-lived twin; ColorOS demoted it to default
        NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL, "Movement on the stairs",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(false);
        ch.setSound(null, null); // the chime (Settings) is the only sound; the screen waking is the alert
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
        NotificationChannel actual = nm.getNotificationChannel(ALERT_CHANNEL);
        Log.i(TAG, "Alert channel importance=" + (actual == null ? -1 : actual.getImportance()));
        PendingIntent show = PendingIntent.getActivity(c, 0,
                new Intent(c, LockCamActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = new Notification.Builder(c, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_cam_small)
                .setContentTitle("Movement on the stairs")
                .setContentText("Tap to watch the Stairs camera")
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(show)
                .setFullScreenIntent(show, true)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000);
        Notification n = nb.build();
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
