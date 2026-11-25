package io.github.saefalasalynbtel198.shcport.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import io.github.saefalasalynbtel198.shcport.core.InputAction;
import io.github.saefalasalynbtel198.shcport.core.SessionLifecycle;
import io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper;
import io.github.saefalasalynbtel198.shcport.core.DisplayResolution;
import io.github.saefalasalynbtel198.shcport.core.ScalingMode;
import io.github.saefalasalynbtel198.shcport.core.ViewportTransform;
import io.github.saefalasalynbtel198.shcport.core.X11DisplayPlan;
import io.github.saefalasalynbtel198.shcport.core.X11Input;

/**
 * The playable game: Stronghold Crusader through the device-verified path (runtime r6, Box64, Wine, cnc-ddraw GDI,
 * Xvfb, the private game copy), drawn full screen with its 4:3 aspect kept, controlled by touch (tap = left click,
 * long press = right click, drag = left drag, two-finger drag = scroll, pinch = wheel), the soft keyboard, a hardware
 * keyboard and a few on-screen keys. One session per app; it survives this screen being paused or recreated.
 */
public final class GameActivity extends Activity implements Choreographer.FrameCallback {

    private ShcApplication app;
    private GLSurfaceView view;
    private final FramebufferRenderer renderer = new FramebufferRenderer();
    private PlaySurface surface;
    private TextView overlay;
    private LinearLayout overlayBox;
    private Button again;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private int pauses, resumes;
    private boolean attachedSource;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (ShcApplication) getApplication();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setRenderer(renderer);
        view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        renderer.wholeFrame = true;

        surface = new PlaySurface(this);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.addView(key("Esc", X11Input.XK_ESCAPE));
        bar.addView(key("Enter", X11Input.XK_RETURN));
        bar.addView(key("Space", X11Input.XK_SPACE));
        rightToggle = small("Right", v -> setRightMode(!surface.rightMode()));
        bar.addView(rightToggle);
        bar.addView(small("Keys", v -> surface.showKeyboard()));
        bar.addView(small("Report", v -> copyReport()));
        bar.addView(small("Exit", v -> confirmExit()));

        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(16);
        overlay.setGravity(Gravity.CENTER);
        again = Ui.button(this, "Start again", v -> restart());
        Button back = Ui.button(this, "Back to the menu", v -> exitGame());
        overlayBox = new LinearLayout(this);
        overlayBox.setOrientation(LinearLayout.VERTICAL);
        overlayBox.setGravity(Gravity.CENTER);
        overlayBox.setBackgroundColor(0xb0000000);
        overlayBox.setPadding(48, 32, 48, 32);
        overlayBox.addView(overlay);
        overlayBox.addView(Ui.row(this, again, back));

        // the game area is the screen left of the button column: the picture (stretched or not) never lies under
        // the buttons, and touches on the buttons never reach the game
        FrameLayout game = new FrameLayout(this);
        game.addView(view, new FrameLayout.LayoutParams(-1, -1));
        game.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        game.addView(overlayBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(game, new LinearLayout.LayoutParams(0, -1, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-2, -1));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        setContentView(root);

        wantStart = !app.playLife.active();      // otherwise: show the running session
        ui.post(tick);
    }

    private Button rightToggle;

    /**
     * Right-button mode: while on, a one-finger touch holds the right mouse button (tap = right click, drag = right
     * drag, which opens the game's camera interface). Off again: taps are left clicks.
     */
    private void setRightMode(boolean on) {
        surface.setRightMode(on);
        rightToggle.setText(on ? "Right ✓" : "Right");
        rightToggle.setAlpha(on ? 1f : 0.75f);
        rightToggle.setBackgroundColor(on ? 0xffb03020 : 0xff505050);
    }

