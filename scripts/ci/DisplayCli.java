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
 *       (synced into <gamedir>-private with SHA-256 verification and launched from there, as the app does)
 *     (the runtime's DirectDraw program under the game's name): the app's WineLaunchPlan + GameSetup + manifest,
 *     the DirectDraw pre-check as the app runs it, then the "game" from D:
 *   prefix-init <runtime> <prefix> <tmp> [--no-parent]: the display test's prefix stage (WinePrefixSetup +
 *     WineTestPlan.prefixInitSteps, run with ProcessBuilder like the app); --no-parent = as 0.3.2-0.3.4 (no mkdirs)
 *   wine-env <runtime> <prefix> <tmp> <display> | wine-argv <runtime> <exe> | check <framebuffer file>
 *   input <display> <bridge.py>: the app's X11 input client (XTEST) against the running Xvfb
 *   audio-bridge <socket> <outdir> <seconds> [pause-at pause-for]: the app's audio receiver, recording to WAV
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
            case "input":          // input <display> <bridge.py>: XTEST through the app's X11Input against Xvfb
                System.exit(input(Integer.parseInt(a[1]), a[2]));
                break;
            case "audio-bridge":   // audio-bridge <socket path> <out dir> <max seconds> [pause-at-s pause-for-s]; stops on <out>/stop
                System.exit(audioBridge(a));
                break;
            case "game-launch":    // game-launch <runtime> <prefix> <gamedir> <exe> <tmp> <display> <fbfile> <logdir>
                System.exit(gameLaunch(a));
                break;
            case "resolution":     // resolution <runtime> <prefix> <tmp> <work> <bridge.py> <ddraw-input-test.exe> <display> <screen WxH> <game WxH> <precheck|no-precheck> [preserve|stretch|fill]
                System.exit(resolution(a));
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
        /** X screen size (Display Resolution); 1024×768 as before unless set. */
        int width = X11DisplayPlan.WIDTH, height = X11DisplayPlan.HEIGHT;

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
            lastArgv = width == X11DisplayPlan.WIDTH && height == X11DisplayPlan.HEIGHT
                    ? X11DisplayPlan.xvfbArgv(rt, d, fb) : X11DisplayPlan.xvfbArgv(rt, d, fb, width, height);
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

    /**
     * The app's input path against the real Xvfb: every gesture goes through the app's TouchMouseMapper (with the
     * owner's 1786x1228 letterboxed view) and X11Input, with real time between the touch events, as the game screen
     * sends them. Checks what the X server reports (QueryPointer/QueryKeymap); what the Windows test program received
     * (messages, their times, polled button state) is checked by the script.
     */
    private static int input(int display, String bridge) throws Exception {
        Process p = new ProcessBuilder("python3", "-I", bridge, String.valueOf(display)).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        int bad = 0;
        try (io.github.saefalasalynbtel198.shcport.core.X11Input x = io.github.saefalasalynbtel198.shcport.core.X11Input
                .connect(p.getInputStream(), p.getOutputStream())) {
            System.out.println("x11 input: root 0x" + Integer.toHexString(x.root) + ", screen " + x.screenWidth + "x" + x.screenHeight
                    + ", XTEST opcode " + x.xtestOpcode + ", keycodes " + x.minKeycode + ".." + x.maxKeycode
                    + ", Escape=" + x.keycodeOf(0xff1b) + " Return=" + x.keycodeOf(0xff0d) + " a=" + x.keycodeOf('a'));
            boolean keymap = x.keycodeOf(0xff1b) != 0 && x.keycodeOf(0xff0d) != 0 && x.keycodeOf('a') != 0 && x.keycodeOf(0xff51) != 0;
            System.out.println("check keymap has Escape, Return, letters, arrows: " + (keymap ? "PASS" : "FAIL"));
            if (!keymap) bad++;
            Touch t = new Touch(x);

            // 0. for comparison: press and release sent together, as 0.4.0 did for a tap
            x.motion(100, 700);
            x.button(1, true);
            x.button(1, false);
            x.queryPointer();
            Thread.sleep(2500);
            System.out.println("gesture instant-click (0.4.0 style) at 100,700 sent");

            // 1. tap
            t.down(700, 500); t.sleep(80); t.up(700, 500);
            int[] tap = x.queryPointer();
            boolean tapOk = tap[0] == 700 && tap[1] == 500 && (tap[2] & 0x500) == 0;
            System.out.println("check tap → pointer at 700,500, no button left held (X server): " + (tapOk ? "PASS" : "FAIL")
                    + " (" + tap[0] + "," + tap[1] + " mask 0x" + Integer.toHexString(tap[2]) + ")");
            if (!tapOk) bad++;
            Thread.sleep(2500);

            // 2. one-finger drag (box select)
            t.down(200, 150); t.sleep(60); t.move(260, 200);
            boolean held = (x.queryPointer()[2] & 0x100) != 0;
            t.sleep(100); t.move(320, 260); t.sleep(100); t.up(320, 260);
            boolean drag = held && (x.queryPointer()[2] & 0x100) == 0;
            System.out.println("check drag 200,150 → 320,260: left held during the drag, released after (X server): " + (drag ? "PASS" : "FAIL"));
            if (!drag) bad++;
            Thread.sleep(2500);

            // 3. long press → right click
            t.down(640, 400); t.sleep(700); t.tick(); t.up(640, 400);
            System.out.println("gesture long press at 640,400 sent");
            Thread.sleep(2500);

            // 4. right-button mode: hold right while moving (camera interface)
            t.mapper.setRightButtonMode(true);
            t.down(600, 300); t.sleep(80);
            boolean rHeld = (x.queryPointer()[2] & 0x400) != 0;
            t.move(650, 330); t.sleep(100); t.move(700, 360); t.sleep(80); t.up(700, 360);
            boolean right = rHeld && (x.queryPointer()[2] & 0x400) == 0;
            t.mapper.setRightButtonMode(false);
            System.out.println("check right-button mode: right held while the finger moves, released after (X server): " + (right ? "PASS" : "FAIL"));
            if (!right) bad++;
            Thread.sleep(2500);

            // 5. double tap
            t.down(512, 384); t.sleep(60); t.up(512, 384); t.sleep(60);
            t.down(518, 388); t.sleep(60); t.up(518, 388);
            System.out.println("gesture double tap at 512,384 sent");
            Thread.sleep(2500);

            // 6. drag interrupted (touch cancelled, e.g. the app is paused)
            t.down(300, 600); t.sleep(60); t.move(380, 640); t.sleep(80); t.cancel();
            boolean released = (x.queryPointer()[2] & 0x500) == 0;
            System.out.println("check cancelled drag releases the button (X server): " + (released ? "PASS" : "FAIL"));
            if (!released) bad++;
            Thread.sleep(2500);

            // 7. second finger during a tap: no click
            t.down(800, 200); t.sleep(40); t.second(900, 250); t.sleep(80); t.allUp();
            System.out.println("gesture two-finger touch at 800,200 sent (must not click)");
            x.wheel(-1);
            x.flush();
            Thread.sleep(2500);

            // 8. keys: Escape, typed "a", "A", Return
            int esc = x.keycodeOf(0xff1b);
            x.key(0xff1b, true);
            boolean escDown = x.isKeyDown(esc);
            x.key(0xff1b, false);
            boolean escUp = !x.isKeyDown(esc);
            boolean typed = x.type('a') && x.type('A');
            x.key(0xff0d, true);
            x.key(0xff0d, false);
            int[] end = x.queryPointer();
            boolean keys = escDown && escUp && typed;
            System.out.println("check keys: Escape pressed/released, 'a' and 'A' typed, Return (X server): " + (keys ? "PASS" : "FAIL"));
            if (!keys) bad++;
            boolean none = (end[2] & 0x1f00) == 0;
            System.out.println("check no mouse button held at the end (X server): " + (none ? "PASS" : "FAIL (mask 0x" + Integer.toHexString(end[2]) + ")"));
            if (!none) bad++;
            System.out.println("x11 input: X errors " + x.errors + (x.errors > 0 ? " (" + x.lastError + ")" : ""));
            if (x.errors > 0) bad++;
        } finally {
            p.destroy();
        }
        return bad == 0 ? 0 : 1;
    }

    /** Touch frames in game pixels through the app's mapper on the owner's 1786x1228 view (letterboxed). */
    private static final class Touch {
        final io.github.saefalasalynbtel198.shcport.core.X11Input x;
        final io.github.saefalasalynbtel198.shcport.core.ViewportTransform vt =
                new io.github.saefalasalynbtel198.shcport.core.ViewportTransform(1024, 768, 1786, 1228);
        final io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper mapper = new io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper(
                new io.github.saefalasalynbtel198.shcport.core.ControlSettings(), vt);
        final long t0 = System.currentTimeMillis();
        float lx, ly;

        Touch(io.github.saefalasalynbtel198.shcport.core.X11Input x) { this.x = x; }

        private float sx(int gx) { return vt.offsetX + (gx + 0.5f) * vt.scale; }

        private float sy(int gy) { return vt.offsetY + (gy + 0.5f) * vt.scale; }

        private long now() { return System.currentTimeMillis() - t0; }

        private void send(List<io.github.saefalasalynbtel198.shcport.core.InputAction> acts) throws Exception {
            for (io.github.saefalasalynbtel198.shcport.core.InputAction a : acts) x.send(a);
            x.flush();
        }

        private void frame(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action a, int gx, int gy) throws Exception {
            lx = sx(gx);
            ly = sy(gy);
            send(mapper.onTouch(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Frame.single(a, lx, ly, now())));
        }

        void down(int gx, int gy) throws Exception { frame(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.DOWN, gx, gy); }

        void move(int gx, int gy) throws Exception { frame(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.MOVE, gx, gy); }

        void up(int gx, int gy) throws Exception { frame(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.UP, gx, gy); }

        void cancel() throws Exception { frame(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.CANCEL, 0, 0); }

        void tick() throws Exception { send(mapper.onTick(now())); }

        void sleep(long ms) throws Exception { Thread.sleep(ms); }

        /** A second finger touches (pointer 1) while the first is down. */
        void second(int gx, int gy) throws Exception {
            send(mapper.onTouch(new io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Frame(
                    io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.POINTER_DOWN, 1, new int[] {0, 1},
                    new float[] {lx, sx(gx)}, new float[] {ly, sy(gy)}, now())));
        }

        /** Both fingers lift: the second, then the first. */
        void allUp() throws Exception {
            send(mapper.onTouch(new io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Frame(
                    io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.POINTER_UP, 1, new int[] {0, 1},
                    new float[] {lx, lx + 100}, new float[] {ly, ly + 50}, now())));
            send(mapper.onTouch(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Frame.single(
                    io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.UP, lx, ly, now())));
        }
    }

    /**
     * The app's audio receiver (core/AudioStreamPump) on a Unix socket, each stream into a PacedRecordingSink (real-time
     * consumption, 100 ms buffer, like AudioTrack): what the ALSA plugin sends from Wine is recorded to WAV files.
     * Optionally pauses all output for a while (as on Android audio-focus loss) to check that the stream resumes.
     */
    private static int audioBridge(String[] a) throws Exception {
        java.nio.file.Path sock = java.nio.file.Paths.get(a[1]);
        File out = new File(a[2]);
        out.mkdirs();
        long seconds = Long.parseLong(a[3]);
        long pauseAt = a.length > 5 ? Long.parseLong(a[4]) : -1, pauseFor = a.length > 5 ? Long.parseLong(a[5]) : 0;
        java.nio.file.Files.deleteIfExists(sock);
        java.nio.channels.ServerSocketChannel server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
        server.bind(java.net.UnixDomainSocketAddress.of(sock));
        io.github.saefalasalynbtel198.shcport.core.AudioSettings settings = new io.github.saefalasalynbtel198.shcport.core.AudioSettings();
        List<io.github.saefalasalynbtel198.shcport.core.PacedRecordingSink> sinks = java.util.Collections.synchronizedList(new ArrayList<>());
        List<io.github.saefalasalynbtel198.shcport.core.AudioStreamPump> pumps = java.util.Collections.synchronizedList(new ArrayList<>());
        System.out.println("audio bridge listening on " + sock);
        Thread acceptor = new Thread(() -> {
            int n = 0;
            while (true) {
                java.nio.channels.SocketChannel ch;
                try {
                    ch = server.accept();
                } catch (Exception e) {
                    return;
                }
                int id = ++n;
                io.github.saefalasalynbtel198.shcport.core.PacedRecordingSink sink =
                        new io.github.saefalasalynbtel198.shcport.core.PacedRecordingSink(new File(out, "stream-" + id + ".wav"), 100);
                io.github.saefalasalynbtel198.shcport.core.AudioStreamPump pump = new io.github.saefalasalynbtel198.shcport.core.AudioStreamPump(() -> settings);
                sinks.add(sink);
                pumps.add(pump);
                new Thread(() -> {
                    try {
                        pump.run(java.nio.channels.Channels.newInputStream(ch), java.nio.channels.Channels.newOutputStream(ch), sink);
                    } catch (Exception e) {
                        // status keeps the reason
                    }
                    System.out.println("audio stream " + id + ": " + pump.status + "; " + sink.frames() + " frames, peak " + pump.peak);
                }, "audio-" + id).start();
            }
        }, "audio-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        long t0 = System.currentTimeMillis(), firstStream = -1;
        boolean pausedOnce = false;
        File stop = new File(out, "stop");
        while (System.currentTimeMillis() - t0 < seconds * 1000 && !stop.exists()) {
            if (firstStream < 0 && !sinks.isEmpty()) firstStream = System.currentTimeMillis();
            long el = firstStream < 0 ? -1 : (System.currentTimeMillis() - firstStream) / 1000;   // since the first stream
            if (pauseAt >= 0 && !pausedOnce && el >= pauseAt) {
                pausedOnce = true;
                System.out.println("audio bridge: pausing output for " + pauseFor + " s (as on audio-focus loss)");
                synchronized (sinks) { for (io.github.saefalasalynbtel198.shcport.core.PacedRecordingSink k : sinks) k.setPaused(true); }
                Thread.sleep(pauseFor * 1000);
                synchronized (sinks) { for (io.github.saefalasalynbtel198.shcport.core.PacedRecordingSink k : sinks) k.setPaused(false); }
                System.out.println("audio bridge: output resumed");
            }
            Thread.sleep(200);
        }
        server.close();
        synchronized (pumps) { for (io.github.saefalasalynbtel198.shcport.core.AudioStreamPump p : pumps) p.stop(); }
        Thread.sleep(500);
        synchronized (sinks) {
            for (int i = 0; i < sinks.size(); i++) {
                io.github.saefalasalynbtel198.shcport.core.PacedRecordingSink k = sinks.get(i);
                k.close();
                System.out.println("audio bridge stream " + (i + 1) + ": " + k.rate() + " Hz, " + k.channels() + " ch, " + k.frames()
                        + " frames (" + (k.rate() > 0 ? k.frames() * 1000 / k.rate() : 0) + " ms)");
            }
        }
        java.nio.file.Files.deleteIfExists(sock);
        System.out.println("audio bridge: " + sinks.size() + " stream(s)");
        return 0;
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
        // as in the app: Wine runs the game from a verified private copy (Android shared storage is noexec)
        File priv = new File(game.getParentFile(), game.getName() + "-private");
        io.github.saefalasalynbtel198.shcport.core.PrivateGameCopy.Result c1 =
                io.github.saefalasalynbtel198.shcport.core.PrivateGameCopy.sync(game, priv, -1, null);
        io.github.saefalasalynbtel198.shcport.core.PrivateGameCopy.Result c2 =
                io.github.saefalasalynbtel198.shcport.core.PrivateGameCopy.sync(game, priv, -1, null);
        String srcSha = io.github.saefalasalynbtel198.shcport.core.PrivateGameCopy.sha256(new File(game, exe));
        String cpySha = c1.ok ? io.github.saefalasalynbtel198.shcport.core.PrivateGameCopy.sha256(new File(priv, exe)) : "-";
        boolean copyOk = c1.ok && c2.ok && c1.copied == c1.files && c2.copied == 0 && c2.executablesVerified >= 1 && srcSha.equals(cpySha);
        System.out.println("private copy: first " + (c1.ok ? c1.summary() : "FAILED " + c1.error) + "; second "
                + (c2.ok ? c2.summary() : "FAILED " + c2.error) + "; exe SHA-256 " + srcSha + " / " + cpySha);
        System.out.println("check private copy (SHA-256 verified, reused on the second run, executables re-verified): " + (copyOk ? "PASS" : "FAIL"));
        if (!copyOk) return 1;
        io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan plan = io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan
                .build(rt, prefix, priv, exe, tmp, display, Arrays.asList("ddraw", "10"));
        io.github.saefalasalynbtel198.shcport.core.GameSetup.Result sr = io.github.saefalasalynbtel198.shcport.core.GameSetup
                .prepare(plan, new io.github.saefalasalynbtel198.shcport.core.CncDdrawConfig());
        System.out.print(sr.report);
        System.out.println("check setup: " + (sr.ok ? "PASS" : "FAIL"));
        if (!sr.ok) return 1;
        List<String> pcArgv = plan.argvFor(rt.guiSmoke().getPath(), Arrays.asList("ddraw", "10"));
        // 0. reproduction of the 0.3.7 device failure: the pre-check under the game's +relay with no relay filter
        //    (as on the phone, where reg.exe ran under +relay itself and did not finish in 120 s), judged both ways
        File repro = new File(logs, "precheck-0.3.7-repro.log");
        int rc = runTo(pcArgv, rt.root, plan.env, repro, 25);
        String old = io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan.loadLine(tailOf(repro, 2_000_000), "ddraw.dll", true);
        io.github.saefalasalynbtel198.shcport.core.DdrawPrecheck rr = io.github.saefalasalynbtel198.shcport.core.DdrawPrecheck.evaluate(repro, rc, true, "not checked");
        System.out.println("repro 0.3.7 (+relay without filter): exit " + rc + ", log " + repro.length() + " bytes, " + rr.logLines
                + " lines; ddraw.dll load line at line " + rr.loadLineNumber + " (" + (rr.nativeLoad ? "native" : "none/not native") + ")");
        System.out.println("repro 0.3.7 detection (last 2 MB of the log): " + (old != null ? "native load found" : "NO native load of ddraw.dll in the log"));
        System.out.println("repro whole-log detection: " + (rr.nativeLoad ? "native load found: " + rr.loadLine : "none"));
        // 1. relay filter: reg.exe without +relay and with the plan's prefix (as the app does now)
        File regLog = new File(logs, "relay-reg.log");
        int regRc = runTo(plan.relayArgv(), rt.root, plan.envWith(io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan.WINEDEBUG_TOOL), regLog, 30);
        System.out.println("check relay filter set (reg add RelayInclude, no +relay, plan's WINEPREFIX): " + (regRc == 0 ? "PASS" : "FAIL (exit " + regRc + ")"));
        if (regRc != 0) bad++;
        // 2. the app's DirectDraw pre-check: runtime program by Unix path, cwd = runtime, 0.3.6 WINEDEBUG
        bad += ddrawRun("precheck", pcArgv, rt.root, plan.envWith(io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan.WINEDEBUG_PRECHECK), fb, new File(logs, "precheck.log"));
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
        io.github.saefalasalynbtel198.shcport.core.GameExitAnalysis ea = io.github.saefalasalynbtel198.shcport.core.GameExitAnalysis.parse(gameLog);
        for (String l : ea.lines()) System.out.println("exit analysis: " + l);
        boolean exitSeen = false;
        for (String e : ea.exits) if (e.contains("ExitProcess(0000002a)")) exitSeen = true;   // the stand-in ends with 42
        System.out.println("check relay trace shows the program's ExitProcess(42) (exit diagnostics work under Box64/WoW64): " + (exitSeen ? "PASS" : "FAIL"));
        if (!exitSeen) bad++;
        boolean iniHere = new File(game, "ddraw.ini").exists() || new File(priv, "ddraw.ini").exists() || new File(plan.ddrawTarget.getParentFile(), "ddraw.ini").exists();
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
        io.github.saefalasalynbtel198.shcport.core.DdrawPrecheck r = io.github.saefalasalynbtel198.shcport.core.DdrawPrecheck
                .evaluate(log, code, pixels != null, pixels == null ? null : "OK: " + pixels);   // the app's verdict
        boolean ok = r.ok;
        System.out.println("check " + label + ": " + r.summary() + " [" + (System.currentTimeMillis() - t0) / 1000 + " s]");
        for (String d : r.details()) System.out.println("  " + label + " | " + d);
        if (!ok) {
            String[] lines = new String(java.nio.file.Files.readAllBytes(log.toPath()), java.nio.charset.StandardCharsets.UTF_8).split("\n");
            for (int i = Math.max(0, lines.length - 30); i < lines.length; i++) System.out.println("  | " + lines[i]);
        }
        return ok ? 0 : 1;
    }

    /** Runs a command with env (stdin closed, output to log), at most maxMinutes; exit code or -1. */
    private static int runTo(List<String> argv, File cwd, Map<String, String> env, File log, int maxMinutes) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true).redirectOutput(log);
        pb.environment().remove("LD_LIBRARY_PATH");
        pb.environment().putAll(env);
        Process p = pb.start();
        p.getOutputStream().close();
        if (!p.waitFor(maxMinutes, java.util.concurrent.TimeUnit.MINUTES)) { p.destroyForcibly(); p.waitFor(); return -1; }
        return p.exitValue();
    }

    /** Last max bytes of a file, as the 0.3.7 app read the pre-check log. */
    private static String tailOf(File f, int max) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            int n = (int) Math.min(len, max);
            byte[] b = new byte[n];
            raf.seek(len - n);
            raf.readFully(b);
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------------------------------------ 0.6.0

    private static int[] wh(String v) {
        String[] p = v.split("x");
        return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    /**
     * Display Resolution with the app's code: Xvfb at the chosen screen size (X11DisplayPlan), its framebuffer of that
     * size, cnc-ddraw's settings for it (CncDdrawConfig.forScreen), the app's DirectDraw pre-check at that size, then
     * a DirectDraw program as the game in a 16-bit mode (the game's own resolution) and taps through the app's
     * TouchMouseMapper (phone view 2400x1080, viewport of the X screen) + X11Input: the program must receive each
     * click at the tapped game pixel (cnc-ddraw scales the picture onto the X screen and maps the mouse back);
     * a touch in the phone's black bars must produce no input at all.
     */
    private static int resolution(String[] a) throws Exception {
        RuntimeLayout rt = new RuntimeLayout(new File(a[1]));
        File prefix = new File(a[2]), tmp = new File(a[3]), work = new File(a[4]);
        String bridge = a[5];
        File testExe = new File(a[6]);
        int d = Integer.parseInt(a[7]);
        int[] sc = wh(a[8]), g = wh(a[9]);
        boolean pre = a[10].equals("precheck");
        // 0.6.1 Display Scaling Mode (Android transform; cnc-ddraw stretches too only for Stretch on a non-4:3 screen)
        io.github.saefalasalynbtel198.shcport.core.ScalingMode mode = a.length > 11 ? io.github.saefalasalynbtel198.shcport.core.ScalingMode.fromKey(a[11]) : io.github.saefalasalynbtel198.shcport.core.ScalingMode.PRESERVE;
        boolean ddStretch = io.github.saefalasalynbtel198.shcport.core.CncDdrawConfig.stretchesGame(mode, sc[0], sc[1]);
        String tag = a[8] + "-game-" + a[9] + (mode == io.github.saefalasalynbtel198.shcport.core.ScalingMode.PRESERVE ? "" : "-" + mode.key);
        File dir = new File(work, tag);
        dir.mkdirs();
        int bad = 0;
        String verr = io.github.saefalasalynbtel198.shcport.core.DisplayResolution.validate(sc[0], sc[1]);
        System.out.println("[" + tag + "] check screen size valid for the app: " + (verr == null ? "PASS" : "FAIL " + verr));
        if (verr != null) return 1;

        // 1. Xvfb at the size, as the app starts it
        CliHost h = new CliHost(rt, new File(dir, "fb"), dir, new ArrayList<>(), false);
        h.width = sc[0];
        h.height = sc[1];
        XvfbStarter.Result r = XvfbStarter.start(h, d, d, 300_000);
        if (!r.ok()) {
            System.out.println("[" + tag + "] check Xvfb started: FAIL " + r.error);
            return 2;
        }
        boolean argvOk = h.lastArgv.contains(sc[0] + "x" + sc[1] + "x24");
        System.out.println("[" + tag + "] check Xvfb argv has -screen 0 " + sc[0] + "x" + sc[1] + "x24: " + (argvOk ? "PASS" : "FAIL " + quoted(h.lastArgv)));
        if (!argvOk) bad++;
        File fb = new File(new File(new File(dir, "fb"), String.valueOf(d)), "Xvfb_screen0");
        String fbSize;
        try (RandomAccessFile raf = new RandomAccessFile(fb, "r"); FileChannel ch = raf.getChannel()) {
            XwdFramebuffer x = XwdFramebuffer.parse(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()));
            fbSize = x.width + "x" + x.height;
        }
        boolean fbOk = fbSize.equals(a[8]);
        System.out.println("[" + tag + "] check framebuffer (app's XWD parser) is " + a[8] + ": " + (fbOk ? "PASS" : "FAIL " + fbSize));
        if (!fbOk) bad++;

        // 2. the game's setup with cnc-ddraw settings for this screen; the stand-in has the game's file name
        File game = new File(dir, "game");
        game.mkdirs();
        String exe = "Stronghold Crusader.exe";
        java.nio.file.Files.copy(testExe.toPath(), new File(game, exe).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan plan = io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan
                .build(rt, prefix, game, exe, tmp, d, Arrays.asList(String.valueOf(g[0]), String.valueOf(g[1]), "1500"));
        io.github.saefalasalynbtel198.shcport.core.CncDdrawConfig cfg = new io.github.saefalasalynbtel198.shcport.core.CncDdrawConfig().forScreen(sc[0], sc[1]).forScaling(mode, sc[0], sc[1]);
        io.github.saefalasalynbtel198.shcport.core.GameSetup.Result sr = io.github.saefalasalynbtel198.shcport.core.GameSetup.prepare(plan, cfg);
        boolean def = sc[0] == X11DisplayPlan.WIDTH && sc[1] == X11DisplayPlan.HEIGHT;
        boolean iniOk = sr.ok && (def ? !cfg.toIni().contains("inject_resolution")
                : cfg.toIni().split("inject_resolution=" + a[8] + "\r\n", -1).length == 3)
                && cfg.toIni().contains(ddStretch ? "maintas=false\r\n" : "maintas=true\r\n");
        System.out.println("[" + tag + "] scaling " + mode.label + ": cnc-ddraw " + (ddStretch ? "stretches the game picture (maintas=false)" : "keeps its shape (maintas=true)"));
        System.out.println("[" + tag + "] check setup, ini " + (def ? "unchanged from 0.5.1" : "injects " + a[8] + " in both game sections") + ": " + (iniOk ? "PASS" : "FAIL"));
        if (!iniOk) { System.out.print(sr.report); bad++; }

        // 3. the app's DirectDraw pre-check at this screen (runtime's program, 1024x768x16, picture checked in the framebuffer)
        if (pre) {
            bad += ddrawRun("[" + tag + "] precheck", plan.argvFor(rt.guiSmoke().getPath(), Arrays.asList("ddraw", "10")), rt.root,
                    plan.envWith(io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan.WINEDEBUG_PRECHECK), fb, new File(dir, "precheck.log"));
        }

        // 4. the DirectDraw program as the game, in mode g
        File log = new File(dir, "game.log");
        ProcessBuilder pb = new ProcessBuilder(plan.argv).directory(plan.workingDirectory).redirectErrorStream(true).redirectOutput(log);
        pb.environment().remove("LD_LIBRARY_PATH");
        pb.environment().putAll(plan.envWith(io.github.saefalasalynbtel198.shcport.core.WineLaunchPlan.WINEDEBUG_PLAY));
        long t0 = System.currentTimeMillis();
        Process p = pb.start();
        p.getOutputStream().close();
        String pixels = null;
        while (pixels == null && p.isAlive() && System.currentTimeMillis() - t0 < 30 * 60_000L) {
            Thread.sleep(3000);
            try (RandomAccessFile raf = new RandomAccessFile(fb, "r"); FileChannel ch = raf.getChannel()) {
                MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
                DisplayTestPattern.Check c = DisplayTestPattern.checkQuadrants(XwdFramebuffer.parse(m), m,
                        DisplayTestPattern.DDRAW_COLORS, DisplayTestPattern.DDRAW_NAMES);
                if (c.ok) pixels = c.detail;
            } catch (Exception ignored) { }
        }
        String gl = new String(java.nio.file.Files.readAllBytes(log.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        boolean listed = gl.contains("requested mode " + g[0] + "x" + g[1] + " listed");
        boolean modeSet = gl.contains("SetDisplayMode(" + g[0] + "," + g[1] + ",16) 0x00000000");
        boolean screenListed = gl.contains("ddraw-input: mode " + a[8] + "\n") || gl.contains("ddraw-input: mode " + a[8] + "\r");
        StringBuilder modes = new StringBuilder();
        for (String l : gl.split("\n")) if (l.startsWith("ddraw-input: mode ")) modes.append(l.substring(18).trim()).append(' ');
        System.out.println("[" + tag + "] modes DirectDraw offers the game: " + modes.toString().trim());
        System.out.println("[" + tag + "] check X screen size " + a[8] + " offered to the game as a mode: " + (screenListed ? "PASS" : "FAIL"));
        if (!screenListed) bad++;
        // cnc-ddraw lists only modes that fit the screen; a larger mode saved in the game's options is still set
        boolean fits = g[0] <= sc[0] && g[1] <= sc[1];
        System.out.println("[" + tag + "] check game mode " + a[9] + (fits ? " offered and set: " : " (larger than the screen, not offered) set: ")
                + ((fits ? listed : true) && modeSet ? "PASS" : "FAIL (listed " + listed + ", set " + modeSet + ")"));
        if ((fits && !listed) || !modeSet) bad++;
        System.out.println("[" + tag + "] check game picture in the X screen (4 colours, aspect kept): " + (pixels != null ? "PASS " + pixels : "FAIL")
                + " [" + (System.currentTimeMillis() - t0) / 1000 + " s]");
        if (pixels == null) {
            bad++;
            for (String l : gl.split("\n")) if (l.startsWith("ddraw-input:") || l.contains("err:")) System.out.println("  | " + l);
            p.destroyForcibly();
            return 1;
        }

        // 5. taps on a phone view through the app's mapper and X11 client
        Process br = new ProcessBuilder("python3", "-I", bridge, String.valueOf(d)).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        io.github.saefalasalynbtel198.shcport.core.ViewportTransform vt =
                new io.github.saefalasalynbtel198.shcport.core.ViewportTransform(sc[0], sc[1], 2400, 1080, mode);
        io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper mapper = new io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper(
                new io.github.saefalasalynbtel198.shcport.core.ControlSettings(), vt);
        // where cnc-ddraw puts the game picture on the X screen (maintas: aspect kept, centred)
        // (maintas=false: stretched per axis over the whole screen)
        float s = Math.min((float) sc[0] / g[0], (float) sc[1] / g[1]);
        float gsx = ddStretch ? (float) sc[0] / g[0] : s, gsy = ddStretch ? (float) sc[1] / g[1] : s;
        float ox = (sc[0] - g[0] * gsx) / 2f, oy = (sc[1] - g[1] * gsy) / 2f;
        int[][] targets = {{g[0] / 3, g[1] / 3}, {g[0] / 10, g[1] / 10}, {g[0] / 2, g[1] / 2}, {g[0] * 9 / 10, g[1] * 85 / 100}};
        long tt = System.currentTimeMillis();
        boolean barQuiet;
        try (io.github.saefalasalynbtel198.shcport.core.X11Input x = io.github.saefalasalynbtel198.shcport.core.X11Input
                .connect(br.getInputStream(), br.getOutputStream())) {
            System.out.println("[" + tag + "] x11 input: screen " + x.screenWidth + "x" + x.screenHeight + "; phone view 2400x1080, picture "
                    + Math.round(vt.drawnWidth()) + "x" + Math.round(vt.drawnHeight()) + " at " + Math.round(vt.offsetX) + "," + Math.round(vt.offsetY)
                    + " (" + mode.label + ")");
            for (int[] t : targets) {
                int xi = (int) Math.floor(ox + (t[0] + 0.5f) * gsx), yi = (int) Math.floor(oy + (t[1] + 0.5f) * gsy);
                float vx = vt.viewX(xi), vy = vt.viewY(yi);
                List<io.github.saefalasalynbtel198.shcport.core.InputAction> acts = new ArrayList<>(mapper.onTouch(
                        io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Frame.single(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.DOWN, vx, vy, System.currentTimeMillis() - tt)));
                Thread.sleep(80);
                acts.addAll(mapper.onTouch(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Frame.single(
                        io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.UP, vx, vy, System.currentTimeMillis() - tt)));
                for (io.github.saefalasalynbtel198.shcport.core.InputAction ia : acts) x.send(ia);
                x.flush();
                System.out.println("[" + tag + "] tap game " + t[0] + "," + t[1] + " = X " + xi + "," + yi + " = view " + Math.round(vx) + "," + Math.round(vy) + " -> " + acts);
                Thread.sleep(4000);
            }
            // a touch in a black bar; when the X screen has the view's shape (Device) there is none, so the bar is
            // tried on a second view shape (1786x1228, as the CI input test) with the same X screen
            io.github.saefalasalynbtel198.shcport.core.ViewportTransform bvt = vt;
            io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper bm = mapper;
            if (mode == io.github.saefalasalynbtel198.shcport.core.ScalingMode.STRETCH) {
                // no bars at all: the corners of the phone view are the corners of the X screen
                boolean c0 = vt.contains(0, 0) && vt.toGameX(0) == 0 && vt.toGameY(0) == 0;
                boolean c1 = vt.contains(2399.9f, 1079.9f) && vt.toGameX(2399.9f) == sc[0] - 1 && vt.toGameY(1079.9f) == sc[1] - 1;
                System.out.println("[" + tag + "] check Stretch: no bars, view corners are X screen corners 0,0 and "
                        + (sc[0] - 1) + "," + (sc[1] - 1) + ": " + (c0 && c1 ? "PASS" : "FAIL"));
                if (!(c0 && c1)) bad++;
                bvt = new io.github.saefalasalynbtel198.shcport.core.ViewportTransform(sc[0], sc[1], 1786, 1228, io.github.saefalasalynbtel198.shcport.core.ScalingMode.PRESERVE);
                bm = new io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper(new io.github.saefalasalynbtel198.shcport.core.ControlSettings(), bvt);
                System.out.println("[" + tag + "] info: bar touch tried with Preserve on 1786x1228");
            } else if (vt.offsetX < 8 && vt.offsetY < 8) {
                System.out.println("[" + tag + "] info: no black bars on the 2400x1080 view (same shape as the X screen); bar touch tried on 1786x1228");
                bvt = new io.github.saefalasalynbtel198.shcport.core.ViewportTransform(sc[0], sc[1], 1786, 1228);
                bm = new io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper(new io.github.saefalasalynbtel198.shcport.core.ControlSettings(), bvt);
            }
            float bx = bvt.offsetX >= 8 ? bvt.offsetX / 2 : bvt.gameWidth * bvt.scale / 2, by = bvt.offsetY >= 8 ? bvt.offsetY / 2 : bvt.gameHeight * bvt.scale / 2;
            boolean inBar = !bvt.contains(bx, by);
            List<io.github.saefalasalynbtel198.shcport.core.InputAction> bar = new ArrayList<>(bm.onTouch(
                    io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Frame.single(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.DOWN, bx, by, System.currentTimeMillis() - tt)));
            Thread.sleep(80);
            bar.addAll(bm.onTouch(io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Frame.single(
                    io.github.saefalasalynbtel198.shcport.core.TouchMouseMapper.Action.UP, bx, by, System.currentTimeMillis() - tt)));
            barQuiet = inBar && bar.isEmpty();
            for (io.github.saefalasalynbtel198.shcport.core.InputAction ia : bar) x.send(ia);   // nothing, if correct
            x.flush();
            System.out.println("[" + tag + "] check touch in the black bar at " + Math.round(bx) + "," + Math.round(by) + " sends no input: "
                    + (barQuiet ? "PASS" : "FAIL (in bar " + inBar + ", actions " + bar + ")"));
        }
        if (!barQuiet) bad++;
        Thread.sleep(30_000);   // Wine under qemu delivers the messages with some delay
        p.destroyForcibly();
        p.waitFor();
        br.destroy();
        gl = new String(java.nio.file.Files.readAllBytes(log.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        List<int[]> downs = new ArrayList<>();
        for (String l : gl.split("\n")) {
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("WM_LBUTTONDOWN (-?\\d+),(-?\\d+) cursor (-?\\d+),(-?\\d+)").matcher(l);
            if (mm.find()) {
                downs.add(new int[] {Integer.parseInt(mm.group(1)), Integer.parseInt(mm.group(2)), Integer.parseInt(mm.group(3)), Integer.parseInt(mm.group(4))});
                System.out.println("  | " + l.trim());
            }
        }
        // one game pixel covers 1/s X pixels: the tolerance grows when the picture is smaller than the game
        int tolX = 2 + (int) Math.ceil(1 / gsx), tolY = 2 + (int) Math.ceil(1 / gsy);
        int found = 0;
        for (int i = 1; i < targets.length; i++) {        // targets[0] only wakes the window (cnc-ddraw may take that click to lock the mouse)
            int[] t = targets[i];
            boolean hit = false;
            for (int[] dn : downs) if (Math.abs(dn[0] - t[0]) <= tolX && Math.abs(dn[1] - t[1]) <= tolY) hit = true;
            System.out.println("[" + tag + "] click at game " + t[0] + "," + t[1] + " (±" + tolX + "," + tolY + "): " + (hit ? "received" : "NOT received"));
            if (hit) found++;
        }
        boolean clicksOk = found == targets.length - 1 && downs.size() <= targets.length;
        System.out.println("[" + tag + "] check taps arrive at the tapped game pixel (" + found + "/" + (targets.length - 1) + ", " + downs.size()
                + " clicks in all, none from the bar): " + (clicksOk ? "PASS" : "FAIL"));
        if (!clicksOk) bad++;
        return bad == 0 ? 0 : 1;
    }
}
