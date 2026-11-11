package com.nishant.stairscam;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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

/** One extra, always-muted live view (the second camera shown under Stairs on a detection). */
@OptIn(markerClass = UnstableApi.class)
final class CamStream {
    private static final long RETRY_MS = 3000;

    private final Context ctx;
    private final PlayerView view;
    private final TextView status;
    private final Uri uri;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;

    private final Runnable retry = this::reconnect;

    private void reconnect() {
        if (player != null) {
            status.setText("Reconnecting…");
            player.prepare();
        }
    }

    CamStream(Context ctx, PlayerView view, TextView status, Uri uri) {
        this.ctx = ctx;
        this.view = view;
        this.status = status;
        this.uri = uri;
    }

    void start() {
        if (player != null) return;
        status.setVisibility(android.view.View.VISIBLE);
        status.setText("Connecting to second camera…");
        player = new ExoPlayer.Builder(ctx).setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 2000, 200, 300).build()).build();
        player.setVolume(0f);
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    handler.removeCallbacks(retry);
                    status.setVisibility(android.view.View.GONE);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                status.setVisibility(android.view.View.VISIBLE);
                String msg = error.getMessage() + " " + error.getCause();
                status.setText(msg.contains("401")
                        ? "Second camera: wrong username/password"
                        : "Can't reach second camera - retrying");
                handler.removeCallbacks(retry);
                handler.postDelayed(retry, RETRY_MS);
            }
        });
        player.setMediaSource(new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(6000)
                .createMediaSource(MediaItem.fromUri(uri)));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    void release() {
        handler.removeCallbacks(retry);
        if (player != null) {
            view.setPlayer(null);
            player.release();
            player = null;
        }
    }
}
