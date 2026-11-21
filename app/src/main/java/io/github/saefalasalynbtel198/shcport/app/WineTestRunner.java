package io.github.saefalasalynbtel198.shcport.app;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.saefalasalynbtel198.shcport.BuildConfig;
import io.github.saefalasalynbtel198.shcport.core.ElfInterp;
import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;
import io.github.saefalasalynbtel198.shcport.core.RuntimeProbe;
import io.github.saefalasalynbtel198.shcport.core.WinePrefixSetup;
import io.github.saefalasalynbtel198.shcport.core.WineTestPlan;

/**
 * Runs {@link WineTestPlan} on the device and produces a complete text report. Blocking: call from a
 * background thread. The report is shown, saved to logs/wine-tests-*.txt and can be copied.
 *
 * <p>Besides the test steps it records the evidence needed when Wine fails on a device: the process
 * environment (uid, seccomp mode, SELinux context, page size), the process watcher's account of how each
 * Wine process ended, the state of the wineserver directory after a failure, the app-started wineserver's
 * exit status and log, and SELinux/seccomp lines from this app's logcat.
 */
final class WineTestRunner {

    interface Progress { void update(String message); }

    private static final int MAX_OUTPUT = 512 * 1024;
    private static final int REPORT_TAIL = 9000;

    private final AppPaths paths;
    private Process bgServer;
    private File bgLog;
    private String bgName;

    WineTestRunner(AppPaths paths) { this.paths = paths; }

    private static final class Result {
        boolean ok;
        int exit;
    }

    String run(GameInstallation game, Progress progress) {
        long startMs = System.currentTimeMillis();
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
        sb.append("Process watcher: ").append(rt.ptraceWatch().isFile()
                ? "yes (lines starting with [watch])" : "not in this runtime (r5 or newer adds it)").append('\n');
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

        progress.update("Checking the environment…");
        sb.append(environment(prefix));
        sb.append(prepare(prefix));

        //noinspection ResultOfMethodCallIgnored
        prefix.getParentFile().mkdirs();
        List<WineTestPlan.Step> steps = WineTestPlan.steps(rt, prefix, tmp, paths.gameDir(), gameExe);
        int required = 0, passed = 0;
        for (WineTestPlan.Step s : steps) if (s.required) required++;
        boolean stop = false, appServer = false;
        for (WineTestPlan.Step s : steps) {
            if (appServer && s.postCheck == WineTestPlan.Check.WINESERVER_DIR && !s.background) {
                // the prefix was only created with the app-started server: keep using that way
                if (bgServer != null) sb.append("== Status of the previous app-started wineserver ==\n").append(backgroundStatus());
                s = WineTestPlan.appServer(rt, prefix, tmp, "wineserver started by the app for the next tests (-f -p)",
                        "wineserver-tests.log", "-f", "-p");
            }
            boolean cleanup = s.argv.size() > 2 && "-k".equals(s.argv.get(s.argv.size() - 1));
            if (stop && !cleanup) continue;
            if (gameExe != null && s.argv.contains("D:\\" + gameExe)) {
                String link = linkGameDrive(prefix, paths.gameDir());
                sb.append("dosdevices/d: -> ").append(link).append('\n');
            }
            Result r = runStep(s, prefix, sb, progress);
            if (s.prefixInit && !r.ok) {
                sb.append("== State after the failed prefix initialisation ==\n").append(prefixState(prefix)).append('\n');
                r.ok = runAlternative(rt, prefix, tmp, sb, progress);
                appServer = r.ok;
                sb.append(r.ok
                        ? "Prefix initialisation: FAILED with the wineserver started by wine, PASSED with the wineserver started by the app.\n\n"
                        : "Prefix initialisation: FAILED both ways.\n\n");
            }
            if (s.required) {
                if (r.ok) passed++;
                else stop = true;
            }
        }
        sb.append(finishBackgroundServer());
        sb.append(logcatExcerpt(startMs));
        sb.append(String.format(Locale.ROOT, "Result: %d of %d required steps passed.\n", passed, required));
        if (passed < required) sb.append("Wine is not yet usable on this device. Please send this whole report.\n");
        String report = sb.toString();
        save(report);
        return report;
    }

