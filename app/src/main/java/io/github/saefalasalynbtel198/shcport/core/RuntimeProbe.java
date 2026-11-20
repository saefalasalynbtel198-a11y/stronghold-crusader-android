package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The on-device runtime self-test: a fixed sequence of commands that shows, step by step, whether
 * the runtime can execute on this device, and if not, why.
 *
 * <ol>
 *   <li>glibc loader runs (exec permission; the loader's --version path exits before full start-up)</li>
 *   <li>seccomp probe (static, no glibc): lists syscalls Android's app filter blocks — diagnostic</li>
 *   <li>glibc test program on the Android-patched glibc: start-up, threads, fork, faccessat — required</li>
 *   <li>Box64 without GLIBC_TUNABLES — diagnostic (the patched glibc must not need it)</li>
 *   <li>Box64 in the runtime's real configuration — required</li>
 *   <li>Box64 runs the x86-64 Wine loader and Wine reports its version — required</li>
 * </ol>
 *
 * Diagnostic steps never stop the sequence; required steps do.
 */
public final class RuntimeProbe {

    /**
     * glibc ≥ 2.35 registers restartable sequences (the rseq syscall) during start-up. Android's app
     * seccomp filter does not allow rseq. The packaged glibc is patched to default this off
     * (runtime/glibc-android); the tunable is kept as a second guard.
     */
    public static final String GLIBC_TUNABLES = "glibc.pthread.rseq=0";

    public static final class Step {
        public final String name;
        /** Text the output must contain for the step to pass. */
        public final String expectedOutput;
        public final List<String> argv;
        public final Map<String, String> env;
        public final long timeoutMs;
        /** Required steps stop the sequence on failure; diagnostic steps only report. */
        public final boolean required;

        Step(String name, String expectedOutput, List<String> argv, Map<String, String> env, long timeoutMs, boolean required) {
            this.name = name;
            this.expectedOutput = expectedOutput;
            this.argv = Collections.unmodifiableList(argv);
            this.env = Collections.unmodifiableMap(env);
            this.timeoutMs = timeoutMs;
            this.required = required;
        }

        public boolean passed(int exitCode, String output) {
            return exitCode == 0 && output.contains(expectedOutput);
        }
    }

    private RuntimeProbe() {}

    public static List<Step> steps(RuntimeLayout rt, File prefixDir, File tmpDir) {
        Map<String, String> env = baseEnv(rt, prefixDir, tmpDir);
        Map<String, String> glibcDefaults = new LinkedHashMap<>(env);
        glibcDefaults.remove("GLIBC_TUNABLES");
        String ld = rt.loader().getPath();
        String lib = rt.nativeLibDir().getPath();
        String box64 = rt.box64().getPath();
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("glibc loader", "ld.so",
                Arrays.asList(ld, "--version"), env, 15_000, true));
        steps.add(new Step("Seccomp syscall probe (diagnostic)", "probe done",
                Arrays.asList(rt.sysprobe().getPath()), env, 15_000, false));
        steps.add(new Step("glibc start-up, threads, fork (Android-patched glibc)", "glibctest ok",
                Arrays.asList(ld, "--library-path", lib, rt.glibctest().getPath(), tmpDir.getPath()), env, 30_000, true));
        steps.add(new Step("Box64 without GLIBC_TUNABLES (diagnostic)", "Box64",
                Arrays.asList(ld, "--library-path", lib, box64, "--version"), glibcDefaults, 30_000, false));
        steps.add(new Step("Box64 (rseq registration disabled)", "Box64",
                Arrays.asList(ld, "--library-path", lib, box64, "--version"), env, 30_000, true));
        steps.add(new Step("Wine (x86-64 WoW64 via Box64)", "wine-",
                Arrays.asList(ld, "--library-path", lib, box64, rt.wine().getPath(), "--version"), env, 120_000, true));
        return steps;
    }

    /** Environment shared by the probe and the game launch. */
    public static Map<String, String> baseEnv(RuntimeLayout rt, File prefixDir, File tmpDir) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GLIBC_TUNABLES", GLIBC_TUNABLES);
        env.put("LD_LIBRARY_PATH", rt.nativeLibDir().getPath());
        env.put("BOX64_LD_LIBRARY_PATH", rt.x64LibDir().getPath());
        env.put("BOX64_PATH", rt.wineBinDir().getPath());
        env.put("BOX64_LOG", "1");
        env.put("BOX64_NOBANNER", "0");
        // Box64 otherwise runs "lscpu | sed" through /system/bin/sh at every start (and fails: no lscpu on
        // Android). The value is only the CPU brand string reported to x86 programs.
        env.put("BOX64_CPUNAME", "ARM64 (Android)");
        env.put("WINEPREFIX", prefixDir.getPath());
        env.put("WINEDEBUG", "-all");
        env.put("HOME", prefixDir.getParentFile() == null ? prefixDir.getPath() : prefixDir.getParent());
        env.put("TMPDIR", tmpDir.getPath());
        env.put("LC_ALL", "C");
        return env;
    }

    /**
     * Explains a process exit code. Java on Linux/Android reports a child killed by signal N as
     * exit code 128 + N.
     */
    public static String describeExit(int code) {
        if (code == -1) return "timed out";
        if (code == -2) return "could not be started";
        if (code > 128 && code <= 128 + 64) {
            int sig = code - 128;
            return String.format(Locale.ROOT, "killed by signal %d (%s)", sig, signalName(sig));
        }
        return "exit code " + code;
    }

    static String signalName(int sig) {
        switch (sig) {
            case 4: return "SIGILL: illegal instruction";
            case 6: return "SIGABRT: aborted";
            case 7: return "SIGBUS: bus error";
            case 9: return "SIGKILL: killed";
            case 11: return "SIGSEGV: segmentation fault";
            case 31: return "SIGSYS: system call blocked, e.g. by Android's seccomp filter";
            default: return "see signal(7)";
        }
    }
}
