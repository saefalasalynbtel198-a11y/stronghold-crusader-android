package io.github.saefalasalynbtel198.shcport.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

import io.github.saefalasalynbtel198.shcport.core.ControlSettings;

/** Adjusts touch-control sensitivity. Changes are saved immediately. */
public final class SettingsActivity extends Activity {

    private interface IntSetter { void set(int value); }

    private ControlSettingsStore store;
    private ControlSettings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new ControlSettingsStore(this);
        settings = store.load();
        build();
    }

    private void build() {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.title(this, "Touch control settings"));
        col.addView(Ui.muted(this, "Try changes on the touch-controls test screen. Defaults keep the original "
                + "mouse mechanics: one-finger drag = left-button drag (box select, walls)."));

        addSlider(col, "Long press → right click (ms)", 200, 1500, settings.longPressMs,
                v -> settings.longPressMs = v);
        addSlider(col, "Click: button held for (ms)", 30, 500, settings.clickHoldMs,
                v -> settings.clickHoldMs = v);
        addSlider(col, "Double-tap window (ms)", 100, 800, settings.doubleTapMs,
                v -> settings.doubleTapMs = v);
        addSlider(col, "Tap tolerance (px)", 4, 120, Math.round(settings.touchSlopPx),
                v -> settings.touchSlopPx = v);
        addSlider(col, "Pinch sensitivity (% change per wheel notch)", 5, 100, Math.round(settings.pinchStep * 100),
                v -> settings.pinchStep = v / 100f);
        addSlider(col, "Two-finger scroll threshold (px)", 8, 300, Math.round(settings.panThresholdPx),
                v -> settings.panThresholdPx = v);

        addSwitch(col, "One-finger drag scrolls the map (disables drag-select and wall drawing by drag)",
                settings.oneFingerDragPansCamera, (b, on) -> { settings.oneFingerDragPansCamera = on; save(); });
        addSwitch(col, "Invert scroll direction", settings.invertPan,
                (b, on) -> { settings.invertPan = on; save(); });

        col.addView(Ui.button(this, "Reset to defaults", v -> {
            store.reset();
            settings = store.load();
            build();
        }));
        setContentView(Ui.scroll(this, col));
    }

    private void addSlider(LinearLayout col, String label, final int min, int max, int value, final IntSetter setter) {
        final TextView t = Ui.body(this, "");
        col.addView(Ui.heading(this, label));
        col.addView(t);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        t.setText(String.format(Locale.ROOT, "%d", value));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                t.setText(String.format(Locale.ROOT, "%d", progress + min));
                if (fromUser) setter.set(progress + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { save(); }
        });
        col.addView(bar);
    }

    private void addSwitch(LinearLayout col, String label, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TEXT);
        s.setChecked(checked);
        s.setMinHeight(Ui.dp(this, 48));
        s.setOnCheckedChangeListener(l);
        col.addView(s);
    }

    private void save() { store.save(settings); }
}
