package io.github.saefalasalynbtel198.shcport.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts raw multitouch frames into mouse and keyboard events the original game understands,
 * without changing game mechanics:
 *
 * <ul>
 *   <li>tap → left click (select, place building, give order); the button is held for
 *       {@link ControlSettings#clickHoldMs} like a real mouse click, because the game reads the button state once per
 *       frame and misses a press and release sent together</li>
 *   <li>two quick taps → two clicks snapped to the same pixel, so Windows reports a double click
 *       (selects all units of that type)</li>
 *   <li>long press → right click (deselect / cancel placement)</li>
 *   <li>right-button mode ({@link #setRightButtonMode}): one finger holds the right button while it touches the
 *       screen (tap = right click, drag = right-button drag for the camera interface)</li>
 *   <li>one-finger drag → left-button drag (box select, wall drawing); optionally camera pan</li>
 *   <li>two-finger drag → holds arrow keys to scroll the map</li>
 *   <li>pinch → mouse-wheel notches at the pinch centre</li>
 * </ul>
 *
 * <p>Pure Java and single-threaded; the Android view feeds it {@link Frame}s and calls
 * {@link #onTick(long)} when the long-press deadline passes.
 */
public final class TouchMouseMapper {

    public enum Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    /** One MotionEvent worth of data, in screen pixels. */
    public static final class Frame {
        public final Action action;
        public final int actionPointerId;
        public final int[] ids;
        public final float[] xs;
        public final float[] ys;
        public final long timeMs;

        public Frame(Action action, int actionPointerId, int[] ids, float[] xs, float[] ys, long timeMs) {
            if (ids.length != xs.length || ids.length != ys.length) {
                throw new IllegalArgumentException("Pointer arrays must have equal length");
            }
            this.action = action;
            this.actionPointerId = actionPointerId;
            this.ids = ids;
            this.xs = xs;
            this.ys = ys;
            this.timeMs = timeMs;
        }

        /** Convenience for single-pointer frames (pointer id 0). */
        public static Frame single(Action action, float x, float y, long t) {
            return new Frame(action, 0, new int[] {0}, new float[] {x}, new float[] {y}, t);
        }

        int indexOf(int id) {
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) return i;
            return -1;
        }
    }

    enum State { IDLE, PENDING, LEFT_DRAG, RIGHT_HOLD, CAMERA_DRAG, LONG_PRESSED, MULTI, WAIT_ALL_UP }

    private ControlSettings settings;
    private ViewportTransform viewport;
    private State state = State.IDLE;

    private int primaryId;
    private boolean rightButtonMode;
    /** When the held button (drag or right-button mode) was pressed. */
    private long pressTime;
    private float downX, downY;
    private long downTime;

    private boolean hasLastTap;
    private float lastTapX, lastTapY;
    private long lastTapTime;

    private float baseDistance;
    private float baseCx, baseCy;
    private int heldHorizontal;
    private int heldVertical;

    public TouchMouseMapper(ControlSettings settings, ViewportTransform viewport) {
        this.settings = settings.sanitized();
        this.viewport = viewport;
    }

    public void setSettings(ControlSettings s) { this.settings = s.sanitized(); }

    public void setViewport(ViewportTransform v) { this.viewport = v; }

    /** On: the next one-finger touches hold the right mouse button instead of producing left clicks. */
    public void setRightButtonMode(boolean on) { this.rightButtonMode = on; }

    public boolean rightButtonMode() { return rightButtonMode; }

    State state() { return state; }

    /** Time at which {@link #onTick} should be called to fire a long press, or -1 if none pending. */
    public long longPressDeadline() {
        return state == State.PENDING ? downTime + settings.longPressMs : -1;
    }

    public List<InputAction> onTick(long nowMs) {
        List<InputAction> out = new ArrayList<>();
        if (state == State.PENDING && nowMs - downTime >= settings.longPressMs) {
            click(out, InputAction.Button.RIGHT, downX, downY);
            hasLastTap = false;
            state = State.LONG_PRESSED;
        }
        return out;
    }

    public List<InputAction> onTouch(Frame f) {
        List<InputAction> out = new ArrayList<>();
        switch (f.action) {
            case DOWN: onDown(f, out); break;
            case POINTER_DOWN: onPointerDown(f, out); break;
            case MOVE: onMove(f, out); break;
            case POINTER_UP: onPointerUp(f, out); break;
            case UP: onUp(f, out); break;
            case CANCEL: cancel(out); break;
            default: break;
        }
        return out;
    }

    private void onDown(Frame f, List<InputAction> out) {
        if (state != State.IDLE) cancel(out);
        primaryId = f.actionPointerId;
        int i = Math.max(0, f.indexOf(primaryId));
        downX = f.xs[i];
        downY = f.ys[i];
        downTime = f.timeMs;
        if (!viewport.contains(downX, downY)) {
            // a touch that starts in the black bars does nothing (no move, no click) until all fingers are up
            hasLastTap = false;
            state = State.WAIT_ALL_UP;
            return;
        }
        out.add(moveTo(downX, downY));
        if (rightButtonMode) {
            state = State.RIGHT_HOLD;
            pressTime = f.timeMs;
            hasLastTap = false;
            out.add(InputAction.down(InputAction.Button.RIGHT));
        } else {
            state = State.PENDING;
        }
    }

    private void onPointerDown(Frame f, List<InputAction> out) {
        switch (state) {
            case PENDING:
                hasLastTap = false;
                beginMulti(f);
                break;
            case CAMERA_DRAG:
                releasePanKeys(out);
                beginMulti(f);
                break;
            case MULTI:
                beginMulti(f); // re-baseline with the new finger set
                break;
            default:
                // LEFT_DRAG keeps tracking the primary finger only; LONG_PRESSED / WAIT_ALL_UP ignore.
                break;
        }
    }

    private void onMove(Frame f, List<InputAction> out) {
        int i = f.indexOf(primaryId);
        switch (state) {
            case PENDING: {
                if (i < 0) return;
                float x = f.xs[i], y = f.ys[i];
                if (distance(x, y, downX, downY) > settings.touchSlopPx) {
                    hasLastTap = false;
                    if (settings.oneFingerDragPansCamera) {
                        state = State.CAMERA_DRAG;
                        updatePan(x - downX, y - downY, out);
                    } else {
                        state = State.LEFT_DRAG;
                        pressTime = f.timeMs;
                        out.add(moveTo(downX, downY));
                        out.add(InputAction.down(InputAction.Button.LEFT));
                        out.add(moveTo(x, y));
                    }
                }
                break;
            }
            case LEFT_DRAG:
            case RIGHT_HOLD:
                if (i >= 0) out.add(moveTo(f.xs[i], f.ys[i]));
                break;
            case CAMERA_DRAG:
                if (i >= 0) updatePan(f.xs[i] - downX, f.ys[i] - downY, out);
                break;
            case MULTI:
                updateMulti(f, out);
                break;
            default:
                break;
        }
    }

    private void onPointerUp(Frame f, List<InputAction> out) {
        switch (state) {
            case MULTI:
                if (f.ids.length - 1 < 2) {
                    releasePanKeys(out);
                    state = State.WAIT_ALL_UP;
                } else {
                    beginMultiExcluding(f, f.actionPointerId);
                }
                break;
            case LEFT_DRAG:
            case RIGHT_HOLD:
                if (f.actionPointerId == primaryId) {
                    int i = f.indexOf(primaryId);
                    if (i >= 0) out.add(moveTo(f.xs[i], f.ys[i]));
                    release(out, state == State.RIGHT_HOLD ? InputAction.Button.RIGHT : InputAction.Button.LEFT, f.timeMs);
                    state = State.WAIT_ALL_UP;
                }
                break;
            case CAMERA_DRAG:
                if (f.actionPointerId == primaryId) {
                    releasePanKeys(out);
                    state = State.WAIT_ALL_UP;
                }
                break;
            default:
                break;
        }
    }

    private void onUp(Frame f, List<InputAction> out) {
        int i = Math.max(0, f.indexOf(f.actionPointerId));
        float x = f.xs[i], y = f.ys[i];
        switch (state) {
            case PENDING: {
                float tx = downX, ty = downY;
                // gap from the first tap's lift to this touch (independent of how long each click is held)
                boolean isSecondTap = hasLastTap
                        && downTime - lastTapTime <= settings.doubleTapMs
                        && distance(downX, downY, lastTapX, lastTapY) <= settings.touchSlopPx * 2;
                if (isSecondTap) {
                    tx = lastTapX;
                    ty = lastTapY;
                }
                click(out, InputAction.Button.LEFT, tx, ty);
                if (isSecondTap) {
                    hasLastTap = false; // a third tap starts a new sequence
                } else {
                    hasLastTap = true;
                    lastTapX = tx;
                    lastTapY = ty;
                }
                lastTapTime = f.timeMs;
                break;
            }
            case LEFT_DRAG:
                out.add(moveTo(x, y));
                release(out, InputAction.Button.LEFT, f.timeMs);
                break;
            case RIGHT_HOLD:
                out.add(moveTo(x, y));
                release(out, InputAction.Button.RIGHT, f.timeMs);
                break;
            case CAMERA_DRAG:
            case MULTI:
                releasePanKeys(out);
                break;
            default:
                break;
        }
        state = State.IDLE;
    }

    private void cancel(List<InputAction> out) {
        if (state == State.LEFT_DRAG) out.add(InputAction.up(InputAction.Button.LEFT));
        if (state == State.RIGHT_HOLD) out.add(InputAction.up(InputAction.Button.RIGHT));
        releasePanKeys(out);
        hasLastTap = false;
        state = State.IDLE;
    }

    private void beginMulti(Frame f) { beginMultiExcluding(f, Integer.MIN_VALUE); }

    private void beginMultiExcluding(Frame f, int excludedId) {
        float[] c = centroidAndSpread(f, excludedId);
        baseCx = c[0];
        baseCy = c[1];
        baseDistance = Math.max(1f, c[2]);
        state = State.MULTI;
    }

    private void updateMulti(Frame f, List<InputAction> out) {
        float[] c = centroidAndSpread(f, Integer.MIN_VALUE);
        if (c[3] < 2) return;
        float ratio = c[2] / baseDistance;
        float step = 1f + settings.pinchStep;
        if (ratio >= step || ratio <= 1f / step) {
            out.add(moveTo(c[0], c[1]));
            out.add(InputAction.wheel(ratio >= step ? 1 : -1));
            baseDistance = Math.max(1f, c[2]);
        }
        updatePan(c[0] - baseCx, c[1] - baseCy, out);
    }

    /** Holds arrow keys while the drag displacement exceeds the threshold (joystick-style scrolling). */
    private void updatePan(float dx, float dy, List<InputAction> out) {
        float t = settings.panThresholdPx;
        // Dragging the "world" right means the camera moves left, unless inverted.
        int wantH = 0;
        if (dx > t) wantH = settings.invertPan ? InputAction.VK_RIGHT : InputAction.VK_LEFT;
        else if (dx < -t) wantH = settings.invertPan ? InputAction.VK_LEFT : InputAction.VK_RIGHT;
        int wantV = 0;
        if (dy > t) wantV = settings.invertPan ? InputAction.VK_DOWN : InputAction.VK_UP;
        else if (dy < -t) wantV = settings.invertPan ? InputAction.VK_UP : InputAction.VK_DOWN;

        if (wantH != heldHorizontal) {
            if (heldHorizontal != 0) out.add(InputAction.keyUp(heldHorizontal));
            if (wantH != 0) out.add(InputAction.keyDown(wantH));
            heldHorizontal = wantH;
        }
        if (wantV != heldVertical) {
            if (heldVertical != 0) out.add(InputAction.keyUp(heldVertical));
            if (wantV != 0) out.add(InputAction.keyDown(wantV));
            heldVertical = wantV;
        }
    }

    private void releasePanKeys(List<InputAction> out) {
        if (heldHorizontal != 0) out.add(InputAction.keyUp(heldHorizontal));
        if (heldVertical != 0) out.add(InputAction.keyUp(heldVertical));
        heldHorizontal = 0;
        heldVertical = 0;
    }

    /** Returns {cx, cy, mean distance between consecutive pointers, count}. */
    private static float[] centroidAndSpread(Frame f, int excludedId) {
        float sx = 0, sy = 0;
        int n = 0;
        for (int i = 0; i < f.ids.length; i++) {
            if (f.ids[i] == excludedId) continue;
            sx += f.xs[i];
            sy += f.ys[i];
            n++;
        }
        if (n == 0) return new float[] {0, 0, 1, 0};
        float cx = sx / n, cy = sy / n;
        float spread = 0;
        for (int i = 0; i < f.ids.length; i++) {
            if (f.ids[i] == excludedId) continue;
            spread += distance(f.xs[i], f.ys[i], cx, cy);
        }
        return new float[] {cx, cy, spread / n * 2f, n};
    }

    /** A complete click: pointer to the position, press, hold like a real mouse click, release. */
    private void click(List<InputAction> out, InputAction.Button b, float x, float y) {
        out.add(moveTo(x, y));
        out.add(InputAction.down(b));
        out.add(InputAction.pause(settings.clickHoldMs));
        out.add(InputAction.up(b));
    }

    /** Releases a held button; a very short hold is extended to a click's hold time first. */
    private void release(List<InputAction> out, InputAction.Button b, long nowMs) {
        long held = nowMs - pressTime;
        if (held < settings.clickHoldMs) out.add(InputAction.pause((int) (settings.clickHoldMs - Math.max(0, held))));
        out.add(InputAction.up(b));
    }

    private InputAction moveTo(float x, float y) {
        return InputAction.move(viewport.toGameX(x), viewport.toGameY(y));
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
