package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action;
import io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Frame;

public class TouchMouseMapperTest {

    // 1:1 viewport so screen pixels equal game pixels.
    private final ViewportTransform identity = new ViewportTransform(1024, 768, 1024, 768);

    private TouchMouseMapper mapper(ControlSettings s) { return new TouchMouseMapper(s, identity); }

    private static String str(List<InputAction> actions) { return actions.toString(); }

    private static Frame two(Action a, int actionId, float x0, float y0, float x1, float y1, long t) {
        return new Frame(a, actionId, new int[] {0, 1}, new float[] {x0, x1}, new float[] {y0, y1}, t);
    }

    @Test
    public void tapIsLeftClickAtFingerPosition() {
        TouchMouseMapper m = mapper(new ControlSettings());
        List<InputAction> out = new ArrayList<>();
        out.addAll(m.onTouch(Frame.single(Action.DOWN, 100, 200, 0)));
        out.addAll(m.onTouch(Frame.single(Action.MOVE, 105, 203, 50)));
        out.addAll(m.onTouch(Frame.single(Action.UP, 105, 203, 90)));
        // one complete click with the button held like a real mouse click (0.4.0 sent press and release together)
        assertEquals("[MOVE(100,200), MOVE(100,200), LEFT_DOWN, PAUSE(120), LEFT_UP]", str(out));
        assertEquals(TouchMouseMapper.State.IDLE, m.state());
    }

    @Test
    public void longPressIsRightClickAndSuppressesLeftClick() {
        ControlSettings s = new ControlSettings();
        s.longPressMs = 400;
        TouchMouseMapper m = mapper(s);
        m.onTouch(Frame.single(Action.DOWN, 50, 60, 1000));
        assertEquals(1400, m.longPressDeadline());
        assertTrue(m.onTick(1300).isEmpty());
        assertEquals("[MOVE(50,60), RIGHT_DOWN, PAUSE(120), RIGHT_UP]", str(m.onTick(1400)));
        assertTrue(m.onTouch(Frame.single(Action.UP, 50, 60, 1600)).isEmpty());
        assertEquals(-1, m.longPressDeadline());
    }

    @Test
    public void dragIsLeftButtonDragForBoxSelectAndWalls() {
        TouchMouseMapper m = mapper(new ControlSettings());
        List<InputAction> out = new ArrayList<>();
        out.addAll(m.onTouch(Frame.single(Action.DOWN, 100, 100, 0)));
        out.addAll(m.onTouch(Frame.single(Action.MOVE, 200, 150, 30)));
        out.addAll(m.onTouch(Frame.single(Action.MOVE, 300, 200, 60)));
        out.addAll(m.onTick(10_000)); // no long press once dragging
        out.addAll(m.onTouch(Frame.single(Action.UP, 310, 210, 90)));
        // pressed at t=30 when the finger left the tap radius, lifted at t=90: held to the click time before release
        assertEquals("[MOVE(100,100), MOVE(100,100), LEFT_DOWN, MOVE(200,150), MOVE(300,200), "
                + "MOVE(310,210), PAUSE(60), LEFT_UP]", str(out));
    }

    @Test
    public void secondTapSnapsToFirstSoWindowsSeesDoubleClick() {
        TouchMouseMapper m = mapper(new ControlSettings());
        m.onTouch(Frame.single(Action.DOWN, 400, 300, 0));
        m.onTouch(Frame.single(Action.UP, 400, 300, 60));
        m.onTouch(Frame.single(Action.DOWN, 412, 309, 150));
        List<InputAction> second = m.onTouch(Frame.single(Action.UP, 412, 309, 200));
        assertEquals("[MOVE(400,300), LEFT_DOWN, PAUSE(120), LEFT_UP]", str(second));
        // A tap after the double-tap window is not snapped.
        m.onTouch(Frame.single(Action.DOWN, 412, 309, 1000));
        assertEquals("[MOVE(412,309), LEFT_DOWN, PAUSE(120), LEFT_UP]", str(m.onTouch(Frame.single(Action.UP, 412, 309, 1050))));
    }

