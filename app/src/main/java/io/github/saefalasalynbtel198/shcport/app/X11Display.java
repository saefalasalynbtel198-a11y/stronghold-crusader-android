package io.github.saefalasalynbtel198.shcport.app;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;
import io.github.saefalasalynbtel198.shcport.core.X11DisplayPlan;

/**
 * The runtime's Xvfb as a child of the app (BUILD 3 phase 3). Started from the private runtime directory with the
 * CI-proven flags ({@link X11DisplayPlan}) and -displayfd (no lock file: Android forbids the hard link it needs);
 * ready when Xvfb reports its display number on the -displayfd pipe and this app can connect to its abstract socket
 * (which also shows that SELinux lets app processes talk to it). Blocking: call from a background thread.
 */
final class X11Display {
    final RuntimeLayout rt;
    final File fbDir;
    final File log;
    Process process;
    int display = -1;
    List<String> argv;
    long readyMs = -1;

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
                    .append(") - expected on Android: SELinux never allows apps hard links; Xvfb runs with -displayfd, so it needs none; ");
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
        StringBuilder why = new StringBuilder();
        for (int d = X11DisplayPlan.FIRST_DISPLAY; d <= X11DisplayPlan.LAST_DISPLAY; d++) {
            if (canConnect(d)) {           // another app (or a leftover of ours) already serves this display
                why.append(":").append(d).append(" in use; ");
                continue;
            }
            argv = X11DisplayPlan.launchArgv(X11DisplayPlan.ANDROID_SHELL, rt, d, fbDir);
            ProcessBuilder pb = new ProcessBuilder(argv).directory(X11DisplayPlan.xvfbWorkingDir(rt))
                    .redirectError(ProcessBuilder.Redirect.appendTo(log));   // stdout = the -displayfd pipe
            pb.environment().remove("LD_LIBRARY_PATH");   // the loader gets --library-path; /bin/sh (bionic) needs none
            long t0 = System.currentTimeMillis();
            try {
                process = pb.start();
            } catch (IOException e) {
                return "could not start Xvfb: " + e.getMessage();
            }
            final Process p = process;
            final String[] ready = new String[1];
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    synchronized (ready) { ready[0] = line == null ? "" : line; ready.notifyAll(); }
                } catch (IOException e) {
                    synchronized (ready) { ready[0] = ""; ready.notifyAll(); }
                }
            }, "xvfb-displayfd");
            reader.start();
            long deadline = t0 + timeoutMs;
            synchronized (ready) {
                while (ready[0] == null && System.currentTimeMillis() < deadline) ready.wait(200);
            }
            String line = ready[0];
            int reported = X11DisplayPlan.parseReadyLine(line);
            if (reported == d) {
                readyMs = System.currentTimeMillis() - t0;
                display = d;
                // The X server says it is ready; this app must also be able to connect (SELinux, abstract socket).
                if (!canConnect(d)) {
                    stop();
                    return "Xvfb reported display :" + d + " ready, but this app cannot connect to @"
                            + X11DisplayPlan.abstractSocketName(d);
                }
                return null;
            }
            if (line == null) {          // nothing reported in time
                stop();
                return "Xvfb did not report ready within " + timeoutMs / 1000 + " s";
            }
            try {
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) {
                stop();
                return "Xvfb reported \"" + line + "\" instead of display " + d;
            }
            String tail = logTail(4000);
            int code = p.exitValue();
            process = null;
            if (X11DisplayPlan.classify(tail) == X11DisplayPlan.StartFailure.DISPLAY_IN_USE) {
                why.append(":").append(d).append(" in use (Xvfb could not bind its socket); ");
                continue;
            }
            return "Xvfb exited with code " + code + " (" + io.github.saefalasalynbtel198.shcport.core.RuntimeProbe.describeExit(code) + ")";
        }
        return "no free display between :" + X11DisplayPlan.FIRST_DISPLAY + " and :" + X11DisplayPlan.LAST_DISPLAY + " (" + why + ")";
    }

    boolean isRunning() { return process != null && process.isAlive(); }

    Integer exitCode() { return process != null && !process.isAlive() ? process.exitValue() : null; }

    void stop() {
        Process p = process;
        if (p == null) return;
        p.destroy();   // SIGTERM: Xvfb removes its lock file and framebuffer file
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
