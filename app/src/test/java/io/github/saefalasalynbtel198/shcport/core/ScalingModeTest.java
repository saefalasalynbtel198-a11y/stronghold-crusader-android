package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Display Scaling Mode (0.6.1): geometry of Stretch / Preserve / Fill / Auto, touch mapping in every mode, the
 * cnc-ddraw rule (only Stretch on a non-4:3 screen changes the ini), and transform rebuilds (pause/resume).
 */
public class ScalingModeTest {

    /** The owner's phone: 2772x1280 display; the game area is left of the ~232 px button column. */
    private static final int AREA_W = 2540, AREA_H = 1280;
    private static final int[][] AREAS = {{AREA_W, AREA_H}, {2400, 1080}, {1786, 1228}, {1280, 1600}};
    private static final int[][] SCREENS = {{640, 480}, {800, 600}, {1024, 768}, {1280, 960}, {2536, 1280}};

    @Test
    public void defaultIsStretchAndKeysRoundTrip() {
        assertEquals(ScalingMode.STRETCH, ScalingMode.DEFAULT);
        assertEquals(ScalingMode.STRETCH, ScalingMode.fromKey(null));
        assertEquals(ScalingMode.STRETCH, ScalingMode.fromKey("nonsense"));
        for (ScalingMode m : ScalingMode.values()) assertEquals(m, ScalingMode.fromKey(m.key));
        assertEquals(ScalingMode.PRESERVE, ScalingMode.AUTO.effective());
    }

    /** 4:3 game screen on the wide phone area in each mode. */
    @Test
    public void fourThreeOnAWidePhone() {
        ViewportTransform p = new ViewportTransform(1024, 768, AREA_W, AREA_H, ScalingMode.PRESERVE);
        assertEquals(1280f / 768, p.scaleX, 1e-5);
        assertEquals(p.scaleX, p.scaleY, 0f);
        assertEquals((AREA_W - 1024 * p.scaleX) / 2, p.offsetX, 1e-3);   // pillar bars
        assertEquals(0f, p.offsetY, 1e-3);
        assertFalse(p.contains(10, 640));
        assertFalse(p.contains(AREA_W - 10, 640));

        ViewportTransform s = new ViewportTransform(1024, 768, AREA_W, AREA_H, ScalingMode.STRETCH);
        assertEquals((float) AREA_W / 1024, s.scaleX, 1e-6);
        assertEquals((float) AREA_H / 768, s.scaleY, 1e-6);
        assertEquals(0f, s.offsetX, 0f);
        assertEquals(0f, s.offsetY, 0f);
        assertEquals(AREA_W, s.drawnWidth(), 1e-2);                       // the whole area: no bars
        assertEquals(AREA_H, s.drawnHeight(), 1e-2);
        assertTrue(s.contains(0, 0) && s.contains(AREA_W - 1, AREA_H - 1));
        assertEquals(0, s.toGameX(0));
        assertEquals(1023, s.toGameX(AREA_W - 0.01f));                    // nothing cropped: every column reachable
        assertEquals(767, s.toGameY(AREA_H - 0.01f));
        assertArrayEquals(new int[] {0, 0, 1024, 768}, s.visibleGameRect(AREA_W, AREA_H));

        ViewportTransform f = new ViewportTransform(1024, 768, AREA_W, AREA_H, ScalingMode.FILL);
        assertEquals((float) AREA_W / 1024, f.scale, 1e-6);
        assertEquals(0f, f.offsetX, 1e-3);
        assertTrue(f.offsetY < 0);                                        // top and bottom cut off equally
        assertEquals(-f.offsetY, f.drawnHeight() + f.offsetY - AREA_H, 1e-2);
        int[] vis = f.visibleGameRect(AREA_W, AREA_H);
        assertEquals(0, vis[0]);
        assertEquals(1024, vis[2]);
        assertTrue(vis[1] > 0 && vis[3] < 768);
        assertEquals(768 - vis[3], vis[1], 1);                            // symmetric crop
        assertTrue(f.contains(0, 0) && f.contains(AREA_W - 1, AREA_H - 1));

        ViewportTransform a = new ViewportTransform(1024, 768, AREA_W, AREA_H, ScalingMode.AUTO);
        assertEquals(ScalingMode.PRESERVE, a.mode);
        assertEquals(p.offsetX, a.offsetX, 0f);
        assertEquals(p.scale, a.scale, 0f);
    }

