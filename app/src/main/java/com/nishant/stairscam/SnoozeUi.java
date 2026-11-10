package com.nishant.stairscam;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

/** Snooze button + duration chips shared by the floating window and the lock-screen view. */
final class SnoozeUi {
    private SnoozeUi() {}

    private static final int[][] CHOICES = {
            {R.id.snooze_15, 15}, {R.id.snooze_30, 30}, {R.id.snooze_60, 60}, {R.id.snooze_120, 120}};

    /**
     * @param onPaused   runs after a duration is chosen (e.g. close the pop-up)
     * @param onInteract runs when the snooze button is tapped (e.g. keep controls visible); may be null
     */
    static void attach(View root, Runnable onPaused, Runnable onInteract) {
        View options = root.findViewById(R.id.snooze_options);
        root.findViewById(R.id.btn_snooze).setOnClickListener(v -> {
            options.setVisibility(options.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (onInteract != null) onInteract.run();
        });
        for (int[] choice : CHOICES) {
            final int minutes = choice[1];
            root.findViewById(choice[0]).setOnClickListener(v -> {
                Context c = v.getContext().getApplicationContext();
                Prefs.pauseFor(c, minutes);
                Toast.makeText(c, "Stairs pop-ups paused until " + Prefs.clock(Prefs.pausedUntil(c)),
                        Toast.LENGTH_LONG).show();
                options.setVisibility(View.GONE);
                onPaused.run();
            });
        }
    }
}
