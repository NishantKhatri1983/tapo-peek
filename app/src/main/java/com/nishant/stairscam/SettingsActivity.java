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
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
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
    private EditText ip, user, pass;
    private TextView findStatus, previewStatus;
    private LinearLayout camList;
    private View previewBox;
    private PlayerView preview;
    private ExoPlayer previewPlayer;
    private Button findButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);
        ip = findViewById(R.id.ip);
        user = findViewById(R.id.user);
        pass = findViewById(R.id.pass);
        findStatus = findViewById(R.id.find_status);
        camList = findViewById(R.id.cam_list);
        previewBox = findViewById(R.id.preview_box);
        preview = findViewById(R.id.preview);
        previewStatus = findViewById(R.id.preview_status);
        findButton = findViewById(R.id.find);
        RadioGroup quality = findViewById(R.id.quality);

        ip.setText(Prefs.ip(this));
        user.setText(Prefs.user(this));
        pass.setText(Prefs.pass(this));
        quality.check(Prefs.hd(this) ? R.id.q_hd : R.id.q_sd);

        findButton.setOnClickListener(v -> findCameras());
        findViewById(R.id.overlay).setOnClickListener(v -> startActivity(
                new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()))));

        android.widget.Switch autoOpen = findViewById(R.id.auto_open);
        EditText camName = findViewById(R.id.cam_name);
        autoOpen.setChecked(Prefs.autoOpen(this));
        camName.setText(Prefs.camName(this));
        android.widget.Switch quiet = findViewById(R.id.quiet);
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

        android.widget.Switch lockScreen = findViewById(R.id.lock_screen);
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
            Prefs.save(this, i, u, p, quality.getCheckedRadioButtonId() == R.id.q_hd);
            stopPreview();
            startActivity(new Intent(this, LaunchActivity.class));
            finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        Prefs.setAutoOpen(this, ((android.widget.Switch) findViewById(R.id.auto_open)).isChecked(),
                ((EditText) findViewById(R.id.cam_name)).getText().toString().trim());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean listening = getSystemService(android.app.NotificationManager.class)
                .isNotificationListenerAccessGranted(new android.content.ComponentName(this, DetectListener.class));
        ((TextView) findViewById(R.id.notif_status)).setText(listening
                ? "Notification access: on. Turn on Tapo's detection notifications for this camera too."
                : "Notification access: off. Needed so Stairs can see Tapo's detection alerts.");
        findViewById(R.id.notif_access).setVisibility(listening ? View.GONE : View.VISIBLE);
        updateLockStatus();
        updatePause();
        boolean allowed = android.provider.Settings.canDrawOverlays(this);
        ((TextView) findViewById(R.id.overlay_status)).setText(allowed
                ? "Floating window: on. Drag to move, pinch to resize, double-tap for full/half width. Tap the icon again to close."
                : "Floating window: off (using Android's small picture-in-picture window). Allow it so the window opens full width and remembers where you put it.");
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
                ? "Lock-screen alerts: allowed."
                : "Lock-screen alerts: not ready - " + why + ".");
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
        showFindStatus("Found " + cams.size() + ". Tap one to preview it, and pick the one showing the stairs:");
        for (String addr : cams) {
            Button b = new Button(this);
            b.setText(addr);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                ip.setText(addr);
                startPreview(addr);
            });
            camList.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void startPreview(String addr) {
        String u = user.getText().toString().trim();
        String p = pass.getText().toString();
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
