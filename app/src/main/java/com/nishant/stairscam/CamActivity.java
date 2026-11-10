package com.nishant.stairscam;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.Collections;

/** Live RTSP view of the Stairs camera; lives mostly as a picture-in-picture mini window. */
@OptIn(markerClass = UnstableApi.class)
public class CamActivity extends Activity {
    static final String EXTRA_ENTER_PIP = "enter_pip";
    private static final String ACTION_MUTE = "com.nishant.stairscam.TOGGLE_MUTE";
    private static final long RETRY_MS = 3000;
    static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PlayerView video;
    private TextView status;
    private View controls;
    private ImageButton muteButton;
    private ExoPlayer player;
    private boolean muted;
    private boolean pendingPip;

    private final BroadcastReceiver muteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            toggleMute();
        }
    };

    private final Runnable retry = () -> {
        if (player != null) {
            status.setText("Reconnecting…");
            player.prepare();
        }
    };

    static PictureInPictureParams pipParams(Context c, boolean muted) {
        Intent toggle = new Intent(ACTION_MUTE).setPackage(c.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, toggle,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String label = muted ? "Unmute" : "Mute";
        RemoteAction mute = new RemoteAction(
                Icon.createWithResource(c, muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on),
                label, label, pi);
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9))
                .setActions(Collections.singletonList(mute));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(true).setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        running = true;
        setContentView(R.layout.cam);
        video = findViewById(R.id.video);
        status = findViewById(R.id.status);
        controls = findViewById(R.id.controls);
        muteButton = findViewById(R.id.btn_mute);
        muted = Prefs.muted(this);
        updateMuteIcon();

        muteButton.setOnClickListener(v -> toggleMute());
        findViewById(R.id.btn_pip).setOnClickListener(v -> goPip());
        findViewById(R.id.btn_close).setOnClickListener(v -> finishAndRemoveTask());
        SnoozeUi.attach(findViewById(android.R.id.content), () -> { }, null);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(muteReceiver, new IntentFilter(ACTION_MUTE), Context.RECEIVER_NOT_EXPORTED);
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goPip);
        } else {
            registerReceiver(muteReceiver, new IntentFilter(ACTION_MUTE));
        }
        setPictureInPictureParams(pipParams(this, muted));
        showControls(!isInPictureInPictureMode());

        pendingPip = getIntent().getBooleanExtra(EXTRA_ENTER_PIP, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingPip) {
            pendingPip = false;
            goPip(); // Some launchers (ColorOS) ignore launch-into-PiP, so shrink ourselves.
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        startPlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Stopped = the mini window was dismissed or the screen was left: free the camera stream.
        releasePlayer();
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(muteReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        releasePlayer();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < 31) goPip();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        goPip(); // Only reached below Android 13; newer versions use the OnBackInvokedCallback.
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        showControls(!inPip);
    }

    private void goPip() {
        if (isInPictureInPictureMode()) return;
        try {
            enterPictureInPictureMode(pipParams(this, muted));
        } catch (IllegalStateException e) {
            // PiP disabled for this app in system settings: stay full screen.
            showControls(true);
        }
    }

    private void showControls(boolean show) {
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        status.setTextSize(show ? 16 : 11);
    }

    private void toggleMute() {
        muted = !muted;
        Prefs.setMuted(this, muted);
        if (player != null) player.setVolume(muted ? 0f : 1f);
        updateMuteIcon();
        setPictureInPictureParams(pipParams(this, muted));
    }

    private void updateMuteIcon() {
        muteButton.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
    }

    private void startPlayer() {
        if (player != null) return;
        status.setVisibility(View.VISIBLE);
        status.setText("Connecting to Stairs…");
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 2000, 200, 300)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        player.setVolume(muted ? 0f : 1f);
        video.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    handler.removeCallbacks(retry);
                    status.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    status.setVisibility(View.VISIBLE);
                    status.setText("Connecting to Stairs…");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                status.setVisibility(View.VISIBLE);
                status.setText(describe(error));
                handler.removeCallbacks(retry);
                handler.postDelayed(retry, RETRY_MS);
            }
        });
        player.setMediaSource(new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(6000)
                .createMediaSource(MediaItem.fromUri(Prefs.rtspUri(this))));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private String describe(PlaybackException e) {
        String msg = e.getMessage() + " " + e.getCause();
        if (msg.contains("401") || msg.toLowerCase().contains("unauthorized")) {
            return "Wrong camera account username/password";
        }
        return "Can't reach Stairs cam\n(home Wi-Fi or Tailscale on?) - retrying";
    }

    private void releasePlayer() {
        handler.removeCallbacks(retry);
        if (player != null) {
            video.setPlayer(null);
            player.release();
            player = null;
        }
    }
}
