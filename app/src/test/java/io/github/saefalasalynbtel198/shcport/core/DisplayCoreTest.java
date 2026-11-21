package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

public class DisplayCoreTest {

    /** An XWD v7 file laid out like Xvfb's (big-endian header with window name, 256 colormap entries, BGRX). */
    static ByteBuffer xwd(int w, int h, int stride, int[] pixels) {
        int header = 100 + 17;  // + "Xvfb main window\0"
        int ncolors = 256;
        int off = header + ncolors * 12;
        ByteBuffer b = ByteBuffer.allocate(off + stride * h).order(ByteOrder.BIG_ENDIAN);
        int[] f = new int[25];
        f[0] = header; f[1] = 7; f[2] = 2; f[3] = 24; f[4] = w; f[5] = h; f[7] = 0; f[8] = 32; f[10] = 32;
        f[11] = 32; f[12] = stride; f[13] = 4; f[14] = 0xff0000; f[15] = 0xff00; f[16] = 0xff; f[17] = 8;
        f[18] = 256; f[19] = ncolors; f[20] = w; f[21] = h;
        for (int i = 0; i < 25; i++) b.putInt(i * 4, f[i]);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = pixels[y * w + x], o = off + y * stride + x * 4;
                b.put(o, (byte) p); b.put(o + 1, (byte) (p >> 8)); b.put(o + 2, (byte) (p >> 16)); b.put(o + 3, (byte) 0);
            }
        }
        return b;
    }

    /** What shc-display-test.exe draws for frame n. */
    static int[] pattern(int frame, int squareX) {
        int w = DisplayTestPattern.WIDTH, h = DisplayTestPattern.HEIGHT;
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) p[y * w + x] = DisplayTestPattern.COLORS[(x >= w / 2 ? 1 : 0) + (y >= h / 2 ? 2 : 0)];
        for (int y = 352; y < 416; y++) for (int x = squareX; x < squareX + 64; x++) p[y * w + x] = 0xffffff;
        for (int i = 0; i < 16; i++) {
            int bit = (frame >> (15 - i)) & 1;
            for (int y = 736; y < 752; y++) for (int x = 16 + 20 * i; x < 32 + 20 * i; x++) p[y * w + x] = bit == 1 ? 0xffffff : 0;
        }
        for (int y = 8; y < 24; y++) for (int x = 8; x < 90; x++) p[y * w + x] = 0;  // "frame N" text box
        return p;
    }

    @Test
    public void parsesXvfbLayoutAndReadsPixels() {
        ByteBuffer b = xwd(4, 2, 16, new int[] {0x112233, 0xff0000, 0x00ff00, 0x0000ff, 1, 2, 3, 0xabcdef});
        XwdFramebuffer fb = XwdFramebuffer.parse(b);
        assertEquals(4, fb.width);
        assertEquals(2, fb.height);
        assertEquals(16, fb.bytesPerLine);
        assertEquals(117 + 256 * 12, fb.pixelOffset);
        assertTrue(fb.isBgrx());
        assertEquals(0x112233, fb.rgb(b, 0, 0));
        assertEquals(0xff0000, fb.rgb(b, 1, 0));
        assertEquals(0xabcdef, fb.rgb(b, 3, 1));
        assertEquals(0, b.position());   // the caller's buffer is not moved
    }

    @Test
    public void handlesStrideWiderThanTheImage() {
        ByteBuffer b = xwd(3, 2, 16, new int[] {1, 2, 3, 4, 5, 0x00c0ff});
        XwdFramebuffer fb = XwdFramebuffer.parse(b);
        assertEquals(4, fb.strideWidth());
        assertEquals(0x00c0ff, fb.rgb(b, 2, 1));
    }

    @Test
    public void rejectsBadFramebuffers() {
        ByteBuffer ok = xwd(2, 2, 8, new int[4]);
        ByteBuffer wrongVersion = ok.duplicate(); wrongVersion.putInt(4, 6);
        ByteBuffer truncated = ByteBuffer.allocate(ok.capacity() - 1);
        for (int i = 0; i < truncated.capacity(); i++) truncated.put(i, ok.get(i));
        ByteBuffer badStride = xwd(2, 2, 8, new int[4]); badStride.putInt(48, 4);
        for (ByteBuffer b : new ByteBuffer[] {wrongVersion, truncated, badStride, ByteBuffer.allocate(50)}) {
            try {
                XwdFramebuffer.parse(b);
                fail("accepted a bad framebuffer");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void recognisesTheTestPatternAndItsCounter() {
        ByteBuffer b = xwd(1024, 768, 4096, pattern(12345, 300));
        XwdFramebuffer fb = XwdFramebuffer.parse(b);
        DisplayTestPattern.Check c = DisplayTestPattern.checkQuadrants(fb, b);
        assertTrue(c.detail, c.ok);
        assertEquals(12345, DisplayTestPattern.decodeCounter(fb, b));
        long sig1 = DisplayTestPattern.bandSignature(fb, b);
        ByteBuffer moved = xwd(1024, 768, 4096, pattern(12346, 600));
        assertTrue(sig1 != DisplayTestPattern.bandSignature(XwdFramebuffer.parse(moved), moved));
    }

    @Test
    public void reportsWrongColoursAndUnreadableCounters() {
        int[] p = pattern(7, 0);
        for (int y = 400; y < 768; y++) for (int x = 600; x < 1024; x++) p[y * 1024 + x] = 0x808080;  // yellow quadrant wrong
        p[744 * 1024 + 16 + 8] = 0x7f7f7f;                                                          // a counter cell grey
        ByteBuffer b = xwd(1024, 768, 4096, p);
        XwdFramebuffer fb = XwdFramebuffer.parse(b);
        DisplayTestPattern.Check c = DisplayTestPattern.checkQuadrants(fb, b);
        assertFalse(c.ok);
        assertTrue(c.detail, c.detail.contains("bottom-right yellow: centre #808080 9/9 points wrong"));
        assertEquals(-1, DisplayTestPattern.decodeCounter(fb, b));
        ByteBuffer black = xwd(1024, 768, 4096, new int[1024 * 768]);
        assertFalse(DisplayTestPattern.checkQuadrants(XwdFramebuffer.parse(black), black).ok);
        ByteBuffer small = xwd(640, 480, 2560, new int[640 * 480]);
        assertFalse(DisplayTestPattern.checkQuadrants(XwdFramebuffer.parse(small), small).ok);
    }

    @Test
    public void xvfbCommandLineMatchesTheCiProvenFlags() {
        RuntimeLayout rt = new RuntimeLayout(new File("/rt"));
        List<String> a = X11DisplayPlan.xvfbArgv(rt, 37, new File("/f/fb1"));
        assertEquals("/rt/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", a.get(0));
        assertEquals("/rt/x11/Xvfb", a.get(3));
        assertEquals(":37", a.get(4));
        String all = String.join(" ", a);
        for (String flag : new String[] {"-screen 0 1024x768x24", "-fbdir /f/fb1", "-nolisten tcp", "-nolisten unix",
                "-extension MIT-SHM", "-extension GLX", "-ac", "-fp built-ins", "-xkbdir /rt/x11/share/X11/xkb"}) {
            assertTrue(flag, all.contains(flag));
        }
        assertFalse(all.contains("-nolock"));
        assertEquals(new File("/rt/x11"), X11DisplayPlan.xvfbWorkingDir(rt));
        assertEquals("/tmp/.X11-unix/X37", X11DisplayPlan.abstractSocketName(37));
        assertEquals(new File("/f/fb1/Xvfb_screen0"), X11DisplayPlan.framebufferFile(new File("/f/fb1")));
        Map<String, String> env = X11DisplayPlan.wineEnv(rt, new File("/p/wine"), new File("/t"), 38);
        assertEquals(":38", env.get("DISPLAY"));
        assertEquals("/p/wine", env.get("WINEPREFIX"));
        assertEquals("ARM64 (Android)", env.get("BOX64_CPUNAME"));
        assertEquals(java.util.Arrays.asList("/rt/bin/box64", "/rt/wine/bin/wine", "/x/t.exe"),
                X11DisplayPlan.testProgramArgv(rt, new File("/x/t.exe")));
    }

    @Test
    public void countsEventsPerSecond() {
        FrameStats s = new FrameStats();
        for (int i = 0; i < 30; i++) s.add(1000 + i * 33);
        assertEquals(30, s.perSecond(1000 + 29 * 33));
        assertEquals(0, s.perSecond(5000));
        assertEquals(30, s.total());
    }
}