    @Test
    public void pinchProducesWheelNotchesAtCentre() {
        ControlSettings s = new ControlSettings();
        s.pinchStep = 0.2f;
        s.panThresholdPx = 1000; // isolate zoom
        TouchMouseMapper m = mapper(s);
        m.onTouch(Frame.single(Action.DOWN, 400, 400, 0));
        assertTrue(m.onTouch(two(Action.POINTER_DOWN, 1, 400, 400, 500, 400, 10)).isEmpty());
        List<InputAction> out = m.onTouch(two(Action.MOVE, 0, 380, 400, 520, 400, 20)); // spread 100 → 140
        assertEquals("[MOVE(450,400), WHEEL(1)]", str(out));
        out = m.onTouch(two(Action.MOVE, 0, 430, 400, 470, 400, 30)); // 140 → 40
        assertEquals("[MOVE(450,400), WHEEL(-1)]", str(out));
        assertTrue(m.onTouch(two(Action.POINTER_UP, 1, 430, 400, 470, 400, 40)).isEmpty());
        assertTrue(m.onTouch(Frame.single(Action.UP, 430, 400, 50)).isEmpty()); // no stray click
    }

    @Test
    public void twoFingerDragHoldsArrowKeysAndReleasesThem() {
        ControlSettings s = new ControlSettings();
        s.panThresholdPx = 30;
        TouchMouseMapper m = mapper(s);
        m.onTouch(Frame.single(Action.DOWN, 100, 100, 0));
        m.onTouch(two(Action.POINTER_DOWN, 1, 100, 100, 200, 100, 5));
        // Fingers move right by 50 px: world follows the fingers, so the camera scrolls left.
        assertEquals("[KEY_DOWN(0x25)]", str(m.onTouch(two(Action.MOVE, 0, 150, 100, 250, 100, 20))));
        // Then up by 50 px as well: camera scrolls down.
        assertEquals("[KEY_DOWN(0x28)]", str(m.onTouch(two(Action.MOVE, 0, 150, 50, 250, 50, 30))));
        assertEquals("[KEY_UP(0x25), KEY_UP(0x28)]",
                str(m.onTouch(two(Action.POINTER_UP, 1, 150, 50, 250, 50, 40))));
        assertTrue(m.onTouch(Frame.single(Action.UP, 150, 50, 50)).isEmpty());
    }

    @Test
    public void invertPanFlipsDirection() {
        ControlSettings s = new ControlSettings();
        s.panThresholdPx = 30;
        s.invertPan = true;
        TouchMouseMapper m = mapper(s);
        m.onTouch(Frame.single(Action.DOWN, 100, 100, 0));
        m.onTouch(two(Action.POINTER_DOWN, 1, 100, 100, 200, 100, 5));
        assertEquals("[KEY_DOWN(0x27)]", str(m.onTouch(two(Action.MOVE, 0, 150, 100, 250, 100, 20))));
    }

    @Test
    public void oneFingerPanModeScrollsInsteadOfDragging() {
        ControlSettings s = new ControlSettings();
        s.oneFingerDragPansCamera = true;
        s.panThresholdPx = 30;
        TouchMouseMapper m = mapper(s);
        m.onTouch(Frame.single(Action.DOWN, 300, 300, 0));
        assertEquals("[KEY_DOWN(0x27)]", str(m.onTouch(Frame.single(Action.MOVE, 240, 300, 20))));
        assertEquals("[KEY_UP(0x27)]", str(m.onTouch(Frame.single(Action.UP, 240, 300, 40))));
    }

    @Test
    public void cancelDuringDragReleasesButton() {
        TouchMouseMapper m = mapper(new ControlSettings());
        m.onTouch(Frame.single(Action.DOWN, 100, 100, 0));
        m.onTouch(Frame.single(Action.MOVE, 200, 200, 10));
        assertEquals("[LEFT_UP]", str(m.onTouch(Frame.single(Action.CANCEL, 200, 200, 20))));
    }

