package com.nishant.stairscam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
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
 * Floating camera window drawn over other apps. Unlike system picture-in-picture, this lets us
 * choose (and remember) its size and position. Drag to move, pinch to resize, tap for controls,
 * double-tap to switch between full width and half width.
 */
@OptIn(markerClass = UnstableApi.class)
public class FloatService extends Service {
    static final String ACTION_STOP = "com.nishant.stairscam.STOP";
    /** Opened by a detection alert: close by itself unless the user touches it. */
    static final String EXTRA_AUTO = "auto";
    /** Show the second camera too (set by detections and by the lock-screen hand-over). */
    static final String EXTRA_DUAL = "dual";
    private static final long AUTO_CLOSE_MS = 60_000;
    private static final String CHANNEL = "live";
    private static final long RETRY_MS = 3000;
    private static final long CONTROLS_MS = 5000;
    static volatile boolean running;
    /** True while the second camera is showing in the open window. */
    static volatile boolean dualShown;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private View root;
    private PlayerView video;
    private TextView status;
    private View controls;
    private ImageButton muteButton;
    private ExoPlayer player;
    private CamStream cam2;
    private boolean dual;
    private boolean muted;
    private int screenW, screenH, margin, minW;

    private final Runnable hideControls = () -> {
        if (controls != null) {
            controls.setVisibility(View.GONE);
            root.findViewById(R.id.snooze_options).setVisibility(View.GONE);
        }
    };

    /** How this window ended, for the cool-down: by its own timer, or the screen turning off. */
    private boolean closedByTimer, closedByScreenOff;
    private final Runnable autoClose = () -> {
        closedByTimer = true;
        stopSelf();
    };
    private boolean userTouched;