    /** Device resolution: the screen has the area's shape, so every mode shows the whole picture without bars. */
    @Test
    public void deviceResolutionFillsTheAreaInEveryMode() {
        DisplayResolution.Screen d = DisplayResolution.resolve(DisplayResolution.Mode.DEVICE, AREA_W, AREA_H, 12L << 30);
        for (ScalingMode m : ScalingMode.values()) {
            ViewportTransform v = new ViewportTransform(d.width, d.height, AREA_W, AREA_H, m);
            assertTrue(m + " " + v.offsetX, v.offsetX > -2 && v.offsetX < 3);    // width rounded to 8: at most a few px
            assertTrue(m + " " + v.offsetY, Math.abs(v.offsetY) < 3);
        }
    }

    /** Touch: the game pixel drawn at a point is the pixel clicked, in every mode, screen and area. */
    @Test
    public void touchMapsToTheDrawnPixelInEveryMode() {
        for (ScalingMode mode : ScalingMode.values()) {
            for (int[] scr : SCREENS) {
                for (int[] area : AREAS) {
                    ViewportTransform vt = new ViewportTransform(scr[0], scr[1], area[0], area[1], mode);
                    TouchMouseMapper m = new TouchMouseMapper(new ControlSettings(), vt);
                    String where = mode + " " + scr[0] + "x" + scr[1] + " on " + area[0] + "x" + area[1];
                    int[] vis = vt.visibleGameRect(area[0], area[1]);
                    int[][] targets = {{vis[0], vis[1]}, {vis[2] - 1, vis[1]}, {vis[0], vis[3] - 1}, {vis[2] - 1, vis[3] - 1},
                            {scr[0] / 2, scr[1] / 2}, {scr[0] / 3, scr[1] * 2 / 3}};
                    long t = 0;
                    for (int[] g : targets) {
                        // the centre of the pixel, or (Fill: an edge pixel cut through by the view edge) its visible part
                        float sx = Math.max(0f, Math.min(area[0] - 0.001f, vt.viewX(g[0])));
                        float sy = Math.max(0f, Math.min(area[1] - 0.001f, vt.viewY(g[1])));
                        assertTrue(where + " target " + g[0] + "," + g[1] + " far outside the view",
                                Math.abs(sx - vt.viewX(g[0])) <= vt.scaleX / 2 + 0.25f && Math.abs(sy - vt.viewY(g[1])) <= vt.scaleY / 2 + 0.25f);
                        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, sx, sy, t));
                        List<InputAction> up = m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, sx, sy, t + 60));
                        assertEquals(where, "[MOVE(" + g[0] + "," + g[1] + "), LEFT_DOWN, PAUSE(120), LEFT_UP]", up.toString());
                        t += 1000;
                    }
                    // area corners: in Stretch and Fill they are on the picture (no bars), in Preserve maybe in a bar
                    for (float[] c : new float[][] {{0.5f, 0.5f}, {area[0] - 0.5f, area[1] - 0.5f}}) {
                        List<InputAction> all = new ArrayList<>(m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, c[0], c[1], t)));
                        all.addAll(m.onTick(t + 2000));
                        all.addAll(m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, c[0], c[1], t + 2100)));
                        if (vt.contains(c[0], c[1])) {
                            String s = all.toString();
                            assertTrue(where + " corner " + s, s.contains("MOVE(" + vt.toGameX(c[0]) + "," + vt.toGameY(c[1]) + ")"));
                        } else {
                            assertTrue(where + " bar touch sent " + all, all.isEmpty());
                        }
                        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.CANCEL, 0, 0, t + 2200));
                        t += 3000;
                    }
                }
            }
        }
    }

    /** Stretch: a drag across the screen moves by the stretched amounts per axis; gestures unchanged. */
    @Test
    public void gesturesUnderStretch() {
        ViewportTransform vt = new ViewportTransform(1024, 768, AREA_W, AREA_H, ScalingMode.STRETCH);
        TouchMouseMapper m = new TouchMouseMapper(new ControlSettings(), vt);
        // drag from game (100,100) to (900,700): the left button is held and released at the end point
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, vt.viewX(100), vt.viewY(100), 0));
        String drag = m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.MOVE, vt.viewX(500), vt.viewY(400), 50)).toString()
                + m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.MOVE, vt.viewX(900), vt.viewY(700), 100))
                + m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, vt.viewX(900), vt.viewY(700), 400));
        assertTrue(drag, drag.contains("LEFT_DOWN") && drag.contains("MOVE(900,700)") && drag.endsWith("LEFT_UP]"));
        // long press = right click at the pixel under the finger
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, vt.viewX(300), vt.viewY(200), 1000));
        String lp = m.onTick(1000 + 500).toString();
        assertTrue(lp, lp.contains("MOVE(300,200)") && lp.contains("RIGHT_DOWN"));
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, vt.viewX(300), vt.viewY(200), 1600));
        // double tap
        float dx = vt.viewX(512), dy = vt.viewY(384);
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, dx, dy, 3000));
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, dx, dy, 3060));
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, dx, dy, 3200));
        String dbl = m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, dx, dy, 3260)).toString();
        assertTrue(dbl, dbl.contains("MOVE(512,384)") && dbl.contains("LEFT_DOWN"));
        // two-finger scroll and pinch
        float cx = AREA_W / 2f, cy = AREA_H / 2f;
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, cx, cy, 5000));
        m.onTouch(new TouchMouseMapper.Frame(TouchMouseMapper.Action.POINTER_DOWN, 1, new int[] {0, 1},
                new float[] {cx, cx + 100}, new float[] {cy, cy}, 5010));
        String scroll = m.onTouch(new TouchMouseMapper.Frame(TouchMouseMapper.Action.MOVE, 0, new int[] {0, 1},
                new float[] {cx + 300, cx + 400}, new float[] {cy, cy}, 5100)).toString();
        assertTrue(scroll, scroll.contains("KEY_DOWN"));
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.CANCEL, 0, 0, 5200));
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, cx - 50, cy, 6000));
        m.onTouch(new TouchMouseMapper.Frame(TouchMouseMapper.Action.POINTER_DOWN, 1, new int[] {0, 1},
                new float[] {cx - 50, cx + 50}, new float[] {cy, cy}, 6010));
        String pinch = m.onTouch(new TouchMouseMapper.Frame(TouchMouseMapper.Action.MOVE, 0, new int[] {0, 1},
                new float[] {cx - 300, cx + 300}, new float[] {cy, cy}, 6100)).toString();
        assertTrue(pinch, pinch.contains("WHEEL"));
    }

    /** Pause/resume and rotation rebuild the transform from the same inputs: mapping is identical afterwards. */
    @Test
    public void rebuildAfterResumeMapsIdentically() {
        for (ScalingMode mode : ScalingMode.values()) {
            ViewportTransform before = new ViewportTransform(1024, 768, AREA_W, AREA_H, mode);
            TouchMouseMapper m = new TouchMouseMapper(new ControlSettings(), before);
            ViewportTransform after = new ViewportTransform(1024, 768, AREA_W, AREA_H, mode);   // new surface
            m.setViewport(after);
            for (float x = 0.5f; x < AREA_W; x += 97.3f) {
                for (float y = 0.5f; y < AREA_H; y += 61.7f) {
                    assertEquals(before.contains(x, y), after.contains(x, y));
                    assertEquals(before.toGameX(x), after.toGameX(x));
                    assertEquals(before.toGameY(y), after.toGameY(y));
                }
            }
        }
    }

    /** cnc-ddraw: unchanged except Stretch on a non-4:3 screen (Device), which turns maintas off. */
    @Test
    public void cncDdrawChangesOnlyForStretchOnAWideScreen() {
        String base1024 = new CncDdrawConfig().toIni();
        for (ScalingMode m : ScalingMode.values()) {
            for (int[] s : new int[][] {{640, 480}, {800, 600}, {1024, 768}, {1280, 960}}) {
                assertFalse(CncDdrawConfig.stretchesGame(m, s[0], s[1]));
                assertEquals(new CncDdrawConfig().forScreen(s[0], s[1]).toIni(),
                        new CncDdrawConfig().forScreen(s[0], s[1]).forScaling(m, s[0], s[1]).toIni());
            }
        }
        assertEquals(base1024, new CncDdrawConfig().forScreen(1024, 768).forScaling(ScalingMode.STRETCH, 1024, 768).toIni());
        String wide = new CncDdrawConfig().forScreen(2536, 1280).forScaling(ScalingMode.STRETCH, 2536, 1280).toIni();
        assertTrue(wide.contains("maintas=false\r\n"));
        assertFalse(wide.contains("maintas=true"));
        assertTrue(wide.contains("adjmouse=true\r\n"));
        assertEquals(new CncDdrawConfig().forScreen(2536, 1280).toIni().replace("maintas=true", "maintas=false"), wide);
        for (ScalingMode m : new ScalingMode[] {ScalingMode.PRESERVE, ScalingMode.FILL, ScalingMode.AUTO}) {
            assertTrue(new CncDdrawConfig().forScreen(2536, 1280).forScaling(m, 2536, 1280).toIni().contains("maintas=true"));
        }
    }

    private static void assertArrayEquals(int[] want, int[] got) {
        assertEquals(java.util.Arrays.toString(want), java.util.Arrays.toString(got));
    }
}