    /** Result of {@link #ensurePrefix}. */
    static final class PrefixResult {
        boolean ok;
        boolean created;
        String reason = "";
        String report = "";
        /** Short form for a successful run: headers, exit lines and checks only. */
        String summary = "";
    }

    /**
     * A usable Wine prefix for the display test, with the same steps, environment and working directory as the
     * Wine tests (device-verified 6/6): parent directory created and checked first (Wine creates only the last
     * path component), leftover Wine processes stopped, an incomplete prefix removed, wineboot --init (exit 0) and
     * wineserver -w (exit 0) with the prefix check, the second way with an app-started wineserver if the first
     * fails, then Wine stopped. A complete prefix is kept without running wineboot.
     */
    PrefixResult ensurePrefix(Progress progress) throws InterruptedException {
        RuntimeLayout rt = new RuntimeLayout(paths.runtimeDir());
        File prefix = paths.prefixDir();
        File tmp = paths.tmpDir();
        PrefixResult res = new PrefixResult();
        StringBuilder sb = new StringBuilder();
        sb.append("Prefix: ").append(prefix).append('\n');
        sb.append(environment(prefix));
        sb.append(prepare(prefix));
        WinePrefixSetup.ParentCheck pc = WinePrefixSetup.ensureParent(prefix);
        sb.append("== Prefix directory ==\n").append(pc.report);
        File parent = prefix.getParentFile();
        if (parent != null) sb.append(describe(parent)).append('\n');
        sb.append(describe(prefix)).append("\n\n");
        if (!pc.ok) {
            res.reason = "the prefix directory cannot be created (see \"Prefix directory\")";
            return finish(res, sb);
        }
        if (WinePrefixSetup.isComplete(prefix)) {
            sb.append("complete prefix (system.reg, drive_c/windows): kept, wineboot not run\n");
            res.ok = true;
            return finish(res, sb);
        }
        boolean ok = true;
        for (WineTestPlan.Step s : WineTestPlan.prefixInitSteps(rt, prefix, tmp)) {
            Result r = runStep(s, prefix, sb, progress);
            if (s.prefixInit && !r.ok) {
                sb.append("== State after the failed prefix initialisation ==\n").append(prefixState(prefix)).append('\n');
                r.ok = runAlternative(rt, prefix, tmp, sb, progress);
                sb.append(r.ok ? "Prefix initialisation: FAILED with the wineserver started by wine, PASSED with the wineserver started by the app.\n\n"
                        : "Prefix initialisation: FAILED both ways.\n\n");
            }
            if (!r.ok) {
                ok = false;
                res.reason = s.name + " failed (exit " + r.exit + ")";
                break;
            }
        }
        runStep(WineTestPlan.stopStep(rt, prefix, tmp), prefix, sb, progress);   // no Wine without a display may stay
        sb.append(finishBackgroundServer());
        boolean complete = WinePrefixSetup.isComplete(prefix);
        sb.append("prefix check after creation: system.reg ").append(new File(prefix, "system.reg").isFile() ? "present" : "MISSING")
                .append(", drive_c/windows ").append(new File(prefix, "drive_c/windows").isDirectory() ? "present" : "MISSING").append('\n');
        if (ok && !complete) res.reason = "Wine exited 0 but the prefix is incomplete (system.reg or drive_c/windows missing)";
        res.ok = ok && complete;
        res.created = res.ok;
        return finish(res, sb);
    }

    private static PrefixResult finish(PrefixResult res, StringBuilder sb) {
        res.report = sb.toString();
        StringBuilder sum = new StringBuilder();
        for (String line : res.report.split("\n")) {
            if (line.startsWith("Prefix:") || line.startsWith("prefix ") || line.startsWith("parent ") || line.startsWith("== ")
                    || line.startsWith("$ ") || line.startsWith("-> exit") || line.startsWith("post-check") || line.startsWith("complete prefix")
                    || line.startsWith("existing prefix") || line.startsWith("removing") || line.startsWith("no prefix")
                    || line.startsWith("Prefix initialisation") || line.startsWith("prefix check") || line.startsWith("  /")
                    || line.startsWith("/")) {
                sum.append(line).append('\n');
            }
        }
        res.summary = sum.toString();
        return res;
    }

    // ---------------------------------------------------------------- steps

