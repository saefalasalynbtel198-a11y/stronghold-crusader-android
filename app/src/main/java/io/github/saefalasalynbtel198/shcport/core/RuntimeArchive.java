package io.github.saefalasalynbtel198.shcport.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Safe extractor for the runtime package (a plain zip produced by runtime/scripts/package_runtime.py).
 *
 * <p>Zip cannot carry Unix symlinks and permission bits portably, so the package lists them in a
 * text manifest at the archive root:
 *
 * <pre>
 * format\t1
 * version\t&lt;runtime version&gt;
 * x\t&lt;relative path&gt;                 executable file
 * l\t&lt;relative path&gt;\t&lt;relative target&gt;  symbolic link
 * </pre>
 *
 * Every path is validated: no absolute paths, no ".." components, nothing may resolve outside the
 * destination directory (zip-slip and symlink escapes are rejected). Files are written before any
 * symlink exists, so nothing can be written through a link.
 */
public final class RuntimeArchive {

    public static final String MANIFEST_NAME = "SHC_RUNTIME_MANIFEST";
    private static final long MAX_TOTAL_BYTES = 6L << 30;

    public interface Progress {
        void onFile(String path, long bytesWritten);
    }

    public static final class Manifest {
        public final String version;
        public final List<String> executables;
        public final Map<String, String> symlinks;

        Manifest(String version, List<String> executables, Map<String, String> symlinks) {
            this.version = version;
            this.executables = Collections.unmodifiableList(executables);
            this.symlinks = Collections.unmodifiableMap(symlinks);
        }

        public static Manifest parse(String text) throws IOException {
            String version = null;
            boolean formatSeen = false;
            List<String> exes = new ArrayList<>();
            Map<String, String> links = new LinkedHashMap<>();
            BufferedReader r = new BufferedReader(new StringReader(text));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                switch (f[0]) {
                    case "format":
                        if (f.length != 2 || !f[1].equals("1")) throw bad(n, "unsupported format");
                        formatSeen = true;
                        break;
                    case "version":
                        if (f.length != 2 || f[1].isEmpty()) throw bad(n, "bad version");
                        version = f[1];
                        break;
                    case "x":
                        if (f.length != 2) throw bad(n, "bad executable entry");
                        exes.add(checkRelative(f[1]));
                        break;
                    case "l":
                        if (f.length != 3) throw bad(n, "bad symlink entry");
                        links.put(checkRelative(f[1]), checkLinkTarget(f[1], f[2]));
                        break;
                    default:
                        throw bad(n, "unknown record '" + f[0] + "'");
                }
            }
            if (!formatSeen) throw new IOException("Manifest has no format line");
            if (version == null) throw new IOException("Manifest has no version line");
            return new Manifest(version, exes, links);
        }

        private static IOException bad(int line, String why) {
            return new IOException("Manifest line " + line + ": " + why);
        }
    }

    private RuntimeArchive() {}

    /** Extracts {@code zip} into the empty or non-existent directory {@code dest}. */
    public static Manifest extract(File zip, File dest, Progress progress) throws IOException {
        if (dest.exists()) {
            String[] existing = dest.list();
            if (existing == null || existing.length > 0) throw new IOException("Destination is not empty: " + dest);
        } else if (!dest.mkdirs()) {
            throw new IOException("Cannot create " + dest);
        }
        File root = dest.getCanonicalFile();

        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry me = zf.getEntry(MANIFEST_NAME);
            if (me == null) throw new IOException("Not a runtime package: " + MANIFEST_NAME + " missing");
            Manifest manifest;
            try (InputStream in = zf.getInputStream(me)) {
                manifest = Manifest.parse(new String(readAll(in, 4 << 20), StandardCharsets.UTF_8));
            }

            long total = 0;
            byte[] buf = new byte[1 << 16];
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name.equals(MANIFEST_NAME)) continue;
                boolean isDir = name.endsWith("/");
                String rel = checkRelative(isDir ? name.substring(0, name.length() - 1) : name);
                if (manifest.symlinks.containsKey(rel)) throw new IOException("Entry is also declared as symlink: " + rel);
                File out = inside(root, rel);
                if (isDir) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create " + out);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
                try (InputStream in = zf.getInputStream(e); OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_TOTAL_BYTES) throw new IOException("Runtime package is unreasonably large");
                        os.write(buf, 0, n);
                    }
                }
                if (progress != null) progress.onFile(rel, total);
            }

            for (Map.Entry<String, String> l : manifest.symlinks.entrySet()) {
                File link = inside(root, l.getKey());
                File parent = link.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
                Files.createSymbolicLink(link.toPath(), Paths.get(l.getValue()));
            }
            for (String x : manifest.executables) {
                File f = inside(root, x);
                if (!f.isFile()) throw new IOException("Executable listed but missing: " + x);
                if (!f.setExecutable(true, true)) throw new IOException("Cannot mark executable: " + x);
            }
            return manifest;
        }
    }

    /** Validates a relative, '/'-separated path without empty, "." or ".." components. */
    static String checkRelative(String p) throws IOException {
        if (p == null || p.isEmpty() || p.startsWith("/") || p.indexOf('\\') >= 0 || p.indexOf('\0') >= 0) {
            throw new IOException("Unsafe path: " + p);
        }
        for (String part : p.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe path: " + p);
        }
        return p;
    }

    /** A link target must be relative and resolve, from the link's directory, to a path inside the root. */
    static String checkLinkTarget(String linkPath, String target) throws IOException {
        if (target.isEmpty() || target.startsWith("/") || target.indexOf('\\') >= 0 || target.indexOf('\0') >= 0) {
            throw new IOException("Unsafe symlink target: " + linkPath + " -> " + target);
        }
        Path base = Paths.get("/root").resolve(linkPath).getParent();
        Path resolved = base.resolve(target).normalize();
        if (!resolved.startsWith(Paths.get("/root"))) {
            throw new IOException("Symlink escapes runtime root: " + linkPath + " -> " + target);
        }
        return target;
    }

    private static File inside(File root, String rel) throws IOException {
        File f = new File(root, rel);
        String rootPath = root.getPath() + File.separator;
        // Normalise lexically: parents were created by us and are never symlinks at this point.
        String p = f.toPath().normalize().toString();
        if (!p.startsWith(rootPath)) throw new IOException("Path escapes destination: " + rel);
        return f;
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            bos.write(b, 0, n);
            if (bos.size() > limit) throw new IOException("Manifest too large");
        }
        return bos.toByteArray();
    }

    /** Reads the manifest only (for showing the version before installing). */
    public static Manifest readManifest(File zip) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry me = zf.getEntry(MANIFEST_NAME);
            if (me == null) throw new IOException("Not a runtime package: " + MANIFEST_NAME + " missing");
            try (InputStream in = zf.getInputStream(me)) {
                return Manifest.parse(new String(readAll(in, 4 << 20), StandardCharsets.UTF_8));
            }
        }
    }
}
