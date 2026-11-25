package io.github.saefalasalynbtel198.shcport.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

import io.github.saefalasalynbtel198.shcport.core.ControlSettings;
import io.github.saefalasalynbtel198.shcport.core.DisplayResolution;

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
        col.addView(Ui.title(this, "Settings"));
        buildDisplay(col);
        buildSound(col);
        col.addView(Ui.heading(this, "Touch controls"));
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

    /**
     * Display Resolution: the size of the X screen the game draws into. Saved immediately, applied at the next
     * Start Game; the diagnostic tests always use 1024 × 768.
     */
    private void buildDisplay(LinearLayout col) {
        DisplaySettingsStore store = new DisplaySettingsStore(this);
        DisplayResolution.Mode current = store.load();
        android.graphics.Point real = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getRealSize(real);
        int pw = Math.max(real.x, real.y), ph = Math.min(real.x, real.y);
        long ram = DisplaySettingsStore.totalMemory(this);

        col.addView(Ui.heading(this, "Display Resolution"));
        col.addView(Ui.muted(this, "The size of the screen the game draws into. The picture always keeps its "
                + "4:3 shape (or the phone's shape for Device) with black bars where needed, and touches are "
                + "mapped to it exactly. Changes apply at the next Start Game. Also choose the same resolution in "
                + "the game's own options, otherwise the game keeps the resolution saved there."));
        col.addView(Ui.muted(this, String.format(Locale.ROOT, "This phone: display %d × %d pixels, %.1f GB RAM. "
                + "Device uses the game area beside the side buttons (exact size at Start Game).", pw, ph, ram / 1e9)));

        final TextView now = Ui.body(this, "");
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        for (DisplayResolution.Mode m : DisplayResolution.Mode.values()) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(m.label + " — " + DisplayResolution.describe(m));
            rb.setTextColor(Ui.TEXT);
            rb.setTag(m);
            group.addView(rb);
            if (m == current) rb.setChecked(true);
        }
        Runnable show = () -> {
            DisplayResolution.Mode m = store.load();
            DisplayResolution.Screen sc = DisplayResolution.resolve(m,
                    m == DisplayResolution.Mode.DEVICE ? 0 : pw, ph, ram);
            now.setText("Selected: " + m.label + (m == DisplayResolution.Mode.DEVICE
                    ? " (about " + pw + " × " + ph + " minus the side buttons)" : " → X screen " + sc));
        };
        group.setOnCheckedChangeListener((g, id) -> {
            View v = g.findViewById(id);
            if (v == null) return;
            store.save((DisplayResolution.Mode) v.getTag());
            show.run();
        });
        col.addView(group);
        col.addView(now);
        show.run();
        col.addView(Ui.button(this, "Restore default display (1024 × 768)", v -> {
            store.restoreDefault();
            build();
        }));
    }

    /** Game sound: on/off (next Start Game), volume and mute (immediately, also while the game runs). */
    private void buildSound(LinearLayout col) {
        AudioSettingsStore store = new AudioSettingsStore(this);
        io.github.saefalasalynbtel198.shcport.core.AudioSettings a = store.load();
        ShcApplication app = (ShcApplication) getApplication();
        Runnable save = () -> {
            store.save(a);
            app.audio = a.sanitized();
        };
        col.addView(Ui.heading(this, "Sound"));
        col.addView(Ui.muted(this, "The game's own options set music and effects separately; these apply on top. "
                + "Turning sound on or off takes effect at the next Start Game."));
        addSwitch(col, "Game sound", a.enabled, (b, on) -> { a.enabled = on; save.run(); });
        final TextView t = Ui.body(this, "");
        col.addView(Ui.heading(this, "Volume (%)"));
        col.addView(t);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(a.volume);
        t.setText(String.format(Locale.ROOT, "%d", a.volume));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                t.setText(String.format(Locale.ROOT, "%d", progress));
                if (fromUser) { a.volume = progress; app.audio = a.sanitized(); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { save.run(); }
        });
        col.addView(bar);
        addSwitch(col, "Mute", a.muted, (b, on) -> { a.muted = on; save.run(); });
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