    private Result runStep(WineTestPlan.Step s, File prefix, StringBuilder sb, Progress progress) {
        progress.update(s.name + "…");
        sb.append("== ").append(s.name).append(" ==\n$ ").append(String.join(" ", s.argv)).append('\n');
        long t0 = System.nanoTime();
        Result r = new Result();
        String out;
        String post;
        try {
            ProcessBuilder pb = new ProcessBuilder(s.argv);
            pb.environment().putAll(s.env);
            //noinspection ResultOfMethodCallIgnored
            s.cwd.mkdirs();
            pb.directory(s.cwd);
            pb.redirectErrorStream(true);
            if (s.background) {
                bgLog = new File(paths.logsDir(), s.logFileName != null ? s.logFileName : "background.log");
                pb.redirectOutput(bgLog);
                bgServer = pb.start();
                bgName = s.name;
                long deadline = System.currentTimeMillis() + s.timeoutMs;
                post = WineTestPlan.Check.NONE == s.postCheck ? null : "not ready";
                while (System.currentTimeMillis() < deadline) {
                    post = postCheck(s.postCheck, prefix);
                    if (post == null || !bgServer.isAlive()) break;
                    Thread.sleep(200);
                }
                if (post == null && bgServer.isAlive()) {
                    r.exit = 0;
                    out = "[running in the background, output in " + bgLog.getName() + "]\n" + tailOfFile(bgLog, 3000);
                } else if (!bgServer.isAlive()) {
                    r.exit = bgServer.exitValue();
                    out = tailOfFile(bgLog, REPORT_TAIL);
                    post = "the process ended before it was ready";
                } else {
                    r.exit = -1;
                    out = tailOfFile(bgLog, REPORT_TAIL);
                }
                r.ok = post == null && r.exit == 0;
            } else {
                Process p = pb.start();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                Thread reader = drain(p.getInputStream(), bos);
                boolean done = p.waitFor(s.timeoutMs, TimeUnit.MILLISECONDS);
                if (!done) p.destroyForcibly();
                reader.join(5000);
                synchronized (bos) {
                    out = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                }
                r.exit = done ? p.exitValue() : -1;
                post = postCheck(s.postCheck, prefix);
                r.ok = s.outputOk(r.exit, out) && post == null;
            }
        } catch (Exception e) {
            r.exit = -2;
            out = "[could not start: " + e + "]";
            post = null;
            r.ok = false;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        out = out.replace("\r", "");
        String watch = watchLines(out);
        if (!watch.isEmpty()) sb.append("-- process watcher --\n").append(watch).append("-- output --\n");
        sb.append(tail(out, REPORT_TAIL)).append('\n');
        if (post != null) sb.append("post-check: ").append(post).append('\n');
        sb.append(String.format(Locale.ROOT, "-> exit %d (%s), %d ms: %s%s\n\n", r.exit, RuntimeProbe.describeExit(r.exit), ms,
                r.ok ? "PASS" : "FAIL", s.required ? "" : " [diagnostic]"));
        return r;
    }

    /** Second way: the app starts wineserver in the foreground, then wineboot connects to it. */
    private boolean runAlternative(RuntimeLayout rt, File prefix, File tmp, StringBuilder sb, Progress progress) {
        sb.append("== Second way: wineserver started by the app (no daemon fork; the app sees its exit status) ==\n");
        sb.append(removeServerDirs(prefix)).append('\n');
        boolean ok = true;
        for (WineTestPlan.Step s : WineTestPlan.alternativeInit(rt, prefix, tmp)) {
            Result r = runStep(s, prefix, sb, progress);
            if (!r.ok) { ok = false; break; }
        }
        sb.append(backgroundStatus());
        return ok;
    }

    private String backgroundStatus() {
        if (bgServer == null) return "";
        StringBuilder sb = new StringBuilder("-- app-started wineserver: ");
        if (bgServer.isAlive()) {
            sb.append("still running\n");
        } else {
            int code = bgServer.exitValue();
            sb.append("ended, exit ").append(code).append(" (").append(RuntimeProbe.describeExit(code)).append(")\n");
        }
        sb.append("-- its log (").append(bgLog.getName()).append(", ").append(bgLog.length()).append(" bytes), first lines --\n")
                .append(headOfFile(bgLog, 40)).append("\n-- last lines --\n").append(tailOfFile(bgLog, 6000)).append("\n\n");
        return sb.toString();
    }

    private String finishBackgroundServer() {
        if (bgServer == null) return "";
        try {
            if (bgServer.isAlive() && !bgServer.waitFor(10, TimeUnit.SECONDS)) {
                bgServer.destroy();
                bgServer.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return "== Final status of the app-started wineserver (" + bgName + ") ==\n" + backgroundStatus();
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

    // ---------------------------------------------------------------- evidence

    private static String environment(File prefix) {
        StringBuilder sb = new StringBuilder("== Environment ==\n");
        sb.append("app uid ").append(Os.getuid()).append(", pid ").append(Os.getpid())
                .append(", kernel ").append(System.getProperty("os.version"))
                .append(", page size ").append(Os.sysconf(OsConstants._SC_PAGESIZE)).append('\n');
        for (String line : readLines(new File("/proc/self/status"))) {
            if (line.startsWith("Uid:") || line.startsWith("Gid:") || line.startsWith("Seccomp")
                    || line.startsWith("NoNewPrivs") || line.startsWith("CapEff")) {
                sb.append("  ").append(line.replace('\t', ' ')).append('\n');
            }
        }
        sb.append("  SELinux context: ").append(readText(new File("/proc/self/attr/current"))).append('\n');
        File p = prefix.getParentFile();
        if (p != null) sb.append("  ").append(describe(p)).append('\n');
        sb.append("  ").append(describe(prefix)).append("\n\n");
        return sb.toString();
    }

    /** Clean start: stop leftover Wine processes and remove an incomplete prefix from an earlier failed run. */
    private static String prepare(File prefix) {
        StringBuilder sb = new StringBuilder("== Preparation ==\n");
        List<Integer> leftovers = wineProcesses();
        for (int pid : leftovers) {
            sb.append("stopping leftover Wine process ").append(pid).append(": ").append(cmdline(pid)).append('\n');
            android.os.Process.killProcess(pid);
        }
        if (prefix.isDirectory() && !new File(prefix, "system.reg").isFile()) {
            sb.append("removing incomplete prefix from an earlier run: ").append(deleteTree(prefix)).append('\n');
        } else if (prefix.isDirectory()) {
            sb.append("existing prefix kept (system.reg present)\n");
            sb.append(removeServerDirs(prefix)).append('\n');
        } else {
            sb.append("no prefix yet\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    /** Wineserver directory contents after a failure. A socket left behind means the server did not exit normally. */
    private static String prefixState(File prefix) {
        StringBuilder sb = new StringBuilder();
        String[] top = prefix.list();
        sb.append("prefix contents: ").append(top == null ? "(missing)" : Arrays.toString(top)).append('\n');
        boolean socketLeft = false;
        File[] bases = prefix.listFiles((d, n) -> n.startsWith(".wineserver"));
        if (bases == null || bases.length == 0) sb.append("no .wineserver* directory: wineserver never created its directory\n");
        else for (File b : bases) {
            sb.append(describe(b)).append('\n');
            File[] servers = b.listFiles();
            if (servers != null) for (File s : servers) {
                sb.append("  ").append(describe(s)).append('\n');
                File[] files = s.listFiles();
                if (files != null) for (File f : files) {
                    sb.append("    ").append(describe(f)).append('\n');
                    if (f.getName().equals("socket")) socketLeft = true;
                }
            }
        }
        List<Integer> running = wineProcesses();
        sb.append("Wine processes running now: ");
        if (running.isEmpty()) sb.append("none\n");
        else for (int pid : running) sb.append(pid).append(' ').append(cmdline(pid)).append("; ");
        if (socketLeft && running.isEmpty()) {
            sb.append("\nNote: the socket is still there although no Wine process runs. wineserver removes it when it exits"
                    + " normally, so it was killed (signal) or crashed rather than exiting.\n");
        }
        return sb.toString();
    }

    private static String removeServerDirs(File prefix) {
        File[] bases = prefix.listFiles((d, n) -> n.startsWith(".wineserver"));
        if (bases == null || bases.length == 0) return "no stale wineserver directory";
        StringBuilder sb = new StringBuilder();
        for (File b : bases) sb.append("removed stale ").append(b.getName()).append(": ").append(deleteTree(b)).append(' ');
        return sb.toString().trim();
    }

    /** Lines from this app's logcat since the test started that concern SELinux, seccomp or native crashes. */
    private static String logcatExcerpt(long startMs) {
        StringBuilder sb = new StringBuilder("== logcat (this app only; SELinux, seccomp, crashes) ==\n");
        String since = String.format(Locale.ROOT, "%d.%03d", startMs / 1000, startMs % 1000);
        List<String> hits = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-T", since).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String l = line.toLowerCase(Locale.ROOT);
                    if (l.contains("avc:") || l.contains("seccomp") || l.contains("sigsys") || l.contains("type=1326")
                            || l.contains("tombstone") || l.contains("fatal signal") || l.contains("wineserver")
                            || l.contains("box64") || l.contains("phantom") || l.contains("killing")) {
                        hits.add(line);
                    }
                }
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            sb.append("logcat not readable: ").append(e).append("\n\n");
            return sb.toString();
        }
        if (hits.isEmpty()) sb.append("(no matching lines)\n");
        int from = Math.max(0, hits.size() - 60);
        for (int i = from; i < hits.size(); i++) sb.append(hits.get(i)).append('\n');
        sb.append('\n');
        return sb.toString();
    }

    private static List<Integer> wineProcesses() {
        List<Integer> pids = new ArrayList<>();
        File[] procs = new File("/proc").listFiles((d, n) -> n.matches("\\d+"));
        if (procs == null) return pids;
        int self = Os.getpid();
        for (File f : procs) {
            int pid = Integer.parseInt(f.getName());
            if (pid == self) continue;
            String c = cmdline(pid);
            if (c.contains("wineserver") || c.contains("/wine/bin/wine") || c.contains("wineboot")) pids.add(pid);
        }
        return pids;
    }

    private static String cmdline(int pid) {
        try {
            byte[] b = Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath());
            return new String(b, StandardCharsets.UTF_8).replace('\0', ' ').trim();
        } catch (Exception e) {
            return "";
        }
    }

    static String describe(File f) {
        try {
            StructStat st = Os.lstat(f.getPath());
            String type = OsConstants.S_ISDIR(st.st_mode) ? "dir" : OsConstants.S_ISSOCK(st.st_mode) ? "socket"
                    : OsConstants.S_ISLNK(st.st_mode) ? "link" : OsConstants.S_ISREG(st.st_mode) ? "file" : "other";
            return String.format(Locale.ROOT, "%s %s mode %04o uid %d size %d", f.getPath(), type,
                    st.st_mode & 07777, st.st_uid, st.st_size);
        } catch (Exception e) {
            return f.getPath() + " (" + e.getMessage() + ")";
        }
    }

    /** Deletes a directory tree without following symbolic links (dosdevices/z: points at /). */
    private static String deleteTree(File dir) {
        try {
            Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            return "ok";
        } catch (Exception e) {
            return "failed (" + e.getMessage() + ")";
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The process watcher's lines, so they stay in the report even when the output is long. */
    private static String watchLines(String out) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : out.split("\n")) {
            if (line.startsWith("[watch")) {
                if (n++ < 200) sb.append(line).append('\n');
            }
        }
        if (n > 200) sb.append("[… ").append(n - 200).append(" more watcher lines]\n");
        return sb.toString();
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

    private static String tailOfFile(File f, int max) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            int n = (int) Math.min(len, max);
            byte[] b = new byte[n];
            raf.seek(len - n);
            raf.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8).replace("\r", "");
            return (len > n ? "[… " + (len - n) + " bytes omitted …]\n" : "") + s.trim();
        } catch (Exception e) {
            return "[log unreadable: " + e.getMessage() + "]";
        }
    }

    private static String headOfFile(File f, int lines) {
        StringBuilder sb = new StringBuilder();
        List<String> all = readLines(f, lines);
        for (String l : all) sb.append(l).append('\n');
        return sb.toString().trim();
    }

    private static List<String> readLines(File f) { return readLines(f, 400); }

    private static List<String> readLines(File f, int max) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while (out.size() < max && (line = r.readLine()) != null) out.add(line);
        } catch (Exception ignored) {
            // unreadable: empty
        }
        return out;
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
