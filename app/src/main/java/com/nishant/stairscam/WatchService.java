package com.nishant.stairscam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional ("Watch the camera directly", off by default): keeps a PullPoint event subscription open
 * to the camera and treats its own motion events like a Tapo alert. Catches movement Tapo does not
 * alert on (for example someone coming back a few seconds after leaving).
 *
 * Cheap on purpose: the phone only wakes when the camera answers a short poll; no wake lock.
 */
public class WatchService extends Service {
    private static final String TAG = "StairsWatch";
    private static final String CHANNEL = "watch";
    private static final int NOTIF_ID = 4;
    // Short on purpose: this camera seems to hand over new events only at the end of a poll, so a
    // long wait means a late pop-up (a 10 s wait gave ~7 s late events; 5 s still gave 7-10 s late ones).
    private static final int POLL_SECONDS = 2;
    private static final long RESUBSCRIBE_MS = 90_000;
    private static final long MIN_TRIGGER_GAP_MS = 3_000;

    static volatile boolean running;
    private volatile boolean stop;
    private volatile long lagMs;
    private Thread worker;

    static void sync(Context c) {
        if (Prefs.watchOn(c) && Prefs.configured(c)) {
            try {
                c.startForegroundService(new Intent(c, WatchService.class));
            } catch (RuntimeException e) {
                Log.w(TAG, "could not start watcher", e);
            }
        } else {
            c.stopService(new Intent(c, WatchService.class));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.watchOn(this) || !Prefs.configured(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Camera watch",
                NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cam_small)
                .setContentTitle("Watching the Stairs camera")
                .setContentText("Tap to open settings")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        running = true;
        if (worker == null || !worker.isAlive()) {
            stop = false;
            worker = new Thread(this::loop, "stairs-watch");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        if (worker != null) worker.interrupt();
        Prefs.setWatchStatus(this, "");
        super.onDestroy();
    }

    /** e.g. "battery 87%, on battery" - lets the battery cost of the watcher be read back later. */
    private String batteryText() {
        android.content.Intent b = registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return "battery ?";
        int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        boolean plugged = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return "battery " + (level * 100 / Math.max(1, scale)) + "%, " + (plugged ? "charging" : "on battery");
    }

    private void status(String s) {
        Prefs.setWatchStatus(this, s);
    }

    private void loop() {
        String pullUrl = null;
        long subscribedAt = 0;
        long lastTrigger = 0;
        int failures = 0;
        long polls = 0, lastBeat = 0, lastLogged = 0;
        EventLog.add(this, "WATCH started, " + batteryText());
        while (!stop && Prefs.watchOn(this)) {
            try {
                if (Prefs.isPaused(this) || Prefs.inQuietHours(this)) {
                    // No pop-ups wanted right now: stay idle, drop the subscription.
                    pullUrl = null;
                    status("Idle (pop-ups paused or quiet hours)");
                    Thread.sleep(30_000);
                    continue;
                }
                if (pullUrl == null || System.currentTimeMillis() - subscribedAt > RESUBSCRIBE_MS) {
                    pullUrl = subscribe();
                    subscribedAt = System.currentTimeMillis();
                    if (failures > 0) {
                        Log.i(TAG, "connected again");
                        EventLog.add(this, "WATCH camera reachable again");
                    }
                    failures = 0;
                    status("Connected to the camera");
                }
                String reply = Onvif.post(pullUrl, this,
                        "<s:Body><tev:PullMessages><tev:Timeout>PT" + POLL_SECONDS + "S</tev:Timeout>"
                                + "<tev:MessageLimit>50</tev:MessageLimit></tev:PullMessages></s:Body>",
                        (POLL_SECONDS + 10) * 1000);
                if (!reply.contains("PullMessagesResponse")) throw new IllegalStateException("bad reply");
                polls++;
                if (System.currentTimeMillis() - lastBeat > 60_000) {
                    lastBeat = System.currentTimeMillis();
                    if (lastBeat - lastLogged > 1_800_000) {
                        lastLogged = lastBeat;
                        EventLog.add(this, "WATCH alive, " + polls + " polls since start, " + batteryText());
                    }
                    Log.i(TAG, "alive: " + polls + " polls so far, subscription age "
                            + (lastBeat - subscribedAt) / 1000 + " s");
                }
                if (motionIn(reply)) {
                    long now = System.currentTimeMillis();
                    if (now - lastTrigger >= MIN_TRIGGER_GAP_MS) {
                        lastTrigger = now;
                        Log.i(TAG, "Camera reported movement");
                        status("Last movement seen " + Prefs.clock(now));
                        DetectListener.handleDetection(this, "WATCH camera movement (event "
                                + Math.max(0, lagMs) / 1000 + " s old)");
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                pullUrl = null;
                failures++;
                long wait = Math.min(60, 3L << Math.min(failures, 5));
                Log.w(TAG, "camera not reachable (" + e + "), retry in " + wait + "s");
                if (failures == 1) EventLog.add(this, "WATCH camera not reachable (" + e.getClass().getSimpleName() + ")");
                status("Camera not reachable, retrying");
                try {
                    Thread.sleep(wait * 1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
        stopSelf();
    }

    private String subscribe() throws Exception {
        String r = Onvif.post(Onvif.eventsUrl(this), this,
                "<s:Body><tev:CreatePullPointSubscription><tev:InitialTerminationTime>PT"
                        + (RESUBSCRIBE_MS / 1000 + 60)
                        + "S</tev:InitialTerminationTime></tev:CreatePullPointSubscription></s:Body>", 8000);
        Matcher m = Pattern.compile("Address[^>]*>(http[^<]+)<").matcher(r);
        if (!m.find()) throw new IllegalStateException("no subscription address");
        return m.group(1);
    }

    private static final Pattern MESSAGE = Pattern.compile(
            "<wsnt:NotificationMessage>(.*?)</wsnt:NotificationMessage>", Pattern.DOTALL);
    private static final Pattern TOPIC = Pattern.compile("Topic[^>]*>([^<]+)<");
    private static final Pattern UTC = Pattern.compile("UtcTime=\"([^\"]+)\"");
    private static final Pattern ITEM_A = Pattern.compile(
            "SimpleItem Value=\"([^\"]*)\" Name=\"(IsMotion|IsPeople)\"");
    private static final Pattern ITEM_B = Pattern.compile(
            "SimpleItem Name=\"(IsMotion|IsPeople)\" Value=\"([^\"]*)\"");

    /** True if the reply holds a fresh "motion"/"person" = true event of a kind the user wants. */
    private boolean motionIn(String reply) {
        int kind = Prefs.alertKind(this);
        boolean hit = false;
        Matcher nm = MESSAGE.matcher(reply);
        while (nm.find()) {
            String msg = nm.group(1);
            Matcher t = TOPIC.matcher(msg);
            String topic = t.find() ? t.group(1) : "";
            boolean isPeople = topic.contains("PeopleDetector");
            boolean isMotion = topic.contains("CellMotionDetector");
            String name = null, value = null;
            Matcher a = ITEM_A.matcher(msg);
            if (a.find()) {
                value = a.group(1);
                name = a.group(2);
            } else {
                Matcher b = ITEM_B.matcher(msg);
                if (b.find()) {
                    name = b.group(1);
                    value = b.group(2);
                }
            }
            if (name == null || !"true".equals(value)) continue;
            boolean wanted = (isMotion && kind != Prefs.KIND_PERSON) || (isPeople && kind != Prefs.KIND_MOTION);
            if (!wanted) continue;
            Matcher u = UTC.matcher(msg);
            if (u.find()) {
                long age = ageMs(u.group(1));
                if (age > 30_000) continue; // stale
                lagMs = age;
            }
            hit = true;
        }
        return hit;
    }

    private static long ageMs(String utc) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(utc);
            return d == null ? 0 : System.currentTimeMillis() - d.getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }
}