    private final Runnable retry = () -> {
        if (player != null) {
            status.setText("Reconnecting…");
            player.prepare();
        }
    };

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            closedByScreenOff = true;
            stopSelf(); // Don't keep streaming in a pocket.
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean auto = intent != null && intent.getBooleanExtra(EXTRA_AUTO, false);
        boolean fresh = root == null;
        goForeground();
        if (fresh) {
            dual = (auto || (intent != null && intent.getBooleanExtra(EXTRA_DUAL, false)))
                    && Prefs.hasCam2(this);
            running = true;
            userTouched = false;
            showWindow();
            registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            startPlayer();
        }
        if (!fresh && !auto && intent != null && intent.getBooleanExtra(EXTRA_DUAL, false)) {
            setDual(true); // "Both cameras" while the window is already open
        }
        if (!auto) {
            setKeepOn(Prefs.keepAwake(this)); // watching on purpose: don't let the screen time out
            userTouched = true; // opened by hand: stay open
            handler.removeCallbacks(autoClose);
        } else if (!userTouched) {
            handler.removeCallbacks(autoClose); // new detection: extend
            handler.postDelayed(autoClose, AUTO_CLOSE_MS);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        dualShown = false;
        if (!closedByScreenOff) Prefs.startCooldown(this, closedByTimer);
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenOff);
        } catch (IllegalArgumentException ignored) {
        }
        releasePlayer();
        if (root != null) {
            wm.removeView(root);
            root = null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (root == null) return;
        measureScreen();
        setWidth(Math.min(lp.width, screenW));
        clampPosition();
        wm.updateViewLayout(root, lp);
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Live camera",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, FloatService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cam_small)
                .setContentTitle("Stairs cam is live")
                .setContentText("Tap to close")
                .setContentIntent(stop)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(1, n);
        }
    }

    private void measureScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            screenW = b.width();
            screenH = b.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
        minW = Math.round(screenW * 0.35f);
    }

    private void showWindow() {
        wm = getSystemService(WindowManager.class);
        float density = getResources().getDisplayMetrics().density;
        margin = Math.round(12 * density);
        measureScreen();

        root = LayoutInflater.from(this).inflate(R.layout.floating, null);
        video = root.findViewById(R.id.video);
        status = root.findViewById(R.id.status);
        controls = root.findViewById(R.id.controls);
        muteButton = root.findViewById(R.id.btn_mute);
        muted = Prefs.muted(this);
        updateMuteIcon();
        muteButton.setOnClickListener(v -> {
            toggleMute();
            showControls();
        });
        ((TextView) root.findViewById(R.id.label)).setText(Prefs.camName(this));
        if (dual) showSecondPane();
        ImageButton cam2Button = root.findViewById(R.id.btn_cam2);
        if (Prefs.hasCam2(this)) {
            cam2Button.setVisibility(View.VISIBLE);
            cam2Button.setAlpha(dual ? 1f : 0.55f);
            cam2Button.setOnClickListener(v -> {
                setDual(!dual);
                showControls();
            });
        }
        root.findViewById(R.id.btn_close).setOnClickListener(v -> stopSelf());
        SnoozeUi.attach(root, this::stopSelf, this::showControls);

        final float radius = 24 * density;
        root.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        root.setClipToOutline(true);
        root.setElevation(8 * density);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        // Last size/position, or default: full width, in the lower part of the screen.
        int w = Prefs.floatW(this);
        setWidth(w > 0 ? w : screenW - 2 * margin);
        int x = Prefs.floatX(this);
        int y = Prefs.floatY(this);
        lp.x = x >= 0 ? x : margin;
        lp.y = y >= 0 ? y : Math.round(screenH * 0.57f);
        clampPosition();

        attachGestures();
        wm.addView(root, lp);
    }

    private void setKeepOn(boolean on) {
        if (root == null) return;
        int f = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        lp.flags = on ? (lp.flags | f) : (lp.flags & ~f);
        wm.updateViewLayout(root, lp);
    }

    private void showSecondPane() {
        ((TextView) root.findViewById(R.id.label2)).setText(Prefs.name2(this));
        root.findViewById(R.id.pane2).setVisibility(View.VISIBLE);
        cam2 = new CamStream(this, root.findViewById(R.id.video2),
                root.findViewById(R.id.status2), Prefs.rtsp2Uri(this));
        dualShown = true;
    }

    /** Show or hide the second camera in the open window (the camera button, or "Both cameras"). */
    private void setDual(boolean on) {
        if (root == null || on == dual || (on && !Prefs.hasCam2(this))) return;
        dual = on;
        if (on) {
            showSecondPane();
            if (player != null) cam2.start();
        } else {
            if (cam2 != null) {
                cam2.release();
                cam2 = null;
            }
            root.findViewById(R.id.pane2).setVisibility(View.GONE);
            dualShown = false;
        }
        root.findViewById(R.id.btn_cam2).setAlpha(on ? 1f : 0.55f);
        setWidth(lp.width);
        clampPosition();
        wm.updateViewLayout(root, lp);
    }

    private void setWidth(int w) {
        w = Math.max(minW, Math.min(w, screenW));
        lp.width = w;
        lp.height = Math.round(w * 9f / 16f) * (dual ? 2 : 1);
    }

    private void clampPosition() {
        lp.x = Math.max(0, Math.min(lp.x, screenW - lp.width));
        lp.y = Math.max(0, Math.min(lp.y, screenH - lp.height));
    }

    private void savePlacement() {
        Prefs.saveFloat(this, lp.width, lp.x, lp.y);
    }

    private void attachGestures() {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final boolean[] scaled = {false};

        ScaleGestureDetector scale = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        scaled[0] = true;
                        int oldW = lp.width, oldH = lp.height;
                        setWidth(Math.round(lp.width * d.getScaleFactor()));
                        lp.x -= (lp.width - oldW) / 2; // grow around the centre
                        lp.y -= (lp.height - oldH) / 2;
                        clampPosition();
                        wm.updateViewLayout(root, lp);
                        return true;
                    }
                });

        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (controls.getVisibility() == View.VISIBLE) {
                    hideControls.run();
                } else {
                    showControls();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                int full = screenW - 2 * margin;
                int oldW = lp.width;
                setWidth(lp.width > full * 0.8f ? Math.round(screenW * 0.55f) : full);
                lp.x -= (lp.width - oldW) / 2;
                clampPosition();
                wm.updateViewLayout(root, lp);
                savePlacement();
                return true;
            }
        });

        root.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (!userTouched) {
                    userTouched = true; // user is looking: don't auto-close
                    handler.removeCallbacks(autoClose);
                }
                scale.onTouchEvent(e);
                taps.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        dragging = false;
                        scaled[0] = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (e.getPointerCount() == 1 && !scaled[0] && !scale.isInProgress()) {
                            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                            if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                            if (dragging) {
                                lp.x = startX + Math.round(dx);
                                lp.y = startY + Math.round(dy);
                                clampPosition();
                                wm.updateViewLayout(root, lp);
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging || scaled[0]) savePlacement();
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, CONTROLS_MS);
    }

    private void toggleMute() {
        muted = !muted;
        Prefs.setMuted(this, muted);
        if (player != null) player.setVolume(muted ? 0f : 1f);
        updateMuteIcon();
    }

    private void updateMuteIcon() {
        muteButton.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
    }

    private void startPlayer() {
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
                String msg = error.getMessage() + " " + error.getCause();
                status.setText(msg.contains("401")
                        ? "Wrong camera account username/password"
                        : "Can't reach Stairs cam\n(home Wi-Fi or Tailscale on?) - retrying");
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
        if (cam2 != null) cam2.start();
    }

    private void releasePlayer() {
        handler.removeCallbacks(retry);
        if (cam2 != null) {
            cam2.release();
            cam2 = null;
        }
        if (player != null) {
            video.setPlayer(null);
            player.release();
            player = null;
        }
    }
}
