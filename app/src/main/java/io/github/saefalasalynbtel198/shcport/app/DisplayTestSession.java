package io.github.saefalasalynbtel198.shcport.app;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.github.saefalasalynbtel198.shcport.BuildConfig;
import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;
import io.github.saefalasalynbtel198.shcport.core.RuntimeProbe;
import io.github.saefalasalynbtel198.shcport.core.WineTestPlan;
import io.github.saefalasalynbtel198.shcport.core.X11DisplayPlan;

/**
 * BUILD 3 step 3.2 display test: Xvfb from the runtime, the test program through Box64 + Wine into it, the app
 * drawing the real framebuffer ({@link FramebufferRenderer}). Owns the processes and writes the report.
 * {@link #start} and {@link #stop} block: call them from a background thread.
 */
final class DisplayTestSession {
    static final String TEST_PROGRAM_ASSET = "shc-display-test.exe";

    enum State { IDLE, PREPARING, STARTING_XVFB, STARTING_WINE, RUNNING, STOPPING, STOPPED, FAILED }

    interface Listener { void onState(State s, String message); }

    private final Context context;
    private final AppPaths paths;
    private final RuntimeLayout rt;
    private final Listener listener;
    private final StringBuilder report = new StringBuilder();
    private final long startedAt = SystemClock.uptimeMillis();
    volatile State state = State.IDLE;
    volatile String status = "";
    volatile String wineStatus = "not started";
    volatile String xvfbStatus = "not started";
    volatile String error;
    X11Display x11;
    Process wine;
    File wineLog;
    String xdpyinfoSummary = "";
    final List<String> timeline = new ArrayList<>();
    private final Map<Integer, Long> lastTicks = new LinkedHashMap<>();
    private long lastSampleMs;

    DisplayTestSession(Context context, AppPaths paths, Listener listener) {
        this.context = context.getApplicationContext();
        this.paths = paths;
        this.rt = new RuntimeLayout(paths.runtimeDir());
        this.listener = listener;
    }

    private void set(State s, String message) {
        state = s;
        status = message;
        log(message);
        listener.onState(s, message);
    }

    private void fail(String message) {
        error = message;
        set(State.FAILED, "FAILED: " + message);
    }

    void log(String line) {
        synchronized (report) {
            report.append(String.format(Locale.ROOT, "[%6.1f s] ", (SystemClock.uptimeMillis() - startedAt) / 1000.0))
                    .append(line).append('\n');
        }
    }

