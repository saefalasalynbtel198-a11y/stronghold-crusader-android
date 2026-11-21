package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Directory checks around Wine prefix creation, shared by the Wine tests, the display test and CI.
 *
 * <p>Wine creates only the prefix directory itself: ntdll's setup_config_dir does a single mkdir(WINEPREFIX), ignores
 * its result, and then fails with "chdir to &lt;prefix&gt; : No such file or directory" if the parent is missing. The
 * app's prefix is files/prefix/wine, so files/prefix must exist before Wine starts.
 */
public final class WinePrefixSetup {

    public static final class ParentCheck {
        public final boolean ok;
        public final String report;

        ParentCheck(boolean ok, String report) {
            this.ok = ok;
            this.report = report;
        }
    }

    private WinePrefixSetup() {}

    /**
     * Creates the prefix's parent directories if needed and checks that the app can create the prefix there:
     * the parent is a directory, a probe file can be created and deleted in it, and the prefix path, if it
     * exists, is a directory.
     */
    public static ParentCheck ensureParent(File prefix) {
        StringBuilder r = new StringBuilder();
        File parent = prefix.getAbsoluteFile().getParentFile();
        r.append("prefix ").append(prefix).append(": ").append(state(prefix)).append('\n');
        if (parent == null) return new ParentCheck(false, r.append("no parent directory\n").toString());
        boolean existed = parent.isDirectory();
        boolean created = false;
        if (!existed) created = parent.mkdirs();
        r.append("parent ").append(parent).append(": ")
                .append(existed ? "existed" : created ? "created now (mkdirs)" : "COULD NOT BE CREATED (mkdirs failed)");
        if (!parent.isDirectory()) {
            r.append("; ").append(state(parent)).append('\n');
            return new ParentCheck(false, r.toString());
        }
        File probe = new File(parent, ".shc-prefix-write-test");
        String write;
        boolean writable;
        try (FileOutputStream o = new FileOutputStream(probe)) {
            o.write('x');
            writable = true;
            write = "ok";
        } catch (IOException e) {
            writable = false;
            write = "FAILED (" + e.getMessage() + ")";
        }
        boolean deleted = !probe.exists() || probe.delete();
        r.append("; write test: ").append(write).append(deleted ? "" : " (probe file not deleted)").append('\n');
        boolean prefixOk = !prefix.exists() || prefix.isDirectory();
        if (!prefixOk) r.append("prefix path exists but is not a directory\n");
        return new ParentCheck(writable && prefixOk, r.toString());
    }

    /** A usable prefix: the registry Wine writes on exit and the Windows directory. */
    public static boolean isComplete(File prefix) {
        return new File(prefix, "system.reg").isFile() && new File(prefix, "drive_c/windows").isDirectory();
    }

    /** One line: what is at the path. */
    public static String state(File f) {
        if (!f.exists()) return "does not exist";
        if (f.isDirectory()) {
            String[] list = f.list();
            return "directory (" + (list == null ? "unreadable" : list.length + " entries") + ")";
        }
        return "file (" + f.length() + " bytes)";
    }
}
