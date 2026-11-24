package io.github.saefalasalynbtel198.shcport.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Private copy of the imported game in the app's internal storage (BUILD 3 step 3.3 fix).
 *
 * <p>Why: Android mounts shared/external storage (/storage/emulated/..., also the app's own Android/data folder)
 * without execute permission. Wine maps each PE section of a program from its file; for the executable sections it
 * then asks for execute permission, which fails there ("map_image_into_view failed to set 60000020 protection ...
 * noexec filesystem?" on the device for the game's .exe, binkw32.dll and mss32.dll, never for programs in internal
 * storage). Box64 then does not record those pages as executable and refuses to translate them (it falls back to its
 * interpreter). Internal app storage allows execute mappings (the runtime runs from there).
 *
 * <p>The imported folder is only read. Every file is copied, its SHA-256 computed while reading the source and
 * again from the written copy, and both must match. A manifest (".shc-private-copy.tsv" in the copy) records size,
 * modification time and hash of each source file, so later launches copy only what changed and re-verify
 * executables and DLLs every time. Files the game itself creates in the copy are kept, never deleted.
 */
public final class PrivateGameCopy {

    public static final String MANIFEST = ".shc-private-copy.tsv";
    /** Free space kept on internal storage in addition to the copy. */
    public static final long SPACE_MARGIN = 256L << 20;

    public interface Progress {
        void update(String message);
    }

    public static final class Result {
        public boolean ok;
        public String error;
        public int files, copied, reused, extraInCopy;
        public long bytes, bytesCopied, millis;
        public int executablesVerified;

        public String summary() {
            return String.format(Locale.ROOT, "%d files (%.1f MB): %d copied (%.1f MB), %d unchanged since the last copy; "
                            + "SHA-256 of every copied file verified, %d executables/DLLs re-verified; %d extra files in the copy kept; %.1f s",
                    files, bytes / 1048576.0, copied, bytesCopied / 1048576.0, reused, executablesVerified, extraInCopy, millis / 1000.0);
        }
    }

    private static final class Entry {
        final long size, mtime;
        final String sha256;

        Entry(long size, long mtime, String sha256) {
            this.size = size;
            this.mtime = mtime;
            this.sha256 = sha256;
        }
    }

    private PrivateGameCopy() {}

    /** Bytes that a sync would copy (files new or changed since the manifest). */
    public static long bytesToCopy(File source, File target) throws IOException {
        Map<String, Entry> m = readManifest(target);
        long[] n = {0};
        walk(source, "", (rel, f) -> {
            Entry e = m.get(rel);
            File t = new File(target, rel);
            if (e == null || e.size != f.length() || e.mtime != f.lastModified() || !t.isFile() || t.length() != f.length()) n[0] += f.length();
        });
        return n[0];
    }

    /**
     * Makes target an exact, verified copy of source (extra files in target are kept).
     *
     * @param availableBytes free bytes on the target's storage (checked before copying), or -1 to skip the check
     */
    public static Result sync(File source, File target, long availableBytes, Progress progress) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        try {
            if (!source.isDirectory()) throw new IOException("imported game folder missing: " + source);
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create " + target);
            long need = bytesToCopy(source, target);
            if (availableBytes >= 0 && need + SPACE_MARGIN > availableBytes) {
                throw new IOException(String.format(Locale.ROOT, "not enough internal storage: the private copy needs %.0f MB more (+%d MB margin), %.0f MB free",
                        need / 1048576.0, SPACE_MARGIN >> 20, availableBytes / 1048576.0));
            }
            Map<String, Entry> old = readManifest(target);
            Map<String, Entry> now = new TreeMap<>();
            long[] done = {0};
            IOException[] failure = {null};
            walk(source, "", (rel, f) -> {
                if (failure[0] != null) return;
                try {
                    r.files++;
                    r.bytes += f.length();
                    File t = new File(target, rel);
                    Entry e = old.get(rel);
                    boolean same = e != null && e.size == f.length() && e.mtime == f.lastModified() && t.isFile() && t.length() == f.length();
                    if (same && isExecutable(rel)) {
                        if (!sha256(t).equals(e.sha256)) same = false;   // executables: re-verify every launch
                        else r.executablesVerified++;
                    }
                    if (same) {
                        r.reused++;
                        now.put(rel, e);
                        return;
                    }
                    if (progress != null) progress.update(String.format(Locale.ROOT, "Copying the game into private storage: %.0f / %.0f MB (%s)",
                            done[0] / 1048576.0, need / 1048576.0, rel));
                    String sha = copyVerified(f, t);
                    r.copied++;
                    r.bytesCopied += f.length();
                    done[0] += f.length();
                    now.put(rel, new Entry(f.length(), f.lastModified(), sha));
                } catch (IOException ex) {
                    failure[0] = new IOException(rel + ": " + ex.getMessage(), ex);
                }
            });
            if (failure[0] != null) throw failure[0];
            writeManifest(target, now);
            int[] extra = {0};
            walk(target, "", (rel, f) -> { if (!rel.equals(MANIFEST) && !now.containsKey(rel)) extra[0]++; });
            r.extraInCopy = extra[0];
            r.ok = true;
        } catch (IOException e) {
            r.error = e.getMessage();
        }
        r.millis = System.currentTimeMillis() - t0;
        return r;
    }

    /** Copies via a temporary file; SHA-256 of the source while reading and of the written file afterwards must match. */
    static String copyVerified(File src, File dst) throws IOException {
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, dst.getName() + ".shc-partial");
        MessageDigest md = digest();
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        }
        String want = hex(md.digest());
        String got = sha256(tmp);
        if (!want.equals(got) || tmp.length() != src.length()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("verification failed after copying (SHA-256 " + got + " instead of " + want + ")");
        }
        Files.move(tmp.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return want;
    }

    /** PE files that Wine maps with executable sections (by extension; checked again on every launch). */
    public static boolean isExecutable(String rel) {
        String l = rel.toLowerCase(Locale.ROOT);
        return l.endsWith(".exe") || l.endsWith(".dll") || l.endsWith(".asi") || l.endsWith(".m3d") || l.endsWith(".flt")
                || l.endsWith(".ax") || l.endsWith(".acm") || l.endsWith(".drv") || l.endsWith(".ocx");
    }

    interface Visitor {
        void file(String rel, File f);
    }

    private static void walk(File dir, String rel, Visitor v) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) throw new IOException("cannot list " + dir);
        java.util.Arrays.sort(children);
        for (File f : children) {
            String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (rel.isEmpty() && f.getName().equals(MANIFEST)) continue;
            if (f.getName().endsWith(".shc-partial")) continue;
            if (Files.isSymbolicLink(f.toPath())) continue;   // not followed
            if (f.isDirectory()) walk(f, r, v);
            else if (f.isFile()) v.file(r, f);
        }
    }

    private static Map<String, Entry> readManifest(File target) {
        Map<String, Entry> m = new TreeMap<>();
        File f = new File(target, MANIFEST);
        if (!f.isFile()) return m;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length == 4) m.put(p[0], new Entry(Long.parseLong(p[1]), Long.parseLong(p[2]), p[3]));
            }
        } catch (IOException | NumberFormatException e) {
            m.clear();   // unreadable manifest: everything is copied and verified again
        }
        return m;
    }

    private static void writeManifest(File target, Map<String, Entry> m) throws IOException {
        File tmp = new File(target, MANIFEST + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, Entry> e : m.entrySet()) {
                w.write(e.getKey() + "\t" + e.getValue().size + "\t" + e.getValue().mtime + "\t" + e.getValue().sha256 + "\n");
            }
        }
        Files.move(tmp.toPath(), new File(target, MANIFEST).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static String sha256(File f) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