    private Button key(String label, int keysym) {
        return small(label, v -> {
            GameInput in = app.playInput;
            if (in != null) in.tapKey(keysym);
        });
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setAlpha(0.75f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        return b;
    }

    // ------------------------------------------------------------------------------------------------ session

    /** Set when this screen should start a session as soon as no other session is running or closing. */
    private boolean wantStart;

    private void start() {
        // Display Resolution (Settings): "Device" needs the size of the game view, known after the first layout
        DisplaySettingsStore display = new DisplaySettingsStore(this);
        DisplayResolution.Mode mode = display.load();
        if (mode == DisplayResolution.Mode.DEVICE && (surface.getWidth() == 0 || surface.getHeight() == 0)) return;
        SessionLifecycle life = app.playLife;
        if (!life.tryStart()) return;            // a session exists: this screen shows it
        wantStart = false;
        final int gen = life.generation();
        DisplayTestSession s = new DisplayTestSession(this, app.paths(), true, true, (st, msg) -> { });
        s.requestedScreen = DisplayResolution.resolve(mode, surface.getWidth(), surface.getHeight(),
                DisplaySettingsStore.totalMemory(this));
        s.scaling = display.loadScaling();       // Display Scaling Mode: fixed for this session
        surface.setGameSize(X11DisplayPlan.WIDTH, X11DisplayPlan.HEIGHT, ScalingMode.PRESERVE);
        startSound(s);
        app.playSession = s;
        attachedSource = false;
        new Thread(() -> {
            try {
                s.start(() -> {
                    GameInput in = new GameInput(s.display());
                    app.playInput = in;
                    in.connect();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (s.state == DisplayTestSession.State.FAILED) life.ended(gen, s.error);
            else life.started(gen);
        }, "game-start").start();
    }

    /**
     * Game sound (when switched on in Settings and the APK has the ALSA plugin): the bridge listens before the game
     * starts and the game gets ALSA_CONFIG_PATH. Without it the game starts exactly as in 0.4.1 (no sound output).
     */
    private void startSound(DisplayTestSession s) {
        io.github.saefalasalynbtel198.shcport.core.AudioSettings a = app.audio;
        if (a == null || !a.enabled) {
            s.log("game sound: off (Settings)");
            return;
        }
        AudioBridge b = new AudioBridge(() -> app.audio);
        java.io.File conf = AudioBridge.prepareConfig(this, app.paths(), b.socketName);
        if (conf == null) {
            s.log("game sound: not available (no ALSA plugin in this APK or app storage not writable)");
            return;
        }
        try {
            b.start();
        } catch (java.io.IOException e) {
            s.log("game sound: cannot listen: " + e.getMessage());
            return;
        }
        s.extraGameEnv.putAll(io.github.saefalasalynbtel198.shcport.core.GameSoundEnv.forConfig(conf.getPath()));
        app.playAudio = b;
        requestAudioFocus(b);
        s.log("game sound: " + b.status + ", ALSA config " + conf);
    }

    private boolean focusLost;

    /** Pauses the sound while another app (call, alarm, music) has Android's audio focus. */
    private void requestAudioFocus(AudioBridge b) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null || android.os.Build.VERSION.SDK_INT < 26) return;
        ShcApplication a = app;
        android.media.AudioFocusRequest req = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_GAME)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(change -> ui.post(() -> {
                    if (a.playAudio != b) return;
                    if (change == android.media.AudioManager.AUDIOFOCUS_LOSS
                            || change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        focusLost = true;
                        b.setPaused(true);
                    } else if (change == android.media.AudioManager.AUDIOFOCUS_GAIN) {
                        focusLost = false;
                        if (resumed) b.setPaused(false);
                    }
                }))
                .build();
        am.requestAudioFocus(req);
        app.playAudioFocus = req;
    }

    /** Stops the processes of the current session (Wine, Xvfb) and releases input and sound; background thread. */
    private static void stopSession(ShcApplication app) {
        SessionLifecycle life = app.playLife;
        DisplayTestSession s = app.playSession;
        GameInput in = app.playInput;
        AudioBridge audio = app.playAudio;
        if (life.beginStop()) {
            if (in != null) in.close();
            if (s != null) {
                s.stop();
                if (audio != null) for (String l : audio.describe()) s.log(l);
                s.save(s.report(new FramebufferRenderer.Stats(), 0, 0, false));
            }
            if (audio != null) audio.close();       // after Wine has stopped: no stream is left open
        }
        Object focus = app.playAudioFocus;
        if (focus != null && android.os.Build.VERSION.SDK_INT >= 26) {
            android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(AUDIO_SERVICE);
            if (am != null) am.abandonAudioFocusRequest((android.media.AudioFocusRequest) focus);
        }
        app.playAudioFocus = null;
        app.playAudio = null;
        app.playInput = null;
        app.playSession = null;
        life.stopped();
    }

    private void restart() {
        renderer.setSource(null);
        attachedSource = false;
        overlay.setText("Stopping the previous session…");
        new Thread(() -> {
            stopSession(app);
            ui.post(() -> wantStart = true);
        }, "game-restart").start();
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("Exit game?")
                .setMessage("Stronghold Crusader is closed. Progress that the game has not saved is lost.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Exit game", (d, w) -> exitGame())
                .show();
    }

    private void exitGame() {
        renderer.setSource(null);
        view.requestRender();
        Toast.makeText(this, "Closing the game…", Toast.LENGTH_SHORT).show();
        ShcApplication a = app;
        new Thread(() -> stopSession(a), "game-exit").start();
        finish();
    }

    private void copyReport() {
        DisplayTestSession s = app.playSession;
        String text = s == null ? "No game session." : s.report(renderer.stats, pauses, resumes, true);
        if (s != null) s.save(text);
        GameInput in = app.playInput;
        AudioBridge audio = app.playAudio;
        String sound = audio == null ? "Sound: off or not available\n" : String.join("\n", audio.describe()) + "\n";
        if (in != null) text = "Input: " + in.status + ", " + in.sent + " events sent\n" + sound + text;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("SHC Port game report", text));
        Toast.makeText(this, "Report copied (also saved in the app's logs)", Toast.LENGTH_SHORT).show();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            DisplayTestSession s = app.playSession;
            SessionLifecycle life = app.playLife;
            if (s != null) {
                s.poll(renderer.stats);
                if (!attachedSource && s.framebuffer() != null) {
                    // touches map onto the X screen the game really uses (after any fallback to 1024x768)
                    // the renderer and the touch mapper use the same transform (size and scaling mode)
                    renderer.scaling = s.scaling;
                    surface.setGameSize(s.screenW, s.screenH, s.scaling);
                    renderer.setSource(s.framebuffer());
                    view.requestRender();
                    attachedSource = true;
                    if (!s.displayNote.isEmpty()) {
                        Toast.makeText(GameActivity.this, "Display Resolution: " + s.displayNote, Toast.LENGTH_LONG).show();
                    }
                }
                if (s.state == DisplayTestSession.State.FAILED && life.phase() == SessionLifecycle.Phase.RUNNING) {
                    life.ended(life.generation(), s.error);
                }
            }
            SessionLifecycle.Phase p = life.phase();
            if (wantStart && p == SessionLifecycle.Phase.IDLE) {
                start();
                p = life.phase();
            }
            if (p == SessionLifecycle.Phase.STOPPING || (wantStart && p != SessionLifecycle.Phase.IDLE)) {
                overlay.setText("Closing the previous game session…");
                again.setVisibility(View.GONE);
                overlayBox.setVisibility(View.VISIBLE);
            } else if (p == SessionLifecycle.Phase.ENDED) {
                String e = life.endReason();
                boolean closed = e != null && e.contains("EXITED") && e.contains("code 0");
                overlay.setText(closed ? "Stronghold Crusader has closed." : "The game stopped:\n" + e);
                again.setVisibility(View.VISIBLE);
                overlayBox.setVisibility(View.VISIBLE);
            } else if (s == null) {
                overlay.setText("Starting…");
                again.setVisibility(View.GONE);
                overlayBox.setVisibility(View.VISIBLE);
            } else if (s.state != DisplayTestSession.State.RUNNING) {
                overlay.setText(s.status + (s.state == DisplayTestSession.State.STARTING_WINE ? "\n(the first start takes longer)" : ""));
                again.setVisibility(View.GONE);
                overlayBox.setVisibility(View.VISIBLE);
            } else {
                overlayBox.setVisibility(View.GONE);
            }
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------------------------------------ lifecycle

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!resumed) return;
        view.requestRender();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        view.onResume();
        surface.reloadSettings();
        if (pauses > 0) {
            resumes++;
            renderer.markResumed();
            DisplayTestSession s = app.playSession;
            if (s != null) s.log("game screen resumed (" + resumes + ")");
        }
        resumed = true;
        AudioBridge audio = app.playAudio;
        if (audio != null && !focusLost) audio.setPaused(false);
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        resumed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        surface.releaseAll();
        AudioBridge audio = app.playAudio;
        if (audio != null) audio.setPaused(true);   // no game sound while the screen is not shown
        view.onPause();                  // the game and Xvfb keep running; drawing resumes in onResume
        pauses++;
        DisplayTestSession s = app.playSession;
        if (s != null) s.log("game screen paused (" + pauses + ")");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        renderer.setSource(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        confirmExit();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);
        int sym = keysymFor(keyCode, event);
        GameInput in = app.playInput;
        if (sym != 0 && in != null) {
            if (event.getRepeatCount() == 0 || sym < 0xff00 || isArrow(sym)) in.key(sym, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event);
        int sym = keysymFor(keyCode, event);
        GameInput in = app.playInput;
        if (sym != 0 && in != null) {
            in.key(sym, false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private static boolean isArrow(int sym) { return sym >= X11Input.XK_LEFT && sym <= X11Input.XK_DOWN; }

    /** X keysym of a hardware key (keyboards, game pads' D-pad); 0 if it is not sent to the game. */
    static int keysymFor(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE: return X11Input.XK_ESCAPE;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return X11Input.XK_RETURN;
            case KeyEvent.KEYCODE_DEL: return X11Input.XK_BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return X11Input.XK_DELETE;
            case KeyEvent.KEYCODE_TAB: return X11Input.XK_TAB;
            case KeyEvent.KEYCODE_SPACE: return X11Input.XK_SPACE;
            case KeyEvent.KEYCODE_DPAD_LEFT: return X11Input.XK_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return X11Input.XK_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return X11Input.XK_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return X11Input.XK_DOWN;
            case KeyEvent.KEYCODE_PAGE_UP: return X11Input.XK_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return X11Input.XK_PAGE_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return X11Input.XK_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return X11Input.XK_END;
            case KeyEvent.KEYCODE_INSERT: return X11Input.XK_INSERT;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return X11Input.XK_SHIFT_L;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return X11Input.XK_CONTROL_L;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return X11Input.XK_ALT_L;
            default: break;
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) return X11Input.XK_F1 + (keyCode - KeyEvent.KEYCODE_F1);
        int ch = e.getUnicodeChar(0);              // the key's base character (Shift is sent as its own key)
        return ch > 0 ? X11Input.keysymForChar(Character.toLowerCase(ch)) : 0;
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ------------------------------------------------------------------------------------------------ touch + IME

    /** Transparent layer over the game picture: touch → {@link TouchMouseMapper} → X11 input; soft keyboard text. */
    final class PlaySurface extends View {
        private final Runnable longPressCheck = this::checkLongPress;
        private TouchMouseMapper mapper;
        private ViewportTransform viewport;
        /** Size of the X screen shown (Display Resolution); the renderer takes it from the framebuffer itself. */
        private int gameW = X11DisplayPlan.WIDTH, gameH = X11DisplayPlan.HEIGHT;
        private ScalingMode scaling = ScalingMode.PRESERVE;

        PlaySurface(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        private boolean rightMode;

        boolean rightMode() { return rightMode; }

        void setRightMode(boolean on) {
            rightMode = on;
            if (mapper != null) mapper.setRightButtonMode(on);
        }

        void reloadSettings() {
            if (mapper != null) mapper.setSettings(new ControlSettingsStore(getContext()).load());
        }

        void setGameSize(int w, int h, ScalingMode mode) {
            if (w <= 0 || h <= 0 || (w == gameW && h == gameH && mode == scaling)) return;
            releaseAll();                        // no button stays down across the change
            gameW = w;
            gameH = h;
            scaling = mode;
            if (getWidth() > 0 && getHeight() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            // the same letterbox geometry as the renderer, so a touch lands on the pixel shown under the finger
            viewport = new ViewportTransform(gameW, gameH, w, h, scaling);
            if (mapper == null) {
                mapper = new TouchMouseMapper(new ControlSettingsStore(getContext()).load(), viewport);
                mapper.setRightButtonMode(rightMode);
            } else {
                mapper.setViewport(viewport);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (mapper == null) return false;
            TouchMouseMapper.Action a;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: a = TouchMouseMapper.Action.DOWN; break;
                case MotionEvent.ACTION_POINTER_DOWN: a = TouchMouseMapper.Action.POINTER_DOWN; break;
                case MotionEvent.ACTION_MOVE: a = TouchMouseMapper.Action.MOVE; break;
                case MotionEvent.ACTION_POINTER_UP: a = TouchMouseMapper.Action.POINTER_UP; break;
                case MotionEvent.ACTION_UP: a = TouchMouseMapper.Action.UP; break;
                case MotionEvent.ACTION_CANCEL: a = TouchMouseMapper.Action.CANCEL; break;
                default: return true;
            }
            int n = e.getPointerCount();
            int[] ids = new int[n];
            float[] xs = new float[n], ys = new float[n];
            for (int i = 0; i < n; i++) {
                ids[i] = e.getPointerId(i);
                xs[i] = e.getX(i);
                ys[i] = e.getY(i);
            }
            send(mapper.onTouch(new TouchMouseMapper.Frame(a, e.getPointerId(e.getActionIndex()), ids, xs, ys, e.getEventTime())));
            ui.removeCallbacks(longPressCheck);
            long deadline = mapper.longPressDeadline();
            if (deadline > 0) ui.postAtTime(longPressCheck, deadline);
            if (a == TouchMouseMapper.Action.UP) performClick();
            return true;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        private void checkLongPress() {
            if (mapper != null) send(mapper.onTick(SystemClock.uptimeMillis()));
        }

        void releaseAll() {
            ui.removeCallbacks(longPressCheck);
            if (mapper != null) send(mapper.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.CANCEL, 0, 0, SystemClock.uptimeMillis())));
            GameInput in = app.playInput;
            if (in != null) in.releaseButtons();
        }

        private void send(List<InputAction> actions) {
            GameInput in = app.playInput;
            if (in != null) in.send(actions);
        }

        void showKeyboard() {
            requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo out) {
            // plain characters without suggestions or composition, sent to the game as typed
            out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_DONE;
            return new BaseInputConnection(this, false) {
                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    GameInput in = app.playInput;
                    if (in != null && text.length() > 0) in.type(text);
                    return true;
                }

                @Override
                public boolean deleteSurroundingText(int before, int after) {
                    GameInput in = app.playInput;
                    for (int i = 0; in != null && i < Math.max(1, before); i++) in.tapKey(X11Input.XK_BACKSPACE);
                    return true;
                }

                @Override
                public boolean sendKeyEvent(KeyEvent ev) {
                    int sym = keysymFor(ev.getKeyCode(), ev);
                    GameInput in = app.playInput;
                    if (sym != 0 && in != null) in.key(sym, ev.getAction() == KeyEvent.ACTION_DOWN);
                    return true;
                }

                @Override
                public boolean performEditorAction(int actionCode) {
                    GameInput in = app.playInput;
                    if (in != null) in.tapKey(X11Input.XK_RETURN);
                    return true;
                }
            };
        }
    }
}
