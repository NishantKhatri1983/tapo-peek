package com.nishant.stairscam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the optional camera watcher after a reboot or an app update, if it was switched on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Prefs.watchOn(context)) WatchService.sync(context);
    }
}
