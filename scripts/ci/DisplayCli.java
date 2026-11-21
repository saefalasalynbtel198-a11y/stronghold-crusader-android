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
}
