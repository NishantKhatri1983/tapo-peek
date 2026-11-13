package com.nishant.stairscam;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.DynamicColors;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@OptIn(markerClass = UnstableApi.class)
public class SettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText ip, user, pass, ip2, user2, pass2;
    private TextView findStatus, previewStatus;
    private LinearLayout camList;
    private View previewBox;
    private PlayerView preview;
    private ExoPlayer previewPlayer;
    private Button findButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.settings);
        ip = findViewById(R.id.ip);
        user = findViewById(R.id.user);
        pass = findViewById(R.id.pass);
        ip2 = findViewById(R.id.ip2);
        user2 = findViewById(R.id.user2);
        pass2 = findViewById(R.id.pass2);
        ((EditText) findViewById(R.id.name2)).setText(Prefs.name2(this));
        ip2.setText(Prefs.ip2(this));
        user2.setText(Prefs.user2Raw(this));
        pass2.setText(Prefs.pass2Raw(this));
        findStatus = findViewById(R.id.find_status);
        camList = findViewById(R.id.cam_list);
        previewBox = findViewById(R.id.preview_box);
        preview = findViewById(R.id.preview);
        previewStatus = findViewById(R.id.preview_status);
        findButton = findViewById(R.id.find);
        MaterialButtonToggleGroup quality = findViewById(R.id.quality);

        ip.setText(Prefs.ip(this));
        user.setText(Prefs.user(this));
        pass.setText(Prefs.pass(this));
        quality.check(Prefs.hd(this) ? R.id.q_hd : R.id.q_sd);

        findButton.setOnClickListener(v -> findCameras());
        findViewById(R.id.overlay).setOnClickListener(v -> startActivity(
                new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()))));

        CompoundButton autoOpen = findViewById(R.id.auto_open);
        EditText camName = findViewById(R.id.cam_name);
        autoOpen.setChecked(Prefs.autoOpen(this));
        camName.setText(Prefs.camName(this));
        CompoundButton quiet = findViewById(R.id.quiet);
        Button qs = findViewById(R.id.quiet_start), qe = findViewById(R.id.quiet_end);
        final int[] q = {Prefs.quietStart(this), Prefs.quietEnd(this)};
        Runnable showQuiet = () -> {
            qs.setText("From " + Prefs.hhmm(q[0]));
            qe.setText("Until " + Prefs.hhmm(q[1]));
            qs.setEnabled(quiet.isChecked());
            qe.setEnabled(quiet.isChecked());
        };
        Runnable saveQuiet = () -> {
            Prefs.setQuiet(this, quiet.isChecked(), q[0], q[1]);
            showQuiet.run();
        };
        quiet.setChecked(Prefs.quietOn(this));
        showQuiet.run();
        quiet.setOnCheckedChangeListener((b, on) -> saveQuiet.run());
        qs.setOnClickListener(v -> new android.app.TimePickerDialog(this, (tp, h, m) -> {
            q[0] = h * 60 + m;
            saveQuiet.run();
        }, q[0] / 60, q[0] % 60, false).show());
        qe.setOnClickListener(v -> new android.app.TimePickerDialog(this, (tp, h, m) -> {
            q[1] = h * 60 + m;
            saveQuiet.run();
        }, q[1] / 60, q[1] % 60, false).show());

        MaterialButtonToggleGroup kind = findViewById(R.id.alert_kind);
        kind.check(Prefs.alertKind(this) == Prefs.KIND_PERSON ? R.id.k_person
                : Prefs.alertKind(this) == Prefs.KIND_BOTH ? R.id.k_both : R.id.k_motion);
        kind.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) Prefs.setAlertKind(this,
                    id == R.id.k_person ? Prefs.KIND_PERSON : id == R.id.k_both ? Prefs.KIND_BOTH : Prefs.KIND_MOTION);
        });

        // Cool-down: two pickers (closed by me / closed by itself).
        setupCoolButton(findViewById(R.id.cool_manual), true, "After I close it");
        setupCoolButton(findViewById(R.id.cool_auto), false, "After it closes itself");

        // Experimental camera watch (off by default).
        CompoundButton watch = findViewById(R.id.watch);
        watch.setChecked(Prefs.watchOn(this));
        watch.setOnCheckedChangeListener((b, on) -> {
            if (on && !Prefs.configured(this)) {
                b.setChecked(false);
                Toast.makeText(this, "Enter and save the camera account first", Toast.LENGTH_SHORT).show();
                return;
            }
            Prefs.setWatchOn(this, on);
            WatchService.sync(this);
            updateWatchStatus();
        });

        // Chime when a pop-up opens (off until switched on; sound picked with the system picker).
        CompoundButton keepAwake = findViewById(R.id.keep_awake);
        keepAwake.setChecked(Prefs.keepAwake(this));
        keepAwake.setOnCheckedChangeListener((b, on) -> Prefs.setKeepAwake(this, on));
        CompoundButton iconBoth = findViewById(R.id.icon_both);
        iconBoth.setChecked(Prefs.iconBoth(this));
        iconBoth.setOnCheckedChangeListener((b, on) -> Prefs.setIconBoth(this, on));
        CompoundButton chime = findViewById(R.id.chime);
        MaterialButton chimePick = findViewById(R.id.chime_pick);
        chime.setChecked(Prefs.chimeOn(this));
        chime.setOnCheckedChangeListener((b, on) -> {
            Prefs.setChimeOn(this, on);
            if (on) Chime.play(this);
        });
        CompoundButton chimeLoud = findViewById(R.id.chime_loud);
        chimeLoud.setChecked(Prefs.chimeLoud(this));
        chimeLoud.setOnCheckedChangeListener((b, on) -> {
            Prefs.setChimeLoud(this, on);
            Chime.play(this);
        });
        chimePick.setText(Chime.title(this));
        chimePick.setOnClickListener(v -> {
            try {
                startActivityForResult(Chime.pickerIntent(this), REQ_CHIME);
            } catch (RuntimeException e) {
                Toast.makeText(this, "No sound picker found on this phone", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.chime_test).setOnClickListener(v -> Chime.play(this));

        CompoundButton lockScreen = findViewById(R.id.lock_screen);
        lockScreen.setChecked(Prefs.lockScreen(this));
        lockScreen.setOnCheckedChangeListener((b, on) -> Prefs.setLockScreen(this, on));
        findViewById(R.id.lock_access).setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            } else if (!fullScreenAllowed()) {
                openFullScreenSettings();
            } else if (!channelHigh()) {
                startActivity(new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, DetectListener.ALERT_CHANNEL));
            }
        });
        autoOpen.setOnCheckedChangeListener((b, on) ->
                Prefs.setAutoOpen(this, on, camName.getText().toString().trim()));
        findViewById(R.id.notif_access).setOnClickListener(v -> {
            Intent i;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                i = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new android.content.ComponentName(this, DetectListener.class).flattenToString());
            } else {
                i = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            }
            try {
                startActivity(i);
            } catch (RuntimeException e) {
                startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });

        findViewById(R.id.save).setOnClickListener(v -> {
            String i = ip.getText().toString().trim();
            String u = user.getText().toString().trim();
            String p = pass.getText().toString();
            if (i.isEmpty() || u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "Fill in username, password and camera IP", Toast.LENGTH_SHORT).show();
                return;
            }
            Prefs.save(this, i, u, p, quality.getCheckedButtonId() == R.id.q_hd);
            saveCam2();
            stopPreview();
            startActivity(new Intent(this, LaunchActivity.class));
            finish();
        });
    }

    private static final int REQ_CHIME = 7;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CHIME && resultCode == RESULT_OK) {
            Chime.savePicked(this, data);
            ((MaterialButton) findViewById(R.id.chime_pick)).setText(Chime.title(this));
            Chime.play(this); // let the user hear what they picked
        }
    }

    private void saveCam2() {
        Prefs.saveCam2(this, ip2.getText().toString().trim(), user2.getText().toString().trim(),
                pass2.getText().toString());
        Prefs.setName2(this, ((EditText) findViewById(R.id.name2)).getText().toString().trim());
    }

    private void setupCoolButton(MaterialButton b, boolean manual, String label) {
        Runnable show = () -> b.setText(label + "\n" + Prefs.coolLabel(manual ? Prefs.coolManualMs(this)
                : Prefs.coolAutoMs(this)));
        show.run();
        b.setAllCaps(false);
        b.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("No movement for this long, " + label.toLowerCase(java.util.Locale.ROOT))
                .setItems(Prefs.COOL_LABELS, (d, which) -> {
                    Prefs.setCool(this, manual, Prefs.COOL_CHOICES_MS[which]);
                    show.run();
                })
                .show());
    }

    private void updateWatchStatus() {
        TextView t = findViewById(R.id.watch_status);
        if (!Prefs.watchOn(this)) {
            t.setText("Off");
            return;
        }
        String s = Prefs.watchStatus(this);
        t.setText(s.isEmpty() ? "Starting…" : s);
    }

    private void setDot(int id, boolean ok) {
        findViewById(id).setBackgroundResource(ok ? R.drawable.dot_on : R.drawable.dot_off);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCam2();
        Prefs.setAutoOpen(this, ((CompoundButton) findViewById(R.id.auto_open)).isChecked(),
                ((EditText) findViewById(R.id.cam_name)).getText().toString().trim());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean listening = getSystemService(android.app.NotificationManager.class)
                .isNotificationListenerAccessGranted(new android.content.ComponentName(this, DetectListener.class));
        ((TextView) findViewById(R.id.notif_status)).setText(listening
                ? "Reading Tapo alerts"
                : "Notification access needed to see Tapo's alerts");
        setDot(R.id.dot_notif, listening);
        findViewById(R.id.notif_access).setVisibility(listening ? View.GONE : View.VISIBLE);
        updateLockStatus();
        updatePause();
        updateWatchStatus();
        String last = Prefs.lastTapoAlert(this);
        ((TextView) findViewById(R.id.last_alert)).setText(last.isEmpty()
                ? "No Tapo alert seen yet."
                : "Last Tapo alert (" + (DetectListener.isPersonAlert(last.toLowerCase(java.util.Locale.ROOT))
                        ? "counts as person" : "counts as motion") + "): " + last);
        boolean allowed = android.provider.Settings.canDrawOverlays(this);
        ((TextView) findViewById(R.id.overlay_status)).setText(allowed
                ? "Floating window allowed"
                : "Floating window needs permission (otherwise a small system window is used)");
        setDot(R.id.dot_overlay, allowed);
        findViewById(R.id.overlay).setVisibility(allowed ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        updateLockStatus();
        if (!lockAlertsReady() && android.os.Build.VERSION.SDK_INT >= 34 && notificationsAllowed()) {
            openFullScreenSettings();
        }
    }

    private void openFullScreenSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        }
    }

    private boolean notificationsAllowed() {
        return getSystemService(android.app.NotificationManager.class).areNotificationsEnabled();
    }

    private boolean fullScreenAllowed() {
        if (android.os.Build.VERSION.SDK_INT < 34) return true;
        // canUseFullScreenIntent() can say yes while the app-op is still "default" (= denied).
        android.app.AppOpsManager ops = getSystemService(android.app.AppOpsManager.class);
        int mode = ops.unsafeCheckOpNoThrow("android:use_full_screen_intent",
                android.os.Process.myUid(), getPackageName());
        return mode == android.app.AppOpsManager.MODE_ALLOWED;
    }

    private boolean channelHigh() {
        android.app.NotificationChannel ch = getSystemService(android.app.NotificationManager.class)
                .getNotificationChannel(DetectListener.ALERT_CHANNEL);
        return ch == null || ch.getImportance() >= android.app.NotificationManager.IMPORTANCE_HIGH;
    }

    private boolean lockAlertsReady() {
        return notificationsAllowed() && fullScreenAllowed() && channelHigh();
    }

    private void updatePause() {
        TextView status = findViewById(R.id.pause_status);
        Button btn = findViewById(R.id.pause_btn);
        if (Prefs.isPaused(this)) {
            status.setText("Automatic pop-ups paused until " + Prefs.clock(Prefs.pausedUntil(this))
                    + ". Tapping the icon still opens the camera.");
            btn.setText("Resume pop-ups now");
            btn.setOnClickListener(v -> {
                Prefs.pauseFor(this, 0);
                updatePause();
            });
        } else {
            status.setText("Automatic pop-ups: active.");
            btn.setText("Pause pop-ups...");
            final String[] labels = {"15 minutes", "30 minutes", "1 hour", "2 hours", "4 hours"};
            final int[] minutes = {15, 30, 60, 120, 240};
            btn.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                    .setTitle("Pause automatic pop-ups for")
                    .setItems(labels, (d, which) -> {
                        Prefs.pauseFor(this, minutes[which]);
                        updatePause();
                    })
                    .show());
        }
    }

    private void updateLockStatus() {
        boolean ready = lockAlertsReady();
        String why = !notificationsAllowed() ? "notifications are off for Stairs"
                : !fullScreenAllowed() ? "allow Full-screen notifications for Stairs"
                : !channelHigh() ? "set 'Movement on the stairs' notifications to High / pop-up"
                : "";
        ((TextView) findViewById(R.id.lock_status)).setText(ready
                ? "Lock-screen alerts allowed"
                : "Lock-screen alerts: " + why);
        setDot(R.id.dot_lock, ready);
        findViewById(R.id.lock_access).setVisibility(ready ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPreview();
    }

    /** Scans the phone's Wi-Fi subnet for devices with the RTSP port (554) open. */
    private void findCameras() {
        String prefix = subnetPrefix();
        if (prefix == null) {
            showFindStatus("Connect to your home Wi-Fi first.");
            return;
        }
        findButton.setEnabled(false);
        camList.removeAllViews();
        showFindStatus("Scanning " + prefix + "x …");
        new Thread(() -> {
            List<String> found = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(48);
            for (int host = 1; host < 255; host++) {
                final String addr = prefix + host;
                pool.execute(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(addr, 554), 600);
                        found.add(addr);
                    } catch (Exception ignored) {
                    }
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            List<String> sorted = new ArrayList<>(found);
            sorted.sort((a, b) -> Integer.compare(lastOctet(a), lastOctet(b)));
            handler.post(() -> showCameras(sorted));
        }).start();
    }

    private void showCameras(List<String> cams) {
        findButton.setEnabled(true);
        if (cams.isEmpty()) {
            showFindStatus("No cameras found on this Wi-Fi.");
            return;
        }
        showFindStatus("Found " + cams.size() + ". Tap an address to preview it and use it for Stairs, or tap 2nd to use it as the second camera:");
        for (String addr : cams) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, 6, 0, 0);
            MaterialButton b = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(addr);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                ip.setText(addr);
                startPreview(addr, user.getText().toString().trim(), pass.getText().toString());
            });
            MaterialButton b2 = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b2.setText("2nd");
            b2.setAllCaps(false);
            b2.setOnClickListener(v -> {
                ip2.setText(addr);
                boolean own = !user2.getText().toString().trim().isEmpty()
                        && !pass2.getText().toString().isEmpty();
                startPreview(addr, own ? user2.getText().toString().trim() : user.getText().toString().trim(),
                        own ? pass2.getText().toString() : pass.getText().toString());
            });
            row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3));
            row.addView(b2, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            camList.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void startPreview(String addr, String u, String p) {
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(this, "Enter the camera account first", Toast.LENGTH_SHORT).show();
            return;
        }
        stopPreview();
        previewBox.setVisibility(View.VISIBLE);
        previewStatus.setVisibility(View.VISIBLE);
        previewStatus.setText("Connecting to " + addr + " …");
        previewPlayer = new ExoPlayer.Builder(this).build();
        previewPlayer.setVolume(0f);
        preview.setPlayer(previewPlayer);
        previewPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) previewStatus.setVisibility(View.GONE);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                String msg = e.getMessage() + " " + e.getCause();
                previewStatus.setVisibility(View.VISIBLE);
                previewStatus.setText(msg.contains("401")
                        ? addr + ": username/password not accepted by this camera"
                        : addr + ": no video (" + e.getErrorCodeName() + ")");
            }
        });
        previewPlayer.setMediaSource(new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(6000)
                .createMediaSource(MediaItem.fromUri(Prefs.rtspUri(addr, u, p, false))));
        previewPlayer.setPlayWhenReady(true);
        previewPlayer.prepare();
    }

    private void stopPreview() {
        if (previewPlayer != null) {
            preview.setPlayer(null);
            previewPlayer.release();
            previewPlayer = null;
        }
    }

    private void showFindStatus(String text) {
        findStatus.setVisibility(View.VISIBLE);
        findStatus.setText(text);
    }

    /** "192.168.8." for the phone's active network, assuming a /24 home LAN. */
    private String subnetPrefix() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                String a = la.getAddress().getHostAddress();
                return a.substring(0, a.lastIndexOf('.') + 1);
            }
        }
        return null;
    }

    private static int lastOctet(String a) {
        return Integer.parseInt(a.substring(a.lastIndexOf('.') + 1));
    }
}
