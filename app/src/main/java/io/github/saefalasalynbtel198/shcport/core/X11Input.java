package io.github.saefalasalynbtel198.shcport.core;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A minimal X11 client that injects mouse and keyboard input into the X server (Xvfb) with the XTEST extension
 * (FakeInput), the same way xdotool does. Wine receives these as ordinary X events and turns them into Windows
 * mouse/keyboard messages for the game. Speaks the X11 wire protocol directly over any byte stream (on Android the
 * app's connection to Xvfb's abstract socket; in CI a bridge to the same Xvfb), little-endian, no authentication
 * (Xvfb runs with -ac).
 *
 * <p>Not thread-safe: use from one thread (the app has a dedicated input thread).
 */
public final class X11Input implements Closeable {

    // core event types used by XTEST FakeInput
    static final int KEY_PRESS = 2, KEY_RELEASE = 3, BUTTON_PRESS = 4, BUTTON_RELEASE = 5, MOTION_NOTIFY = 6;
    private static final int OP_QUERY_POINTER = 38, OP_QUERY_KEYMAP = 44, OP_QUERY_EXTENSION = 98, OP_GET_KEYBOARD_MAPPING = 101;
    private static final int XTEST_FAKE_INPUT = 2;
    /** X keysyms of the keys the game needs (X11/keysymdef.h). */
    public static final int XK_BACKSPACE = 0xff08, XK_TAB = 0xff09, XK_RETURN = 0xff0d, XK_ESCAPE = 0xff1b,
            XK_LEFT = 0xff51, XK_UP = 0xff52, XK_RIGHT = 0xff53, XK_DOWN = 0xff54, XK_PAGE_UP = 0xff55, XK_PAGE_DOWN = 0xff56,
            XK_HOME = 0xff50, XK_END = 0xff57, XK_INSERT = 0xff63, XK_DELETE = 0xffff, XK_F1 = 0xffbe,
            XK_SHIFT_L = 0xffe1, XK_CONTROL_L = 0xffe3, XK_ALT_L = 0xffe9, XK_SPACE = 0x20;
    public static final int BUTTON_LEFT = 1, BUTTON_MIDDLE = 2, BUTTON_RIGHT = 3, WHEEL_UP = 4, WHEEL_DOWN = 5;

    private final InputStream in;
    private final DataInputStream din;
    private final OutputStream out;
    private int sequence;
    public final int root;
    public final int screenWidth, screenHeight;
    public final int minKeycode, maxKeycode;
    public final int xtestOpcode;
    /** keysym → keycode, and whether Shift is needed (keysym in the second column). */
    private final Map<Integer, int[]> keycodes = new HashMap<>();
    private final int shiftKeycode;
    /** Errors the server sent back (normally 0). */
    public int errors;
    public String lastError = "";

    private X11Input(InputStream in, OutputStream out) throws IOException {
        this.in = in;
        this.din = new DataInputStream(in);
        this.out = out;
        // connection setup: little-endian, protocol 11.0, no authorization
        byte[] setup = new byte[12];
        setup[0] = 'l';
        setup[2] = 11;
        out.write(setup);
        out.flush();
        byte[] head = new byte[8];
        din.readFully(head);
        ByteBuffer h = le(head);
        int extraWords = h.getShort(6) & 0xffff;
        byte[] extra = new byte[extraWords * 4];
        din.readFully(extra);
        if (head[0] != 1) {
            int reasonLen = head[1] & 0xff;
            String reason = new String(extra, 0, Math.min(reasonLen, extra.length), StandardCharsets.ISO_8859_1);
            throw new IOException("X server refused the connection" + (head[0] == 2 ? " (authentication required)" : "") + ": " + reason.trim());
        }
        ByteBuffer s = le(extra);
        int vendorLen = s.getShort(16) & 0xffff;
        int formats = s.get(21) & 0xff;
        minKeycode = s.get(26) & 0xff;
        maxKeycode = s.get(27) & 0xff;
        int screenOff = 32 + pad4(vendorLen) + 8 * formats;
        root = s.getInt(screenOff);
        screenWidth = s.getShort(screenOff + 20) & 0xffff;
        screenHeight = s.getShort(screenOff + 22) & 0xffff;

        xtestOpcode = queryExtension("XTEST");
        if (xtestOpcode == 0) throw new IOException("the X server has no XTEST extension");
        loadKeyboardMapping();
        int[] shift = keycodes.get(XK_SHIFT_L);
        shiftKeycode = shift == null ? 0 : shift[0];
    }

