package com.nishant.stairscam;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

/**
 * Shown on top of the lock screen (waking the screen) when a detection arrives while the phone
 * is locked. The phone stays locked; unlocking hands over to the floating window.
 */
@OptIn(markerClass = UnstableApi.class)
public class LockCamActivity extends Activity {
    private static final long AUTO_CLOSE_MS = 40_000;
    private static final long RETRY_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PlayerView video;
    private TextView status;
    private ImageButton muteButton;
    private ExoPlayer player;
    private boolean muted;
    /** True once the camera has actually been on a lit screen (not just launched while asleep). */
    private boolean shown;

    private final Runnable autoClose = () -> {
        // Let the screen go dark again: stop holding it on before closing.
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        findViewById(android.R.id.content).setKeepScreenOn(false);
        setTurnScreenOn(false);
        finish();
    };
    private final Runnable retry = () -> {
        if (player != null) {
            status.setText("Reconnecting…");
            player.prepare();
        }
    };

    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Phone unlocked while watching: carry on in the floating window.
            if (android.provider.Settings.canDrawOverlays(LockCamActivity.this)) {
                startForegroundService(new Intent(LockCamActivity.this, FloatService.class));
            }
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        setContentView(R.layout.cam);
        video = findViewById(R.id.video);
        status = findViewById(R.id.status);
        muteButton = findViewById(R.id.btn_mute);
        findViewById(R.id.btn_pip).setVisibility(View.GONE);
        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        SnoozeUi.attach(findViewById(android.R.id.content), this::finish, null);
        muted = Prefs.muted(this);
        updateMuteIcon();
        muteButton.setOnClickListener(v -> {
            muted = !muted;
            Prefs.setMuted(this, muted);
            if (player != null) player.setVolume(muted ? 0f : 1f);
            updateMuteIcon();
        });
        registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_PRESENT));
        getSystemService(android.app.NotificationManager.class).cancel(DetectListener.ALERT_ID);
        handler.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        getSystemService(android.app.NotificationManager.class).cancel(DetectListener.ALERT_ID);
        restartTimer(); // another detection: show for another 40 s
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        restartTimer(); // touching gives another 40 s, but it always ends on its own
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startPlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (getSystemService(android.os.PowerManager.class).isInteractive()) shown = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
        // Launched while the device was still asleep, Android may stop us for a moment before
        // the screen turns on (seen on Samsung). Only treat a stop as "done" once we were shown.
        if (shown) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(unlocked);
        } catch (IllegalArgumentException ignored) {
        }
        releasePlayer();
    }

    private void restartTimer() {
        handler.removeCallbacks(autoClose);
        handler.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    private void updateMuteIcon() {
        muteButton.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
    }

    private void startPlayer() {
        if (player != null) return;
        status.setVisibility(View.VISIBLE);
        status.setText("Movement on the stairs - connecting…");
        player = new ExoPlayer.Builder(this).setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 2000, 200, 300).build()).build();
        player.setVolume(muted ? 0f : 1f);
        video.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    handler.removeCallbacks(retry);
                    status.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                status.setVisibility(View.VISIBLE);
                status.setText("Can't reach Stairs cam\n(home Wi-Fi or Tailscale on?) - retrying");
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

    private void releasePlayer() {
        handler.removeCallbacks(retry);
        if (player != null) {
            video.setPlayer(null);
            player.release();
            player = null;
        }
    }
}
