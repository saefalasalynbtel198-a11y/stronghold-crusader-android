package io.github.saefalasalynbtel198.shcport.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;
import io.github.saefalasalynbtel198.shcport.core.X11DisplayPlan;

/**
 * BUILD 3 step 3.2: shows Xvfb's real framebuffer while the Windows test program draws into it through Box64 and
 * Wine. Diagnostic screen only; Start Game stays disabled.
 */
public final class DisplayTestActivity extends Activity implements Choreographer.FrameCallback {
    private GLSurfaceView view;
    private final FramebufferRenderer renderer = new FramebufferRenderer();
    private TextView statusText;
    private Button start;
    private Button stop;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile DisplayTestSession session;
    private volatile boolean busy;
    private boolean resumed;
    private int pauses;
    private int resumes;
    private long framesAtResumeCheck = -1;
    private boolean recovered;
    private String lastReport = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AppPaths paths = new AppPaths(this);
        RuntimeLayout rt = new RuntimeLayout(paths.runtimeDir());

        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setRenderer(renderer);
        view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        LinearLayout side = Ui.column(this);
        side.addView(Ui.title(this, "Display test"));
        side.addView(Ui.muted(this, "Box64 → Wine → X11 → Xvfb → this screen. " + X11DisplayPlan.WIDTH + "×"
                + X11DisplayPlan.HEIGHT + ", letterboxed. Diagnostic only; Start Game stays disabled."));
        start = Ui.button(this, "Start display test", v -> startTest());
        stop = Ui.button(this, "Stop display test", v -> stopTest());
        Button copy = Ui.button(this, "Copy report", v -> copyReport());
        side.addView(Ui.row(this, start, stop));
        side.addView(copy);
        statusText = Ui.body(this, rt.hasX11() ? "Ready." : "This runtime has no X server: install runtime r6 or newer.");
        statusText.setTextSize(12);
        side.addView(statusText);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xff000000);
        root.addView(view, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));
        root.addView(Ui.scroll(this, side), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f));
        setContentView(root);
        updateButtons();
        ui.post(statusTick);
    }

    private void updateButtons() {
        boolean running = session != null && session.state != DisplayTestSession.State.STOPPED
                && session.state != DisplayTestSession.State.FAILED;
        start.setEnabled(!busy && !running && new RuntimeLayout(new AppPaths(this).runtimeDir()).hasX11());
        stop.setEnabled(!busy && session != null && (running || session.state == DisplayTestSession.State.FAILED));
    }

    private void startTest() {
        busy = true;
        updateButtons();
        pauses = 0;
        resumes = 0;
        recovered = false;
        framesAtResumeCheck = -1;
        DisplayTestSession s = new DisplayTestSession(this, new AppPaths(this), (st, msg) -> ui.post(this::updateButtons));
        session = s;
        new Thread(() -> {
            try {
                File fb = s.start(() -> { /* Xvfb ready */ });
                if (fb != null) renderer.setSource(fb);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                busy = false;
                ui.post(this::updateButtons);
            }
        }, "display-test-start").start();
    }

    private void stopTest() {
        DisplayTestSession s = session;
        if (s == null) return;
        busy = true;
        updateButtons();
        lastReport = s.report(renderer.stats, pauses, resumes, recovered);
        renderer.setSource(null);            // stop reading the framebuffer before Xvfb goes away
        view.requestRender();
        new Thread(() -> {
            s.stop();
            lastReport = s.report(renderer.stats, pauses, resumes, recovered);
            s.save(lastReport);
            busy = false;
            ui.post(this::updateButtons);
        }, "display-test-stop").start();
    }

    private void copyReport() {
        DisplayTestSession s = session;
        String text = s == null ? "No display test run yet." : s.report(renderer.stats, pauses, resumes, recovered);
        if (s != null) s.save(text);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("SHC display test", text));
        Toast.makeText(this, "Report copied (also saved in the app's logs)", Toast.LENGTH_SHORT).show();
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            DisplayTestSession s = session;
            FramebufferRenderer.Stats st = renderer.stats;
            if (s != null) s.poll(st);
            if (resumes > 0 && framesAtResumeCheck < 0 && st.framesSinceResume > 30 && st.pixelOk) recovered = true;
            StringBuilder sb = new StringBuilder();
            if (s == null) {
                sb.append(statusText.getText().toString().startsWith("This runtime") ? statusText.getText() : "Ready.");
            } else {
                sb.append("State: ").append(s.state).append(" — ").append(s.status).append('\n');
                if (s.error != null) sb.append("ERROR: ").append(s.error).append('\n');
                sb.append("Xvfb: ").append(s.xvfbStatus).append('\n');
                sb.append("Wine display: ").append(s.wineStatus).append('\n');
                sb.append(String.format(Locale.ROOT, "Resolution: %s → view %dx%d%n",
                        st.format.isEmpty() ? "-" : X11DisplayPlan.WIDTH + "x" + X11DisplayPlan.HEIGHT, st.viewWidth, st.viewHeight));
                sb.append(String.format(Locale.ROOT, "Display %d fps · uploads %d/s · Wine content %d fps%n",
                        st.displayFps, st.uploadsPerSecond, st.contentFps));
                sb.append("Frame counter: ").append(st.lastCounter).append(" · dropped ").append(st.droppedFrames).append('\n');
                sb.append("Pixel check: ").append(st.pixelOk ? "PASS" : "not passed").append('\n');
                if (!st.pixelOk) sb.append(st.pixelCheck).append('\n');
                if (st.error != null) sb.append("Renderer: ").append(st.error).append('\n');
                sb.append("Paused ").append(pauses).append(" · resumed ").append(resumes)
                        .append(resumes > 0 ? (recovered ? " · display updating after resume" : " · waiting for frames after resume") : "").append('\n');
                sb.append(s.resources());
            }
            statusText.setText(sb.toString());
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!resumed) return;
        view.requestRender();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
        if (pauses > 0) {
            resumes++;
            recovered = false;
            framesAtResumeCheck = -1;
            renderer.markResumed();
            DisplayTestSession s = session;
            if (s != null) s.log("activity resumed (" + resumes + ")");
        }
        resumed = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        resumed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        view.onPause();     // GL thread pauses (context may be destroyed); Xvfb and Wine keep running
        pauses++;
        DisplayTestSession s = session;
        if (s != null) s.log("activity paused (" + pauses + ")");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(statusTick);
        DisplayTestSession s = session;
        if (s != null && s.state != DisplayTestSession.State.STOPPED) {
            renderer.setSource(null);
            new Thread(() -> {
                s.stop();
                s.save(s.report(renderer.stats, pauses, resumes, recovered));
            }, "display-test-destroy").start();
        }
        super.onDestroy();
    }
}
