package com.nishant.stairscam;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Home-screen icon. Opens the camera directly as a picture-in-picture mini window
 * (Android 13+ launch-into-PiP), so there is no full-screen step in between.
 */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Prefs.configured(this)) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }
        if (android.provider.Settings.canDrawOverlays(this)) {
            // Floating window: tap the icon to open it, tap again to close it.
            Intent svc = new Intent(this, FloatService.class);
            if (FloatService.running) {
                stopService(svc);
            } else {
                startForegroundService(svc);
            }
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        if (CamActivity.running) { // Mini window already showing.
            finish();
            return;
        }
        // Launch-into-PiP only works when the new activity lands in this same task.
        Intent cam = new Intent(this, CamActivity.class).putExtra(CamActivity.EXTRA_ENTER_PIP, true);
        boolean launched = false;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                ActivityOptions opts = ActivityOptions.makeLaunchIntoPip(
                        CamActivity.pipParams(this, Prefs.muted(this)));
                startActivity(cam, opts.toBundle());
                launched = true;
            } catch (RuntimeException ignored) {
                // Fall back to entering PiP from inside CamActivity.
            }
        }
        if (!launched) {
            startActivity(cam);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
