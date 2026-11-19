package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Checks that an imported folder looks like a complete Stronghold Crusader installation.
 * Windows paths are case-insensitive, so every lookup here is case-insensitive too.
 */
public final class GameDirectoryValidator {

    /** Read-only view of a directory tree; lets the validator run on java.io.File or in tests. */
    public interface Tree {
        /** Names of the direct children of {@code relDir} ("" = root), or an empty list. */
        List<String> list(String relDir);
        boolean isDirectory(String relPath);
    }

    public static final String[] EXECUTABLE_NAMES = {
            "Stronghold Crusader.exe", "Stronghold_Crusader_Extreme.exe"
    };
    static final String[] REQUIRED_DLLS = {"binkw32.dll", "mss32.dll"};
    static final String[][] REQUIRED_ASSET_DIRS = {
            // {directory, extension, minimum count}
            {"gm", ".gm1", "100"},
            {"gfx", ".tgx", "100"},
            {"fx", ".wav", "100"},
            {"maps", ".map", "1"},
    };
    static final String[][] OPTIONAL_ASSET_DIRS = {
            {"fx/music", ".raw", "Music"},
            {"fx/speech", ".wav", "Speech"},
            {"binks", ".bik", "Bink cut-scenes"},
            {"aiv", ".aiv", "AI castle layouts"},
            {"mapsextreme", ".map", "Extreme maps"},
    };

    public static final class Report {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> executables = new ArrayList<>();

        public boolean isValid() { return errors.isEmpty() && !executables.isEmpty(); }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(isValid() ? "VALID" : "INVALID").append('\n');
            sb.append("Executables: ").append(executables).append('\n');
            for (String e : errors) sb.append("ERROR: ").append(e).append('\n');
            for (String w : warnings) sb.append("WARNING: ").append(w).append('\n');
            return sb.toString();
        }
    }

    private GameDirectoryValidator() {}

    public static Report validate(Tree tree) {
        Report r = new Report();
        List<String> root = tree.list("");

        for (String exe : EXECUTABLE_NAMES) {
            String actual = findIgnoreCase(root, exe);
            if (actual != null && !tree.isDirectory(actual)) r.executables.add(actual);
        }
        if (r.executables.isEmpty()) {
            r.errors.add("No game executable found (expected \"Stronghold Crusader.exe\" or "
                    + "\"Stronghold_Crusader_Extreme.exe\" in the selected folder).");
        }
        for (String dll : REQUIRED_DLLS) {
            if (findIgnoreCase(root, dll) == null) r.errors.add("Missing required library: " + dll);
        }
        for (String[] spec : REQUIRED_ASSET_DIRS) {
            String dir = resolveDir(tree, spec[0]);
            int min = Integer.parseInt(spec[2]);
            if (dir == null) {
                r.errors.add("Missing asset folder: " + spec[0]);
                continue;
            }
            int count = countWithExtension(tree.list(dir), spec[1]);
            if (count < min) {
                r.errors.add(String.format(Locale.ROOT, "Folder %s has %d %s files; expected at least %d.",
                        spec[0], count, spec[1], min));
            }
        }
        for (String[] spec : OPTIONAL_ASSET_DIRS) {
            String dir = resolveDir(tree, spec[0]);
            if (dir == null || countWithExtension(tree.list(dir), spec[1]) == 0) {
                r.warnings.add(spec[2] + " not found (" + spec[0] + "); the game may run without them.");
            }
        }
        return r;
    }

    /** Resolves a "/"-separated relative directory path case-insensitively. */
    static String resolveDir(Tree tree, String relPath) {
        String current = "";
        for (String part : relPath.split("/")) {
            String match = findIgnoreCase(tree.list(current), part);
            if (match == null) return null;
            current = current.isEmpty() ? match : current + "/" + match;
            if (!tree.isDirectory(current)) return null;
        }
        return current;
    }

    static String findIgnoreCase(List<String> names, String wanted) {
        for (String n : names) {
            if (n.equalsIgnoreCase(wanted)) return n;
        }
        return null;
    }

    static int countWithExtension(List<String> names, String ext) {
        int c = 0;
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).endsWith(ext)) c++;
        }
        return c;
    }

    /** Tree implementation backed by the real filesystem. */
    public static Tree fileTree(final File root) {
        return new Tree() {
            @Override public List<String> list(String relDir) {
                File dir = relDir.isEmpty() ? root : new File(root, relDir);
                String[] names = dir.list();
                if (names == null) return Collections.emptyList();
                List<String> out = new ArrayList<>(names.length);
                Collections.addAll(out, names);
                return out;
            }

            @Override public boolean isDirectory(String relPath) {
                return new File(root, relPath).isDirectory();
            }
        };
    }
}
