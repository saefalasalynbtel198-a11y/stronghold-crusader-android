package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BUILD 3 phase 1–2 on-device test: Wine prefix initialisation, wineserver, Windows process creation
 * (32-bit WoW64 and 64-bit PE smoke programs) and a launch attempt of the imported game executable.
 *
 * <p>Box64 is executed directly (its PT_INTERP points at the packaged glibc loader, see {@link ElfInterp}),
 * so the processes it re-executes for x86-64 children — wineserver and Wine helpers — also start on
 * the Android-patched glibc. The game step is diagnostic: graphics output is not available until phase 3.
 */
public final class WineTestPlan {

    public enum Check { NONE, PREFIX_CREATED, WINESERVER_DIR }

    public static final class Step {
        public final String name;
        public final List<String> argv;
        public final Map<String, String> env;
        public final File cwd;
        public final long timeoutMs;
        /** Expected exit code, or null if any exit code is acceptable. */
        public final Integer expectedExit;
        /** Text the output must contain, or null. */
        public final String expectedOutput;
        public final Check postCheck;
        public final boolean required;
        /** Started and left running (the app is its parent); the runner waits only until it is ready. */
        public boolean background;
        /** This step creates the prefix; if it fails the runner tries {@link #alternativeInit}. */
        public boolean prefixInit;
        /** Output goes to a log file instead of memory (verbose wineserver traces). */
        public String logFileName;

        Step(String name, List<String> argv, Map<String, String> env, File cwd, long timeoutMs,
             Integer expectedExit, String expectedOutput, Check postCheck, boolean required) {
            this.name = name;
            this.argv = Collections.unmodifiableList(argv);
            this.env = Collections.unmodifiableMap(env);
            this.cwd = cwd;
            this.timeoutMs = timeoutMs;
            this.expectedExit = expectedExit;
            this.expectedOutput = expectedOutput;
            this.postCheck = postCheck;
            this.required = required;
        }

        public boolean outputOk(int exit, String output) {
            if (exit < 0) return false; // timed out or could not start
            if (expectedExit != null && exit != expectedExit) return false;
            return expectedOutput == null || output.contains(expectedOutput);
        }
    }

    private WineTestPlan() {}

    /**
     * Main path. If the runtime ships the process watcher (r5+), Wine commands run under it so the report
     * shows how every process of the tree ends (exit code, fatal signal, seccomp syscall); the watcher only
     * observes and re-delivers every signal.
     *
     * @param gameExe executable name inside the imported game directory (D:), or null to skip the game step
     *                (no game imported, or the build classifier does not allow a launch attempt)
     */
    public static List<Step> steps(RuntimeLayout rt, File prefixDir, File tmpDir, File gameDir, String gameExe) {
        Map<String, String> env = wineEnv(rt, prefixDir, tmpDir);
        String box64 = rt.box64().getPath();
        String wine = rt.wine().getPath();
        String wineserver = new File(rt.wineBinDir(), "wineserver").getPath();
        File driveC = new File(prefixDir, "drive_c");
        Map<String, String> initEnv = new LinkedHashMap<>(env);
        initEnv.put("BOX64_LOG", "1");   // which libraries each Box64 process loads, process renames
        List<Step> s = new ArrayList<>();
        s.add(new Step("Box64 direct exec (PT_INTERP = patched glibc loader)", Arrays.asList(box64, "--version"),
                env, rt.root, 30_000, 0, "Box64", Check.NONE, true));
        Step init = new Step("wineboot --init (create Wine prefix; wine starts wineserver itself)",
                watched(rt, Arrays.asList(box64, wine, "wineboot", "--init")),
                initEnv, rt.root, 20 * 60_000, 0, null, Check.NONE, true);
        init.prefixInit = true;
        s.add(init);
        // wineboot returns while wineserver is still running; the registry (system.reg) is only written
        // when wineserver flushes it on exit, so wait for the server before checking the prefix.
        s.add(new Step("wineserver -w (wait for Wine to save the prefix)", Arrays.asList(box64, wineserver, "-w"),
                env, rt.root, 5 * 60_000, 0, null, Check.PREFIX_CREATED, true));
        s.add(new Step("wineserver directory inside the prefix", Arrays.asList(box64, wineserver, "-p5"),
                env, rt.root, 60_000, null, null, Check.WINESERVER_DIR, true));
        s.add(new Step("Windows PE smoke test, 64-bit", watched(rt, Arrays.asList(box64, wine, rt.peSmoke64().getPath(), "C:\\")),
                env, driveC, 10 * 60_000, 42, "pe-smoke ok", Check.NONE, true));
        s.add(new Step("Windows PE smoke test, 32-bit (WoW64, like the game)",
                watched(rt, Arrays.asList(box64, wine, rt.peSmoke32().getPath(), "C:\\")),
                env, driveC, 10 * 60_000, 42, "pe-smoke ok", Check.NONE, true));
        if (gameExe != null) {
            Map<String, String> gameEnv = new LinkedHashMap<>(env);
            gameEnv.put("WINEDEBUG", "err+all,fixme-all,+loaddll");
            s.add(new Step("Game executable launch attempt (diagnostic: no display until phase 3)",
                    Arrays.asList(box64, wine, "D:\\" + gameExe), gameEnv, gameDir, 90_000,
                    null, null, Check.NONE, false));
        }
        s.add(new Step("wineserver -k (stop Wine)", Arrays.asList(box64, wineserver, "-k"),
                env, rt.root, 60_000, null, null, Check.NONE, false));
        return s;
    }

