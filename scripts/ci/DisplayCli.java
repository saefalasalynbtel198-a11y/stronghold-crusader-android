import io.github.saefalasalynbtel198.shcport.core.DisplayTestPattern;
import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;
import io.github.saefalasalynbtel198.shcport.core.X11DisplayPlan;
import io.github.saefalasalynbtel198.shcport.core.XwdFramebuffer;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.saefalasalynbtel198.shcport.core.XvfbStarter;

/**
 * CI helper: exposes the app's own display code (core/X11DisplayPlan, XwdFramebuffer, DisplayTestPattern) so the
 * qemu test starts Xvfb and Wine exactly as the app does and reads the framebuffer exactly as the app does.
 *   xvfb-argv <runtime> <display> <fbdir> | classify <xvfb log> | xvfb-cwd <runtime> | socket <display> |
 *   xdpyinfo-argv <runtime> <display>
 *   start <runtime> <fbbase> <logdir> <first> <last> [--no-probe] [-- prefix...]: the app's XvfbStarter with real
 *     processes (prefix: e.g. the hard-link filter); leaves Xvfb running, prints "display N" and "pid P"
 *   cycle <runtime> <fbbase> <logdir> <rounds> [-- prefix...]: start on :37, check stdout, stop, check cleanup
 *   game-launch <runtime> <prefix> <gamedir> <exe> <tmp> <display> <fbfile> <logdir>: step 3.3 with a stand-in game
 *     (the runtime's DirectDraw program under the game's name): the app's WineLaunchPlan + GameSetup + manifest,
 *     the DirectDraw pre-check as the app runs it, then the "game" from D:
 *   prefix-init <runtime> <prefix> <tmp> [--no-parent]: the display test's prefix stage (WinePrefixSetup +
 *     WineTestPlan.prefixInitSteps, run with ProcessBuilder like the app); --no-parent = as 0.3.2-0.3.4 (no mkdirs)
 *   wine-env <runtime> <prefix> <tmp> <display> | wine-argv <runtime> <exe> | check <framebuffer file>
 */