    /** Returns the framebuffer file to draw once Xvfb is ready, or null when the test could not start. */
    File start(Runnable onXvfbReady) throws InterruptedException {
        log("SHC port " + BuildConfig.VERSION_NAME + " display test; " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        log("runtime: " + readText(rt.versionFile()));
        set(State.PREPARING, "Preparing");
        if (!rt.looksInstalled()) { fail("runtime not installed"); return null; }
        if (!rt.hasX11()) { fail("this runtime has no X server: install runtime r6 or newer"); return null; }
        File exe = copyTestProgram();
        if (exe == null) return null;
        File prefix = paths.prefixDir();
        File logs = paths.logsDir();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());

        // Leftovers: an earlier Xvfb, and Wine processes started without a display (Wine's desktop process decides
        // the display driver for later programs, found in the CI feasibility test).
        List<Integer> oldX = ProcInfo.find("/x11/Xvfb");
        if (!oldX.isEmpty()) { log("stopping leftover Xvfb " + oldX); ProcInfo.kill(oldX); }
        if (!ProcInfo.find("wineserver", "/wine/bin/wine").isEmpty()) {
            log("stopping leftover Wine processes: " + run("wineserver -k", Arrays.asList(rt.box64().getPath(),
                    new File(rt.wineBinDir(), "wineserver").getPath(), "-k"), baseWineEnv(), 60));
        }
        if (!new File(prefix, "system.reg").isFile()) {
            set(State.PREPARING, "Creating the Wine prefix (first run, can take minutes)");
            log(run("wineboot --init", Arrays.asList(rt.box64().getPath(), rt.wine().getPath(), "wineboot", "--init"),
                    baseWineEnv(), 20 * 60));
            log(run("wineserver -w", Arrays.asList(rt.box64().getPath(), new File(rt.wineBinDir(), "wineserver").getPath(), "-w"),
                    baseWineEnv(), 300));
            log(run("wineserver -k", Arrays.asList(rt.box64().getPath(), new File(rt.wineBinDir(), "wineserver").getPath(), "-k"),
                    baseWineEnv(), 60));
            if (!new File(prefix, "system.reg").isFile()) { fail("Wine prefix could not be created (run the Wine tests)"); return null; }
        }

        // Xvfb, in a new framebuffer directory for this session (older ones removed)
        File fbRoot = new File(paths.internalRoot(), "x11-fb");
        deleteTree(fbRoot);
        File fbDir = new File(fbRoot, stamp);
        x11 = new X11Display(rt, fbDir, new File(logs, "display-xvfb-" + stamp + ".log"));
        set(State.STARTING_XVFB, "Starting Xvfb");
        log("Xvfb working directory check: " + x11.preflight());
        String why = x11.start(120_000);
        if (x11.removedLocks.length() > 0) log("removed old lock files (not used with -displayfd): " + x11.removedLocks);
        log("Xvfb command (ProcessBuilder argv, no shell; stdout = display-number pipe, stderr = Xvfb log): "
                + argvForLog(x11.argv));
        log("display attempts: " + String.join("; ", x11.attempts));
        if (why != null) {
            xvfbStatus = "failed: " + why;
            log("Xvfb log:\n" + x11.logTail(6000));
            fail("Xvfb: " + why);
            return null;
        }
        xvfbStatus = "running on " + X11DisplayPlan.displayName(x11.display) + " (abstract socket @"
                + X11DisplayPlan.abstractSocketName(x11.display) + ", ready after " + x11.readyMs + " ms)";
        log("Xvfb " + xvfbStatus);
        try {
            x11.stdout.awaitClosed(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log("Xvfb stdout: " + x11.stdout.afterLineSummary());
        File fbFile = x11.framebufferFile();
        log("framebuffer file: " + fbFile + " (" + fbFile.length() + " bytes)");
        xdpyinfoSummary = xdpyinfo();
        log("xdpyinfo: " + xdpyinfoSummary);
        onXvfbReady.run();

        // the test program through Box64 + Wine, with the display
        set(State.STARTING_WINE, "Starting the test program through Box64 + Wine");
        wineLog = new File(logs, "display-wine-" + stamp + ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(X11DisplayPlan.testProgramArgv(rt, exe)).directory(new File(prefix, "drive_c"))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(wineLog));
            pb.environment().putAll(X11DisplayPlan.wineEnv(rt, prefix, paths.tmpDir(), x11.display));
            wine = pb.start();
        } catch (Exception e) {
            fail("could not start Wine: " + e.getMessage());
            return fbFile;
        }
        wineStatus = "starting";
        return fbFile;
    }

    /** Called about once a second by the activity while the test runs. */
    void poll(FramebufferRenderer.Stats s) {
        long now = SystemClock.uptimeMillis();
        if (x11 != null && state != State.STOPPING && state != State.STOPPED) {
            Integer xc = x11.exitCode();
            if (xc != null && state != State.FAILED) {
                xvfbStatus = "EXITED unexpectedly, code " + xc + " (" + RuntimeProbe.describeExit(xc) + ")";
                log("Xvfb log:\n" + x11.logTail(4000));
                fail("Xvfb " + xvfbStatus);
            }
        }
        if (wine != null && state != State.STOPPING && state != State.STOPPED) {
            if (!wine.isAlive() && state != State.FAILED) {
                int c = wine.exitValue();
                wineStatus = "EXITED, code " + c + " (" + RuntimeProbe.describeExit(c) + ")";
                log("Wine log:\n" + tail(wineLog, 6000));
                fail("test program " + wineStatus);
            } else if (wine.isAlive()) {
                String w = tail(wineLog, 3000);
                if (s.pixelOk) wineStatus = "connected: window drawn into the X display";
                else if (w.contains("window") && w.contains("created")) wineStatus = "window created, waiting for pixels";
                else if (w.contains("nodrv_CreateWindow")) wineStatus = "NO display driver (nodrv_CreateWindow)";
                else wineStatus = "starting (Box64/Wine start-up)";
                if (s.pixelOk && state == State.STARTING_WINE) set(State.RUNNING, "Running: test window on screen");
            }
        }
        if (now - lastSampleMs >= 5000) {
            lastSampleMs = now;
            timeline.add(String.format(Locale.ROOT,
                    "t=%4.0fs display %2d fps, uploads %2d/s, Wine content %2d fps, counter %d, dropped %d, pixels %s, %s",
                    (now - startedAt) / 1000.0, s.displayFps, s.uploadsPerSecond, s.contentFps, s.lastCounter,
                    s.droppedFrames, s.pixelOk ? "OK" : "not OK", resources()));
        }
    }

    /** CPU (% of one core since the last call) and resident memory of the app, Xvfb and the Wine processes. */
    String resources() {
        long now = SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder();
        long elapsed = Math.max(1, now - lastResourceMs);
        lastResourceMs = now;
        sb.append(cpuMem("app", Arrays.asList(android.os.Process.myPid()), elapsed)).append("; ");
        sb.append(cpuMem("Xvfb", ProcInfo.find("/x11/Xvfb"), elapsed)).append("; ");
        sb.append(cpuMem("Wine", ProcInfo.find("/wine/bin/wine", "wineserver", ".exe"), elapsed));
        return sb.toString();
    }

    private long lastResourceMs = SystemClock.uptimeMillis();

    private String cpuMem(String label, List<Integer> pids, long elapsedMs) {
        long ticks = 0, rss = 0;
        for (int pid : pids) {
            long t = ProcInfo.cpuTicks(pid);
            Long before = lastTicks.put(pid, t);
            if (t >= 0 && before != null && before >= 0) ticks += t - before;
            long r = ProcInfo.rssKiB(pid);
            if (r > 0) rss += r;
        }
        double cpu = ticks * 1000.0 / ProcInfo.CLOCK_TICKS / elapsedMs * 100.0;
        return String.format(Locale.ROOT, "%s CPU %.0f%% RSS %.0f MB (%d proc)", label, cpu, rss / 1024.0, pids.size());
    }

    void stop() {
        if (state == State.STOPPED) return;
        State before = state;
        set(State.STOPPING, "Stopping");
        if (wine != null) {
            log(run("wineserver -k", Arrays.asList(rt.box64().getPath(), new File(rt.wineBinDir(), "wineserver").getPath(), "-k"),
                    baseWineEnv(), 30));
            try {
                if (!wine.waitFor(5, TimeUnit.SECONDS)) wine.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log("test program: " + (wine.isAlive() ? "still running" : "exit " + wine.exitValue()) + "; log:\n" + tail(wineLog, 4000));
        }
        if (x11 != null) {
            boolean wasRunning = x11.isRunning();
            boolean ended = x11.stop();
            log("Xvfb stopped (" + (wasRunning ? "was running" : "had exited") + "; process "
                    + (ended ? "ended" : "STILL RUNNING") + "); stdout: "
                    + (x11.stdout != null ? x11.stdout.afterLineSummary() : "-") + "; log:\n" + x11.logTail(3000));
        }
        state = before == State.FAILED ? State.FAILED : State.STOPPED;
        status = state == State.FAILED ? "Stopped after failure" : "Stopped";
        listener.onState(state, status);
    }

    String report(FramebufferRenderer.Stats s, int pauses, int resumes, boolean recovered) {
        StringBuilder r = new StringBuilder("SHC port display test (BUILD 3 step 3.2)\n");
        r.append("Result: ").append(state).append(error != null ? " — " + error : "").append('\n');
        r.append("Xvfb: ").append(xvfbStatus).append('\n');
        r.append("Wine display connection: ").append(wineStatus).append('\n');
        r.append("xdpyinfo: ").append(xdpyinfoSummary).append('\n');
        r.append("Framebuffer: ").append(s.format).append('\n');
        r.append("Pixel check (app reading the real framebuffer): ").append(s.pixelOk ? "PASS — " : "NOT PASSED — ").append(s.pixelCheck).append('\n');
        r.append(String.format(Locale.ROOT, "Display: %d fps, uploads %d/s, last upload %d µs; Wine content %d fps (counter %d), dropped %d%n",
                s.displayFps, s.uploadsPerSecond, s.uploadMicros, s.contentFps, s.lastCounter, s.droppedFrames));
        r.append(String.format(Locale.ROOT, "View %dx%d; surface created %d times; paused %d, resumed %d; display after resume: %s%n",
                s.viewWidth, s.viewHeight, s.surfaceCreations, pauses, resumes, resumes == 0 ? "not tested" : recovered ? "updating" : "NOT updating"));
        r.append("Latency: not measured end to end; the app uploads a changed picture at most one display frame after Xvfb holds it.\n");
        r.append("Resources now: ").append(resources()).append(" (CPU in % of one core; ").append(ProcInfo.CPUS).append(" cores)\n");
        r.append("\nlogcat (this app: SELinux, seccomp, crashes):\n").append(logcat()).append('\n');
        r.append("\nTimeline:\n");
        for (String t : timeline) r.append(t).append('\n');
        r.append("\nLog:\n");
        synchronized (report) {
            r.append(report);
        }
        if (wineLog != null) r.append("\nWine log (end):\n").append(tail(wineLog, 3000)).append('\n');
        return r.toString();
    }

    void save(String text) {
        String name = "display-test-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        try (OutputStream o = new FileOutputStream(new File(paths.logsDir(), name))) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // shown on screen anyway
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** This app's logcat lines since the session started that concern SELinux (avc), seccomp or crashes. */
    private String logcat() {
        long since = System.currentTimeMillis() - (SystemClock.uptimeMillis() - startedAt);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-T",
                    String.format(Locale.ROOT, "%d.%03d", since / 1000, since % 1000)).redirectErrorStream(true).start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String l = line.toLowerCase(Locale.ROOT);
                    if (l.contains("avc:") || l.contains("seccomp") || l.contains("sigsys") || l.contains("fatal signal")
                            || l.contains("xvfb") || l.contains("tombstone")) {
                        if (n++ < 60) sb.append(line).append('\n');
                    }
                }
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "not readable: " + e.getMessage();
        }
        return n == 0 ? "(no matching lines)" : sb.toString().trim();
    }

