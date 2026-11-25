package io.github.saefalasalynbtel198.shcport.app;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.saefalasalynbtel198.shcport.core.InputAction;
import io.github.saefalasalynbtel198.shcport.core.X11DisplayPlan;
import io.github.saefalasalynbtel198.shcport.core.X11Input;

/**
 * Sends the player's input to the game: an X11 connection to the session's Xvfb (abstract socket) on its own thread,
 * XTEST FakeInput events through {@link X11Input}. Wine turns them into the Windows mouse/keyboard messages the game
 * reads. Calls never block the UI thread; if the connection is lost, the next event reconnects.
 */
final class GameInput {
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> new Thread(r, "game-input"));
    private final int display;
    private LocalSocket socket;
    private X11Input x;
    private boolean leftDown, rightDown;
    volatile String status = "not connected";
    volatile long sent;

    GameInput(int display) {
        this.display = display;
    }

    /** Connects in the background (also done lazily by the first event). */
    void connect() {
        thread.execute(this::ensure);
    }

    void send(List<InputAction> actions) {
        if (actions.isEmpty()) return;
        thread.execute(() -> {
            if (!ensure()) return;
            try {
                for (InputAction a : actions) {
                    if (a.type == InputAction.Type.BUTTON_DOWN || a.type == InputAction.Type.BUTTON_UP) {
                        boolean down = a.type == InputAction.Type.BUTTON_DOWN;
                        if (a.button == InputAction.Button.RIGHT) rightDown = down; else leftDown = down;
                    }
                    x.send(a);
                    sent++;
                }
                x.flush();
            } catch (Exception e) {
                drop("send failed: " + e.getMessage());
            }
        });
    }

    /** A key by X keysym, pressed and released (on-screen keys). */
    void tapKey(int keysym) {
        thread.execute(() -> {
            if (!ensure()) return;
            try {
                x.key(keysym, true);
                x.key(keysym, false);
                x.flush();
                sent += 2;
            } catch (Exception e) {
                drop("key failed: " + e.getMessage());
            }
        });
    }

    /** A hardware key held down or released. */
    void key(int keysym, boolean down) {
        thread.execute(() -> {
            if (!ensure()) return;
            try {
                x.key(keysym, down);
                x.flush();
                sent++;
            } catch (Exception e) {
                drop("key failed: " + e.getMessage());
            }
        });
    }

    /** Text from the soft keyboard, typed character by character. */
    void type(CharSequence text) {
        String t = text.toString();
        thread.execute(() -> {
            if (!ensure()) return;
            try {
                t.codePoints().forEach(cp -> {
                    try {
                        x.type(cp);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                x.flush();
                sent += t.length();
            } catch (Exception e) {
                drop("typing failed: " + e.getMessage());
            }
        });
    }

    /** Releases held mouse buttons (activity paused, touch cancelled): the game must not see a stuck button. */
    void releaseButtons() {
        thread.execute(() -> {
            if (x == null) return;
            try {
                if (leftDown) x.button(X11Input.BUTTON_LEFT, false);
                if (rightDown) x.button(X11Input.BUTTON_RIGHT, false);
                leftDown = rightDown = false;
                x.flush();
            } catch (Exception e) {
                drop("release failed: " + e.getMessage());
            }
        });
    }

    void close() {
        thread.execute(() -> drop("closed"));
        thread.shutdown();
        try {
            thread.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean ensure() {
        if (x != null) return true;
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(X11DisplayPlan.abstractSocketName(display), LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(10_000);
            X11Input c = X11Input.connect(s.getInputStream(), s.getOutputStream());
            socket = s;
            x = c;
            status = "connected to " + X11DisplayPlan.displayName(display) + " (XTEST opcode " + c.xtestOpcode + ", screen "
                    + c.screenWidth + "x" + c.screenHeight + ")";
            return true;
        } catch (Exception e) {
            status = "cannot connect to the display: " + e.getMessage();
            return false;
        }
    }

    private void drop(String why) {
        status = why;
        try {
            if (x != null) x.close();
        } catch (Exception ignored) {
            // closing anyway
        }
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
            // closing anyway
        }
        x = null;
        socket = null;
    }
}
