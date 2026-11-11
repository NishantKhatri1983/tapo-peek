package com.nishant.stairscam;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Camera connection settings, entered by the user in SettingsActivity. */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cam", Context.MODE_PRIVATE);
    }

    static String ip(Context c) { return sp(c).getString("ip", ""); }
    static String user(Context c) { return sp(c).getString("user", ""); }
    static String pass(Context c) { return sp(c).getString("pass", ""); }
    static boolean hd(Context c) { return sp(c).getBoolean("hd", false); }
    static boolean muted(Context c) { return sp(c).getBoolean("muted", true); }

    /** Floating window width/position in px; 0 / -1 mean "use the default". */
    static int floatW(Context c) { return sp(c).getInt("floatW", 0); }
    static int floatX(Context c) { return sp(c).getInt("floatX", -1); }
    static int floatY(Context c) { return sp(c).getInt("floatY", -1); }

    static void saveFloat(Context c, int w, int x, int y) {
        sp(c).edit().putInt("floatW", w).putInt("floatX", x).putInt("floatY", y).apply();
    }

    /** Auto-open the window when a Tapo detection alert names this camera. */
    static boolean autoOpen(Context c) { return sp(c).getBoolean("autoOpen", true); }
    /** When locked or asleep: wake the screen and show the camera over the lock screen. */
    static boolean lockScreen(Context c) { return sp(c).getBoolean("lockScreen", true); }
    static void setLockScreen(Context c, boolean on) { sp(c).edit().putBoolean("lockScreen", on).apply(); }

    /** Quiet hours: no automatic pop-ups. Times are minutes after midnight; may wrap midnight. */
    static boolean quietOn(Context c) { return sp(c).getBoolean("quietOn", true); }
    static int quietStart(Context c) { return sp(c).getInt("quietStart", 23 * 60); }
    static int quietEnd(Context c) { return sp(c).getInt("quietEnd", 7 * 60); }

    static void setQuiet(Context c, boolean on, int start, int end) {
        sp(c).edit().putBoolean("quietOn", on).putInt("quietStart", start).putInt("quietEnd", end).apply();
    }

    static boolean inQuietHours(Context c) {
        if (!quietOn(c)) return false;
        java.util.Calendar now = java.util.Calendar.getInstance();
        int m = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        int s = quietStart(c), e = quietEnd(c);
        return s <= e ? (m >= s && m < e) : (m >= s || m < e);
    }

    static String hhmm(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        return String.format(java.util.Locale.ROOT, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "AM" : "PM");
    }

    /** Pause ("do not disturb") for automatic pop-ups until a time; manual opening still works. */
    static long pausedUntil(Context c) { return sp(c).getLong("pausedUntil", 0); }
    static boolean isPaused(Context c) { return System.currentTimeMillis() < pausedUntil(c); }

    static void pauseFor(Context c, int minutes) {
        long until = minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
        sp(c).edit().putLong("pausedUntil", until).apply();
    }

    static String clock(long millis) {
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(millis));
    }

    /** Which Tapo alerts pop the window up: 0 = motion zone only, 1 = person zone only, 2 = both. */
    static final int KIND_MOTION = 0, KIND_PERSON = 1, KIND_BOTH = 2;
    static int alertKind(Context c) { return sp(c).getInt("alertKind", KIND_MOTION); }
    static void setAlertKind(Context c, int k) { sp(c).edit().putInt("alertKind", k).apply(); }

    /**
     * Cool-down: after a pop-up closes, ignore detections for a while so one visit does not
     * keep re-opening it. Separate lengths for "I closed it" and "it closed by itself" (ms; 0 = off).
     */
    static final long[] COOL_CHOICES_MS = {0, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000};
    static final String[] COOL_LABELS = {"Off", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes", "15 minutes"};
    static long coolManualMs(Context c) { return sp(c).getLong("coolManual", 60_000); }
    static long coolAutoMs(Context c) { return sp(c).getLong("coolAuto", 60_000); }
    static void setCool(Context c, boolean manual, long ms) {
        sp(c).edit().putLong(manual ? "coolManual" : "coolAuto", ms).apply();
    }
    static long coolUntil(Context c) { return sp(c).getLong("coolUntil", 0); }
    static boolean inCooldown(Context c) { return System.currentTimeMillis() < coolUntil(c); }

    /** Called when a pop-up window closes. {@code byTimer}: it closed by itself. */
    static void startCooldown(Context c, boolean byTimer) {
        long ms = byTimer ? coolAutoMs(c) : coolManualMs(c);
        sp(c).edit().putLong("coolUntil", ms > 0 ? System.currentTimeMillis() + ms : 0).apply();
    }

    static String coolLabel(long ms) {
        for (int i = 0; i < COOL_CHOICES_MS.length; i++) if (COOL_CHOICES_MS[i] == ms) return COOL_LABELS[i];
        return (ms / 1000) + " seconds";
    }

    /** Experimental: listen to the camera's own motion events (ONVIF). Off unless switched on. */
    static boolean watchOn(Context c) { return sp(c).getBoolean("watchOn", false); }
    static void setWatchOn(Context c, boolean on) { sp(c).edit().putBoolean("watchOn", on).apply(); }
    static String watchStatus(Context c) { return sp(c).getString("watchStatus", ""); }
    static void setWatchStatus(Context c, String s) { sp(c).edit().putString("watchStatus", s).apply(); }

    /**
     * Chime when a pop-up opens. Off until switched on. Sound: "" = system default notification
     * sound, "none" = silent, otherwise a sound URI picked with the system sound picker.
     */
    static boolean chimeOn(Context c) { return sp(c).getBoolean("chimeOn", false); }
    static void setChimeOn(Context c, boolean on) { sp(c).edit().putBoolean("chimeOn", on).apply(); }
    /** Play on the alarm volume instead of the notification volume (heard even when notifications are muted). */
    static boolean chimeLoud(Context c) { return sp(c).getBoolean("chimeLoud", false); }
    static void setChimeLoud(Context c, boolean on) { sp(c).edit().putBoolean("chimeLoud", on).apply(); }
    static String chimeUri(Context c) { return sp(c).getString("chimeUri", ""); }
    static void setChimeUri(Context c, String s) { sp(c).edit().putString("chimeUri", s).apply(); }

    /** Label shown on the second camera's pane. */
    static String name2(Context c) { return sp(c).getString("name2", "Camera 2"); }
    static void setName2(Context c, String s) { sp(c).edit().putString("name2", s.isEmpty() ? "Camera 2" : s).apply(); }

    static String camName(Context c) { return sp(c).getString("camName", "Stairs"); }
    static String lastTapoAlert(Context c) { return sp(c).getString("lastTapo", ""); }

    static void setAutoOpen(Context c, boolean on, String name) {
        sp(c).edit().putBoolean("autoOpen", on).putString("camName", name).apply();
    }

    static void setLastTapoAlert(Context c, String s) { sp(c).edit().putString("lastTapo", s).apply(); }

    static void setMuted(Context c, boolean m) { sp(c).edit().putBoolean("muted", m).apply(); }

    static void save(Context c, String ip, String user, String pass, boolean hd) {
        sp(c).edit().putString("ip", ip).putString("user", user).putString("pass", pass)
                .putBoolean("hd", hd).apply();
    }

    /** Optional second camera, shown under Stairs when a detection pops the window up. */
    static String ip2(Context c) { return sp(c).getString("ip2", ""); }
    static String user2Raw(Context c) { return sp(c).getString("user2", ""); }
    static String pass2Raw(Context c) { return sp(c).getString("pass2", ""); }

    static void saveCam2(Context c, String ip, String user, String pass) {
        sp(c).edit().putString("ip2", ip).putString("user2", user).putString("pass2", pass).apply();
    }

    static boolean hasCam2(Context c) { return configured(c) && !ip2(c).isEmpty(); }

    /** The second camera's own account if one was entered, otherwise the Stairs account. */
    static Uri rtsp2Uri(Context c) {
        boolean own = !user2Raw(c).isEmpty() && !pass2Raw(c).isEmpty();
        return rtspUri(ip2(c), own ? user2Raw(c) : user(c), own ? pass2Raw(c) : pass(c), hd(c));
    }

    static boolean configured(Context c) {
        return !ip(c).isEmpty() && !user(c).isEmpty() && !pass(c).isEmpty();
    }

    /** Tapo RTSP: stream1 = HD main stream, stream2 = SD sub stream. */
    static Uri rtspUri(Context c) {
        return rtspUri(ip(c), user(c), pass(c), hd(c));
    }

    static Uri rtspUri(String ip, String user, String pass, boolean hd) {
        String auth = Uri.encode(user) + ":" + Uri.encode(pass);
        return Uri.parse("rtsp://" + auth + "@" + ip + ":554/" + (hd ? "stream1" : "stream2"));
    }
}
