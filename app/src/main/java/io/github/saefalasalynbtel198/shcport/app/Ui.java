package io.github.saefalasalynbtel198.shcport.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small helpers for the programmatic UI (no XML layouts, no AndroidX). */
final class Ui {

    static final int TEXT = Color.parseColor("#F2E6CF");
    static final int MUTED = Color.parseColor("#BFAF92");
    static final int ACCENT = Color.parseColor("#D9A441");

    private Ui() {}

    static int dp(Activity a, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, a.getResources().getDisplayMetrics());
    }

    static LinearLayout column(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    static ScrollView scroll(Activity a, View content) {
        ScrollView s = new ScrollView(a);
        s.setBackgroundColor(Color.parseColor("#1A120A"));
        s.addView(content);
        return s;
    }

    static TextView title(Activity a, String text) {
        TextView t = text(a, text, 22, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView heading(Activity a, String text) {
        TextView t = text(a, text, 17, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(a, 14), 0, dp(a, 4));
        return t;
    }

    static TextView body(Activity a, String text) { return text(a, text, 14, TEXT); }

    static TextView muted(Activity a, String text) { return text(a, text, 12, MUTED); }

    static TextView text(Activity a, String text, float sp, int color) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTextIsSelectable(true);
        return t;
    }

    /** Buttons are at least 48dp tall to meet touch-target guidance. */
    static Button button(Activity a, String label, View.OnClickListener onClick) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setMinHeight(dp(a, 48));
        b.setOnClickListener(onClick);
        return b;
    }

    static LinearLayout row(Activity a, View... children) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : children) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, dp(a, 8), 0);
            r.addView(v, lp);
        }
        return r;
    }
}
