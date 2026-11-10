package com.nishant.stairscam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Debug hook: lets a USB-connected computer simulate a Stairs detection
 * (adb shell am broadcast -n com.nishant.stairscam/.TestTrigger). Guarded by the DUMP
 * permission, which only the adb shell and system hold.
 */
public class TestTrigger extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Wait past the short background-start grace a shell broadcast grants, so the test takes
        // the same path as a real Tapo alert.
        final Context app = context.getApplicationContext();
        final PendingResult done = goAsync();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            DetectListener.handleDetection(app);
            done.finish();
        }, intent.getIntExtra("delay", 15) * 1000L);
    }
}
