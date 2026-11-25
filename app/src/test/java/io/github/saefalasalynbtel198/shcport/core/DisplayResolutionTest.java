package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Display Resolution (0.6.0): modes, limits, fallback, Xvfb/cnc-ddraw configuration, pixel check, touch mapping. */
public class DisplayResolutionTest {

    @Test
    public void classicModesAreTheirSize() {
        int[][] want = {{640, 480}, {800, 600}, {1024, 768}, {1280, 960}};
        DisplayResolution.Mode[] modes = {DisplayResolution.Mode.R640X480, DisplayResolution.Mode.R800X600,
                DisplayResolution.Mode.R1024X768, DisplayResolution.Mode.R1280X960};
        for (int i = 0; i < modes.length; i++) {
            DisplayResolution.Screen s = DisplayResolution.resolve(modes[i], 2620, 1280, 12L << 30);
            assertEquals(want[i][0], s.width);
            assertEquals(want[i][1], s.height);
            assertNull(s.toString(), DisplayResolution.validate(s.width, s.height));
        }
    }

    @Test
    public void defaultForExistingAndUnknownSettingsIs1024x768() {
        assertEquals(DisplayResolution.Mode.R1024X768, DisplayResolution.DEFAULT);
        assertEquals(DisplayResolution.Mode.R1024X768, DisplayResolution.Mode.fromKey(null));
        assertEquals(DisplayResolution.Mode.R1024X768, DisplayResolution.Mode.fromKey("garbage"));
        for (DisplayResolution.Mode m : DisplayResolution.Mode.values()) assertEquals(m, DisplayResolution.Mode.fromKey(m.key));
    }

    @Test
    public void deviceResolutionUsesTheGameViewInLandscape() {
        // the owner's phone: game view 2620x1280 (Report of 0.5.0)
        DisplayResolution.Screen s = DisplayResolution.resolve(DisplayResolution.Mode.DEVICE, 2620, 1280, 12L << 30);
        assertEquals(2616, s.width);          // multiple of 8 (cnc-ddraw's Stronghold setting)
        assertEquals(1280, s.height);
        // portrait numbers are turned to landscape
        DisplayResolution.Screen p = DisplayResolution.resolve(DisplayResolution.Mode.DEVICE, 1080, 2400, 0);
        assertEquals(2400, p.width);
        assertEquals(1080, p.height);
        // odd sizes are rounded down
        DisplayResolution.Screen o = DisplayResolution.resolve(DisplayResolution.Mode.DEVICE, 2003, 999, 0);
        assertEquals(2000, o.width);
        assertEquals(998, o.height);
    }

    @Test
    public void veryLargeDisplaysAreScaledDownWithTheirAspectKept() {
        DisplayResolution.Screen s = DisplayResolution.resolve(DisplayResolution.Mode.DEVICE, 3840, 2880, 0);  // 4:3 tablet
        assertNull(s.toString(), DisplayResolution.validate(s.width, s.height));
        assertTrue(s.reason, s.reason.contains("scaled down"));
        assertEquals(4.0 / 3.0, (double) s.width / s.height, 0.01);
        assertTrue((long) s.width * s.height <= DisplayResolution.MAX_PIXELS);
    }

    @Test
    public void unknownOrUnusableDeviceSizeFallsBackTo1024x768() {
        DisplayResolution.Screen u = DisplayResolution.resolve(DisplayResolution.Mode.DEVICE, 0, 0, 0);
        assertTrue(u.isDefault());
        DisplayResolution.Screen tiny = DisplayResolution.resolve(DisplayResolution.Mode.DEVICE, 600, 400, 0);
        assertTrue(tiny.reason, tiny.isDefault());
        assertTrue(tiny.reason, tiny.reason.contains("not usable"));
    }

    @Test
    public void autoPrefersStablePerformance() {
        assertTrue(DisplayResolution.resolve(DisplayResolution.Mode.AUTO, 2620, 1280, 12L << 30).isDefault());
        assertTrue(DisplayResolution.resolve(DisplayResolution.Mode.AUTO, 2620, 1280, 0).isDefault());   // unknown RAM
        DisplayResolution.Screen low = DisplayResolution.resolve(DisplayResolution.Mode.AUTO, 1600, 720, 3L << 30);
        assertEquals(800, low.width);
        assertEquals(600, low.height);
    }

