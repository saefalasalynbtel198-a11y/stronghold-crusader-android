package io.github.saefalasalynbtel198.shcport.core;

/**
 * Where the game image (the X screen, gameWidth × gameHeight) is drawn in the view, and the inverse mapping from
 * screen coordinates to game pixels. One object serves the renderer and the touch mapper, so the picture and the
 * touch mapping always agree.
 * <ul>
 * <li>{@link ScalingMode#PRESERVE} (and AUTO): one scale, the whole image visible, centred, black bars.</li>
 * <li>{@link ScalingMode#FILL}: one scale, the view completely covered, centred; the overhanging edges are cropped
 * (negative offset) and cannot be reached.</li>
 * <li>{@link ScalingMode#STRETCH}: separate horizontal and vertical scales, the whole image exactly covering the
 * view: no bars, no cropping.</li>
 * </ul>
 * A view point maps to game pixel floor((x - offsetX) / scaleX), clamped to the image.
 */
public final class ViewportTransform {

    public final int gameWidth;
    public final int gameHeight;
    public final ScalingMode mode;
    public final float scaleX;
    public final float scaleY;
    /** The uniform scale for PRESERVE and FILL; for STRETCH the smaller of {@link #scaleX} and {@link #scaleY}. */
    public final float scale;
    public final float offsetX;
    public final float offsetY;

    /** Aspect ratio kept (letter/pillar-boxing), as before 0.6.1. */
    public ViewportTransform(int gameWidth, int gameHeight, int viewWidth, int viewHeight) {
        this(gameWidth, gameHeight, viewWidth, viewHeight, ScalingMode.PRESERVE);
    }

    public ViewportTransform(int gameWidth, int gameHeight, int viewWidth, int viewHeight, ScalingMode mode) {
        if (gameWidth <= 0 || gameHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        this.gameWidth = gameWidth;
        this.gameHeight = gameHeight;
        this.mode = mode.effective();
        float fx = (float) viewWidth / gameWidth, fy = (float) viewHeight / gameHeight;
        switch (this.mode) {
            case STRETCH:
                scaleX = fx;
                scaleY = fy;
                scale = Math.min(fx, fy);
                break;
            case FILL:
                scaleX = scaleY = scale = Math.max(fx, fy);
                break;
            default:
                scaleX = scaleY = scale = Math.min(fx, fy);
                break;
        }
        this.offsetX = this.mode == ScalingMode.STRETCH ? 0f : (viewWidth - gameWidth * scaleX) / 2f;
        this.offsetY = this.mode == ScalingMode.STRETCH ? 0f : (viewHeight - gameHeight * scaleY) / 2f;
    }

    public int toGameX(float screenX) {
        int x = (int) Math.floor((screenX - offsetX) / scaleX);
        return Math.max(0, Math.min(gameWidth - 1, x));
    }

    public int toGameY(float screenY) {
        int y = (int) Math.floor((screenY - offsetY) / scaleY);
        return Math.max(0, Math.min(gameHeight - 1, y));
    }

    /** True if the screen point lies on the game picture (not in the black bars). */
    public boolean contains(float screenX, float screenY) {
        return screenX >= offsetX && screenX < offsetX + gameWidth * scaleX
                && screenY >= offsetY && screenY < offsetY + gameHeight * scaleY;
    }

    /** View x of the centre of game column gx (the inverse of {@link #toGameX}). */
    public float viewX(float gx) { return offsetX + (gx + 0.5f) * scaleX; }

    /** View y of the centre of game row gy. */
    public float viewY(float gy) { return offsetY + (gy + 0.5f) * scaleY; }

    /** Width and height of the drawn image in view pixels (larger than the view in FILL: cropped). */
    public float drawnWidth() { return gameWidth * scaleX; }

    public float drawnHeight() { return gameHeight * scaleY; }

    /** The first and one-past-last game column and row visible in a viewW × viewH view: {x0, y0, x1, y1}. */
    public int[] visibleGameRect(int viewW, int viewH) {
        int x0 = toGameX(0), y0 = toGameY(0);
        int x1 = Math.min(gameWidth, (int) Math.ceil((viewW - offsetX) / scaleX));
        int y1 = Math.min(gameHeight, (int) Math.ceil((viewH - offsetY) / scaleY));
        return new int[] {x0, y0, x1, y1};
    }
}
