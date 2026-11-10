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
