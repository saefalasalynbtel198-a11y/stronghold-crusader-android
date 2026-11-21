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
 * CI-proven flags ({@link X11DisplayPlan}); ready when this app can connect to its abstract socket (which also
 * shows that SELinux lets app processes talk to it). Blocking: call from a background thread.
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

    /** Starts Xvfb on the first free display number; returns null on success, otherwise the reason. */
    String start(long timeoutMs) throws InterruptedException {
        if (!rt.hasX11()) return "this runtime has no X server (runtime r6 or newer needed)";
        //noinspection ResultOfMethodCallIgnored
        fbDir.mkdirs();
        StringBuilder why = new StringBuilder();
        for (int d = X11DisplayPlan.FIRST_DISPLAY; d <= X11DisplayPlan.LAST_DISPLAY; d++) {
            if (canConnect(d)) {           // another app (or a leftover of ours) already serves this display
                why.append(":").append(d).append(" already in use; ");
                continue;
            }
            removeStaleLock(d);
            argv = X11DisplayPlan.xvfbArgv(rt, d, fbDir);
            ProcessBuilder pb = new ProcessBuilder(argv).directory(X11DisplayPlan.xvfbWorkingDir(rt)).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            pb.environment().remove("LD_LIBRARY_PATH");   // the loader gets --library-path; /bin/sh (bionic) needs none
            long t0 = System.currentTimeMillis();
            try {
                process = pb.start();
            } catch (IOException e) {
                return "could not start Xvfb: " + e.getMessage();
            }
            long deadline = t0 + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (canConnect(d)) {
                    display = d;
                    readyMs = System.currentTimeMillis() - t0;
                    return null;
                }
                if (!process.isAlive()) break;
                Thread.sleep(100);
            }
            if (process.isAlive()) {
                stop();
                return "Xvfb did not open its socket within " + timeoutMs / 1000 + " s";
            }
            String tail = logTail(4000);
            int code = process.exitValue();
            process = null;
            if (tail.contains("already active")) { why.append(":").append(d).append(" already active; "); continue; }
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

    private void removeStaleLock(int d) {
        File lock = new File(X11DisplayPlan.xvfbWorkingDir(rt), ".X" + d + "-lock");
        if (lock.exists() && ProcInfo.find("/x11/Xvfb :" + d).isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            lock.delete();
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
