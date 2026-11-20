package io.github.saefalasalynbtel198.shcport.app;

import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.saefalasalynbtel198.shcport.BuildConfig;
import io.github.saefalasalynbtel198.shcport.core.ElfInterp;
import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;
import io.github.saefalasalynbtel198.shcport.core.RuntimeProbe;
import io.github.saefalasalynbtel198.shcport.core.WineTestPlan;

/**
 * Runs {@link WineTestPlan} on the device and produces a complete text report. Blocking: call from a
 * background thread. Each step's full output (capped) goes into the report and logs/wine-tests-*.txt.
 */
final class WineTestRunner {

    interface Progress { void update(String message); }

    private static final int MAX_OUTPUT = 48 * 1024;

    private final AppPaths paths;

    WineTestRunner(AppPaths paths) { this.paths = paths; }

    String run(GameInstallation game, Progress progress) {
        RuntimeLayout rt = new RuntimeLayout(paths.runtimeDir());
        File prefix = paths.prefixDir();
        File tmp = paths.tmpDir();
        StringBuilder sb = new StringBuilder();
        sb.append("SHC port ").append(BuildConfig.VERSION_NAME).append(" — Wine tests (BUILD 3 phase 1–2)\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Runtime: ").append(readText(rt.versionFile())).append('\n');
        try {
            sb.append("Box64 interpreter: ").append(ElfInterp.read(rt.box64())).append('\n');
        } catch (Exception e) {
            sb.append("Box64 interpreter: unreadable (").append(e.getMessage()).append(")\n");
        }
        sb.append("Prefix: ").append(prefix).append('\n');

        String gameExe = null;
        if (game != null && game.present) {
            GameInstallation.Executable c = game.launchCandidate();
            if (c != null) {
                gameExe = c.name;
                sb.append("Game executable: ").append(c.name).append(" — ").append(c.classification.label).append('\n');
            } else {
                sb.append("Game executable: none allowed by build verification; launch step skipped\n");
            }
        } else {
            sb.append("Game executable: no game imported; launch step skipped\n");
        }
        sb.append('\n');

        //noinspection ResultOfMethodCallIgnored
        prefix.getParentFile().mkdirs();
        List<WineTestPlan.Step> steps = WineTestPlan.steps(rt, prefix, tmp, paths.gameDir(), gameExe);
        int required = 0, passed = 0;
        for (WineTestPlan.Step s : steps) if (s.required) required++;
        boolean stop = false;
        for (WineTestPlan.Step s : steps) {
            boolean cleanup = s.argv.size() > 2 && "-k".equals(s.argv.get(s.argv.size() - 1));
            if (stop && !cleanup) continue;
            progress.update(s.name + "…");
            if (s.argv.contains("D:\\" + gameExe) && gameExe != null) {
                String link = linkGameDrive(prefix, paths.gameDir());
                sb.append("dosdevices/d: -> ").append(link).append('\n');
            }
            sb.append("== ").append(s.name).append(" ==\n$ ").append(String.join(" ", s.argv)).append('\n');
            long t0 = System.nanoTime();
            int exit;
            String out;
            try {
                ProcessBuilder pb = new ProcessBuilder(s.argv);
                pb.environment().putAll(s.env);
                //noinspection ResultOfMethodCallIgnored
                s.cwd.mkdirs();
                pb.directory(s.cwd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                Thread reader = drain(p.getInputStream(), bos);
                boolean done = p.waitFor(s.timeoutMs, TimeUnit.MILLISECONDS);
                if (!done) p.destroyForcibly();
                reader.join(3000);
                synchronized (bos) {
                    out = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                }
                exit = done ? p.exitValue() : -1;
            } catch (Exception e) {
                exit = -2;
                out = "[could not start: " + e + "]";
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String post = postCheck(s.postCheck, prefix);
            boolean ok = s.outputOk(exit, out) && post == null;
            sb.append(tail(out.replace("\r", ""), 6000)).append('\n');
            if (post != null) sb.append("post-check: ").append(post).append('\n');
            sb.append(String.format(Locale.ROOT, "-> exit %d (%s), %d ms: %s%s\n\n", exit, RuntimeProbe.describeExit(exit), ms,
                    ok ? "PASS" : "FAIL", s.required ? "" : " [diagnostic]"));
            if (s.required) {
                if (ok) passed++;
                else stop = true;
            }
        }
        sb.append(String.format(Locale.ROOT, "Result: %d of %d required steps passed.\n", passed, required));
        if (passed < required) sb.append("Wine is not yet usable on this device. Please send this whole report.\n");
        String report = sb.toString();
        save(report);
        return report;
    }

    /** null = OK, otherwise the reason the post-condition failed. */
    private static String postCheck(WineTestPlan.Check check, File prefix) {
        switch (check) {
            case PREFIX_CREATED:
                return new File(prefix, "system.reg").isFile() && new File(prefix, "drive_c/windows").isDirectory()
                        ? null : "prefix not created (system.reg or drive_c/windows missing)";
            case WINESERVER_DIR: {
                File[] bases = prefix.listFiles((d, n) -> n.startsWith(".wineserver"));
                if (bases == null || bases.length == 0) return "no .wineserver* directory in the prefix";
                for (File b : bases) {
                    File[] servers = b.listFiles((d, n) -> n.startsWith("server-"));
                    if (servers != null) for (File s : servers) if (new File(s, "socket").exists()) return null;
                }
                return "no wineserver socket in " + Arrays.toString(bases);
            }
            default:
                return null;
        }
    }

    private static String linkGameDrive(File prefix, File gameDir) {
        try {
            Path link = new File(prefix, "dosdevices/d:").toPath();
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, gameDir.toPath());
            return gameDir.getPath();
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }

    private static Thread drain(final InputStream in, final ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[8192];
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
        }, "wine-test-output");
        t.start();
        return t;
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s.trim() : "[… " + (s.length() - max) + " chars omitted …]\n" + s.substring(s.length() - max).trim();
    }

    private static String readText(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void save(String report) {
        String name = "wine-tests-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(paths.logsDir(), name)), StandardCharsets.UTF_8)) {
            w.write(report);
        } catch (Exception ignored) {
            // report is still shown on screen
        }
    }
}