    @Test
    public void invalidSizesAreRejected() {
        assertNotNull(DisplayResolution.validate(320, 240));
        assertNotNull(DisplayResolution.validate(1025, 768));     // not a multiple of 8
        assertNotNull(DisplayResolution.validate(1024, 767));
        assertNotNull(DisplayResolution.validate(8192, 1080));
        assertNotNull(DisplayResolution.validate(4096, 2160 + 2));
        assertNull(DisplayResolution.validate(1024, 768));
    }

    @Test
    public void defaultSizeKeepsTheXvfbCommandAndCncDdrawFileOf051() {
        RuntimeLayout rt = new RuntimeLayout(new File("/r"));
        assertEquals(X11DisplayPlan.xvfbArgv(rt, 37, new File("/fb")), X11DisplayPlan.xvfbArgv(rt, 37, new File("/fb"), 1024, 768));
        assertTrue(X11DisplayPlan.xvfbArgv(rt, 37, new File("/fb")).contains("1024x768x24"));
        assertEquals(new CncDdrawConfig().toIni(), new CncDdrawConfig().forScreen(1024, 768).toIni());
        assertFalse(new CncDdrawConfig().toIni().contains("inject_resolution"));
    }

    @Test
    public void otherSizesStartXvfbAtThatSizeAndOfferItToTheGame() {
        List<String> argv = X11DisplayPlan.xvfbArgv(new RuntimeLayout(new File("/r")), 37, new File("/fb"), 2616, 1280);
        assertTrue(argv.toString(), argv.contains("2616x1280x24"));
        assertFalse(argv.contains("1024x768x24"));
        String ini = new CncDdrawConfig().forScreen(2616, 1280).toIni();
        for (String game : CncDdrawConfig.GAME_SECTIONS) {
            int at = ini.indexOf("[" + game + "]");
            assertTrue(ini, at >= 0 && ini.indexOf("inject_resolution=2616x1280", at) > at);
        }
        assertTrue(ini.contains("maintas=true"));                 // the aspect ratio is kept when scaling
        assertTrue(ini.contains("adjmouse=true"));                // mouse scaled back with the picture
    }

