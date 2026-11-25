package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the app starts the runtime's X server for the display (BUILD 3 phase 3), with the flags proven in CI
 * (runtime/scripts/test-display-qemu.sh): Xvfb from the runtime's x11/ directory (its working directory: the
 * patched XKB and lock-file paths are relative to it), started through the Android-patched glibc loader;
 * Linux abstract socket only (no /tmp/.X11-unix); no MIT-SHM (System V shared memory is blocked on Android);
 * no GLX; built-in fonts; screen kept in a file (-fbdir) that the app maps and draws.
 *
 * <p>Locking: Xvfb normally locks a display with a lock file it puts in place with a hard link (os/utils.c
 * LockServer: link(".tX37-lock", ".X37-lock")). Android's SELinux policy never lets apps create hard links
 * (app_neverallows.te), so that fails with EACCES on the device ("Linking lock file ... Permission denied").
 * The documented option -displayfd disables the lock file (os/utils.c: nolock = TRUE); with an explicit display
 * number the display is claimed by binding its abstract socket, which the kernel grants to one process only, and
 * which disappears with the process (nothing stale).
 *
 * <p>Readiness: -displayfd 1, i.e. Xvfb's own stdout, which the app reads as a pipe ({@link XvfbStarter}); stderr is
 * kept separate. Xvfb is started directly through the runtime loader, without a shell: an earlier version passed
 * descriptor 3 through "/system/bin/sh -c 'exec 3>&1 ...; exec ...'", but Android's shell (mksh) marks descriptors
 * above 2 opened by "exec" close-on-exec, so Xvfb failed with "Cannot write display number to fd 3". Descriptors 0-2
 * are always passed on across exec (by ProcessBuilder, mksh, the glibc loader and Xvfb alike).
 */
public final class X11DisplayPlan {
    public static final int WIDTH = 1024;
    public static final int HEIGHT = 768;
    public static final int DEPTH = 24;
    /** Abstract sockets are shared by all apps on the device: avoid the common :0 and try a few numbers. */
    public static final int FIRST_DISPLAY = 37;
    public static final int LAST_DISPLAY = 40;

    /** Descriptor Xvfb writes its display number to when ready (-displayfd): its stdout. */
    public static final int DISPLAY_FD = 1;

    /** Why an Xvfb start failed, from its log. */
    public enum StartFailure { DISPLAY_IN_USE, OTHER }

    private X11DisplayPlan() {}

    public static StartFailure classify(String xvfbLog) {
        String l = xvfbLog == null ? "" : xvfbLog;
        if (l.contains("Cannot establish any listening sockets") || l.contains("Server is already active")
                || l.contains("Address already in use")) {
            return StartFailure.DISPLAY_IN_USE;
        }
        return StartFailure.OTHER;
    }

    /** Lock files an Xvfb without -displayfd tried to create (left behind by the failed attempts on Android). */
    public static List<String> legacyLockFiles(int display) {
        return Arrays.asList(".X" + display + "-lock", ".tX" + display + "-lock");
    }

    public static List<String> xvfbArgv(RuntimeLayout rt, int display, File fbDir) {
        return xvfbArgv(rt, display, fbDir, WIDTH, HEIGHT);
    }

    /** As {@link #xvfbArgv(RuntimeLayout, int, File)} with another screen size (Display Resolution setting). */
    public static List<String> xvfbArgv(RuntimeLayout rt, int display, File fbDir, int width, int height) {
        return new ArrayList<>(Arrays.asList(rt.loader().getPath(), "--library-path", rt.nativeLibDir().getPath(),
                rt.xvfb().getPath(), ":" + display,
                "-screen", "0", width + "x" + height + "x" + DEPTH,
                "-fbdir", fbDir.getPath(),
                "-nolisten", "tcp", "-nolisten", "unix",
                "-extension", "MIT-SHM", "-extension", "GLX",
                "-ac", "-fp", "built-ins",
                "-xkbdir", rt.xkbDir().getPath(),
                "-displayfd", String.valueOf(DISPLAY_FD)));
    }

    /** Xvfb must run in x11/: it starts ./xkbcomp and writes ./.X<n>-lock there. */
    public static File xvfbWorkingDir(RuntimeLayout rt) { return rt.x11Dir(); }

    /** Name of the abstract Unix socket (without the leading NUL) that clients connect to for display n. */
    public static String abstractSocketName(int display) { return "/tmp/.X11-unix/X" + display; }

    public static String displayName(int display) { return ":" + display; }

    public static File framebufferFile(File fbDir) { return new File(fbDir, "Xvfb_screen0"); }

    public static List<String> xdpyinfoArgv(RuntimeLayout rt, int display) {
        return new ArrayList<>(Arrays.asList(rt.loader().getPath(), "--library-path", rt.nativeLibDir().getPath(),
                rt.xdpyinfo().getPath(), "-display", displayName(display)));
    }

    /** The Wine environment of the device-verified Wine tests, plus the display. */
    public static Map<String, String> wineEnv(RuntimeLayout rt, File prefixDir, File tmpDir, int display) {
        Map<String, String> env = new LinkedHashMap<>(WineTestPlan.wineEnv(rt, prefixDir, tmpDir));
        env.put("DISPLAY", displayName(display));
        return env;
    }

    public static List<String> testProgramArgv(RuntimeLayout rt, File exe) {
        return new ArrayList<>(Arrays.asList(rt.box64().getPath(), rt.wine().getPath(), exe.getPath()));
    }
}
