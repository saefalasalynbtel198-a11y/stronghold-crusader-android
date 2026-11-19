package io.github.saefalasalynbtel198.shcport.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * Single source of truth for on-device locations.
 *
 * <ul>
 *   <li>Game data (≈1.2 GB) lives in app-specific external storage when available, so it does not
 *       count against the smaller internal partition on older devices.</li>
 *   <li>Runtime, Wine prefix and logs live in internal storage, where symlinks and executable
 *       permissions behave predictably.</li>
 * </ul>
 * Nothing here requires a storage permission.
 */
public final class AppPaths {

    private final File gameRoot;
    private final File internal;

    public AppPaths(Context context) {
        File ext = context.getExternalFilesDir(null);
        File base = ext != null ? ext : context.getFilesDir();
        this.gameRoot = base;
        this.internal = context.getFilesDir();
    }

    public File gameDir() { return new File(gameRoot, "game"); }

    public File gameStagingDir() { return new File(gameRoot, "game.importing"); }

    public File runtimeDir() { return new File(internal, "runtime"); }

    public File prefixDir() { return new File(internal, "prefix/wine"); }

    public File logsDir() { return mkdirs(new File(internal, "logs")); }

    public File crashLog() { return new File(logsDir(), "last-crash.txt"); }

    public File importLog() { return new File(logsDir(), "last-import.txt"); }

    /** Candidate save-game folders, most specific first. */
    public File[] saveDirCandidates() {
        return new File[] {
                new File(prefixDir(), "drive_c/users/steamuser/Documents/Stronghold Crusader/Saves"),
                new File(gameDir(), "saves"),
        };
    }

    public File tmpDir() { return mkdirs(new File(internal, "tmp")); }

    private static File mkdirs(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /** Deletes a directory tree that must lie inside {@code allowedRoot}; never follows symlinks. */
    public static void deleteTree(File target, File allowedRoot) throws IOException {
        String root = allowedRoot.getCanonicalPath() + File.separator;
        String path = target.getCanonicalPath();
        if (!path.startsWith(root)) {
            throw new IOException("Refusing to delete outside app storage: " + path);
        }
        deleteRecursive(target);
    }

    private static void deleteRecursive(File f) throws IOException {
        boolean isLink = java.nio.file.Files.isSymbolicLink(f.toPath());
        if (f.isDirectory() && !isLink) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        if (f.exists() || isLink) {
            if (!f.delete()) throw new IOException("Could not delete " + f);
        }
    }

    public File gameStorageRoot() { return gameRoot; }

    public File internalRoot() { return internal; }
}
