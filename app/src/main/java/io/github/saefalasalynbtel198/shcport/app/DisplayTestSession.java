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
import io.github.saefalasalynbtel198.shcport.core.CncDdrawConfig;
import io.github.saefalasalynbtel198.shcport.core.DdrawPrecheck;
import io.github.saefalasalynbtel198.shcport.core.DisplayStartOrder;
import io.github.saefalasalynbtel198.shcport.core.DisplayTestPattern;
import io.github.saefalasalynbtel198.shcport.core.FrameProbe;
import io.github.saefalasalynbtel198.shcport.core.GameExitAnalysis;
import io.github.saefalasalynbtel198.shcport.core.GameFolderManifest;
import io.github.saefalasalynbtel198.shcport.core.PrivateGameCopy;
import io.github.saefalasalynbtel198.shcport.core.GameSetup;
import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;
import io.github.saefalasalynbtel198.shcport.core.RuntimeProbe;
import io.github.saefalasalynbtel198.shcport.core.WinePrefixSetup;
import io.github.saefalasalynbtel198.shcport.core.WineTestPlan;
import io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan;
import io.github.saefalasalynbtel198.shcport.core.X11DisplayPlan;
import io.github.saefalasalynbtel198.shcport.core.XwdFramebuffer;

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

    /** BUILD 3 step 3.3: the imported game instead of the display test program. */
    final boolean game;
    /** The normal game (Start Game), not the diagnostic launch test. */
    final boolean play;
    volatile String pictureStatus = "-";
    private GameInstallation.Executable gameExe;
    private GameFolderManifest manifestBefore;
    String gameFilesCheck = "not checked";
    private WineLaunchPlan plan;
    String setupReport = "";
    String precheckResult = "not run";
    private FrameProbe lastProbe;
    private int pictureChanges;
    private long lastProbeMs;
    private boolean ddrawNative;
    private String ddrawLoad;
    private long nextSnapshotS = 15;
    final List<String> snapshots = new ArrayList<>();
    private final java.util.concurrent.ExecutorService snapshotter = java.util.concurrent.Executors.newSingleThreadExecutor();

    DisplayTestSession(Context context, AppPaths paths, Listener listener) {
        this(context, paths, false, listener);
    }

    DisplayTestSession(Context context, AppPaths paths, boolean game, Listener listener) {
        this(context, paths, game, false, listener);
    }

    /**
     * @param play the normal game (Start Game): the same launch path as the game launch test, without its diagnostic
     *             extras (no relay trace, no snapshots; the DirectDraw pre-check only until it has passed once for
     *             this runtime, cnc-ddraw and app version)
     */
    DisplayTestSession(Context context, AppPaths paths, boolean game, boolean play, Listener listener) {
        this.game = game || play;
        this.play = play;
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

    private File exe;
    private File prefix;
    private String stamp;
    private File fbFile;
    /** Report of the Wine prefix stage (also kept when it succeeds). */
    String prefixReport = "";

    /**
     * Returns the framebuffer file to draw once Xvfb is ready, or null when the test could not start. Stages in
     * the order of {@link DisplayStartOrder}: runtime, Wine prefix, Xvfb, test program.
     */
    File start(Runnable onXvfbReady) throws InterruptedException {
        log("SHC port " + BuildConfig.VERSION_NAME + " display test; " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        log("runtime: " + readText(rt.versionFile()));
        prefix = paths.prefixDir();
        stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        DisplayStartOrder.Stage failed = DisplayStartOrder.run(new DisplayStartOrder.Stages() {
            @Override public boolean runtime() { return runtimeStage(); }
            @Override public boolean prefix() throws InterruptedException { return prefixStage(); }
            @Override public boolean xvfb() throws InterruptedException { return xvfbStage(onXvfbReady); }
            @Override public boolean testProgram() throws InterruptedException { return game ? gameStage() : testProgramStage(); }
        });
        if (failed != null) log("start stopped at stage " + failed + (failed == DisplayStartOrder.Stage.PREFIX
                ? " (Xvfb was not started)" : ""));
        // the screen is drawn once Xvfb runs, even if the test program could not be started
        return failed == null || failed == DisplayStartOrder.Stage.TEST_PROGRAM ? fbFile : null;
    }

    private boolean runtimeStage() {
        set(State.PREPARING, "Preparing");
        if (!rt.looksInstalled()) { fail("runtime not installed"); return false; }
        if (!rt.hasX11()) { fail("this runtime has no X server: install runtime r6 or newer"); return false; }
        if (game) {
            if (!gameCheck()) return false;
        } else {
            exe = copyTestProgram();
            if (exe == null) return false;
        }
        // An earlier Xvfb, and Wine processes started without a display (Wine's desktop process decides the display
        // driver for later programs, found in the CI feasibility test).
        List<Integer> oldX = ProcInfo.find("/x11/Xvfb");
        if (!oldX.isEmpty()) { log("stopping leftover Xvfb " + oldX); ProcInfo.kill(oldX); }
        if (!ProcInfo.find("wineserver", "/wine/bin/wine").isEmpty()) {
            log("stopping leftover Wine processes: " + run("wineserver -k", Arrays.asList(rt.box64().getPath(),
                    new File(rt.wineBinDir(), "wineserver").getPath(), "-k"), baseWineEnv(), 60));
        }
        return true;
    }

    /**
     * The Wine prefix, with the device-verified sequence of the Wine tests ({@link WineTestRunner#ensurePrefix}):
     * parent directory created and checked, incomplete prefix removed, wineboot --init and wineserver -w with exit 0,
     * system.reg and drive_c/windows present, Wine stopped again (Wine started without a display must not stay).
     */
    private boolean prefixStage() throws InterruptedException {
        boolean fresh = !WinePrefixSetup.isComplete(prefix);
        set(State.PREPARING, fresh ? "Creating the Wine prefix (first run, can take minutes)" : "Checking the Wine prefix");
        WineTestRunner.PrefixResult r = new WineTestRunner(paths).ensurePrefix(m -> listener.onState(State.PREPARING, m));
        prefixReport = r.report;
        log("Wine prefix:\n" + (r.ok ? r.summary : r.report));
        if (!r.ok) {
            fail("Wine prefix could not be created: " + r.reason);
            return false;
        }
        log("Wine prefix ready (" + (r.created ? "created now" : "existing prefix kept") + ")");
        return true;
    }

    private boolean xvfbStage(Runnable onXvfbReady) throws InterruptedException {
        // in a new framebuffer directory for this session (older ones removed)
        File fbRoot = new File(paths.internalRoot(), "x11-fb");
        deleteTree(fbRoot);
        File fbDir = new File(fbRoot, stamp);
        x11 = new X11Display(rt, fbDir, new File(paths.logsDir(), "display-xvfb-" + stamp + ".log"));
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
            return false;
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
        fbFile = x11.framebufferFile();
        log("framebuffer file: " + fbFile + " (" + fbFile.length() + " bytes)");
        xdpyinfoSummary = xdpyinfo();
        log("xdpyinfo: " + xdpyinfoSummary);
        onXvfbReady.run();
        return true;

    }

    private boolean testProgramStage() {
        // through Box64 + Wine, with the display
        set(State.STARTING_WINE, "Starting the test program through Box64 + Wine");
        wineLog = new File(paths.logsDir(), "display-wine-" + stamp + ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(X11DisplayPlan.testProgramArgv(rt, exe)).directory(new File(prefix, "drive_c"))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(wineLog));
            pb.environment().putAll(X11DisplayPlan.wineEnv(rt, prefix, paths.tmpDir(), x11.display));
            wine = pb.start();
        } catch (Exception e) {
            fail("could not start Wine: " + e.getMessage());
            return false;
        }
        wineStatus = "starting";
        return true;
    }

    // ------------------------------------------------------------------------------------------------ game (3.3)

    /** The imported game: executable and its classification, asset check, manifest of the folder (read only). */
    private boolean gameCheck() {
        if (!rt.guiSmoke().isFile()) { fail("this runtime has no DirectDraw test program (runtime r6 or newer needed)"); return false; }
        if (!rt.cncDdraw().isFile()) { fail("this runtime has no cnc-ddraw"); return false; }
        set(State.PREPARING, "Checking the imported game");
        File gameDir = paths.gameDir();
        GameInstallation inst = GameInstallation.inspect(gameDir);
        if (!inst.present) { fail("no game imported (import the game folder first)"); return false; }
        log("game folder: " + gameDir + "\n" + inst.describe());
        gameExe = inst.launchCandidate();
        if (gameExe == null) {
            fail("no executable may be started: the asset check failed or no build is allowed (see the game screen)");
            return false;
        }
        log("game executable: " + gameExe.name + " — " + gameExe.classification.support + " (" + gameExe.classification.label
                + "); SHA-256 " + gameExe.pe.sha256);
        try {
            manifestBefore = GameFolderManifest.snapshot(gameDir);
            log("game folder before the launch: " + manifestBefore.summary());
        } catch (Exception e) {
            fail("cannot read the game folder: " + e.getMessage());
            return false;
        }
        // Android's shared storage (where the import puts the game) does not allow executable mappings; Wine runs
        // the game from a verified private copy in internal storage instead (PrivateGameCopy).
        set(State.PREPARING, "Checking the private copy of the game");
        long free = new android.os.StatFs(paths.privateGameDir().getParentFile().getPath()).getAvailableBytes();
        PrivateGameCopy.Result cr = PrivateGameCopy.sync(gameDir, paths.privateGameDir(), free,
                m -> listener.onState(State.PREPARING, m));
        copyReport = cr.ok ? cr.summary() : "FAILED: " + cr.error;
        log("private game copy (" + paths.privateGameDir() + "): " + copyReport);
        if (!cr.ok) {
            fail("the private copy of the game could not be prepared: " + cr.error);
            return false;
        }
        return true;
    }

    String copyReport = "not made";
    String precheckDetails = "";
    /** Environment of the game process: the plan's, without +relay when the relay filter could not be set. */
    Map<String, String> gameEnv;
    /** Added to the game's environment in play mode (game sound: ALSA_CONFIG_PATH). Set before {@link #start}. */
    final Map<String, String> extraGameEnv = new java.util.LinkedHashMap<>();

    /**
     * Setup inside the prefix, the DirectDraw pre-check (the runtime's own DirectDraw program through the same
     * cnc-ddraw setup: must load cnc-ddraw from syswow64 and show its four colours), then the game.
     */
    private boolean gameStage() throws InterruptedException {
        plan = WineLaunchPlan.build(rt, prefix, paths.privateGameDir(), gameExe.name, paths.tmpDir(), x11.display,
                java.util.Collections.<String>emptyList());
        set(State.STARTING_WINE, "Preparing the game in the Wine prefix (the game folder is not changed)");
        GameSetup.Result sr = GameSetup.prepare(plan, new CncDdrawConfig());
        setupReport = sr.report;
        log("game setup:\n" + sr.report.trim());
        if (!sr.ok) { fail("game setup failed (see the log)"); return false; }
        if (play) {
            // normal game: Wine errors and DLL loading only (as the device-verified pre-check), no relay trace
            gameEnv = plan.envWith(WineLaunchPlan.WINEDEBUG_PLAY);
            if (!extraGameEnv.isEmpty()) {
                Map<String, String> e = new java.util.LinkedHashMap<>(gameEnv);
                e.putAll(extraGameEnv);
                gameEnv = java.util.Collections.unmodifiableMap(e);
                log("game environment additions: " + extraGameEnv);
            }
        } else {
            // Limit Wine's relay trace to the functions that show how the game ends (WineLaunchPlan.RELAY_INCLUDE).
            // reg.exe itself runs without +relay (the filter does not exist yet); only if it succeeds does the game get +relay.
            String rel = run("reg add RelayInclude", plan.relayArgv(), plan.envWith(WineLaunchPlan.WINEDEBUG_TOOL), 600);
            boolean relayFilter = rel.startsWith("reg add RelayInclude: exit 0");
            gameEnv = relayFilter ? plan.env : plan.envWith(WineLaunchPlan.WINEDEBUG_NO_RELAY);
            log("relay trace filter: " + rel + (relayFilter ? "" : "\n-> the game runs without +relay (WINEDEBUG=" + WineLaunchPlan.WINEDEBUG_NO_RELAY + ")"));
        }

        String key = precheckKey();
        if (play && key.equals(readText(precheckMarker()))) {
            precheckResult = "PASS earlier with this runtime, cnc-ddraw and app version (not repeated; the Game launch test always runs it)";
            log("DirectDraw pre-check: " + precheckResult);
        } else if (!precheck()) {
            return false;
        }

        set(State.STARTING_WINE, "Starting the game: " + gameExe.name);
        wineLog = new File(paths.logsDir(), "game-wine-" + stamp + ".log");
        log("game command (cwd " + plan.workingDirectory + " = D:\\): " + argvForLog(plan.argv) + "; DISPLAY=" + plan.env.get("DISPLAY")
                + ", WINEDLLOVERRIDES=" + plan.env.get("WINEDLLOVERRIDES") + ", CNC_DDRAW_CONFIG_FILE=" + plan.env.get("CNC_DDRAW_CONFIG_FILE")
                + ", WINEDEBUG=" + gameEnv.get("WINEDEBUG"));
        try {
            ProcessBuilder pb = new ProcessBuilder(plan.argv).directory(plan.workingDirectory).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(wineLog));
            pb.environment().putAll(gameEnv);
            wine = pb.start();
        } catch (Exception e) {
            fail("could not start the game: " + e.getMessage());
            return false;
        }
        wineStatus = "starting (Box64/Wine start-up, game loading)";
        gameStartMs = SystemClock.uptimeMillis();
        return true;
    }

    /** The DirectDraw pre-check (runtime's test program with the game's cnc-ddraw setup); false = failed (state set). */
    private boolean precheck() throws InterruptedException {
        set(State.STARTING_WINE, "DirectDraw pre-check: cnc-ddraw with the runtime's test program");
        File pcLog = new File(paths.logsDir(), "game-ddraw-check-" + stamp + ".log");
        List<String> pcArgv = plan.argvFor(rt.guiSmoke().getPath(), Arrays.asList("ddraw", "10"));
        log("pre-check command: " + argvForLog(pcArgv));
        Process pc;
        try {
            ProcessBuilder pb = new ProcessBuilder(pcArgv).directory(rt.root).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(pcLog));
            pb.environment().putAll(plan.envWith(WineLaunchPlan.WINEDEBUG_PRECHECK));   // as in 0.3.6 (device PASS)
            pc = pb.start();
        } catch (Exception e) {
            fail("could not start the DirectDraw pre-check: " + e.getMessage());
            return false;
        }
        long t0 = SystemClock.uptimeMillis();
        String pixels = "not seen";
        boolean seen = false;
        while (!pc.waitFor(1, TimeUnit.SECONDS)) {
            if (!seen) {
                String c = checkFrame(DisplayTestPattern.DDRAW_COLORS, DisplayTestPattern.DDRAW_NAMES);
                if (c != null && c.startsWith("OK")) { seen = true; pixels = c; }
            }
            if (SystemClock.uptimeMillis() - t0 > 15 * 60_000) { pc.destroyForcibly(); break; }
        }
        int code = pc.isAlive() ? -1 : pc.exitValue();
        DdrawPrecheck pcr = DdrawPrecheck.evaluate(pcLog, code, seen, pixels);   // reads the whole log
        boolean ok = pcr.ok;
        precheckResult = pcr.summary() + String.format(Locale.ROOT, " [%d s]", (SystemClock.uptimeMillis() - t0) / 1000);
        precheckDetails = String.join("\n", pcr.details());
        log("DirectDraw pre-check: " + precheckResult + "\n" + precheckDetails + "\npre-check log (last 40 lines):\n" + lastLines(tail(pcLog, 200_000), 40));
        if (!ok) { fail("DirectDraw pre-check " + precheckResult + " — the game was not started"); return false; }
        writeText(precheckMarker(), precheckKey());
        return true;
    }

    private File precheckMarker() { return new File(paths.internalRoot(), "ddraw-precheck-pass.txt"); }

    /** What a passed pre-check depends on: runtime version, cnc-ddraw and test program files, app version. */
    private String precheckKey() {
        return readText(rt.versionFile()) + "|" + rt.cncDdraw().length() + "/" + rt.cncDdraw().lastModified() + "|"
                + rt.guiSmoke().length() + "/" + rt.guiSmoke().lastModified() + "|" + BuildConfig.VERSION_CODE;
    }

    private static void writeText(File f, String text) {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // only an optimisation: the pre-check runs again next time
        }
    }

    private long gameStartMs;

    /** Xvfb's display number once it runs (-1 before). */
    int display() { return x11 == null ? -1 : x11.display; }

    /** The framebuffer file once Xvfb runs (null before). */
    File framebuffer() { return fbFile; }

    /** Maps the framebuffer read-only and runs a check on it; null if it cannot be read. */
    private <T> T withFrame(java.util.function.BiFunction<XwdFramebuffer, java.nio.ByteBuffer, T> f) {
        File fbf = fbFile;
        if (fbf == null) return null;
        try (RandomAccessFile raf = new RandomAccessFile(fbf, "r"); java.nio.channels.FileChannel ch = raf.getChannel()) {
            java.nio.MappedByteBuffer m = ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size());
            return f.apply(XwdFramebuffer.parse(m), m);
        } catch (Exception e) {
            return null;
        }
    }

    private String checkFrame(int[] colors, String[] names) {
        return withFrame((fb, m) -> {
            DisplayTestPattern.Check c = DisplayTestPattern.checkQuadrants(fb, m, colors, names);
            return (c.ok ? "OK " : "not OK ") + c.detail;
        });
    }

    /** Game mode part of {@link #poll}: picture, cnc-ddraw, snapshots. Runs on the UI thread: keep it cheap. */
    private void pollGame(long now) {
        if (now - lastProbeMs >= 2000) {
            lastProbeMs = now;
            FrameProbe p = withFrame(FrameProbe::sample);
            if (p != null) {
                if (lastProbe != null && p.differsFrom(lastProbe)) pictureChanges++;
                pictureStatus = p + "; " + (lastProbe == null ? "first sample" : p.differsFrom(lastProbe) ? "changing" : "still")
                        + " (" + pictureChanges + " changes seen)";
                lastProbe = p;
                if (p.nonBlack > 0.02 && state == State.STARTING_WINE) set(State.RUNNING, play ? "Running" : "Running: game picture on screen (no input yet)");
            }
            if (!ddrawNative) {
                String l = WineLaunchPlan.loadLine(tail(wineLog, 262144), "ddraw.dll", false);
                if (l != null) {
                    ddrawNative = l.toLowerCase(Locale.ROOT).endsWith(": native");
                    ddrawLoad = l;
                    log("game loaded ddraw.dll: " + l + (ddrawNative ? " (cnc-ddraw)" : " — NOT cnc-ddraw"));
                }
            }
            wineStatus = "running " + (now - gameStartMs) / 1000 + " s; ddraw.dll " + (ddrawLoad == null ? "not loaded yet"
                    : ddrawNative ? "= cnc-ddraw (native)" : "= Wine builtin (unexpected)");
        }
        long secs = (now - gameStartMs) / 1000;
        if (!play && secs >= nextSnapshotS && snapshots.size() < 12) {
            nextSnapshotS = nextSnapshotS < 120 ? nextSnapshotS * 2 : nextSnapshotS + 120;
            File png = new File(paths.logsDir(), String.format(Locale.ROOT, "game-%s-%04ds.png", stamp, secs));
            snapshotter.execute(() -> {
                Boolean ok = withFrame((fb, m) -> {
                    int[] argb = new int[fb.width * fb.height];
                    for (int y = 0; y < fb.height; y++) for (int x = 0; x < fb.width; x++) argb[y * fb.width + x] = 0xff000000 | fb.rgb(m, x, y);
                    android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(argb, fb.width, fb.height, android.graphics.Bitmap.Config.ARGB_8888);
                    try (OutputStream o = new FileOutputStream(png)) {
                        return b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
                    } catch (Exception e) {
                        return false;
                    } finally {
                        b.recycle();
                    }
                });
                if (Boolean.TRUE.equals(ok)) synchronized (snapshots) { snapshots.add(png.getPath()); }
            });
        }
    }

    /** Lines of the game's Wine log worth reading: errors and the DLLs that matter for this game. */
    private String gameLogDigest() {
        if (wineLog == null) return "(no game log)";
        String all = tail(wineLog, 2_000_000);
        StringBuilder dll = new StringBuilder(), err = new StringBuilder();
        java.util.Set<String> seenErr = new java.util.LinkedHashSet<>();
        int loaded = 0;
        for (String l : all.split("\n")) {
            String low = l.toLowerCase(Locale.ROOT);
            if (low.contains("loaddll")) {
                loaded++;
                if (low.contains("ddraw") || low.contains("binkw32") || low.contains("mss32") || low.contains("dsound")
                        || low.contains("winmm") || low.contains(".exe")) dll.append(l.trim()).append('\n');
            } else if (low.contains("err:") && seenErr.size() < 40) {
                seenErr.add(l.replaceAll("^[0-9a-f]+:", "").trim());
            }
        }
        for (String e : seenErr) err.append(e).append('\n');
        return "DLLs loaded: " + loaded + " lines; relevant:\n" + (dll.length() == 0 ? "(none)\n" : dll)
                + "Distinct err: lines (first 40):\n" + (err.length() == 0 ? "(none)\n" : err);
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
        if (game && wine != null && state != State.STOPPING && state != State.STOPPED) {
            if (!wine.isAlive() && state != State.FAILED) {
                int c = wine.exitValue();
                wineStatus = "EXITED after " + (now - gameStartMs) / 1000 + " s, code " + c + " (" + RuntimeProbe.describeExit(c) + ")";
                log("game Wine log (end):\n" + tail(wineLog, 6000));
                fail("game " + wineStatus);
            } else if (wine.isAlive()) {
                pollGame(now);
            }
        } else if (wine != null && state != State.STOPPING && state != State.STOPPED) {
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
        if (game && now - lastSampleMs >= 5000) {
            lastSampleMs = now;
            timeline.add(String.format(Locale.ROOT, "t=%4.0fs display %2d fps, picture changes %2d/s, picture %s, %s",
                    (now - startedAt) / 1000.0, s.displayFps, s.uploadsPerSecond, pictureStatus, resources()));
        } else if (now - lastSampleMs >= 5000) {
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
            log((game ? "game: " : "test program: ") + (wine.isAlive() ? "still running" : "exit " + wine.exitValue()) + "; log:\n" + tail(wineLog, 4000));
        }
        if (x11 != null) {
            boolean wasRunning = x11.isRunning();
            boolean ended = x11.stop();
            log("Xvfb stopped (" + (wasRunning ? "was running" : "had exited") + "; process "
                    + (ended ? "ended" : "STILL RUNNING") + "); stdout: "
                    + (x11.stdout != null ? x11.stdout.afterLineSummary() : "-") + "; log:\n" + x11.logTail(3000));
        }
        if (game && manifestBefore != null) {
            try {
                GameFolderManifest.Diff d = manifestBefore.compareTo(GameFolderManifest.snapshot(paths.gameDir()));
                gameFilesCheck = d.unchanged() ? "PASS — unchanged (" + manifestBefore.summary() + ")" : "FAILED — " + d;
            } catch (Exception e) {
                gameFilesCheck = "not possible: " + e.getMessage();
            }
            log("game folder after the launch: " + gameFilesCheck);
            if (!gameFilesCheck.startsWith("PASS") && before != State.FAILED) {
                error = "game folder " + gameFilesCheck;
                before = State.FAILED;
            }
        }
        snapshotter.shutdown();
        state = before == State.FAILED ? State.FAILED : State.STOPPED;
        status = state == State.FAILED ? "Stopped after failure" : "Stopped";
        listener.onState(state, status);
    }

    String report(FramebufferRenderer.Stats s, int pauses, int resumes, boolean recovered) {
        if (game) return gameReport(s, pauses, resumes, recovered);
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

    private String gameReport(FramebufferRenderer.Stats s, int pauses, int resumes, boolean recovered) {
        StringBuilder r = new StringBuilder(play ? "SHC Port " + BuildConfig.VERSION_NAME + " game session (Start Game)\n"
                : "SHC port game launch test — diagnostic\n");
        r.append("Result: ").append(state).append(error != null ? " — " + error : "").append('\n');
        r.append("Game executable: ").append(gameExe == null ? "-" : gameExe.name + " — " + gameExe.classification.support
                + " (" + gameExe.classification.label + ")").append('\n');
        r.append("Game files check (before/after): ").append(gameFilesCheck).append('\n');
        r.append("Xvfb: ").append(xvfbStatus).append('\n');
        r.append("DirectDraw pre-check: ").append(precheckResult).append('\n');
        if (!precheckDetails.isEmpty()) r.append(precheckDetails.replaceAll("(?m)^", "  ")).append('\n');
        r.append("Game: ").append(wineStatus).append('\n');
        r.append("Picture: ").append(pictureStatus).append('\n');
        r.append(String.format(Locale.ROOT, "Display: %d fps, picture changes %d/s, last upload %d µs; view %dx%d; paused %d, resumed %d; display after resume: %s%n",
                s.displayFps, s.uploadsPerSecond, s.uploadMicros, s.viewWidth, s.viewHeight, pauses, resumes,
                resumes == 0 ? "not tested" : recovered ? "updating" : "NOT updating"));
        r.append("Resources now: ").append(resources()).append(" (CPU in % of one core; ").append(ProcInfo.CPUS).append(" cores)\n");
        synchronized (snapshots) {
            r.append("Snapshots of the X screen: ").append(snapshots.isEmpty() ? "none" : String.join(", ", snapshots)).append('\n');
        }
        r.append("Setup inside the Wine prefix:\n").append(setupReport);
        r.append("Private copy of the game (internal storage, launched from there): ").append(copyReport).append('\n');
        String gameLog = wineLog == null ? "" : tail(wineLog, 4_000_000);
        r.append("\nWhy the game ended / what it did:\n");
        for (String l : GameExitAnalysis.parse(gameLog).lines()) r.append("  ").append(l).append('\n');
        r.append("\nGame Wine log (last 60 lines):\n").append(lastLines(gameLog, 60)).append('\n');
        r.append("\nGame log digest:\n").append(gameLogDigest());
        r.append("\nlogcat (this app: denials, seccomp, crashes):\n").append(logcat()).append('\n');
        r.append("\nTimeline:\n");
        for (String t : timeline) r.append(t).append('\n');
        r.append("\nLog:\n");
        synchronized (report) {
            r.append(report);
        }
        return r.toString();
    }

    private static String lastLines(String text, int n) {
        String[] l = text.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, l.length - n); i < l.length; i++) b.append(l[i]).append('\n');
        return b.length() == 0 ? "(empty)\n" : b.toString();
    }

    void save(String text) {
        String name = (game ? "game-launch-test-" : "display-test-") + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
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
                    if (l.contains("granted")) continue;   // "avc: granted { execute }" lines are normal noise
                    if (l.contains("avc:") || l.contains("seccomp") || l.contains("sigsys") || l.contains("fatal signal")
                            || l.contains("xvfb") || l.contains("tombstone")) {
                        if (n++ < 30) sb.append(line).append('\n');
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
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true).directory(rt.root);
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
