package com.nishant.stairscam;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small rolling activity log kept on the phone (files/events.log, newest at the end, ~200 KB max),
 * so what happened while the phone was not connected to a computer can be read back later.
 * One line per Tapo alert, camera-watch trigger and pop-up decision.
 */
final class EventLog {
    private EventLog() {}

    private static final long MAX_BYTES = 200_000;
    private static final Object LOCK = new Object();

    static void add(Context c, String line) {
        synchronized (LOCK) {
            try {
                File f = new File(c.getFilesDir(), "events.log");
                if (f.length() > MAX_BYTES) {
                    File old = new File(c.getFilesDir(), "events.old.log");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(old);
                }
                try (FileWriter w = new FileWriter(f, true)) {
                    w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + line + "\n");
                }
            } catch (Exception ignored) {
            }
        }
    }
}
