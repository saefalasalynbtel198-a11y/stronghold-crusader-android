package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the game is started through the step 3.2 display path (BUILD 3 step 3.3): Box64 executed directly (its
 * PT_INTERP is the packaged glibc loader) runs Wine, Wine runs the game from drive D: (a symlink in the prefix to the
 * imported folder) with DISPLAY set to the app's Xvfb, and DirectDraw is cnc-ddraw.
 *
 * <p>Nothing is written into the game folder: cnc-ddraw's ddraw.dll is placed in the prefix's 32-bit system
 * directory (C:\windows\syswow64) and loaded native-only for the game process (WINEDLLOVERRIDES ddraw=n: if it is
 * missing the start fails visibly instead of falling back to Wine's own DirectDraw); its ddraw.ini lives in the
 * prefix (C:\shc-port) and is found through CNC_DDRAW_CONFIG_FILE. This class only computes the plan;
 * {@link GameSetup} puts the files in place and the Android layer starts the process.
 */
public final class WineLaunchPlan {

    /** Windows path of the generated ddraw.ini (inside the prefix). */
    public static final String DDRAW_INI_DOS = "C:\\shc-port\\ddraw.ini";
    public static final String DLL_OVERRIDES = "mscoree,mshtml=d;winemenubuilder.exe=d;ddraw=n";
    public static final String WINEDEBUG = "err+all,fixme-all,+loaddll";

    public final List<String> argv;
    public final Map<String, String> env;
    /** Unix working directory = the game folder, which Wine maps to D:\ (drive D: comes before Z:). */
    public final File workingDirectory;
    /** Symlinks to create inside the Wine prefix before launch: link path → target path. */
    public final Map<String, String> dosDeviceLinks;
    /** Where cnc-ddraw's ddraw.dll goes (prefix, 32-bit system directory) and where it comes from (runtime). */
    public final File ddrawTarget;
    public final File ddrawSource;
    public final File ddrawIni;
    public final File gameDir;
    public final String exeName;

    private WineLaunchPlan(List<String> argv, Map<String, String> env, File workingDirectory, Map<String, String> links,
                           File ddrawTarget, File ddrawSource, File ddrawIni, File gameDir, String exeName) {
        this.argv = Collections.unmodifiableList(argv);
        this.env = Collections.unmodifiableMap(env);
        this.workingDirectory = workingDirectory;
        this.dosDeviceLinks = Collections.unmodifiableMap(links);
        this.ddrawTarget = ddrawTarget;
        this.ddrawSource = ddrawSource;
        this.ddrawIni = ddrawIni;
        this.gameDir = gameDir;
        this.exeName = exeName;
    }

    /**
     * @param exeName plain file name inside the game folder (no path)
     * @param args    extra command-line arguments (none for the game; CI passes the stand-in's mode)
     */
    public static WineLaunchPlan build(RuntimeLayout rt, File prefixDir, File gameDir, String exeName, File tmpDir,
                                       int display, List<String> args) {
        if (exeName.isEmpty() || exeName.contains("/") || exeName.contains("\\") || exeName.contains("..")
                || exeName.contains(":") || !exeName.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
            throw new IllegalArgumentException("exeName must be a plain .exe file name");
        }
        List<String> argv = new ArrayList<>(Arrays.asList(rt.box64().getPath(), rt.wine().getPath(), "D:\\" + exeName));
        argv.addAll(args);

        Map<String, String> env = new LinkedHashMap<>(X11DisplayPlan.wineEnv(rt, prefixDir, tmpDir, display));
        env.put("WINEDLLOVERRIDES", DLL_OVERRIDES);
        env.put("WINEDEBUG", WINEDEBUG);
        env.put("CNC_DDRAW_CONFIG_FILE", DDRAW_INI_DOS);

        Map<String, String> links = new LinkedHashMap<>();
        links.put(new File(prefixDir, "dosdevices/d:").getPath(), gameDir.getPath());

        return new WineLaunchPlan(argv, env, gameDir, links,
                new File(prefixDir, "drive_c/windows/syswow64/ddraw.dll"), rt.cncDdraw(),
                new File(prefixDir, "drive_c/shc-port/ddraw.ini"), gameDir, exeName);
    }

    /** Same environment and DirectDraw setup, for a program outside the game folder (the DirectDraw pre-check). */
    public List<String> argvFor(String unixExe, List<String> args) {
        List<String> a = new ArrayList<>(Arrays.asList(argv.get(0), argv.get(1), unixExe));
        a.addAll(args);
        return a;
    }
}