    /** cnc-ddraw (maintas) places a 1024×768 picture centred on any screen: the pre-check finds it there. */
    @Test
    public void ddrawPictureIsFoundOnEveryScreenSize() {
        int[][] sizes = {{640, 480}, {800, 600}, {1024, 768}, {1280, 960}, {2616, 1280}, {1600, 720}};
        for (int[] sz : sizes) {
            int w = sz[0], h = sz[1];
            float s = Math.min(w / 1024f, h / 768f);
            float ox = (w - 1024 * s) / 2f, oy = (h - 768 * s) / 2f;
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float gx = (x - ox) / s, gy = (y - oy) / s;
                    if (gx < 0 || gy < 0 || gx >= 1024 || gy >= 768) continue;    // black bars
                    px[y * w + x] = DisplayTestPattern.DDRAW_COLORS[(gx >= 512 ? 1 : 0) + (gy >= 384 ? 2 : 0)];
                }
            }
            ByteBuffer file = DisplayCoreTest.xwd(w, h, w * 4, px);
            XwdFramebuffer fb = XwdFramebuffer.parse(file);
            DisplayTestPattern.Check c = DisplayTestPattern.checkQuadrants(fb, file, DisplayTestPattern.DDRAW_COLORS, DisplayTestPattern.DDRAW_NAMES);
            assertTrue(w + "x" + h + ": " + c.detail, c.ok);
        }
    }

    /** Touch at every resolution on the owner's game view: corners, centre, edges; outside the picture nothing. */
    @Test
    public void touchMapsToTheXScreenAtEveryResolution() {
        int[][] screens = {{640, 480}, {800, 600}, {1024, 768}, {1280, 960}, {2616, 1280}};
        int[][] views = {{2620, 1280}, {1786, 1228}};
        for (int[] scr : screens) {
            for (int[] view : views) {
                ViewportTransform vt = new ViewportTransform(scr[0], scr[1], view[0], view[1]);
                TouchMouseMapper m = new TouchMouseMapper(new ControlSettings(), vt);
                String where = scr[0] + "x" + scr[1] + " on " + view[0] + "x" + view[1];
                int[][] targets = {{0, 0}, {scr[0] - 1, 0}, {0, scr[1] - 1}, {scr[0] - 1, scr[1] - 1},
                        {scr[0] / 2, scr[1] / 2}, {scr[0] / 3, scr[1] - 1}};
                long t = 0;
                for (int[] g : targets) {
                    float sx = vt.offsetX + (g[0] + 0.5f) * vt.scale, sy = vt.offsetY + (g[1] + 0.5f) * vt.scale;
                    m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, sx, sy, t));
                    List<InputAction> up = m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, sx, sy, t + 60));
                    assertEquals(where, "[MOVE(" + g[0] + "," + g[1] + "), LEFT_DOWN, PAUSE(120), LEFT_UP]", up.toString());
                    t += 1000;
                }
                // outside the picture (bars, when there are any): no event at all
                List<float[]> outside = new ArrayList<>();
                if (vt.offsetX >= 1) { outside.add(new float[] {vt.offsetX / 2, view[1] / 2f}); outside.add(new float[] {view[0] - vt.offsetX / 2, 10}); }
                if (vt.offsetY >= 1) { outside.add(new float[] {view[0] / 2f, vt.offsetY / 2}); outside.add(new float[] {10, view[1] - vt.offsetY / 2}); }
                for (float[] o : outside) {
                    List<InputAction> all = new ArrayList<>(m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, o[0], o[1], t)));
                    all.addAll(m.onTick(t + 2000));                                       // even as a long press
                    all.addAll(m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, o[0], o[1], t + 2100)));
                    assertTrue(where + " bar touch sent " + all, all.isEmpty());
                    t += 3000;
                }
            }
        }
    }

    /** Gestures on a non-default screen: drag, long press, two-finger scroll and pinch still work as in 0.5.1. */
    @Test
    public void gesturesWorkOnOtherScreenSizes() {
        ViewportTransform vt = new ViewportTransform(1280, 960, 2620, 1280);
        TouchMouseMapper m = new TouchMouseMapper(new ControlSettings(), vt);
        float cx = 1310, cy = 640;
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, cx, cy, 0));
        String drag = m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.MOVE, cx + 200, cy + 100, 50)).toString()
                + m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, cx + 200, cy + 100, 400));
        assertTrue(drag, drag.contains("LEFT_DOWN") && drag.contains("LEFT_UP"));
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, cx, cy, 1000));
        assertTrue(m.onTick(1000 + 500).toString().contains("RIGHT_DOWN"));            // long press = right click
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, cx, cy, 1600));
        // two fingers dragging sideways: arrow keys (scroll)
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, cx, cy, 3000));
        m.onTouch(new TouchMouseMapper.Frame(TouchMouseMapper.Action.POINTER_DOWN, 1, new int[] {0, 1},
                new float[] {cx, cx + 100}, new float[] {cy, cy}, 3010));
        String scroll = m.onTouch(new TouchMouseMapper.Frame(TouchMouseMapper.Action.MOVE, 0, new int[] {0, 1},
                new float[] {cx + 300, cx + 400}, new float[] {cy, cy}, 3100)).toString();
        assertTrue(scroll, scroll.contains("KEY_DOWN"));
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.CANCEL, 0, 0, 3200));
        // pinch: wheel
        m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, cx - 50, cy, 4000));
        m.onTouch(new TouchMouseMapper.Frame(TouchMouseMapper.Action.POINTER_DOWN, 1, new int[] {0, 1},
                new float[] {cx - 50, cx + 50}, new float[] {cy, cy}, 4010));
        String pinch = m.onTouch(new TouchMouseMapper.Frame(TouchMouseMapper.Action.MOVE, 0, new int[] {0, 1},
                new float[] {cx - 300, cx + 300}, new float[] {cy, cy}, 4100)).toString();
        assertTrue(pinch, pinch.contains("WHEEL"));
    }
}
