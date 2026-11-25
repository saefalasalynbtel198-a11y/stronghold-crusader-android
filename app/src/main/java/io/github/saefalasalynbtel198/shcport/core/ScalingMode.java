package io.github.saefalasalynbtel198.shcport.core;

/**
 * How the X screen is fitted into the game area on the phone (Settings → Display Scaling Mode, 0.6.1). Android side
 * only: the renderer and the touch mapper share one {@link ViewportTransform} built with this mode.
 */
public enum ScalingMode {
    /** Whole frame, stretched to the whole area: nothing cropped, no bars, proportions may change. Default. */
    STRETCH("stretch", "Stretch to full screen",
            "Fills the whole game area. The whole picture stays visible but is stretched wider or taller (may look distorted)."),
    /** Whole frame, shape kept, centred, black bars where needed. */
    PRESERVE("preserve", "Preserve aspect ratio",
            "Original shape, centred, black bars where needed. Never distorted."),
    /** Shape kept, scaled until the area is full, centred; the overhanging edges are cropped (and cannot be tapped). */
    FILL("fill", "Fill screen (crop)",
            "Original shape, enlarged until the area is full; the top/bottom or left/right edges are cut off equally."),
    /** Never stretches or crops on its own: the original shape (a Device-resolution screen already has the area's shape). */
    AUTO("auto", "Auto",
            "Keeps the original shape; with Device resolution the picture already has the screen's shape.");

    public static final ScalingMode DEFAULT = STRETCH;

    public final String key, label, description;

    ScalingMode(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    public static ScalingMode fromKey(String key) {
        for (ScalingMode m : values()) if (m.key.equals(key)) return m;
        return DEFAULT;
    }

    /** The geometry actually used: Auto behaves as Preserve (no silent stretching or cropping). */
    public ScalingMode effective() {
        return this == AUTO ? PRESERVE : this;
    }
}