    /**
     * Second way to create the prefix, used only when {@link Step#prefixInit} failed: the app starts
     * wineserver itself in the foreground (no daemon fork, the app is its parent and sees its exit status,
     * full -d1 request trace in a log file), then wineboot connects to that server.
     */
    public static List<Step> alternativeInit(RuntimeLayout rt, File prefixDir, File tmpDir) {
        Map<String, String> env = wineEnv(rt, prefixDir, tmpDir);
        env.put("BOX64_LOG", "1");
        List<Step> s = new ArrayList<>();
        s.add(appServer(rt, prefixDir, tmpDir, "wineserver started by the app in the foreground (-f -d1)",
                "wineserver-foreground.log", "-f", "-d1"));
        Step init = new Step("wineboot --init against the app-started wineserver",
                watched(rt, Arrays.asList(rt.box64().getPath(), rt.wine().getPath(), "wineboot", "--init")),
                env, rt.root, 20 * 60_000, 0, null, Check.NONE, true);
        init.prefixInit = true;
        s.add(init);
        return s;
    }

    /**
     * wineserver started by the app and kept as its child (foreground: no daemon fork). Ready when its
     * socket exists. Used after the prefix was created this way, in place of the self-daemonising
     * "wineserver -p5", with "-f -p" (persistent until "wineserver -k").
     */
    public static Step appServer(RuntimeLayout rt, File prefixDir, File tmpDir, String name, String logFile,
                                 String... args) {
        Map<String, String> env = wineEnv(rt, prefixDir, tmpDir);
        env.put("BOX64_LOG", "1");
        List<String> argv = new ArrayList<>(Arrays.asList(rt.box64().getPath(),
                new File(rt.wineBinDir(), "wineserver").getPath()));
        argv.addAll(Arrays.asList(args));
        Step server = new Step(name, argv, env, rt.root, 120_000, null, null, Check.WINESERVER_DIR, true);
        server.background = true;
        server.logFileName = logFile;
        return server;
    }

    /** Runs a command under the process watcher when the runtime has it (r5+), otherwise unchanged. */
    static List<String> watched(RuntimeLayout rt, List<String> argv) {
        if (!rt.ptraceWatch().isFile()) return argv;
        List<String> w = new ArrayList<>(Arrays.asList(rt.loader().getPath(), "--library-path",
                rt.nativeLibDir().getPath(), rt.ptraceWatch().getPath(), "--"));
        w.addAll(argv);
        return w;
    }

    /** Runtime environment for Wine; DISPLAY is deliberately empty until the display server exists. */
    public static Map<String, String> wineEnv(RuntimeLayout rt, File prefixDir, File tmpDir) {
        Map<String, String> env = RuntimeProbe.baseEnv(rt, prefixDir, tmpDir);
        env.put("WINEDLLOVERRIDES", "mscoree,mshtml=d;winemenubuilder.exe=d");
        env.put("WINEDEBUG", "err+all,fixme-all");
        env.put("DISPLAY", "");
        env.put("BOX64_NOBANNER", "1");
        env.put("BOX64_LOG", "0");
        return env;
    }
}
