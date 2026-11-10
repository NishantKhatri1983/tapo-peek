package com.nishant.stairscam;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Target of the long-press icon shortcuts "Pause pop-ups 1 hour" and "Resume pop-ups". */
public class SnoozeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int minutes = 0;
        Bundle extras = getIntent().getExtras();
        Object extra = extras == null ? null : extras.get("minutes");
        if (extra != null) {
            try {
                minutes = Integer.parseInt(extra.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        Prefs.pauseFor(this, minutes);
        Toast.makeText(this, minutes > 0
                ? "Stairs pop-ups paused until " + Prefs.clock(Prefs.pausedUntil(this))
                : "Stairs pop-ups back on", Toast.LENGTH_LONG).show();
        finish();
    }
}
