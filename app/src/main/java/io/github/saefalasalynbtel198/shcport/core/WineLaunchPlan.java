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
    /**
     * Diagnostics of the game process: errors, DLL loading, exceptions, and relay traces of the few functions in
     * {@link #RELAY_INCLUDE} (Wine traces only those, set in the prefix's registry by {@link #relayArgv}).
     */
    public static final String WINEDEBUG = "err+all,fixme-all,+loaddll,+seh,+relay";
    /**
     * The DirectDraw pre-check: exactly the 0.3.6 setting that passed on the owner's phone. No +relay: an unfiltered
     * relay trace of the pre-check's message loop pushed the +loaddll line out of the part of the log that was read
     * (0.3.7 device result).
     */
    public static final String WINEDEBUG_PRECHECK = "err+all,fixme-all,+loaddll";
    /** The normal game (Start Game): errors and DLL loading only, as the pre-check that passed on the phone. */
    public static final String WINEDEBUG_PLAY = "err+all,fixme-all,+loaddll";
    /** The game when the relay filter could not be set: no +relay (it would trace every call). */
    public static final String WINEDEBUG_NO_RELAY = "err+all,fixme-all,+loaddll,+seh";
    /** Helper programs such as reg.exe: errors only (they must not run under +relay before the filter exists). */
    public static final String WINEDEBUG_TOOL = "err+all,fixme-all";
    /** Functions traced by +relay: how and why the game ends, its windows, message boxes, files and processes. */
    public static final String RELAY_INCLUDE = "ExitProcess;TerminateProcess;MessageBoxA;MessageBoxW;MessageBoxExA;MessageBoxExW;"
            + "CreateWindowExA;CreateWindowExW;ShowWindow;CreateFileA;CreateFileW;CreateProcessA;CreateProcessW;WinExec;"
            + "ShellExecuteA;CreateMutexA;FindWindowA;GetDriveTypeA;DirectDrawCreate;DirectDrawCreateEx";

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
        env.put("BOX64_SHOWSEGV", "1");           // details of crashes inside the emulated code
        env.put("BOX64_DYNAREC_MISSING", "1");    // x86 instructions Box64 cannot translate

        Map<String, String> links = new LinkedHashMap<>();
        links.put(new File(prefixDir, "dosdevices/d:").getPath(), gameDir.getPath());

        return new WineLaunchPlan(argv, env, gameDir, links,
                new File(prefixDir, "drive_c/windows/syswow64/ddraw.dll"), rt.cncDdraw(),
                new File(prefixDir, "drive_c/shc-port/ddraw.ini"), gameDir, exeName);
    }

    /**
     * The +loaddll line that shows how Wine loaded a DLL ("... Loaded L"C:\\windows\\system32\\ddraw.dll" at
     * 10000000: native"), or null. A 32-bit process sees C:\windows\syswow64 as C:\windows\system32 (WoW64
     * file system redirection), so the path alone does not tell syswow64 from system32; "native" (not "builtin")
     * shows that the file in the prefix, i.e. cnc-ddraw, was loaded and not Wine's own DLL.
     */
    public static String loadLine(String wineLog, String dllName, boolean nativeOnly) {
        if (wineLog == null) return null;
        String needle = ("\\" + dllName + "\"").toLowerCase(java.util.Locale.ROOT);
        for (String l : wineLog.split("\n")) {
            String low = l.toLowerCase(java.util.Locale.ROOT).trim();
            if (!low.contains("loaddll") || !low.contains("loaded l\"") || !low.contains(needle)) continue;
            if (!nativeOnly || low.endsWith(": native")) return l.trim();
        }
        return null;
    }

    /** The same environment with another WINEDEBUG value. */
    public Map<String, String> envWith(String winedebug) {
        Map<String, String> e = new LinkedHashMap<>(env);
        e.put("WINEDEBUG", winedebug);
        return Collections.unmodifiableMap(e);
    }

    /** reg.exe command that limits +relay to {@link #RELAY_INCLUDE} (HKCU\Software\Wine\Debug, RelayInclude). */
    public List<String> relayArgv() {
        return Arrays.asList(argv.get(0), argv.get(1), "reg", "add", "HKCU\\Software\\Wine\\Debug", "/v", "RelayInclude",
                "/t", "REG_SZ", "/d", RELAY_INCLUDE, "/f");
    }

    /** Same environment and DirectDraw setup, for a program outside the game folder (the DirectDraw pre-check). */
    public List<String> argvFor(String unixExe, List<String> args) {
        List<String> a = new ArrayList<>(Arrays.asList(argv.get(0), argv.get(1), unixExe));
        a.addAll(args);
        return a;
    }
}
