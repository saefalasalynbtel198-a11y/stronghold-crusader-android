package io.github.saefalasalynbtel198.shcport.core;

/** User-adjustable touch control parameters. Values are clamped to sane ranges by {@link #sanitized()}. */
public final class ControlSettings {

    /** Hold time before a stationary finger produces a right click (cancel / deselect in-game). */
    public int longPressMs = 450;
    /**
     * How long a click holds the mouse button (ms), like a real mouse click. The game reads the button state once per
     * frame; a press and release sent together are missed (0.4.0 on the phone: taps did not click).
     */
    public int clickHoldMs = 120;
    /** Maximum gap between two taps that are snapped together so Windows sees a double click. */
    public int doubleTapMs = 300;
    /** Movement (screen px) below which a touch still counts as a tap. */
    public float touchSlopPx = 24f;
    /** Relative change of the two-finger distance that produces one mouse-wheel notch. */
    public float pinchStep = 0.18f;
    /** Two-finger displacement (screen px) that starts holding an arrow key to scroll the map. */
    public float panThresholdPx = 40f;
    /**
     * When false (default) a one-finger drag is a left-button drag, which the game needs for box
     * selection and for drawing walls. When true a one-finger drag scrolls the map instead.
     */
    public boolean oneFingerDragPansCamera = false;
    /** Reverses the scroll direction of map panning. */
    public boolean invertPan = false;

    public ControlSettings sanitized() {
        ControlSettings s = copy();
        s.longPressMs = clamp(s.longPressMs, 200, 1500);
        s.doubleTapMs = clamp(s.doubleTapMs, 100, 800);
        s.clickHoldMs = clamp(s.clickHoldMs, 30, 500);
        s.touchSlopPx = clamp(s.touchSlopPx, 4f, 120f);
        s.pinchStep = clamp(s.pinchStep, 0.05f, 1.0f);
        s.panThresholdPx = clamp(s.panThresholdPx, 8f, 300f);
        return s;
    }

    public ControlSettings copy() {
        ControlSettings s = new ControlSettings();
        s.longPressMs = longPressMs;
        s.doubleTapMs = doubleTapMs;
        s.clickHoldMs = clickHoldMs;
        s.touchSlopPx = touchSlopPx;
        s.pinchStep = pinchStep;
        s.panThresholdPx = panThresholdPx;
        s.oneFingerDragPansCamera = oneFingerDragPansCamera;
        s.invertPan = invertPan;
        return s;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
