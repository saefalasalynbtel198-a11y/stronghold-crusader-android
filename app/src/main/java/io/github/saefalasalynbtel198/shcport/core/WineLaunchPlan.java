package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes how the game process will be started inside the runtime: the packaged glibc loader
 * starts Box64, Box64 executes the x86-64 Wine WoW64 build, the imported game folder is drive D:,
 * and cnc-ddraw replaces DirectDraw. This class only computes the plan; starting processes is the
 * Android layer's job.
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

    public static WineLaunchPlan build(File runtimeRoot, File prefixDir, File gameDir, String exeName, File tmpDir) {
        if (exeName.contains("/") || exeName.contains("\\") || exeName.contains("..")) {
            throw new IllegalArgumentException("exeName must be a plain file name");
        }
        RuntimeLayout rt = new RuntimeLayout(runtimeRoot);

        List<String> argv = new ArrayList<>();
        argv.add(rt.loader().getPath());
        argv.add("--library-path");
        argv.add(rt.nativeLibDir().getPath());
        argv.add(rt.box64().getPath());
        argv.add(rt.wine().getPath());
        argv.add("D:\\" + exeName);

        Map<String, String> env = RuntimeProbe.baseEnv(rt, prefixDir, tmpDir);
        env.put("WINEARCH", "win64");
        // cnc-ddraw is copied into the game folder as ddraw.dll; prefer it over Wine's builtin DirectDraw.
        env.put("WINEDLLOVERRIDES", "ddraw=n,b;winemenubuilder.exe=d;mscoree,mshtml=d");
        env.put("BOX64_NOBANNER", "1");

        Map<String, String> links = new LinkedHashMap<>();
        links.put(new File(prefixDir, "dosdevices/d:").getPath(), gameDir.getPath());

        return new WineLaunchPlan(argv, env, gameDir, links);
    }
}