    private Map<String, String> baseWineEnv() { return WineTestPlan.wineEnv(rt, paths.prefixDir(), paths.tmpDir()); }

    private File copyTestProgram() {
        File dir = new File(paths.internalRoot(), "display-test");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File exe = new File(dir, TEST_PROGRAM_ASSET);
        try (InputStream in = context.getAssets().open(TEST_PROGRAM_ASSET); OutputStream out = new FileOutputStream(exe)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } catch (Exception e) {
            fail("test program " + TEST_PROGRAM_ASSET + " is not in this APK (" + e.getMessage() + ")");
            return null;
        }
        log("test program: " + exe + " (" + exe.length() + " bytes)");
        return exe;
    }

    private String xdpyinfo() {
        String out = run("xdpyinfo", X11DisplayPlan.xdpyinfoArgv(rt, x11.display), new LinkedHashMap<>(), 60);
        StringBuilder sb = new StringBuilder();
        boolean ext = false, shm = false;
        List<String> exts = new ArrayList<>();
        for (String line : out.split("\n")) {
            String l = line.trim();
            if (l.startsWith("vendor string:") || l.startsWith("dimensions:")) sb.append(l).append("; ");
            if (l.startsWith("number of extensions:")) { ext = true; continue; }
            if (ext) {
                if (line.startsWith("    ")) { exts.add(l); if (l.equals("MIT-SHM")) shm = true; } else ext = false;
            }
        }
        sb.append(exts.size()).append(" extensions (XTEST ").append(exts.contains("XTEST") ? "yes" : "no")
                .append(", MIT-SHM ").append(shm ? "YES — unexpected" : "no").append(")");
        if (exts.isEmpty()) sb.append("; output: ").append(out.length() > 400 ? out.substring(0, 400) : out);
        return sb.toString();
    }