    @Test
    public void secondFingerBeforeSlopCancelsTheTap() {
        TouchMouseMapper m = mapper(new ControlSettings());
        m.onTouch(Frame.single(Action.DOWN, 100, 100, 0));
        m.onTouch(two(Action.POINTER_DOWN, 1, 100, 100, 110, 100, 5));
        m.onTouch(two(Action.POINTER_UP, 1, 100, 100, 110, 100, 10));
        assertTrue(m.onTouch(Frame.single(Action.UP, 100, 100, 20)).isEmpty());
    }

    @Test
    public void secondFingerDuringDragIsIgnored() {
        TouchMouseMapper m = mapper(new ControlSettings());
        m.onTouch(Frame.single(Action.DOWN, 100, 100, 0));
        m.onTouch(Frame.single(Action.MOVE, 200, 100, 10));
        assertTrue(m.onTouch(two(Action.POINTER_DOWN, 1, 200, 100, 600, 600, 20)).isEmpty());
        assertEquals("[MOVE(250,100)]", str(m.onTouch(two(Action.MOVE, 0, 250, 100, 650, 650, 30))));
        assertEquals("[MOVE(250,100), PAUSE(90), LEFT_UP]", str(m.onTouch(two(Action.POINTER_UP, 0, 250, 100, 650, 650, 40))));
        assertTrue(m.onTouch(new Frame(Action.UP, 1, new int[] {1}, new float[] {650}, new float[] {650}, 50)).isEmpty());
    }

    @Test
    public void tapWithSmallFingerMovementIsStillOneClick() {
        TouchMouseMapper m = mapper(new ControlSettings());
        List<InputAction> out = new ArrayList<>();
        out.addAll(m.onTouch(Frame.single(Action.DOWN, 300, 300, 0)));
        for (int i = 1; i <= 5; i++) out.addAll(m.onTouch(Frame.single(Action.MOVE, 300 + i * 3, 300 - i * 2, i * 10)));  // 18 px < slop
        out.addAll(m.onTouch(Frame.single(Action.UP, 315, 290, 80)));
        assertEquals(1, count(out, "LEFT_DOWN"));
        assertEquals(1, count(out, "LEFT_UP"));
        assertEquals(0, count(out, "RIGHT_DOWN"));
        assertTrue(str(out), str(out).endsWith("MOVE(300,300), LEFT_DOWN, PAUSE(120), LEFT_UP]"));
    }

    @Test
    public void downAloneSendsNoButtonAndDragDoesNotAddATap() {
        TouchMouseMapper m = mapper(new ControlSettings());
        assertEquals("[MOVE(100,100)]", str(m.onTouch(Frame.single(Action.DOWN, 100, 100, 0))));
        List<InputAction> out = new ArrayList<>();
        out.addAll(m.onTouch(Frame.single(Action.MOVE, 300, 100, 50)));
        out.addAll(m.onTouch(Frame.single(Action.UP, 400, 100, 400)));
        assertEquals(1, count(out, "LEFT_DOWN"));
        assertEquals(1, count(out, "LEFT_UP"));
        assertTrue(str(out), str(out).endsWith("MOVE(400,100), LEFT_UP]"));        // held 350 ms: no extra pause
        // the next tap is a single click, not a double click with the drag
        m.onTouch(Frame.single(Action.DOWN, 400, 100, 500));
        assertEquals("[MOVE(400,100), LEFT_DOWN, PAUSE(120), LEFT_UP]", str(m.onTouch(Frame.single(Action.UP, 400, 100, 560))));
    }

