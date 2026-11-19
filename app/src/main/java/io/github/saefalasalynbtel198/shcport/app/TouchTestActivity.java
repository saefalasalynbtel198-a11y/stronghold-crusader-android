package io.github.saefalasalynbtel198.shcport.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayDeque;
import java.util.List;

import io.github.saefalasalynbtel198.shcport.core.ControlSettings;
import io.github.saefalasalynbtel198.shcport.core.InputAction;
import io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper;
import io.github.saefalasalynbtel198.shcport.core.ViewportTransform;

/**
 * Full-screen surface that runs the real touch → mouse/keyboard mapper and visualises the
 * resulting Windows input events on a 1024×768 game-sized area. This is the same mapper the game
 * view will use; here the events are drawn instead of being injected into Wine.
 */
public final class TouchTestActivity extends Activity {

    private TouchSurface surface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        surface = new TouchSurface(this);
        root.addView(surface);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        Button esc = Ui.button(this, "Esc", v -> surface.injectKey(InputAction.VK_ESCAPE));
        Button space = Ui.button(this, "Space", v -> surface.injectKey(InputAction.VK_SPACE));
        Button settings = Ui.button(this, "Settings", v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(esc);
        bar.addView(space);
        bar.addView(settings);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        root.addView(bar, lp);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        surface.reloadSettings(new ControlSettingsStore(this).load());
    }

    @Override
    protected void onPause() {
        surface.releaseAll();
        super.onPause();
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

    /** Draws the virtual game area, cursor, held buttons/keys and an event log. */
    static final class TouchSurface extends View {
        private static final int GAME_W = 1024, GAME_H = 768, LOG_LINES = 14;

        private final Paint area = new Paint();
        private final Paint grid = new Paint();
        private final Paint cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayDeque<String> log = new ArrayDeque<>();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable longPressCheck = this::checkLongPress;

        private ControlSettings settings = new ControlSettings();
        private ViewportTransform viewport;
        private TouchMouseMapper mapper;
        private int cursorX = GAME_W / 2, cursorY = GAME_H / 2;
        private boolean leftHeld, rightHeld;
        private final java.util.Set<Integer> keysHeld = new java.util.TreeSet<>();
        private int wheelTotal;

        TouchSurface(Context c) {
            super(c);
            area.setColor(Color.parseColor("#2E4A2A"));
            grid.setColor(Color.parseColor("#3F6139"));
            grid.setStrokeWidth(1f);
            text.setColor(Color.WHITE);
            text.setTextSize(28f);
            setBackgroundColor(Color.BLACK);
        }

        void reloadSettings(ControlSettings s) {
            settings = s;
            if (mapper != null) mapper.setSettings(s);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            viewport = new ViewportTransform(GAME_W, GAME_H, w, h);
            if (mapper == null) mapper = new TouchMouseMapper(settings, viewport);
            else mapper.setViewport(viewport);
        }

        void injectKey(int vk) {
            apply(java.util.Arrays.asList(InputAction.keyDown(vk), InputAction.keyUp(vk)));
        }

        void releaseAll() {
            if (mapper == null) return;
            long now = SystemClock.uptimeMillis();
            apply(mapper.onTouch(TouchMouseMapper.Frame.single(TouchMouseMapper.Action.CANCEL, 0, 0, now)));
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
            int actionId = e.getPointerId(e.getActionIndex());
            apply(mapper.onTouch(new TouchMouseMapper.Frame(a, actionId, ids, xs, ys, e.getEventTime())));
            scheduleLongPress();
            if (a == TouchMouseMapper.Action.UP) performClick();
            return true;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        private void scheduleLongPress() {
            handler.removeCallbacks(longPressCheck);
            long deadline = mapper.longPressDeadline();
            if (deadline > 0) handler.postAtTime(longPressCheck, deadline);
        }

        private void checkLongPress() {
            apply(mapper.onTick(SystemClock.uptimeMillis()));
        }

        private void apply(List<InputAction> actions) {
            for (InputAction act : actions) {
                switch (act.type) {
                    case MOVE: cursorX = act.x; cursorY = act.y; break;
                    case BUTTON_DOWN:
                        if (act.button == InputAction.Button.LEFT) leftHeld = true; else rightHeld = true;
                        break;
                    case BUTTON_UP:
                        if (act.button == InputAction.Button.LEFT) leftHeld = false; else rightHeld = false;
                        break;
                    case WHEEL: wheelTotal += act.wheel; break;
                    case KEY_DOWN: keysHeld.add(act.keyCode); break;
                    case KEY_UP: keysHeld.remove(act.keyCode); break;
                    default: break;
                }
                if (act.type != InputAction.Type.MOVE) {
                    log.addFirst(act.toString());
                    while (log.size() > LOG_LINES) log.removeLast();
                }
            }
            if (!actions.isEmpty()) invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (viewport == null) return;
            float l = viewport.offsetX, t = viewport.offsetY, s = viewport.scale;
            c.drawRect(l, t, l + GAME_W * s, t + GAME_H * s, area);
            for (int gx = 0; gx <= GAME_W; gx += 64) c.drawLine(l + gx * s, t, l + gx * s, t + GAME_H * s, grid);
            for (int gy = 0; gy <= GAME_H; gy += 64) c.drawLine(l, t + gy * s, l + GAME_W * s, t + gy * s, grid);

            float cx = l + cursorX * s, cy = t + cursorY * s;
            cursor.setColor(leftHeld ? Color.YELLOW : rightHeld ? Color.RED : Color.WHITE);
            c.drawCircle(cx, cy, 14f, cursor);

            float y = t + 40;
            c.drawText("Touch-control test — events below are what the game will receive.", l + 16, y, text);
            y += 36;
            c.drawText("The game runtime is not bundled in this development build.", l + 16, y, text);
            y += 36;
            c.drawText("cursor " + cursorX + "," + cursorY + "  L=" + leftHeld + " R=" + rightHeld
                    + "  wheel=" + wheelTotal + "  keys=" + keysHeld, l + 16, y, text);
            for (String line : log) {
                y += 32;
                c.drawText(line, l + 16, y, text);
            }
        }
    }
}
