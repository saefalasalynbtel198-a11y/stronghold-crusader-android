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
 */
public final class X11DisplayPlan {
    public static final int WIDTH = 1024;
    public static final int HEIGHT = 768;
    public static final int DEPTH = 24;
    /** Abstract sockets are shared by all apps on the device: avoid the common :0 and try a few numbers. */
    public static final int FIRST_DISPLAY = 37;
    public static final int LAST_DISPLAY = 40;

    private X11DisplayPlan() {}

    public static List<String> xvfbArgv(RuntimeLayout rt, int display, File fbDir) {
        return new ArrayList<>(Arrays.asList(rt.loader().getPath(), "--library-path", rt.nativeLibDir().getPath(),
                rt.xvfb().getPath(), ":" + display,
                "-screen", "0", WIDTH + "x" + HEIGHT + "x" + DEPTH,
                "-fbdir", fbDir.getPath(),
                "-nolisten", "tcp", "-nolisten", "unix",
                "-extension", "MIT-SHM", "-extension", "GLX",
                "-ac", "-fp", "built-ins",
                "-xkbdir", rt.xkbDir().getPath()));
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
