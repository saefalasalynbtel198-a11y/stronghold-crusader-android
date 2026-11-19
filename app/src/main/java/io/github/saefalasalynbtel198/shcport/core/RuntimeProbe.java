package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The BUILD 2 on-device self-test: a fixed sequence of commands that proves, step by step, that the
 * runtime can execute on this device. Each step only runs if the previous one succeeded.
 *
 * <ol>
 *   <li>glibc loader runs at all (checks exec permission and Android's seccomp filter vs. glibc start-up)</li>
 *   <li>Box64 starts and reports its version (dynarec initialisation)</li>
 *   <li>Box64 runs the x86-64 Wine loader and Wine reports its version</li>
 * </ol>
 */
public final class RuntimeProbe {

    public static final class Step {
        public final String name;
        /** Text the output must contain for the step to pass. */
        public final String expectedOutput;
        public final List<String> argv;
        public final Map<String, String> env;
        public final long timeoutMs;

        Step(String name, String expectedOutput, List<String> argv, Map<String, String> env, long timeoutMs) {
            this.name = name;
            this.expectedOutput = expectedOutput;
            this.argv = Collections.unmodifiableList(argv);
            this.env = Collections.unmodifiableMap(env);
            this.timeoutMs = timeoutMs;
        }

        public boolean passed(int exitCode, String output) {
            return exitCode == 0 && output.contains(expectedOutput);
        }
    }

    private RuntimeProbe() {}

    public static List<Step> steps(RuntimeLayout rt, File prefixDir, File tmpDir) {
        Map<String, String> env = baseEnv(rt, prefixDir, tmpDir);
        String ld = rt.loader().getPath();
        String lib = rt.nativeLibDir().getPath();
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("glibc loader", "ld.so",
                Arrays.asList(ld, "--version"), env, 15_000));
        steps.add(new Step("Box64", "Box64",
                Arrays.asList(ld, "--library-path", lib, rt.box64().getPath(), "--version"), env, 30_000));
        steps.add(new Step("Wine (x86-64 WoW64 via Box64)", "wine-",
                Arrays.asList(ld, "--library-path", lib, rt.box64().getPath(), rt.wine().getPath(), "--version"),
                env, 120_000));
        return steps;
    }

    /** Environment shared by the probe and the game launch. */
    public static Map<String, String> baseEnv(RuntimeLayout rt, File prefixDir, File tmpDir) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LD_LIBRARY_PATH", rt.nativeLibDir().getPath());
        env.put("BOX64_LD_LIBRARY_PATH", rt.x64LibDir().getPath());
        env.put("BOX64_PATH", rt.wineBinDir().getPath());
        env.put("BOX64_LOG", "1");
        env.put("BOX64_NOBANNER", "0");
        env.put("WINEPREFIX", prefixDir.getPath());
        env.put("WINEDEBUG", "-all");
        env.put("HOME", prefixDir.getParentFile() == null ? prefixDir.getPath() : prefixDir.getParent());
        env.put("TMPDIR", tmpDir.getPath());
        env.put("LC_ALL", "C");
        return env;
    }
}