    /** Runs a command to completion (at most timeoutS) and returns "label: exit N" plus the end of its output. */
    private String run(String label, List<String> argv, Map<String, String> env, int timeoutS) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            pb.environment().remove("LD_LIBRARY_PATH");
            pb.environment().putAll(env);
            Process p = pb.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Thread t = new Thread(() -> {
                byte[] b = new byte[8192];
                int n;
                try (InputStream in = p.getInputStream()) {
                    while ((n = in.read(b)) > 0) synchronized (bos) { if (bos.size() < 256 * 1024) bos.write(b, 0, n); }
                } catch (Exception ignored) {
                    // ended
                }
            });
            t.start();
            boolean done = p.waitFor(timeoutS, TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            t.join(3000);
            String o;
            synchronized (bos) { o = new String(bos.toByteArray(), StandardCharsets.UTF_8); }
            String head = label + ": " + (done ? "exit " + p.exitValue() : "timed out after " + timeoutS + " s");
            return "xdpyinfo".equals(label) ? o : head + (o.isEmpty() ? "" : "\n" + tailString(o, 1500));
        } catch (Exception e) {
            return label + ": could not run (" + e.getMessage() + ")";
        }
    }

    private static String tailString(String s, int max) { return s.length() <= max ? s.trim() : "…" + s.substring(s.length() - max).trim(); }

    static String tail(File f, int max) {
        if (f == null) return "";
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            int n = (int) Math.min(len, max);
            byte[] b = new byte[n];
            raf.seek(len - n);
            raf.readFully(b);
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readText(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory() && !java.nio.file.Files.isSymbolicLink(k.toPath())) deleteTree(k);
            else //noinspection ResultOfMethodCallIgnored
                k.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** argv as one line, each element quoted, so the exact vector can be checked in the report. */
    static String argvForLog(java.util.List<String> argv) {
        if (argv == null) return "(not started)";
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < argv.size(); i++) b.append(i == 0 ? "" : ", ").append('"').append(argv.get(i)).append('"');
        return b.append("] (").append(argv.size()).append(" elements)").toString();
    }
}