    /** Connects over an already open stream pair (the caller owns the socket and closes it through {@link #close}). */
    public static X11Input connect(InputStream in, OutputStream out) throws IOException {
        return new X11Input(in, out);
    }

    // ------------------------------------------------------------------------------------------------- requests

    private int queryExtension(String name) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer b = req(OP_QUERY_EXTENSION, 0, 2 + pad4(n.length) / 4);
        b.putShort(4, (short) n.length);
        b.position(8);
        b.put(n);
        send(b);
        ByteBuffer r = reply();
        return r.get(8) != 0 ? r.get(9) & 0xff : 0;
    }

    private void loadKeyboardMapping() throws IOException {
        int count = maxKeycode - minKeycode + 1;
        ByteBuffer b = req(OP_GET_KEYBOARD_MAPPING, 0, 2);
        b.put(4, (byte) minKeycode);
        b.put(5, (byte) count);
        send(b);
        ByteBuffer r = reply();
        int perKeycode = r.get(1) & 0xff;
        for (int i = 0; i < count; i++) {
            for (int col = 0; col < Math.min(perKeycode, 2); col++) {
                int sym = r.getInt(32 + 4 * (i * perKeycode + col));
                if (sym == 0) continue;
                int[] have = keycodes.get(sym);
                if (have == null || (have[1] == 1 && col == 0)) keycodes.put(sym, new int[] {minKeycode + i, col});
            }
        }
    }

    /** Pointer position and button/modifier mask (QueryPointer); also flushes and reads any pending errors. */
    public int[] queryPointer() throws IOException {
        ByteBuffer b = req(OP_QUERY_POINTER, 0, 2);
        b.putInt(4, root);
        send(b);
        ByteBuffer r = reply();
        return new int[] {r.getShort(16), r.getShort(18), r.getShort(24) & 0xffff};
    }

    /** True if the server reports the keycode as pressed (QueryKeymap). */
    public boolean isKeyDown(int keycode) throws IOException {
        send(req(OP_QUERY_KEYMAP, 0, 1));
        ByteBuffer r = reply();
        return (r.get(8 + keycode / 8) & (1 << (keycode % 8))) != 0;
    }

    /** Absolute pointer move on the root window (the game screen is the whole 1024×768 X screen). */
    public void motion(int x, int y) throws IOException {
        fake(MOTION_NOTIFY, 0, clamp(x, screenWidth), clamp(y, screenHeight));
    }

    public void button(int button, boolean down) throws IOException {
        fake(down ? BUTTON_PRESS : BUTTON_RELEASE, button, 0, 0);
    }

    /** One wheel notch per press/release of button 4 (up) or 5 (down). */
    public void wheel(int notches) throws IOException {
        int b = notches > 0 ? WHEEL_UP : WHEEL_DOWN;
        for (int i = 0; i < Math.abs(notches); i++) {
            fake(BUTTON_PRESS, b, 0, 0);
            fake(BUTTON_RELEASE, b, 0, 0);
        }
    }

    public int keycodeOf(int keysym) {
        int[] k = keycodes.get(keysym);
        return k == null ? 0 : k[0];
    }

    /** Presses or releases the key for a keysym; false if the keymap has no key for it. */
    public boolean key(int keysym, boolean down) throws IOException {
        int[] k = keycodes.get(keysym);
        if (k == null) return false;
        fake(down ? KEY_PRESS : KEY_RELEASE, k[0], 0, 0);
        return true;
    }

    /** Types one character (press and release, with Shift when the keymap needs it); false if it has no key. */
    public boolean type(int codePoint) throws IOException {
        int sym = keysymForChar(codePoint);
        int[] k = sym == 0 ? null : keycodes.get(sym);
        if (k == null) return false;
        boolean shift = k[1] == 1 && shiftKeycode != 0;
        if (shift) fake(KEY_PRESS, shiftKeycode, 0, 0);
        fake(KEY_PRESS, k[0], 0, 0);
        fake(KEY_RELEASE, k[0], 0, 0);
        if (shift) fake(KEY_RELEASE, shiftKeycode, 0, 0);
        return true;
    }

    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            out.close();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------------------------------------- mapping

    /** Keysym of a printable character: Latin-1 keysyms equal the code point; others use 0x01000000 + code point. */
    public static int keysymForChar(int cp) {
        if (cp == '\n' || cp == '\r') return XK_RETURN;
        if (cp == '\b') return XK_BACKSPACE;
        if (cp == '\t') return XK_TAB;
        if (cp >= 0x20 && cp <= 0x7e) return cp;
        if (cp >= 0xa0 && cp <= 0xff) return cp;
        if (cp > 0xff && cp <= 0x10ffff) return 0x01000000 | cp;
        return 0;
    }

    /** Keysym for a Windows virtual-key code from {@link InputAction} (letters map to their lower-case keysym). */
    public static int keysymForVk(int vk) {
        if (vk >= 'A' && vk <= 'Z') return vk + 32;
        if (vk >= '0' && vk <= '9') return vk;
        if (vk >= 0x70 && vk <= 0x7b) return XK_F1 + (vk - 0x70);   // F1..F12
        switch (vk) {
            case 0x08: return XK_BACKSPACE;
            case 0x09: return XK_TAB;
            case 0x0d: return XK_RETURN;
            case 0x10: return XK_SHIFT_L;
            case 0x11: return XK_CONTROL_L;
            case 0x12: return XK_ALT_L;
            case InputAction.VK_ESCAPE: return XK_ESCAPE;
            case InputAction.VK_SPACE: return XK_SPACE;
            case 0x21: return XK_PAGE_UP;
            case 0x22: return XK_PAGE_DOWN;
            case 0x23: return XK_END;
            case 0x24: return XK_HOME;
            case InputAction.VK_LEFT: return XK_LEFT;
            case InputAction.VK_UP: return XK_UP;
            case InputAction.VK_RIGHT: return XK_RIGHT;
            case InputAction.VK_DOWN: return XK_DOWN;
            case 0x2d: return XK_INSERT;
            case 0x2e: return XK_DELETE;
            default: return 0;
        }
    }

    /** Sends one {@link InputAction} from the touch mapper (coordinates are already game = X screen pixels). */
    public void send(InputAction a) throws IOException {
        switch (a.type) {
            case MOVE: motion(a.x, a.y); break;
            case BUTTON_DOWN: button(a.button == InputAction.Button.RIGHT ? BUTTON_RIGHT : BUTTON_LEFT, true); break;
            case BUTTON_UP: button(a.button == InputAction.Button.RIGHT ? BUTTON_RIGHT : BUTTON_LEFT, false); break;
            case WHEEL: wheel(a.wheel); break;
            case KEY_DOWN: key(keysymForVk(a.keyCode), true); break;
            case KEY_UP: key(keysymForVk(a.keyCode), false); break;
            case PAUSE:
                // the press must reach the X server now, and stay pressed for the pause
                out.flush();
                try {
                    Thread.sleep(Math.max(0, a.x));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                break;
            default: break;
        }
    }

    // ------------------------------------------------------------------------------------------------- wire

    private void fake(int type, int detail, int x, int y) throws IOException {
        ByteBuffer b = req(xtestOpcode, XTEST_FAKE_INPUT, 9);
        b.put(4, (byte) type);
        b.put(5, (byte) detail);
        b.putInt(8, 0);                  // time: CurrentTime (no delay)
        b.putInt(12, type == MOTION_NOTIFY ? root : 0);
        b.putShort(24, (short) x);
        b.putShort(26, (short) y);
        send(b);
    }

    private ByteBuffer req(int opcode, int data, int words) {
        ByteBuffer b = ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) opcode);
        b.put(1, (byte) data);
        b.putShort(2, (short) words);
        return b;
    }

    private void send(ByteBuffer b) throws IOException {
        out.write(b.array());
        sequence = (sequence + 1) & 0xffff;
    }

    /** Reads the reply to the last request; errors and events in between are counted and skipped. */
    private ByteBuffer reply() throws IOException {
        out.flush();
        while (true) {
            byte[] p = new byte[32];
            din.readFully(p);
            ByteBuffer b = le(p);
            if (p[0] == 1) {
                long extra = (b.getInt(4) & 0xffffffffL) * 4;
                if (extra > (1 << 20)) throw new IOException("X reply too large: " + extra);
                byte[] all = new byte[32 + (int) extra];
                System.arraycopy(p, 0, all, 0, 32);
                din.readFully(all, 32, (int) extra);
                ByteBuffer r = le(all);
                if ((r.getShort(2) & 0xffff) == sequence) return r;
            } else if (p[0] == 0) {
                errors++;
                lastError = "X error code " + (p[1] & 0xff) + " for request " + (p[10] & 0xff) + "." + (b.getShort(8) & 0xffff)
                        + " (sequence " + (b.getShort(2) & 0xffff) + ")";
            }
            // events (type >= 2) are not selected by this client; skipped
        }
    }

    private static ByteBuffer le(byte[] b) { return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); }

    private static int pad4(int n) { return (n + 3) & ~3; }

    private static int clamp(int v, int size) { return Math.max(0, Math.min(size - 1, v)); }
}
