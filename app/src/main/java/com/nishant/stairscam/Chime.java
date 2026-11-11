package com.nishant.stairscam;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * The pop-up chime: plays the chosen system notification sound once. Uses the notification audio
 * stream, so silent mode / Do Not Disturb and the notification volume apply, or (optionally) the
 * alarm stream, which is heard even with notifications muted.
 */
final class Chime {
    private Chime() {}

    private static final String TAG = "StairsChime";
    private static final long MAX_PLAY_MS = 6_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Ringtone current;
    private static final Runnable STOP = Chime::stop;

    /** Plays the chime if it is switched on. */
    static void playIfOn(Context c) {
        if (Prefs.chimeOn(c)) play(c);
    }

    /** Plays the chosen sound now (also used by the Test button). */
    static void play(Context c) {
        Context app = c.getApplicationContext();
        MAIN.post(() -> {
            stop();
            Uri uri = uri(app);
            if (uri == null) return;
            try {
                Ringtone r = RingtoneManager.getRingtone(app, uri);
                if (r == null) return;
                r.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(Prefs.chimeLoud(app) ? AudioAttributes.USAGE_ALARM
                                : AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                r.setLooping(false);
                r.play();
                current = r;
                MAIN.postDelayed(STOP, MAX_PLAY_MS);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not play the chime", e);
            }
        });
    }

    private static void stop() {
        MAIN.removeCallbacks(STOP);
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException ignored) {
            }
            current = null;
        }
    }

    /** The sound to play, or null for "silent". */
    static Uri uri(Context c) {
        String s = Prefs.chimeUri(c);
        if (s.equals("none")) return null;
        if (s.isEmpty()) return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return Uri.parse(s);
    }

    /** Name for the settings button, e.g. "Chime sound: Pixie Dust". */
    static String title(Context c) {
        String s = Prefs.chimeUri(c);
        if (s.equals("none")) return "Chime sound: silent";
        Uri u = s.isEmpty() ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) : Uri.parse(s);
        String name = null;
        try {
            Ringtone r = RingtoneManager.getRingtone(c, u);
            if (r != null) name = r.getTitle(c);
        } catch (RuntimeException ignored) {
        }
        if (name == null || name.isEmpty()) name = "default";
        return "Chime sound: " + (s.isEmpty() ? name + " (default)" : name);
    }

    /** Intent for the system sound picker, showing the current choice. */
    static Intent pickerIntent(Context c) {
        String s = Prefs.chimeUri(c);
        Uri existing = s.equals("none") ? null
                : s.isEmpty() ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) : Uri.parse(s);
        return new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pop-up chime");
    }

    /** Saves the picker's result. */
    @SuppressWarnings("deprecation")
    static void savePicked(Context c, Intent data) {
        if (data == null) return;
        Uri u = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        if (u == null) Prefs.setChimeUri(c, "none");
        else if (u.equals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))) Prefs.setChimeUri(c, "");
        else Prefs.setChimeUri(c, u.toString());
    }
}
