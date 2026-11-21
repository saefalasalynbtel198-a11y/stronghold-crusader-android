package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Record of the imported game folder, taken before and after a launch to show that the port did not change the
 * game's files (BUILD 3 step 3.3): every file with size and modification time, executables and DLLs also with
 * SHA-256. Symbolic links are recorded, not followed.
 */
public final class GameFolderManifest {

    public static final class Entry {
        public final long size;
        public final long mtime;
        /** SHA-256 for .exe/.dll, otherwise null. */
        public final String sha256;

        Entry(long size, long mtime, String sha256) {
            this.size = size;
            this.mtime = mtime;
            this.sha256 = sha256;
        }

        boolean sameAs(Entry o) {
            return size == o.size && mtime == o.mtime && (sha256 == null ? o.sha256 == null : sha256.equals(o.sha256));
        }
    }

    public static final class Diff {
        public final List<String> added = new ArrayList<>();
        public final List<String> removed = new ArrayList<>();
        public final List<String> changed = new ArrayList<>();

        public boolean unchanged() { return added.isEmpty() && removed.isEmpty() && changed.isEmpty(); }

        @Override public String toString() {
            if (unchanged()) return "unchanged";
            return "CHANGED: " + added.size() + " added " + head(added) + ", " + removed.size() + " removed " + head(removed)
                    + ", " + changed.size() + " modified " + head(changed);
        }

        private static String head(List<String> l) {
            if (l.isEmpty()) return "";
            return l.subList(0, Math.min(10, l.size())) + (l.size() > 10 ? " …" : "");
        }
    }

    public final Map<String, Entry> files;
    public final long totalBytes;

    private GameFolderManifest(Map<String, Entry> files, long totalBytes) {
        this.files = Collections.unmodifiableMap(files);
        this.totalBytes = totalBytes;
    }

    public static GameFolderManifest snapshot(File dir) throws IOException {
        if (!dir.isDirectory()) throw new IOException("not a directory: " + dir);
        Map<String, Entry> m = new TreeMap<>();
        long[] total = {0};
        walk(dir, "", m, total);
        return new GameFolderManifest(m, total[0]);
    }

    private static void walk(File dir, String rel, Map<String, Entry> m, long[] total) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) throw new IOException("cannot list " + dir);
        for (File f : children) {
            String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (Files.isSymbolicLink(f.toPath())) {
                m.put(r, new Entry(-1, 0, "link:" + Files.readSymbolicLink(f.toPath())));
            } else if (f.isDirectory()) {
                walk(f, r, m, total);
            } else {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                String hash = lower.endsWith(".exe") || lower.endsWith(".dll") ? GameSetup.sha256(f) : null;
                m.put(r, new Entry(f.length(), f.lastModified(), hash));
                total[0] += f.length();
            }
        }
    }

    public Diff compareTo(GameFolderManifest after) {
        Diff d = new Diff();
        for (Map.Entry<String, Entry> e : files.entrySet()) {
            Entry a = after.files.get(e.getKey());
            if (a == null) d.removed.add(e.getKey());
            else if (!e.getValue().sameAs(a)) d.changed.add(e.getKey());
        }
        for (String k : after.files.keySet()) if (!files.containsKey(k)) d.added.add(k);
        return d;
    }

    public String summary() {
        int hashed = 0;
        for (Entry e : files.values()) if (e.sha256 != null && !e.sha256.startsWith("link:")) hashed++;
        return String.format(Locale.ROOT, "%d files, %.1f MB, %d executables/DLLs hashed", files.size(),
                totalBytes / 1048576.0, hashed);
    }
}
