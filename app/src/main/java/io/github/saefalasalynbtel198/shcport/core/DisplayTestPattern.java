package io.github.saefalasalynbtel198.shcport.core;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * What shc-display-test.exe draws (app/winsrc/shc-display-test.c) and how to check it in a framebuffer:
 * four quadrants of known colours (9 points each, away from the moving square, the text and the counter strip)
 * and a 16-bit frame counter strip (white = 1, black = 0) in the bottom-left quadrant.
 */
public final class DisplayTestPattern {
    public static final int WIDTH = 1024;
    public static final int HEIGHT = 768;
    public static final int[] COLORS = {0xff0000, 0x00ff00, 0x0000ff, 0xffff00};
    public static final String[] NAMES = {"top-left red", "top-right green", "bottom-left blue", "bottom-right yellow"};
    public static final int TOLERANCE = 8;
    /** Moving square band (rows) and counter strip geometry, as in the test program. */
    public static final int BAND_Y = 352;
    public static final int BAND_H = 64;
    static final int BITS_Y = 736;
    static final int BITS_X = 16;
    static final int BITS_STEP = 20;
    static final int BITS_CELL = 16;

    private DisplayTestPattern() {}

    public static final class Check {
        public final boolean ok;
        public final String detail;

        Check(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    /**
     * Colours of the runtime's DirectDraw smoke program (win/shc-gui-smoke.exe ddraw) after cnc-ddraw converts its
     * RGB565 surface: magenta F81F, cyan 07FF, white FFFF, grey 8410; either bit expansion is within the tolerance.
     */
    public static final int[] DDRAW_COLORS = {0xff00ff, 0x00ffff, 0xffffff, 0x848284};
    public static final String[] DDRAW_NAMES = {"top-left magenta", "top-right cyan", "bottom-left white", "bottom-right grey"};

    /** Checks the four quadrant colours of the display test program (9 points each). */
    public static Check checkQuadrants(XwdFramebuffer fb, ByteBuffer file) {
        return checkQuadrants(fb, file, COLORS, NAMES);
    }

    /** Checks four quadrant colours (9 points each). */
    public static Check checkQuadrants(XwdFramebuffer fb, ByteBuffer file, int[] colors, String[] names) {
        if (fb.width != WIDTH || fb.height != HEIGHT) {
            return new Check(false, "framebuffer is " + fb.width + "x" + fb.height + ", expected 1024x768");
        }
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (int q = 0; q < 4; q++) {
            int x0 = (q & 1) * WIDTH / 2, y0 = (q >> 1) * HEIGHT / 2;
            int bad = 0, centre = 0;
            for (int fx = 1; fx <= 3; fx++) {
                for (int fy = 1; fy <= 3; fy++) {
                    int c = fb.rgb(file, x0 + WIDTH / 2 * fx / 4, y0 + HEIGHT / 2 * fy / 4);
                    if (fx == 2 && fy == 2) centre = c;
                    if (!close(c, colors[q])) bad++;
                }
            }
            ok &= bad == 0;
            sb.append(String.format(Locale.ROOT, "%s: centre #%06x %s; ", names[q], centre,
                    bad == 0 ? "OK" : bad + "/9 points wrong"));
        }
        return new Check(ok, sb.toString().trim());
    }

    /** Frame counter from the strip, or -1 if any cell is neither clearly white nor clearly black. */
    public static int decodeCounter(XwdFramebuffer fb, ByteBuffer file) {
        if (fb.width != WIDTH || fb.height != HEIGHT) return -1;
        int v = 0;
        for (int i = 0; i < 16; i++) {
            int c = fb.rgb(file, BITS_X + BITS_STEP * i + BITS_CELL / 2, BITS_Y + BITS_CELL / 2);
            int bit;
            if (close(c, 0xffffff)) bit = 1;
            else if (close(c, 0x000000)) bit = 0;
            else return -1;
            v = v << 1 | bit;
        }
        return v;
    }

    /** Cheap signature of the moving band (16 samples), to tell whether the picture changed. */
    public static long bandSignature(XwdFramebuffer fb, ByteBuffer file) {
        long h = 1125899906842597L;
        int y = Math.min(fb.height - 1, BAND_Y + BAND_H / 2);
        for (int i = 0; i < 16; i++) h = 31 * h + fb.rgb(file, (fb.width - 1) * i / 15, y);
        return h;
    }

    static boolean close(int a, int b) {
        for (int s = 0; s <= 16; s += 8) {
            if (Math.abs(((a >> s) & 0xff) - ((b >> s) & 0xff)) > TOLERANCE) return false;
        }
        return true;
    }
}
