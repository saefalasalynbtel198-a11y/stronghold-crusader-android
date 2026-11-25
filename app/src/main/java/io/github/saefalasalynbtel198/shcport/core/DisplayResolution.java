package io.github.saefalasalynbtel198.shcport.core;

import java.util.Locale;

/**
 * The player's Display Resolution setting and what it means for a game session.
 *
 * <p>Four sizes are involved, and they are not the same:
 * <ol>
 *   <li>the phone's display (physical pixels);</li>
 *   <li>the X screen (Xvfb's virtual screen, the "desktop" Wine reports): <b>this setting chooses it</b>;</li>
 *   <li>the game's rendering resolution: chosen in the game's own options; cnc-ddraw scales the game's image onto the X
 *       screen, keeping its aspect ratio, and the X screen size is added to the game's list of resolutions
 *       (cnc-ddraw inject_resolution) so the game can render at exactly that size;</li>
 *   <li>the picture on the phone: the X screen scaled to the game view with its aspect kept (black bars), by the GPU
 *       ({@link ViewportTransform}).</li>
 * </ol>
 * 1024×768 is the default and the fallback when another size cannot be started.
 */
public final class DisplayResolution {

    public enum Mode {
        R640X480("640x480", "640 × 480", 640, 480),
        R800X600("800x600", "800 × 600", 800, 600),
        R1024X768("1024x768", "1024 × 768 (default)", 1024, 768),
        R1280X960("1280x960", "1280 × 960", 1280, 960),
        DEVICE("device", "Device resolution", 0, 0),
        AUTO("auto", "Auto", 0, 0);

        public final String key, label;
        final int w, h;

        Mode(String key, String label, int w, int h) {
            this.key = key;
            this.label = label;
            this.w = w;
            this.h = h;
        }

        public static Mode fromKey(String key) {
            for (Mode m : values()) if (m.key.equals(key)) return m;
            return R1024X768;
        }
    }

    public static final Mode DEFAULT = Mode.R1024X768;
    public static final int DEFAULT_W = 1024, DEFAULT_H = 768;
    public static final int MIN_W = 640, MIN_H = 480, MAX_W = 4096, MAX_H = 2160;
    /** X screen memory: Xvfb's framebuffer (4 bytes/pixel) is mapped by the app and uploaded each frame. */
    public static final long MAX_PIXELS = 3840L * 2160;
    /** Auto picks 800×600 below this much RAM (the game, Wine and Box64 already use ~700 MB at 1024×768). */
    public static final long AUTO_LOW_MEMORY = 4L << 30;

    /** The X screen size to start, and why. */
    public static final class Screen {
        public final int width, height;
        public final String reason;

        Screen(int width, int height, String reason) {
            this.width = width;
            this.height = height;
            this.reason = reason;
        }

        public boolean isDefault() { return width == DEFAULT_W && height == DEFAULT_H; }

        @Override public String toString() { return width + "x" + height; }
    }

    private DisplayResolution() {}

    /**
     * The X screen for a mode.
     *
     * @param viewW  width of the game view on the phone in pixels (landscape, system bars hidden), or 0 if unknown
     * @param viewH  its height
     * @param totalMemBytes the phone's RAM, or 0 if unknown
     */
    public static Screen resolve(Mode mode, int viewW, int viewH, long totalMemBytes) {
        switch (mode) {
            case DEVICE: {
                if (viewW <= 0 || viewH <= 0) return new Screen(DEFAULT_W, DEFAULT_H, "device size unknown: 1024×768");
                int w = Math.max(viewW, viewH), h = Math.min(viewW, viewH);           // landscape
                // above the limits, scale down with the device's aspect ratio kept
                double f = Math.min(1.0, Math.min((double) MAX_W / w, (double) MAX_H / h));
                f = Math.min(f, Math.sqrt((double) MAX_PIXELS / ((double) w * h)));
                w = (int) Math.floor(w * f);
                h = (int) Math.floor(h * f);
                w -= w % 8;                  // cnc-ddraw's Stronghold setting rounds the desktop width to 8 pixels
                h -= h % 2;
                String why = "device " + Math.max(viewW, viewH) + "×" + Math.min(viewW, viewH)
                        + (f < 1.0 ? " (scaled down to the size limit)" : "");
                String err = validate(w, h);
                return err == null ? new Screen(w, h, why) : new Screen(DEFAULT_W, DEFAULT_H, why + " not usable (" + err + "): 1024×768");
            }
            case AUTO:
                if (totalMemBytes > 0 && totalMemBytes < AUTO_LOW_MEMORY) {
                    return new Screen(800, 600, String.format(Locale.ROOT, "Auto: %.1f GB RAM, 800×600", totalMemBytes / 1e9));
                }
                return new Screen(DEFAULT_W, DEFAULT_H, "Auto: 1024×768");
            default:
                return new Screen(mode.w, mode.h, mode.label);
        }
    }

    /** Null if an X screen of w×h can be started; otherwise why not. */
    public static String validate(int w, int h) {
        if (w < MIN_W || h < MIN_H) return "smaller than " + MIN_W + "×" + MIN_H;
        if (w > MAX_W || h > MAX_H) return "larger than " + MAX_W + "×" + MAX_H;
        if (w % 8 != 0) return "width not a multiple of 8";
        if (h % 2 != 0) return "odd height";
        if ((long) w * h > MAX_PIXELS) return "more than " + MAX_PIXELS + " pixels";
        return null;
    }

    /** Short explanation for the settings screen. */
    public static String describe(Mode m) {
        switch (m) {
            case R640X480: return "Small screen: fastest, large interface.";
            case R800X600: return "Classic size, a little faster than 1024 × 768.";
            case R1024X768: return "The tested default.";
            case R1280X960: return "Sharper, needs more CPU: the game must also be set to 1280 × 960 in its options.";
            case DEVICE: return "Your phone's own pixel size, no black bars if the game uses it too (set it in the game's "
                    + "options). The heaviest choice: the game draws several times more pixels.";
            case AUTO: return "1024 × 768; 800 × 600 on phones with less than 4 GB of RAM.";
            default: return "";
        }
    }
}
