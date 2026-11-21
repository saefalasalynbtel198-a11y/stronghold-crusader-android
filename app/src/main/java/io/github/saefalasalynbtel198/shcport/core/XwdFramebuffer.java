package io.github.saefalasalynbtel198.shcport.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Layout of an Xvfb framebuffer file (Xvfb -fbdir: an X Window Dump, XWD version 7, kept up to date in place by
 * the X server). Parses the big-endian header and reads pixels without copying the image.
 */
public final class XwdFramebuffer {
    public final int headerSize;
    public final int depth;
    public final int width;
    public final int height;
    public final int bitsPerPixel;
    public final int bytesPerLine;
    public final boolean lsbFirst;
    public final int redMask;
    public final int greenMask;
    public final int blueMask;
    public final int colormapEntries;
    /** Byte offset of pixel (0,0) in the file. */
    public final int pixelOffset;

    private XwdFramebuffer(ByteBuffer b) {
        headerSize = b.getInt(0);
        depth = b.getInt(12);
        width = b.getInt(16);
        height = b.getInt(20);
        lsbFirst = b.getInt(28) == 0;
        bitsPerPixel = b.getInt(44);
        bytesPerLine = b.getInt(48);
        redMask = b.getInt(56);
        greenMask = b.getInt(60);
        blueMask = b.getInt(64);
        colormapEntries = b.getInt(76);
        pixelOffset = headerSize + colormapEntries * 12;
    }

    /** Parses the header of a mapped or loaded framebuffer file; the buffer's position and order are not changed. */
    public static XwdFramebuffer parse(ByteBuffer file) {
        if (file.capacity() < 100) throw new IllegalArgumentException("framebuffer file too short");
        ByteBuffer b = file.duplicate().order(ByteOrder.BIG_ENDIAN);
        int version = b.getInt(4);
        if (version != 7) throw new IllegalArgumentException("not an XWD v7 framebuffer (version " + version + ")");
        XwdFramebuffer fb = new XwdFramebuffer(b);
        if (fb.width <= 0 || fb.height <= 0 || fb.width > 16384 || fb.height > 16384) {
            throw new IllegalArgumentException("bad size " + fb.width + "x" + fb.height);
        }
        if (fb.bitsPerPixel != 32) throw new IllegalArgumentException("unsupported " + fb.bitsPerPixel + " bits per pixel");
        if (fb.bytesPerLine < fb.width * 4 || fb.bytesPerLine % 4 != 0) {
            throw new IllegalArgumentException("bad stride " + fb.bytesPerLine + " for width " + fb.width);
        }
        if (fb.headerSize < 100 || fb.colormapEntries < 0 || fb.colormapEntries > 65536) {
            throw new IllegalArgumentException("bad header");
        }
        long end = (long) fb.pixelOffset + (long) fb.bytesPerLine * fb.height;
        if (end > file.capacity()) {
            throw new IllegalArgumentException("file has " + file.capacity() + " bytes, image needs " + end);
        }
        return fb;
    }

    /** Pixel bytes in memory are B, G, R, X: upload as RGBA and swap red/blue in the shader. */
    public boolean isBgrx() {
        return lsbFirst && redMask == 0xff0000 && greenMask == 0xff00 && blueMask == 0xff;
    }

    public long imageBytes() { return (long) bytesPerLine * height; }

    /** Width of the stored rows in pixels (stride / 4); may exceed {@link #width}. */
    public int strideWidth() { return bytesPerLine / 4; }

    /** Pixel as 0xRRGGBB. */
    public int rgb(ByteBuffer file, int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) throw new IndexOutOfBoundsException(x + "," + y);
        int o = pixelOffset + y * bytesPerLine + x * 4;
        int v = lsbFirst
                ? (file.get(o) & 0xff) | (file.get(o + 1) & 0xff) << 8 | (file.get(o + 2) & 0xff) << 16 | (file.get(o + 3) & 0xff) << 24
                : (file.get(o + 3) & 0xff) | (file.get(o + 2) & 0xff) << 8 | (file.get(o + 1) & 0xff) << 16 | (file.get(o) & 0xff) << 24;
        return channel(v, redMask) << 16 | channel(v, greenMask) << 8 | channel(v, blueMask);
    }

    private static int channel(int v, int mask) {
        if (mask == 0) return 0;
        int shift = Integer.numberOfTrailingZeros(mask);
        int bits = Integer.bitCount(mask);
        int c = (v & mask) >>> shift;
        return bits >= 8 ? c >>> (bits - 8) : c << (8 - bits);
    }

    @Override
    public String toString() {
        return width + "x" + height + " depth " + depth + ", " + bitsPerPixel + " bpp, stride " + bytesPerLine
                + (lsbFirst ? ", LSB first" : ", MSB first") + String.format(", masks %06x/%06x/%06x", redMask, greenMask, blueMask);
    }
}
