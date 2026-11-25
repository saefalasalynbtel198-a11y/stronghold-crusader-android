package io.github.saefalasalynbtel198.shcport.core;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * What is on the X screen, for a program without a known test pattern (the game): the share of non-black pixels and
 * a signature of a 64×48 sample grid, so the report can tell "nothing drawn" from "a still picture" from "changing
 * picture" (BUILD 3 step 3.3).
 */
public final class FrameProbe {
    public static final int GRID_W = 64;
    public static final int GRID_H = 48;
    /** A pixel counts as non-black if any channel is above this. */
    public static final int BLACK_LEVEL = 16;

    public final double nonBlack;
    public final long signature;
    public final int distinctColours;

    private FrameProbe(double nonBlack, long signature, int distinctColours) {
        this.nonBlack = nonBlack;
        this.signature = signature;
        this.distinctColours = distinctColours;
    }

    public static FrameProbe sample(XwdFramebuffer fb, ByteBuffer file) {
        int lit = 0;
        long h = 1125899906842597L;
        java.util.Set<Integer> colours = new java.util.HashSet<>();
        for (int gy = 0; gy < GRID_H; gy++) {
            int y = (int) ((gy + 0.5) * fb.height / GRID_H);
            for (int gx = 0; gx < GRID_W; gx++) {
                int x = (int) ((gx + 0.5) * fb.width / GRID_W);
                int c = fb.rgb(file, x, y) & 0xffffff;
                if (((c >> 16) & 0xff) > BLACK_LEVEL || ((c >> 8) & 0xff) > BLACK_LEVEL || (c & 0xff) > BLACK_LEVEL) lit++;
                if (colours.size() < 4096) colours.add(c);
                h = 31 * h + c;
            }
        }
        return new FrameProbe(lit / (double) (GRID_W * GRID_H), h, colours.size());
    }

    /**
     * Cheap change detector for every display frame: hash of a gw×gh sample grid (no allocation). Changes between
     * sample points are caught by the renderer's periodic upload.
     */
    public static long signature(XwdFramebuffer fb, ByteBuffer file, int gw, int gh) {
        long h = 1125899906842597L;
        for (int gy = 0; gy < gh; gy++) {
            int y = (int) ((gy + 0.5) * fb.height / gh);
            for (int gx = 0; gx < gw; gx++) h = 31 * h + fb.rgb(file, (int) ((gx + 0.5) * fb.width / gw), y);
        }
        return h;
    }

    public boolean differsFrom(FrameProbe o) { return o == null || o.signature != signature; }

    @Override public String toString() {
        return String.format(Locale.ROOT, "%.0f%% non-black, %d colours, signature %016x", nonBlack * 100, distinctColours, signature);
    }
}
