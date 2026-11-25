package io.github.saefalasalynbtel198.shcport.app;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;
import io.github.saefalasalynbtel198.shcport.core.X11DisplayPlan;
import io.github.saefalasalynbtel198.shcport.core.XvfbStarter;

/**
 * The runtime's Xvfb as a child of the app (BUILD 3 phase 3). Started directly through the runtime loader (no shell)
 * from the private runtime directory with the CI-proven flags ({@link X11DisplayPlan}) and -displayfd 1 (no lock
 * file: Android forbids the hard link it needs); ready when Xvfb writes its display number to its stdout pipe and
 * this app can connect to its abstract socket ({@link XvfbStarter}). Blocking: call from a background thread.
 */
final class X11Display {
    final RuntimeLayout rt;
    final File fbDir;
    final File log;
    Process process;
    XvfbStarter.ReadyReader stdout;
    List<String> attempts = java.util.Collections.emptyList();
    int display = -1;
    List<String> argv;
    long readyMs = -1;
    /** Screen size (Display Resolution setting in play mode; 1024×768 otherwise). Set before {@link #start}. */
    int width = X11DisplayPlan.WIDTH, height = X11DisplayPlan.HEIGHT;

    X11Display(RuntimeLayout rt, File fbDir, File log) {
        this.rt = rt;
        this.fbDir = fbDir;
        this.log = log;
    }

    File framebufferFile() { return X11DisplayPlan.framebufferFile(fbDir); }

    /**
     * Facts about Xvfb's working directory, checked the way Xvfb uses it: create/write/delete, and a hard link
     * (Xvfb's lock file needs one without -displayfd; Android's SELinux policy forbids hard links for apps).
     */
    String preflight() {
        File dir = X11DisplayPlan.xvfbWorkingDir(rt);
        StringBuilder sb = new StringBuilder();
        try {
            android.system.StructStat st = android.system.Os.stat(dir.getPath());
            sb.append(String.format(java.util.Locale.ROOT, "working directory %s (canonical %s): owner uid %d, mode %04o; app uid %d%n",
                    dir, dir.getCanonicalPath(), st.st_uid, st.st_mode & 07777, android.system.Os.getuid()));
        } catch (Exception e) {
            sb.append("working directory ").append(dir).append(": ").append(e).append('\n');
        }
        File probe = new File(dir, ".shc-write-test");
        File link = new File(dir, ".shc-link-test");
        //noinspection ResultOfMethodCallIgnored
        link.delete();
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(probe)) {
            o.write("test".getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
            sb.append("create/write: ok; ");
        } catch (IOException e) {
            sb.append("create/write: FAILED (").append(e.getMessage()).append("); ");
        }
        try {
            android.system.Os.link(probe.getPath(), link.getPath());   // link(2), as Xvfb's LockServer
            sb.append("hard link: allowed; ");
        } catch (android.system.ErrnoException e) {
            sb.append("hard link: DENIED (").append(android.system.OsConstants.errnoName(e.errno))
                    .append(") - expected on Android: SELinux never allows apps hard links; Xvfb runs with -displayfd 1, so it needs none; ");
        }
        //noinspection ResultOfMethodCallIgnored
        link.delete();
        sb.append(probe.delete() ? "delete: ok" : "delete: FAILED");
        return sb.toString();
    }

    /** Starts Xvfb on the first free display number; returns null on success, otherwise the reason. */
    String start(long timeoutMs) throws InterruptedException {
        if (!rt.hasX11()) return "this runtime has no X server (runtime r6 or newer needed)";
        //noinspection ResultOfMethodCallIgnored
        fbDir.mkdirs();
        removeLegacyLocks();
        XvfbStarter.Result r = XvfbStarter.start(new AndroidHost(), X11DisplayPlan.FIRST_DISPLAY, X11DisplayPlan.LAST_DISPLAY, timeoutMs);
        attempts = r.attempts;
        if (!r.ok()) return r.error;
        process = r.process;
        stdout = r.stdout;
        display = r.display;
        readyMs = r.readyMs;
        return null;
    }

    /** The Android side of {@link XvfbStarter}: ProcessBuilder without a shell, stderr into the session log. */
    private final class AndroidHost implements XvfbStarter.Host {
        private long logOffset;

        @Override public boolean displayAnswers(int d) { return canConnect(d); }

        @Override public Process launch(int d) throws IOException {
            argv = X11DisplayPlan.xvfbArgv(rt, d, fbDir, width, height);
            logOffset = log.length();
            ProcessBuilder pb = new ProcessBuilder(argv).directory(X11DisplayPlan.xvfbWorkingDir(rt))
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)                // stdout: only the display number
                    .redirectError(ProcessBuilder.Redirect.appendTo(log));       // stderr: diagnostics, kept apart
            pb.environment().remove("LD_LIBRARY_PATH");   // the loader gets --library-path
            return pb.start();
        }

        @Override public String errorLog(int d) { return logSince(logOffset, 16000); }

        @Override public void log(String line) { }
    }

    boolean isRunning() { return process != null && process.isAlive(); }

    Integer exitCode() { return process != null && !process.isAlive() ? process.exitValue() : null; }

    /** SIGTERM (Xvfb removes its framebuffer file), SIGKILL after 3 s; true if Xvfb has ended. */
    boolean stop() {
        Process p = process;
        if (p == null) return true;
        try {
            return XvfbStarter.stop(p);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !p.isAlive();
        }
    }

    /** True if a connection to the display's abstract socket succeeds (closed again immediately). */
    static boolean canConnect(int display) {
        try (LocalSocket s = new LocalSocket()) {
            s.connect(new LocalSocketAddress(X11DisplayPlan.abstractSocketName(display), LocalSocketAddress.Namespace.ABSTRACT));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Lock files left by Xvfb starts without -displayfd (they are not used any more). */
    private void removeLegacyLocks() {
        for (int d = X11DisplayPlan.FIRST_DISPLAY; d <= X11DisplayPlan.LAST_DISPLAY; d++) {
            for (String name : X11DisplayPlan.legacyLockFiles(d)) {
                File f = new File(X11DisplayPlan.xvfbWorkingDir(rt), name);
                if (f.exists() && f.delete()) removedLocks.append(name).append(' ');
            }
        }
    }

    final StringBuilder removedLocks = new StringBuilder();

    /** Log text written since the given offset (the current start attempt), at most max bytes from its end. */
    String logSince(long offset, int max) {
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            long len = f.length();
            long from = Math.max(Math.min(offset, len), len - max);
            byte[] b = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    String logTail(int max) {
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            long len = f.length();
            int n = (int) Math.min(len, max);
            byte[] b = new byte[n];
            f.seek(len - n);
            f.readFully(b);
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