public final class DisplayCli {
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "xvfb-argv":
                X11DisplayPlan.xvfbArgv(new RuntimeLayout(new File(a[1])), Integer.parseInt(a[2]), new File(a[3])).forEach(System.out::println);
                break;
            case "start":          // start <runtime> <fbbase> <logdir> <first> <last> [--no-probe] [-- prefix...]
                System.exit(start(a));
                break;
            case "prefix-init":    // prefix-init <runtime> <prefix> <tmp> [--no-parent]
                System.exit(prefixInit(a));
                break;
            case "game-launch":    // game-launch <runtime> <prefix> <gamedir> <exe> <tmp> <display> <fbfile> <logdir>
                System.exit(gameLaunch(a));
                break;
            case "cycle":          // cycle <runtime> <fbbase> <logdir> <rounds> [-- prefix...]
                System.exit(cycle(a));
                break;
            case "classify": {     // classify <xvfb log file>
                System.out.println(X11DisplayPlan.classify(new String(java.nio.file.Files.readAllBytes(new File(a[1]).toPath()),
                        java.nio.charset.StandardCharsets.UTF_8)));
                break;
            }
            case "xvfb-cwd":
                System.out.println(X11DisplayPlan.xvfbWorkingDir(new RuntimeLayout(new File(a[1]))));
                break;
            case "socket":
                System.out.println(X11DisplayPlan.abstractSocketName(Integer.parseInt(a[1])));
                break;
            case "xdpyinfo-argv":
                X11DisplayPlan.xdpyinfoArgv(new RuntimeLayout(new File(a[1])), Integer.parseInt(a[2])).forEach(System.out::println);
                break;
            case "wine-env":
                for (Map.Entry<String, String> e : X11DisplayPlan.wineEnv(new RuntimeLayout(new File(a[1])), new File(a[2]),
                        new File(a[3]), Integer.parseInt(a[4])).entrySet()) {
                    System.out.println(e.getKey() + "=" + e.getValue());
                }
                break;
            case "wine-argv":
                X11DisplayPlan.testProgramArgv(new RuntimeLayout(new File(a[1])), new File(a[2])).forEach(System.out::println);
                break;
            case "check": {
                try (RandomAccessFile raf = new RandomAccessFile(a[1], "r"); FileChannel ch = raf.getChannel()) {
                    MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());   // as the app's renderer
                    XwdFramebuffer fb = XwdFramebuffer.parse(m);
                    DisplayTestPattern.Check c = DisplayTestPattern.checkQuadrants(fb, m);
                    System.out.println("format " + fb + (fb.isBgrx() ? " (BGRX: app renderer path)" : " (NOT BGRX)"));
                    System.out.println("pixels " + (c.ok ? "OK" : "FAIL") + ": " + c.detail);
                    System.out.println("counter " + DisplayTestPattern.decodeCounter(fb, m));
                    System.out.println("band " + Long.toHexString(DisplayTestPattern.bandSignature(fb, m)));
                    System.exit(c.ok && fb.isBgrx() ? 0 : 1);
                }
                break;
            }
            default:
                throw new IllegalArgumentException(a[0]);
        }
    }

    /** XvfbStarter's environment in CI: ProcessBuilder as in the app (plus an optional prefix), /proc/net/unix probe. */
    static final class CliHost implements XvfbStarter.Host {
        final RuntimeLayout rt;
        final File fbBase, logDir;
        final List<String> prefix;
        final boolean noProbe;
        final List<Integer> launched = new ArrayList<>();
        final Map<Integer, File> logs = new java.util.HashMap<>();
        List<String> lastArgv;
        int n;

        CliHost(RuntimeLayout rt, File fbBase, File logDir, List<String> prefix, boolean noProbe) {
            this.rt = rt; this.fbBase = fbBase; this.logDir = logDir; this.prefix = prefix; this.noProbe = noProbe;
        }

        @Override public boolean displayAnswers(int d) {
            if (noProbe && !launched.contains(d)) return false;   // let Xvfb itself meet the busy display
            return listening(d);
        }

        @Override public Process launch(int d) throws java.io.IOException {
            File fb = new File(fbBase, String.valueOf(d));
            fb.mkdirs();
            lastArgv = X11DisplayPlan.xvfbArgv(rt, d, fb);
            List<String> cmd = new ArrayList<>(prefix);
            cmd.addAll(lastArgv);
            File log = new File(logDir, "xvfb-" + d + "-" + (++n) + ".log");
            logs.put(d, log);
            launched.add(d);
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(X11DisplayPlan.xvfbWorkingDir(rt))
                    .redirectOutput(ProcessBuilder.Redirect.PIPE).redirectError(ProcessBuilder.Redirect.to(log));
            pb.environment().remove("LD_LIBRARY_PATH");
            System.out.println("argv: " + quoted(lastArgv) + (prefix.isEmpty() ? "" : " (CI prefix " + quoted(prefix) + ")"));
            return pb.start();
        }

        @Override public String errorLog(int d) {
            try {
                return new String(java.nio.file.Files.readAllBytes(logs.get(d).toPath()), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }

        @Override public void log(String line) { System.out.println(line); }
    }

    /** A listening abstract socket for the display exists (Flags 00010000 = accepting connections). */
    static boolean listening(int d) {
        String name = "@" + X11DisplayPlan.abstractSocketName(d);
        try {
            for (String l : java.nio.file.Files.readAllLines(new File("/proc/net/unix").toPath())) {
                String[] f = l.trim().split("\\s+");
                if (f.length >= 8 && f[7].equals(name) && f[3].equals("00010000")) return true;
            }
        } catch (java.io.IOException ignored) { }
        return false;
    }

    static String quoted(List<String> a) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < a.size(); i++) b.append(i == 0 ? "" : ", ").append('"').append(a.get(i)).append('"');
        return b.append(']').toString();
    }

    private static List<String> prefixOf(String[] a) {
        int i = Arrays.asList(a).indexOf("--");
        return i < 0 ? new ArrayList<>() : new ArrayList<>(Arrays.asList(a).subList(i + 1, a.length));
    }

    private static int start(String[] a) throws Exception {
        boolean noProbe = Arrays.asList(a).contains("--no-probe");
        CliHost h = new CliHost(new RuntimeLayout(new File(a[1])), new File(a[2]), new File(a[3]), prefixOf(a), noProbe);
        XvfbStarter.Result r = XvfbStarter.start(h, Integer.parseInt(a[4]), Integer.parseInt(a[5]), 300_000);
        for (String s : r.attempts) System.out.println("attempt " + s);
        if (!r.ok()) {
            System.out.println("error " + r.error);
            return 2;
        }
        System.out.println("display " + r.display);
        System.out.println("pid " + r.process.pid());
        System.out.println("ready-ms " + r.readyMs);
        r.stdout.awaitClosed(3000);
        System.out.println("stdout " + r.stdout.afterLineSummary());
        return 0;   // Xvfb keeps running after this JVM exits
    }

    private static int cycle(String[] a) throws Exception {
        CliHost h = new CliHost(new RuntimeLayout(new File(a[1])), new File(a[2]), new File(a[3]), prefixOf(a), false);
        int rounds = Integer.parseInt(a[4]), bad = 0;
        for (int i = 1; i <= rounds; i++) {
            XvfbStarter.Result r = XvfbStarter.start(h, X11DisplayPlan.FIRST_DISPLAY, X11DisplayPlan.LAST_DISPLAY, 300_000);
            if (!r.ok()) {
                System.out.println("round " + i + " FAIL: " + r.error + " " + r.attempts);
                bad++;
                continue;
            }
            boolean closed = r.stdout.awaitClosed(5000);
            boolean clean = r.stdout.extraBytes() == 0;
            boolean ended = XvfbStarter.stop(r.process);
            Thread.sleep(500);
            boolean gone = !listening(r.display);
            boolean ok = r.display == X11DisplayPlan.FIRST_DISPLAY && closed && clean && ended && gone;
            System.out.println("round " + i + (ok ? " PASS" : " FAIL") + ": display " + r.display + " ready after "
                    + r.readyMs + " ms; stdout " + r.stdout.afterLineSummary() + "; stopped: " + (ended ? "process ended" : "STILL RUNNING")
                    + " (exit " + (ended ? r.process.exitValue() : "-") + "); socket " + (gone ? "gone" : "STILL LISTENING"));
            if (!ok) bad++;
        }
        return bad == 0 ? 0 : 1;
    }

    /**
     * The app's prefix stage with real processes: parent directory (WinePrefixSetup), then the Wine tests' prefix
     * steps (argv, environment, working directory as in the app) with their exit-code and prefix checks, then
     * wineserver -k. CI difference: qemu-user has no ptrace, so the process watcher in front of wineboot is left out.
     */
    private static int prefixInit(String[] a) throws Exception {
        RuntimeLayout rt = new RuntimeLayout(new File(a[1]));
        File prefix = new File(a[2]), tmp = new File(a[3]);
        boolean noParent = Arrays.asList(a).contains("--no-parent");
        tmp.mkdirs();
        if (!noParent) {
            io.github.saefalasalynbtel198.shcport.core.WinePrefixSetup.ParentCheck pc =
                    io.github.saefalasalynbtel198.shcport.core.WinePrefixSetup.ensureParent(prefix);
            System.out.print(pc.report);
            if (!pc.ok) { System.out.println("result FAIL: prefix directory"); return 1; }
        } else {
            System.out.println("prefix " + prefix + ": " + io.github.saefalasalynbtel198.shcport.core.WinePrefixSetup.state(prefix)
                    + "; parent " + prefix.getParentFile() + ": " + io.github.saefalasalynbtel198.shcport.core.WinePrefixSetup.state(prefix.getParentFile())
                    + " (not created: behaviour of 0.3.2-0.3.4)");
        }
        if (io.github.saefalasalynbtel198.shcport.core.WinePrefixSetup.isComplete(prefix)) {
            System.out.println("result KEPT: complete prefix (system.reg, drive_c/windows), wineboot not run");
            return 0;
        }
        boolean ok = true;
        for (io.github.saefalasalynbtel198.shcport.core.WineTestPlan.Step s
                : io.github.saefalasalynbtel198.shcport.core.WineTestPlan.prefixInitSteps(rt, prefix, tmp)) {
            int exit = runStep(s);
            boolean exitOk = s.expectedExit == null || exit == s.expectedExit;
            boolean post = s.postCheck != io.github.saefalasalynbtel198.shcport.core.WineTestPlan.Check.PREFIX_CREATED
                    || io.github.saefalasalynbtel198.shcport.core.WinePrefixSetup.isComplete(prefix);
            System.out.println("step \"" + s.name + "\": exit " + exit + (exitOk ? "" : " (expected " + s.expectedExit + ")")
                    + (post ? "" : "; prefix check FAILED") + " -> " + (exitOk && post ? "PASS" : "FAIL"));
            if (!(exitOk && post)) { ok = false; break; }
        }
        runStep(io.github.saefalasalynbtel198.shcport.core.WineTestPlan.stopStep(rt, prefix, tmp));
        boolean complete = io.github.saefalasalynbtel198.shcport.core.WinePrefixSetup.isComplete(prefix);
        System.out.println("prefix check: system.reg " + (new File(prefix, "system.reg").isFile() ? "present" : "MISSING")
                + ", drive_c/windows " + (new File(prefix, "drive_c/windows").isDirectory() ? "present" : "MISSING"));
        System.out.println("result " + (ok && complete ? "CREATED" : "FAIL"));
        return ok && complete ? 0 : 1;
    }

    private static int runStep(io.github.saefalasalynbtel198.shcport.core.WineTestPlan.Step s) throws Exception {
        List<String> argv = new ArrayList<>(s.argv);
        int dd = argv.indexOf("--");
        if (dd > 0 && argv.get(dd - 1).endsWith("shc-ptrace-watch")) argv = new ArrayList<>(argv.subList(dd + 1, argv.size()));
        System.out.println("$ (cwd " + s.cwd + ") " + String.join(" ", argv));
        ProcessBuilder pb = new ProcessBuilder(argv).directory(s.cwd).redirectErrorStream(true);
        pb.environment().remove("LD_LIBRARY_PATH");
        pb.environment().putAll(s.env);
        Process p = pb.start();
        p.getOutputStream().close();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Thread t = new Thread(() -> {
            try (java.io.InputStream in = p.getInputStream()) { in.transferTo(out); } catch (java.io.IOException ignored) { }
        });
        t.start();
        boolean done = p.waitFor(s.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!done) p.destroyForcibly();
        t.join(5000);
        String o = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = o.split("\n");
        int from = Math.max(0, lines.length - 25);
        for (int i = from; i < lines.length; i++) if (!lines[i].isEmpty()) System.out.println("  | " + lines[i]);
        return done ? p.exitValue() : -1;
    }

    private static int gameLaunch(String[] a) throws Exception {
        RuntimeLayout rt = new RuntimeLayout(new File(a[1]));
        File prefix = new File(a[2]), game = new File(a[3]), tmp = new File(a[5]), fb = new File(a[7]), logs = new File(a[8]);
        String exe = a[4];
        int display = Integer.parseInt(a[6]);
        logs.mkdirs();
        int bad = 0;
        io.github.saefalasalynbtel198.shcport.core.GameFolderManifest before =
                io.github.saefalasalynbtel198.shcport.core.GameFolderManifest.snapshot(game);
        System.out.println("game folder before: " + before.summary());
        io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan plan = io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan
                .build(rt, prefix, game, exe, tmp, display, Arrays.asList("ddraw", "10"));
        io.github.saefalasalynbtel198.shcport.core.GameSetup.Result sr = io.github.saefalasalynbtel198.shcport.core.GameSetup
                .prepare(plan, new io.github.saefalasalynbtel198.shcport.core.CncDdrawConfig());
        System.out.print(sr.report);
        System.out.println("check setup: " + (sr.ok ? "PASS" : "FAIL"));
        if (!sr.ok) return 1;
        // 1. the app's DirectDraw pre-check: runtime program by Unix path, cwd = runtime
        bad += ddrawRun("precheck", plan.argvFor(rt.guiSmoke().getPath(), Arrays.asList("ddraw", "10")), rt.root, plan.env,
                fb, new File(logs, "precheck.log"));
        // 2. the "game": D:\<exe>, cwd = game folder
        System.out.println("game argv: " + quoted(plan.argv) + " cwd " + plan.workingDirectory);
        bad += ddrawRun("game", plan.argv, plan.workingDirectory, plan.env, fb, new File(logs, "game.log"));
        String gameLog = new String(java.nio.file.Files.readAllBytes(new File(logs, "game.log").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        boolean cwdD = false;
        String ddrawLine = io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan.loadLine(gameLog, "ddraw.dll", false);
        System.out.println("game ddraw.dll load: " + ddrawLine);
        boolean nativeDdraw = ddrawLine != null && ddrawLine.toLowerCase(java.util.Locale.ROOT).endsWith(": native");
        for (String l : gameLog.split("\n")) {
            String low = l.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("loaddll") && low.contains("d:") && low.contains(exe.toLowerCase(java.util.Locale.ROOT))) cwdD = true;
        }
        System.out.println("check +loaddll shows ddraw.dll loaded native (cnc-ddraw from the prefix, not Wine's builtin): " + (nativeDdraw ? "PASS" : "FAIL"));
        if (!nativeDdraw) bad++;
        System.out.println("info: game module loaded from D: " + cwdD);
        boolean iniHere = new File(game, "ddraw.ini").exists() || new File(plan.ddrawTarget.getParentFile(), "ddraw.ini").exists();
        System.out.println("check no ddraw.ini next to the game or in syswow64 (CNC_DDRAW_CONFIG_FILE honoured): " + (iniHere ? "FAIL" : "PASS"));
        if (iniHere) bad++;
        io.github.saefalasalynbtel198.shcport.core.GameFolderManifest.Diff d = before.compareTo(
                io.github.saefalasalynbtel198.shcport.core.GameFolderManifest.snapshot(game));
        System.out.println("check game folder unchanged: " + (d.unchanged() ? "PASS" : "FAIL " + d));
        if (!d.unchanged()) bad++;
        return bad == 0 ? 0 : 1;
    }

    /** Runs a DirectDraw program (shc-gui-smoke ddraw) to its end; PASS = exit 42, ddraw.dll loaded native (cnc-ddraw), colours seen. */
    private static int ddrawRun(String label, List<String> argv, File cwd, Map<String, String> env, File fb, File log) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true).redirectOutput(log);
        pb.environment().remove("LD_LIBRARY_PATH");
        pb.environment().putAll(env);
        long t0 = System.currentTimeMillis();
        Process p = pb.start();
        p.getOutputStream().close();
        String pixels = null;
        while (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            if (pixels == null && fb.isFile()) {
                try (RandomAccessFile raf = new RandomAccessFile(fb, "r"); FileChannel ch = raf.getChannel()) {
                    MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
                    XwdFramebuffer x = XwdFramebuffer.parse(m);
                    DisplayTestPattern.Check c = DisplayTestPattern.checkQuadrants(x, m, DisplayTestPattern.DDRAW_COLORS, DisplayTestPattern.DDRAW_NAMES);
                    if (c.ok) pixels = c.detail;
                } catch (Exception ignored) { }
            }
            if (System.currentTimeMillis() - t0 > 40 * 60_000L) { p.destroyForcibly(); break; }
        }
        int code = p.isAlive() ? -1 : p.exitValue();
        String out = new String(java.nio.file.Files.readAllBytes(log.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        String loaded = "";
        for (String l : out.split("\n")) if (l.contains("gui-smoke: ddraw.dll = ")) loaded = l.substring(l.indexOf('=') + 1).trim();
        String nativeLine = io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan.loadLine(out, "ddraw.dll", true);
        System.out.println(label + " ddraw.dll load: " + io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan.loadLine(out, "ddraw.dll", false));
        boolean ok = code == 42 && pixels != null && nativeLine != null;
        System.out.println("check " + label + ": " + (ok ? "PASS" : "FAIL") + " (exit " + code + ", "
                + (System.currentTimeMillis() - t0) / 1000 + " s, ddraw.dll = " + loaded + ", pixels " + (pixels == null ? "not seen" : "OK: " + pixels) + ")");
        if (!ok) {
            String[] lines = out.split("\n");
            for (int i = Math.max(0, lines.length - 30); i < lines.length; i++) System.out.println("  | " + lines[i]);
        }
        return ok ? 0 : 1;
    }
}
