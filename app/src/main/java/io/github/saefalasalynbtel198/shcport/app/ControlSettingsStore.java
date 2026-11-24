package io.github.saefalasalynbtel198.shcport.app;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.saefalasalynbtel198.shcport.core.ControlSettings;

/** Persists {@link ControlSettings} in SharedPreferences. */
public final class ControlSettingsStore {

    private static final String FILE = "controls";
    private final SharedPreferences prefs;

    public ControlSettingsStore(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public ControlSettings load() {
        ControlSettings d = new ControlSettings();
        ControlSettings s = new ControlSettings();
        s.longPressMs = prefs.getInt("longPressMs", d.longPressMs);
        s.doubleTapMs = prefs.getInt("doubleTapMs", d.doubleTapMs);
        s.clickHoldMs = prefs.getInt("clickHoldMs", d.clickHoldMs);
        s.touchSlopPx = prefs.getFloat("touchSlopPx", d.touchSlopPx);
        s.pinchStep = prefs.getFloat("pinchStep", d.pinchStep);
        s.panThresholdPx = prefs.getFloat("panThresholdPx", d.panThresholdPx);
        s.oneFingerDragPansCamera = prefs.getBoolean("oneFingerDragPansCamera", d.oneFingerDragPansCamera);
        s.invertPan = prefs.getBoolean("invertPan", d.invertPan);
        return s.sanitized();
    }

    public void save(ControlSettings in) {
        ControlSettings s = in.sanitized();
        prefs.edit()
                .putInt("longPressMs", s.longPressMs)
                .putInt("doubleTapMs", s.doubleTapMs)
                .putInt("clickHoldMs", s.clickHoldMs)
                .putFloat("touchSlopPx", s.touchSlopPx)
                .putFloat("pinchStep", s.pinchStep)
                .putFloat("panThresholdPx", s.panThresholdPx)
                .putBoolean("oneFingerDragPansCamera", s.oneFingerDragPansCamera)
                .putBoolean("invertPan", s.invertPan)
                .apply();
    }

    public void reset() { prefs.edit().clear().apply(); }
}
