package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes how the game process will be started inside the runtime: Box64 executing an x86-64
 * Wine WoW64 build, with the imported game folder mapped to drive D: and cnc-ddraw replacing
 * DirectDraw. This class only computes the plan; starting processes is the Android layer's job.
 *
 * <p>Status: the runtime package is not bundled in the current development build, so this plan
 * is exercised by unit tests only. See docs/ARCHITECTURE.md.
 */
public final class WineLaunchPlan {

    public final List<String> argv;
    public final Map<String, String> env;
    public final File workingDirectory;
    /** Symlinks to create inside the Wine prefix before launch: link path → target path. */
    public final Map<String, String> dosDeviceLinks;

    private WineLaunchPlan(List<String> argv, Map<String, String> env, File workingDirectory,
                           Map<String, String> links) {
        this.argv = Collections.unmodifiableList(argv);
        this.env = Collections.unmodifiableMap(env);
        this.workingDirectory = workingDirectory;
        this.dosDeviceLinks = Collections.unmodifiableMap(links);
    }

    /**
     * @param runtimeRoot extracted runtime package (contains box64 and wine/)
     * @param prefixDir   Wine prefix owned by the app
     * @param gameDir     imported game folder
     * @param exeName     executable file name inside gameDir
     * @param tmpDir      writable temp directory
     */
    public static WineLaunchPlan build(File runtimeRoot, File prefixDir, File gameDir, String exeName, File tmpDir) {
        if (exeName.contains("/") || exeName.contains("\\") || exeName.contains("..")) {
            throw new IllegalArgumentException("exeName must be a plain file name");
        }
        File box64 = new File(runtimeRoot, "bin/box64");
        File wine = new File(runtimeRoot, "wine/bin/wine");

        List<String> argv = new ArrayList<>();
        argv.add(box64.getPath());
        argv.add(wine.getPath());
        argv.add("D:\\" + exeName);

        Map<String, String> env = new LinkedHashMap<>();
        env.put("WINEPREFIX", prefixDir.getPath());
        env.put("WINEARCH", "win64");
        // cnc-ddraw ships as ddraw.dll in the game folder; prefer it over Wine's builtin DirectDraw.
        env.put("WINEDLLOVERRIDES", "ddraw=n,b;winemenubuilder.exe=d;mscoree,mshtml=d");
        env.put("WINEDEBUG", "-all");
        env.put("BOX64_LOG", "1");
        env.put("BOX64_NOBANNER", "1");
        env.put("BOX64_LD_LIBRARY_PATH", new File(runtimeRoot, "lib/x86_64-linux-gnu").getPath());
        env.put("TMPDIR", tmpDir.getPath());
        env.put("HOME", prefixDir.getParentFile() == null ? prefixDir.getPath() : prefixDir.getParent());
        env.put("LC_ALL", "en_US.UTF-8");

        Map<String, String> links = new LinkedHashMap<>();
        links.put(new File(prefixDir, "dosdevices/d:").getPath(), gameDir.getPath());

        return new WineLaunchPlan(argv, env, gameDir, links);
    }
}
