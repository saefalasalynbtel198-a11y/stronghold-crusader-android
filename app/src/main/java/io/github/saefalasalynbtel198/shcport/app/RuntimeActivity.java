package io.github.saefalasalynbtel198.shcport.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.saefalasalynbtel198.shcport.BuildConfig;
import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;
import io.github.saefalasalynbtel198.shcport.core.RuntimeProbe;

/**
 * BUILD 2 screen: install the runtime package and run the on-device self-test
 * (glibc loader → Box64 → Wine). The report is saved to logs/runtime-selftest.txt and can be copied.
 */
public final class RuntimeActivity extends Activity {

    private static final int REQ_PICK_RUNTIME = 10;
    private static final int MAX_OUTPUT = 32 * 1024;

    private ShcApplication app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView report;
    private Button install;
    private Button selfTest;
    private Button wineTest;
    private Button displayTest;
    private Button gameLaunchTest;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (ShcApplication) getApplication();
        LinearLayout col = Ui.column(this);
        col.addView(Ui.title(this, "Runtime (Wine + Box64)"));
        col.addView(Ui.muted(this, "Install the runtime package (shc-runtime-*.zip from the project's "
                + "\"Runtime components\" GitHub Actions run), then run the self-test. The self-test does not "
                + "start the game; it checks that this device can execute the runtime."));
        status = Ui.body(this, "");
        col.addView(status);
        install = Ui.button(this, "Install runtime package…", v -> pick());
        selfTest = Ui.button(this, "Run self-test", v -> runSelfTest());
        wineTest = Ui.button(this, "Run Wine tests (BUILD 3)", v -> runWineTests());
        col.addView(Ui.row(this, install, selfTest, wineTest,
                Ui.button(this, "Copy report", v -> copyReport()),
                Ui.button(this, "Remove runtime", v -> removeRuntime())));
        displayTest = Ui.button(this, "Display test (BUILD 3 step 3.2)…",
                v -> startActivity(new Intent(this, DisplayTestActivity.class)));
        col.addView(displayTest);
        gameLaunchTest = Ui.button(this, "Game launch test (BUILD 3 step 3.3, diagnostic)…",
                v -> startActivity(new Intent(this, DisplayTestActivity.class).putExtra(DisplayTestActivity.EXTRA_MODE, DisplayTestActivity.MODE_GAME)));
        col.addView(gameLaunchTest);
        report = Ui.body(this, "");
        col.addView(report);
        setContentView(Ui.scroll(this, col));
        refresh();
    }

    private void refresh() {
        RuntimeLayout rt = new RuntimeLayout(app.paths().runtimeDir());
        String v = readVersion(rt);
        status.setText(rt.looksInstalled() ? "Installed: " + v : "Not installed.");
        install.setEnabled(!busy);
        selfTest.setEnabled(!busy && rt.looksInstalled());
        wineTest.setEnabled(!busy && rt.looksInstalled() && rt.peSmoke32().isFile());
        displayTest.setEnabled(!busy);   // the screen itself explains a missing r6 runtime
        gameLaunchTest.setEnabled(!busy); // ... and a missing game
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_PICK_RUNTIME);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No document picker available.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_RUNTIME || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        setBusy(true);
        new Thread(() -> {
            String msg;
            try {
                String version = new RuntimeInstaller(this, app.paths()).install(uri, m -> main.post(() -> status.setText(m)));
                msg = "Runtime installed: " + version;
            } catch (Exception e) {
                msg = "Install failed: " + e.getMessage();
            }
            final String m = msg;
            main.post(() -> {
                setBusy(false);
                report.setText(m);
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
            });
        }, "runtime-install").start();
    }

    private void removeRuntime() {
        setBusy(true);
        new Thread(() -> {
            String msg = "Runtime removed.";
            try {
                new RuntimeInstaller(this, app.paths()).uninstall();
            } catch (Exception e) {
                msg = "Remove failed: " + e.getMessage();
            }
            final String m = msg;
            main.post(() -> {
                setBusy(false);
                report.setText(m);
            });
        }, "runtime-remove").start();
    }

    private void runSelfTest() {
        setBusy(true);
        report.setText("Running…");
        new Thread(() -> {
            final String text = selfTestReport();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(app.paths().logsDir(), "runtime-selftest.txt")),
                    StandardCharsets.UTF_8)) {
                w.write(text);
            } catch (Exception ignored) {
                // report is still shown on screen
            }
            main.post(() -> {
                setBusy(false);
                report.setText(text);
            });
        }, "runtime-selftest").start();
    }

    private String selfTestReport() {
        AppPaths p = app.paths();
        RuntimeLayout rt = new RuntimeLayout(p.runtimeDir());
        StringBuilder sb = new StringBuilder();
        sb.append("SHC port ").append(BuildConfig.VERSION_NAME).append(" runtime self-test\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("Runtime: ").append(readVersion(rt)).append("\n\n");

        File prefix = p.prefixDir();
        //noinspection ResultOfMethodCallIgnored
        prefix.mkdirs();
        List<RuntimeProbe.Step> steps = RuntimeProbe.steps(rt, prefix, p.tmpDir());
        int passed = 0;
        for (RuntimeProbe.Step s : steps) {
            sb.append("== ").append(s.name).append(" ==\n$ ").append(String.join(" ", s.argv)).append('\n');
            long t0 = System.nanoTime();
            int exit;
            String out;
            try {
                ProcessBuilder pb = new ProcessBuilder(s.argv);
                pb.environment().putAll(s.env);
                pb.directory(rt.root);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                Thread reader = drain(proc.getInputStream(), bos);
                boolean done = proc.waitFor(s.timeoutMs, TimeUnit.MILLISECONDS);
                if (!done) proc.destroyForcibly();
                reader.join(2000);
                out = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                exit = done ? proc.exitValue() : -1;
                if (!done) out += "\n[timed out after " + s.timeoutMs + " ms]";
            } catch (Exception e) {
                exit = -2;
                out = "[could not start: " + e + "]";
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean ok = s.passed(exit, out);
            sb.append(out.trim()).append('\n');
            sb.append(String.format(Locale.ROOT, "-> exit %d (%s), %d ms: %s%s\n\n", exit, RuntimeProbe.describeExit(exit), ms,
                    ok ? "PASS" : "FAIL", s.required ? "" : " [diagnostic]"));
            if (!s.required) continue;
            if (!ok) break;
            passed++;
        }
        int required = 0;
        for (RuntimeProbe.Step s : steps) if (s.required) required++;
        sb.append(String.format(Locale.ROOT, "Result: %d of %d required steps passed.\n", passed, required));
        if (passed < required) sb.append("The runtime cannot run on this device yet. Please send this whole report.\n");
        return sb.toString();
    }

    private void runWineTests() {
        setBusy(true);
        report.setText("Running Wine tests. The first run creates the Wine prefix and can take several minutes…");
        new Thread(() -> {
            GameInstallation game = GameInstallation.inspect(app.paths().gameDir());
            final String text = new WineTestRunner(app.paths()).run(game, m -> main.post(() -> status.setText(m)));
            main.post(() -> {
                setBusy(false);
                report.setText(text);
            });
        }, "wine-tests").start();
    }

    private static Thread drain(final InputStream in, final ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[4096];
            int n;
            try {
                while ((n = in.read(b)) > 0) {
                    synchronized (out) {
                        if (out.size() < MAX_OUTPUT) out.write(b, 0, Math.min(n, MAX_OUTPUT - out.size()));
                    }
                }
            } catch (Exception ignored) {
                // process ended
            }
        }, "selftest-output");
        t.start();
        return t;
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("SHC runtime self-test", report.getText()));
            Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show();
        }
    }

    private void setBusy(boolean b) {
        busy = b;
        refresh();
    }

    private static String readVersion(RuntimeLayout rt) {
        try {
            return new String(java.nio.file.Files.readAllBytes(rt.versionFile().toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
