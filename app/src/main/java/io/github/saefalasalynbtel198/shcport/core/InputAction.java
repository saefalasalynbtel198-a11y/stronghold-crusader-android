package io.github.saefalasalynbtel198.shcport.core;

import java.util.Locale;

/** A Windows-level input event to inject into the game (mouse in game-pixel coordinates, keys as VK codes). */
public final class InputAction {

    public enum Type { MOVE, BUTTON_DOWN, BUTTON_UP, WHEEL, KEY_DOWN, KEY_UP }

    public enum Button { LEFT, RIGHT }

    /** Windows virtual-key codes used by the touch layer. */
    public static final int VK_ESCAPE = 0x1B;
    public static final int VK_SPACE = 0x20;
    public static final int VK_LEFT = 0x25;
    public static final int VK_UP = 0x26;
    public static final int VK_RIGHT = 0x27;
    public static final int VK_DOWN = 0x28;

    public final Type type;
    public final int x;
    public final int y;
    public final Button button;
    /** Wheel notches, positive = away from the user (zoom in / scroll up). */
    public final int wheel;
    public final int keyCode;

    private InputAction(Type type, int x, int y, Button button, int wheel, int keyCode) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.button = button;
        this.wheel = wheel;
        this.keyCode = keyCode;
    }

    public static InputAction move(int x, int y) { return new InputAction(Type.MOVE, x, y, null, 0, 0); }
    public static InputAction down(Button b) { return new InputAction(Type.BUTTON_DOWN, 0, 0, b, 0, 0); }
    public static InputAction up(Button b) { return new InputAction(Type.BUTTON_UP, 0, 0, b, 0, 0); }
    public static InputAction wheel(int notches) { return new InputAction(Type.WHEEL, 0, 0, null, notches, 0); }
    public static InputAction keyDown(int vk) { return new InputAction(Type.KEY_DOWN, 0, 0, null, 0, vk); }
    public static InputAction keyUp(int vk) { return new InputAction(Type.KEY_UP, 0, 0, null, 0, vk); }

    @Override public String toString() {
        switch (type) {
            case MOVE: return String.format(Locale.ROOT, "MOVE(%d,%d)", x, y);
            case BUTTON_DOWN: return button + "_DOWN";
            case BUTTON_UP: return button + "_UP";
            case WHEEL: return "WHEEL(" + wheel + ")";
            case KEY_DOWN: return String.format(Locale.ROOT, "KEY_DOWN(0x%02X)", keyCode);
            case KEY_UP: return String.format(Locale.ROOT, "KEY_UP(0x%02X)", keyCode);
            default: return type.name();
        }
    }
}