    @Test
    public void rightButtonModeHoldsTheRightButtonForTheWholeTouch() {
        TouchMouseMapper m = mapper(new ControlSettings());
        m.setRightButtonMode(true);
        assertEquals("[MOVE(500,400), RIGHT_DOWN]", str(m.onTouch(Frame.single(Action.DOWN, 500, 400, 0))));
        assertEquals(-1, m.longPressDeadline());                                    // no long press in this mode
        assertEquals("[MOVE(560,380)]", str(m.onTouch(Frame.single(Action.MOVE, 560, 380, 300))));
        assertTrue(m.onTouch(two(Action.POINTER_DOWN, 1, 560, 380, 100, 100, 320)).isEmpty());   // second finger ignored
        assertEquals("[MOVE(600,350), RIGHT_UP]", str(m.onTouch(Frame.single(Action.UP, 600, 350, 900))));
        // a quick tap in this mode is a right click with a full hold
        m.onTouch(Frame.single(Action.DOWN, 10, 10, 2000));
        assertEquals("[MOVE(10,10), PAUSE(100), RIGHT_UP]", str(m.onTouch(Frame.single(Action.UP, 10, 10, 2020))));
        // cancel (app paused) releases it
        m.onTouch(Frame.single(Action.DOWN, 10, 10, 3000));
        assertEquals("[RIGHT_UP]", str(m.onTouch(Frame.single(Action.CANCEL, 10, 10, 3010))));
        m.setRightButtonMode(false);
        m.onTouch(Frame.single(Action.DOWN, 20, 20, 4000));
        assertEquals("[MOVE(20,20), LEFT_DOWN, PAUSE(120), LEFT_UP]", str(m.onTouch(Frame.single(Action.UP, 20, 20, 4050))));
    }

    @Test
    public void clickHoldTimeIsConfigurable() {
        ControlSettings s = new ControlSettings();
        s.clickHoldMs = 200;
        TouchMouseMapper m = mapper(s);
        m.onTouch(Frame.single(Action.DOWN, 1, 1, 0));
        assertEquals("[MOVE(1,1), LEFT_DOWN, PAUSE(200), LEFT_UP]", str(m.onTouch(Frame.single(Action.UP, 1, 1, 30))));
        s.clickHoldMs = 5;
        assertEquals(30, s.sanitized().clickHoldMs);
    }

    @Test
    public void everyGestureLeavesNoButtonHeld() {
        TouchMouseMapper m = mapper(new ControlSettings());
        List<InputAction> out = new ArrayList<>();
        long t = 0;
        for (int round = 0; round < 20; round++) {
            out.addAll(m.onTouch(Frame.single(Action.DOWN, 100, 100, t)));
            if (round % 3 == 1) out.addAll(m.onTouch(Frame.single(Action.MOVE, 300, 300, t + 40)));
            if (round % 3 == 2) out.addAll(m.onTick(t + 1000));
            out.addAll(m.onTouch(Frame.single(round % 5 == 4 ? Action.CANCEL : Action.UP, 300, 300, t + 1100)));
            t += 5000;
        }
        assertEquals(count(out, "LEFT_DOWN"), count(out, "LEFT_UP"));
        assertEquals(count(out, "RIGHT_DOWN"), count(out, "RIGHT_UP"));
        // never two presses of a button without a release in between
        int l = 0, r = 0;
        for (InputAction a : out) {
            String x = a.toString();
            if (x.equals("LEFT_DOWN")) assertEquals(0, l++);
            if (x.equals("LEFT_UP")) assertEquals(1, l--);
            if (x.equals("RIGHT_DOWN")) assertEquals(0, r++);
            if (x.equals("RIGHT_UP")) assertEquals(1, r--);
        }
    }

    private static int count(List<InputAction> out, String what) {
        int n = 0;
        for (InputAction a : out) if (a.toString().equals(what)) n++;
        return n;
    }

    @Test
    public void coordinatesAreMappedThroughViewport() {
        // 1024x768 game on a 2400x1080 screen: scale 1.40625, pillar-box offset 480 px.
        TouchMouseMapper m = new TouchMouseMapper(new ControlSettings(), new ViewportTransform(1024, 768, 2400, 1080));
        assertEquals("[MOVE(0,0)]", str(m.onTouch(Frame.single(Action.DOWN, 10, 0, 0))));
        m.onTouch(Frame.single(Action.CANCEL, 0, 0, 1));
        assertEquals("[MOVE(512,384)]", str(m.onTouch(Frame.single(Action.DOWN, 1200, 540, 2))));
    }
}
