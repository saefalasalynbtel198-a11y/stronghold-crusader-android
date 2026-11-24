package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * X11 wire protocol of the input client against a small in-process X server that decodes what the client sends
 * (the CI qemu job runs the same client against the runtime's real Xvfb).
 */
public class X11InputTest {

    static final int ROOT = 0x123, XTEST = 132;

    /** Minimal X server: setup, QueryExtension, GetKeyboardMapping, QueryPointer, QueryKeymap, XTEST FakeInput. */
    static final class FakeX implements Runnable {
        final ServerSocket server = new ServerSocket(0);
        final List<int[]> fakes = Collections.synchronizedList(new ArrayList<>());   // {type, detail, x, y, root}
        final List<Long> times = Collections.synchronizedList(new ArrayList<>());    // arrival (ms) of each fake event
        volatile boolean refuse;
        int px, py, mask;
        final boolean[] keys = new boolean[256];
        int seq;

        FakeX() throws IOException {
            Thread t = new Thread(this, "fake-x");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void run() {
            try (Socket s = server.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                byte[] setup = new byte[12];
                in.readFully(setup);
                if (setup[0] != 'l' || setup[2] != 11) throw new IOException("bad setup");
                if (refuse) {
                    byte[] reason = "No protocol specified\0\0\0".getBytes();
                    ByteBuffer r = le(8 + reason.length);
                    r.put(0, (byte) 0).put(1, (byte) 21).putShort(2, (short) 11).putShort(6, (short) (reason.length / 4));
                    r.position(8);
                    r.put(reason);
                    out.write(r.array());
                    out.flush();
                    return;
                }
                byte[] vendor = "Fake".getBytes();
                int extra = 32 + 4 + 8 + 40;          // fixed part, vendor, one format, one screen
                ByteBuffer r = le(8 + extra);
                r.put(0, (byte) 1).putShort(2, (short) 11).putShort(6, (short) (extra / 4));
                r.putShort(8 + 16, (short) vendor.length).put(8 + 20, (byte) 1).put(8 + 21, (byte) 1);
                r.put(8 + 26, (byte) 8).put(8 + 27, (byte) 255);
                r.position(8 + 32);
                r.put(vendor);
                int scr = 8 + 32 + 4 + 8;
                r.putInt(scr, ROOT).putShort(scr + 20, (short) 1024).putShort(scr + 22, (short) 768);
                out.write(r.array());
                out.flush();
                while (true) {
                    byte[] h = new byte[4];
                    in.readFully(h);
                    int words = (h[2] & 0xff) | (h[3] & 0xff) << 8;
                    byte[] body = new byte[words * 4 - 4];
                    in.readFully(body);
                    seq++;
                    ByteBuffer b = le(words * 4);
                    b.put(h).put(body);
                    int op = h[0] & 0xff;
                    if (op == 98) {                         // QueryExtension
                        int n = b.getShort(4);
                        String name = new String(b.array(), 8, n);
                        ByteBuffer rep = reply(0);
                        if (name.equals("XTEST")) rep.put(8, (byte) 1).put(9, (byte) XTEST);
                        out.write(rep.array());
                    } else if (op == 101) {                 // GetKeyboardMapping, 2 keysyms per keycode
                        int first = b.get(4) & 0xff, count = b.get(5) & 0xff;
                        ByteBuffer rep = le(32 + count * 8);
                        rep.put(0, (byte) 1).put(1, (byte) 2).putShort(2, (short) seq).putInt(4, count * 2);
                        for (int i = 0; i < count; i++) {
                            int kc = first + i;
                            int[] syms = kc == 9 ? new int[] {0xff1b, 0} : kc == 36 ? new int[] {0xff0d, 0}
                                    : kc == 38 ? new int[] {'a', 'A'} : kc == 50 ? new int[] {0xffe1, 0}
                                    : kc == 65 ? new int[] {' ', 0} : kc == 113 ? new int[] {0xff51, 0}
                                    : kc == 10 ? new int[] {'1', '!'} : new int[] {0, 0};
                            rep.putInt(32 + 8 * i, syms[0]).putInt(36 + 8 * i, syms[1]);
                        }
                        out.write(rep.array());
                    } else if (op == 38) {                  // QueryPointer
                        ByteBuffer rep = reply(0);
                        rep.putInt(8, ROOT).putShort(16, (short) px).putShort(18, (short) py).putShort(24, (short) mask);
                        out.write(rep.array());
                    } else if (op == 44) {                  // QueryKeymap
                        ByteBuffer rep = le(40);
                        rep.put(0, (byte) 1).putShort(2, (short) seq).putInt(4, 2);
                        for (int k = 0; k < 256; k++) if (keys[k]) rep.put(8 + k / 8, (byte) (rep.get(8 + k / 8) | 1 << (k % 8)));
                        out.write(rep.array());
                    } else if (op == XTEST && (h[1] & 0xff) == 2) {
                        if (words != 9) throw new IOException("FakeInput length " + words);
                        int type = b.get(4) & 0xff, detail = b.get(5) & 0xff, x = b.getShort(24), y = b.getShort(26);
                        fakes.add(new int[] {type, detail, x, y, b.getInt(12)});
                        times.add(System.nanoTime() / 1_000_000);
                        if (type == 6) { px = x; py = y; }
                        if (type == 4 && detail <= 3) mask |= 0x80 << detail;
                        if (type == 5 && detail <= 3) mask &= ~(0x80 << detail);
                        if (type == 2) keys[detail] = true;
                        if (type == 3) keys[detail] = false;
                    } else {                                 // unknown request: BadRequest error
                        ByteBuffer err = le(32);
                        err.put(0, (byte) 0).put(1, (byte) 1).putShort(2, (short) seq).put(10, (byte) op);
                        out.write(err.array());
                    }
                    out.flush();
                }
            } catch (IOException ignored) {
                // client closed
            }
        }

        ByteBuffer reply(int extraWords) {
            ByteBuffer rep = le(32 + extraWords * 4);
            rep.put(0, (byte) 1).putShort(2, (short) seq).putInt(4, extraWords);
            return rep;
        }

        static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }
    }

    private FakeX server;
    private Socket socket;

    private X11Input connect(boolean refuse) throws IOException {
        server = new FakeX();
        server.refuse = refuse;
        socket = new Socket("127.0.0.1", server.server.getLocalPort());
        socket.setTcpNoDelay(true);   // the app's Unix socket has no Nagle delay; over TCP it would delay small writes ~40 ms
        InputStream in = socket.getInputStream();
        return X11Input.connect(in, socket.getOutputStream());
    }

    @After
    public void close() throws IOException {
        if (socket != null) socket.close();
        if (server != null) server.server.close();
    }

    @Test
    public void setupFindsRootScreenAndXtest() throws Exception {
        X11Input x = connect(false);
        assertEquals(ROOT, x.root);
        assertEquals(1024, x.screenWidth);
        assertEquals(768, x.screenHeight);
        assertEquals(XTEST, x.xtestOpcode);
        assertEquals(9, x.keycodeOf(X11Input.XK_ESCAPE));
        assertEquals(36, x.keycodeOf(X11Input.XK_RETURN));
    }

    @Test
    public void refusedConnectionReportsTheReason() {
        try {
            connect(true);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("No protocol specified"));
        }
    }

    @Test
    public void motionClicksAndDragsReachTheServer() throws Exception {
        X11Input x = connect(false);
        x.motion(300, 200);
        assertArrayEquals(new int[] {300, 200, 0}, x.queryPointer());
        x.button(X11Input.BUTTON_LEFT, true);
        assertEquals(0x100, x.queryPointer()[2]);              // Button1Mask: held (drag)
        x.motion(400, 250);
        assertArrayEquals(new int[] {400, 250, 0x100}, x.queryPointer());
        x.button(X11Input.BUTTON_LEFT, false);
        x.button(X11Input.BUTTON_RIGHT, true);
        assertEquals(0x400, x.queryPointer()[2]);              // Button3Mask
        x.button(X11Input.BUTTON_RIGHT, false);
        assertEquals(0, x.queryPointer()[2]);
        int[] m = server.fakes.get(0);
        assertArrayEquals(new int[] {6, 0, 300, 200, ROOT}, m);  // absolute MotionNotify on the root window
        x.motion(5000, -20);                                  // clamped to the screen
        assertArrayEquals(new int[] {1023, 0, 0}, x.queryPointer());
        assertEquals(0, x.errors);
    }

    @Test
    public void wheelIsButtonFourOrFivePressAndRelease() throws Exception {
        X11Input x = connect(false);
        x.wheel(-2);
        x.queryPointer();
        assertEquals(4, server.fakes.size());
        assertArrayEquals(new int[] {4, 5}, new int[] {server.fakes.get(0)[0], server.fakes.get(0)[1]});
        assertArrayEquals(new int[] {5, 5}, new int[] {server.fakes.get(1)[0], server.fakes.get(1)[1]});
        x.wheel(1);
        x.queryPointer();
        assertEquals(X11Input.WHEEL_UP, server.fakes.get(4)[1]);
    }

    @Test
    public void keysAndTypedTextUseTheServersKeymap() throws Exception {
        X11Input x = connect(false);
        assertTrue(x.key(X11Input.XK_ESCAPE, true));
        assertTrue(x.isKeyDown(9));
        assertTrue(x.key(X11Input.XK_ESCAPE, false));
        assertFalse(x.isKeyDown(9));
        server.fakes.clear();
        assertTrue(x.type('a'));
        assertTrue(x.type('A'));                               // Shift + a
        assertTrue(x.type('!'));                               // Shift + 1
        assertFalse(x.type('z'));                              // not in this keymap
        x.queryPointer();
        int[][] want = {{2, 38}, {3, 38}, {2, 50}, {2, 38}, {3, 38}, {3, 50}, {2, 50}, {2, 10}, {3, 10}, {3, 50}};
        assertEquals(want.length, server.fakes.size());
        for (int i = 0; i < want.length; i++) {
            assertArrayEquals("event " + i, want[i], new int[] {server.fakes.get(i)[0], server.fakes.get(i)[1]});
        }
        assertFalse(x.key(0x1234567, true));
    }

    @Test
    public void unmappedVirtualKeySendsNothing() throws Exception {
        X11Input x = connect(false);
        x.send(InputAction.keyDown(0xE8));
        x.queryPointer();
        assertTrue(server.fakes.isEmpty());
        assertEquals(0, x.errors);
    }

    @Test
    public void virtualKeysAndCharactersMapToKeysyms() {
        assertEquals(X11Input.XK_ESCAPE, X11Input.keysymForVk(InputAction.VK_ESCAPE));
        assertEquals(X11Input.XK_SPACE, X11Input.keysymForVk(InputAction.VK_SPACE));
        assertEquals(X11Input.XK_LEFT, X11Input.keysymForVk(InputAction.VK_LEFT));
        assertEquals(X11Input.XK_DOWN, X11Input.keysymForVk(InputAction.VK_DOWN));
        assertEquals('p', X11Input.keysymForVk('P'));
        assertEquals(X11Input.XK_F1 + 4, X11Input.keysymForVk(0x74));
        assertEquals(0, X11Input.keysymForVk(0xE8));
        assertEquals('e', X11Input.keysymForChar('e'));
        assertEquals(0xe9, X11Input.keysymForChar('é'));
        assertEquals(0x01000000 | 0x0644, X11Input.keysymForChar(0x0644));
        assertEquals(X11Input.XK_RETURN, X11Input.keysymForChar('\n'));
        assertEquals(0, X11Input.keysymForChar(0x07));
    }

    /**
     * 0.4.0 on the phone: taps moved the cursor but did not click, because press and release left together and the
     * game reads the button state once per frame. A tap must now reach the server as motion → press → (held) →
     * release, the release at least the click hold time after the press.
     */
    @Test
    public void tapReachesTheServerAsAHeldClick() throws Exception {
        X11Input x = connect(false);
        for (int i = 0; i < 200; i++) {                                     // warm up the fake server's code paths
            x.motion(i, i);
            x.button(2, true);
            x.button(2, false);
            x.queryPointer();
        }
        server.fakes.clear();
        server.times.clear();
        ViewportTransform vt = new ViewportTransform(1024, 768, 1786, 1228);
        TouchMouseMapper m = new TouchMouseMapper(new ControlSettings(), vt);
        float sx = vt.offsetX + 300.5f * vt.scale, sy = vt.offsetY + 200.5f * vt.scale;
        for (InputAction a : m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, sx, sy, 0))) x.send(a);
        for (InputAction a : m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, sx, sy, 70))) x.send(a);
        x.queryPointer();
        int press = -1, release = -1;
        for (int i = 0; i < server.fakes.size(); i++) {
            int[] f = server.fakes.get(i);
            if (f[0] == 4) { assertEquals(-1, press); press = i; }
            if (f[0] == 5) { assertEquals(-1, release); release = i; }
        }
        assertTrue("press before release", press >= 0 && release > press);
        assertEquals(1, server.fakes.get(press)[1]);                         // left button
        assertArrayEquals(new int[] {6, 0, 300, 200, ROOT}, server.fakes.get(press - 1));   // moved there first
        long held = server.times.get(release) - server.times.get(press);
        assertTrue("held " + held + " ms", held >= 100);
        assertEquals(0, x.queryPointer()[2]);                                // nothing left pressed
    }

    /** Touch on a letterboxed phone screen → mapper → X11 events at the game pixel under the finger. */
    @Test
    public void touchOnTheLetterboxedScreenLandsOnTheRightGamePixel() throws Exception {
        X11Input x = connect(false);
        // the owner's game view: 1786x1228 → scale 1.5990, 1024x768 picture with pillar bars of ~74 px each side
        ViewportTransform vt = new ViewportTransform(1024, 768, 1786, 1228);
        TouchMouseMapper m = new TouchMouseMapper(new ControlSettings(), vt);
        float sx = vt.offsetX + 577.5f * vt.scale, sy = vt.offsetY + 316.5f * vt.scale;   // "Start Game" button
        for (InputAction a : m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, sx, sy, 1000))) x.send(a);
        for (InputAction a : m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.UP, sx, sy, 1080))) x.send(a);
        x.queryPointer();
        int[] pos = x.queryPointer();
        assertEquals(577, pos[0]);
        assertEquals(316, pos[1]);
        List<Integer> types = new ArrayList<>();
        for (int[] f : server.fakes) types.add(f[0]);
        assertTrue(types.toString(), types.contains(4) && types.contains(5));   // a left click was sent
        for (int[] f : server.fakes) if (f[0] == 4 || f[0] == 5) assertEquals(1, f[1]);
        // a touch in the pillar bar clamps to the picture's edge
        for (InputAction a : m.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.DOWN, 5, sy, 5000))) x.send(a);
        assertEquals(0, x.queryPointer()[0]);
    }
}
