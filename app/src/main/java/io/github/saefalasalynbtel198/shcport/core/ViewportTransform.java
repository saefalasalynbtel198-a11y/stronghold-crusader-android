package io.github.saefalasalynbtel198.shcport.core;

/**
 * Maps screen coordinates to game-surface pixels when the game image is scaled to fit the screen
 * with preserved aspect ratio (letter/pillar-boxing). Points in the bars clamp to the nearest edge.
 */
public final class ViewportTransform {

    public final int gameWidth;
    public final int gameHeight;
    public final float scale;
    public final float offsetX;
    public final float offsetY;

    public ViewportTransform(int gameWidth, int gameHeight, int viewWidth, int viewHeight) {
        if (gameWidth <= 0 || gameHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        this.gameWidth = gameWidth;
        this.gameHeight = gameHeight;
        this.scale = Math.min((float) viewWidth / gameWidth, (float) viewHeight / gameHeight);
        this.offsetX = (viewWidth - gameWidth * scale) / 2f;
        this.offsetY = (viewHeight - gameHeight * scale) / 2f;
    }

    public int toGameX(float screenX) {
        int x = (int) Math.floor((screenX - offsetX) / scale);
        return Math.max(0, Math.min(gameWidth - 1, x));
    }

    public int toGameY(float screenY) {
        int y = (int) Math.floor((screenY - offsetY) / scale);
        return Math.max(0, Math.min(gameHeight - 1, y));
    }
}
