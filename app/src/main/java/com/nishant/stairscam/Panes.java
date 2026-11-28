package com.nishant.stairscam;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The extra camera panes (cameras 2-4) shown next to Stairs, and how they are laid out:
 * one or two cameras stack top to bottom; three or four go in a 2 x 2 grid.
 */
@OptIn(markerClass = UnstableApi.class)
final class Panes {
    private Panes() {}

    /** One extra camera: its pane view and its live stream. */
    static final class Extra {
        final View pane;
        final CamStream stream;

        Extra(View pane, CamStream stream) {
            this.pane = pane;
            this.stream = stream;
        }
    }

    /** Builds a pane and stream for every configured extra camera (not yet started or added). */
    static List<Extra> build(Context c) {
        List<Extra> out = new ArrayList<>();
        for (int slot : Prefs.extraCams(c)) {
            View pane = LayoutInflater.from(c).inflate(R.layout.pane_extra, null);
            String label = Prefs.camLabel(c, slot);
            ((TextView) pane.findViewById(R.id.label)).setText(label);
            CamStream s = new CamStream(c, (PlayerView) pane.findViewById(R.id.video),
                    pane.findViewById(R.id.status), Prefs.rtspUri(c, slot), label);
            out.add(new Extra(pane, s));
        }
        return out;
    }

    /** Puts Stairs (pane1) and the extra panes into the container, replacing whatever was there. */
    static void arrange(LinearLayout container, View pane1, List<Extra> extras) {
        List<View> cells = new ArrayList<>();
        cells.add(pane1);
        for (Extra e : extras) cells.add(e.pane);
        arrange(container, cells);
    }

    /** Lays out any one to four panes: one fills the window, two stack, three or four form a 2 x 2 grid. */
    static void arrange(LinearLayout container, List<View> cells) {
        for (View v : cells) detach(v);
        container.removeAllViews();
        container.setOrientation(LinearLayout.VERTICAL);
        int gap = Math.round(2 * container.getResources().getDisplayMetrics().density);
        if (cells.size() <= 2) {
            for (int i = 0; i < cells.size(); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
                if (i > 0) lp.topMargin = gap;
                add(container, cells.get(i), lp);
            }
            return;
        }
        // Three or four cameras: 2 x 2 grid, first camera top left.
        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(container.getContext());
            r.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (row == 1) rp.topMargin = gap;
            container.addView(r, rp);
            for (int col = 0; col < 2; col++) {
                int i = row * 2 + col;
                View cell = i < cells.size() ? cells.get(i) : new View(container.getContext());
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (col == 1) cp.leftMargin = gap;
                add(r, cell, cp);
            }
        }
    }

    /** Window height as a multiple of one 16:9 picture at the window's width. */
    static float heightFactor(int cameraCount) {
        return cameraCount <= 2 ? cameraCount : 1f; // a 2 x 2 grid of 16:9 cells is itself 16:9
    }

    private static void detach(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private static void add(ViewGroup parent, View v, ViewGroup.LayoutParams lp) {
        parent.addView(v, lp);
    }
}
