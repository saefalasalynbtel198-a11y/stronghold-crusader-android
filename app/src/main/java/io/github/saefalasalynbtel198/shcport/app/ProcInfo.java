package io.github.saefalasalynbtel198.shcport.app;

import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** This app's own processes from /proc: command lines, CPU time and resident memory (no extra permission). */
final class ProcInfo {
    private ProcInfo() {}

    static final long CLOCK_TICKS = Math.max(1, Os.sysconf(OsConstants._SC_CLK_TCK));
    static final int CPUS = Math.max(1, Runtime.getRuntime().availableProcessors());

    static String cmdline(int pid) {
        try {
            byte[] b = Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath());
            return new String(b, StandardCharsets.UTF_8).replace('\0', ' ').trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** PIDs (other than this process) whose command line contains any of the given strings. */
    static List<Integer> find(String... needles) {
        List<Integer> out = new ArrayList<>();
        File[] procs = new File("/proc").listFiles((d, n) -> n.matches("\\d+"));
        if (procs == null) return out;
        int self = Os.getpid();
        for (File f : procs) {
            int pid = Integer.parseInt(f.getName());
            if (pid == self) continue;
            String c = cmdline(pid);
            for (String n : needles) {
                if (c.contains(n)) { out.add(pid); break; }
            }
        }
        return out;
    }

    /** utime + stime in clock ticks, or -1. */
    static long cpuTicks(int pid) {
        try {
            String s = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), StandardCharsets.UTF_8);
            String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);   // fields 14 and 15 of the full line
        } catch (Exception e) {
            return -1;
        }
    }

    /** VmRSS in KiB, or -1. */
    static long rssKiB(int pid) {
        try {
            for (String line : Files.readAllLines(new File("/proc/" + pid + "/status").toPath())) {
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {
            // process gone
        }
        return -1;
    }

    static void kill(List<Integer> pids) {
        for (int pid : pids) android.os.Process.killProcess(pid);
    }
}
