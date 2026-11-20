package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free reader for the parts of a PE (Windows .exe) header the launcher needs:
 * machine type, section names, the VS_FIXEDFILEINFO file version and content hashes.
 *
 * <p>It never executes or modifies the file. Malformed input raises {@link PeFormatException}.
 */
public final class PeInspector {

    private static final int VS_FFI_SIGNATURE = 0xFEEF04BD;
    private static final int MAX_SECTIONS = 96;

    public static final class PeFormatException extends IOException {
        private static final long serialVersionUID = 1L;

        public PeFormatException(String message) { super(message); }
    }

    private PeInspector() {}

    public static PeInfo inspect(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return inspect(raf);
        }
    }

    public static PeInfo inspect(RandomAccessFile raf) throws IOException {
        long length = raf.length();
        if (length < 0x40) throw new PeFormatException("File too small to be a PE executable");

        byte[] dos = readAt(raf, 0, 0x40);
        if (dos[0] != 'M' || dos[1] != 'Z') throw new PeFormatException("Missing MZ signature");
        long peOffset = u32(dos, 0x3C);
        if (peOffset <= 0 || peOffset + 24 > length) throw new PeFormatException("Invalid e_lfanew");

        byte[] coff = readAt(raf, peOffset, 24);
        if (coff[0] != 'P' || coff[1] != 'E' || coff[2] != 0 || coff[3] != 0) {
            throw new PeFormatException("Missing PE signature");
        }
        int machine = u16(coff, 4);
        int numberOfSections = u16(coff, 6);
        long timeDateStamp = u32(coff, 8);
        int sizeOfOptionalHeader = u16(coff, 20);
        if (numberOfSections > MAX_SECTIONS) throw new PeFormatException("Too many sections");

        long sectionTable = peOffset + 24 + sizeOfOptionalHeader;
        if (sectionTable + 40L * numberOfSections > length) {
            throw new PeFormatException("Section table beyond end of file");
        }
        byte[] table = readAt(raf, sectionTable, 40 * numberOfSections);
        List<String> names = new ArrayList<>();
        int[] version = null;
        Map<String, String> strings = new LinkedHashMap<>();
        for (int i = 0; i < numberOfSections; i++) {
            int base = i * 40;
            String name = sectionName(table, base);
            names.add(name);
            long rawSize = u32(table, base + 16);
            long rawPtr = u32(table, base + 20);
            if (version == null && name.equals(".rsrc") && rawPtr > 0 && rawSize > 0
                    && rawPtr + rawSize <= length && rawSize <= 64L * 1024 * 1024) {
                byte[] rsrc = readAt(raf, rawPtr, (int) rawSize);
                version = findFixedFileVersion(rsrc);
                strings = findVersionStrings(rsrc);
            }
        }

        String[] hashes = hashes(raf);
        return new PeInfo(machine, timeDateStamp, names, version, length, hashes[0], hashes[1], strings);
    }

    /** Scans a resource section for VS_FIXEDFILEINFO and returns {major, minor, build, revision}. */
    static int[] findFixedFileVersion(byte[] rsrc) {
        for (int i = 0; i + 16 <= rsrc.length; i += 4) {
            if ((int) u32(rsrc, i) == VS_FFI_SIGNATURE) {
                long ms = u32(rsrc, i + 8);
                long ls = u32(rsrc, i + 12);
                return new int[] {(int) (ms >>> 16), (int) (ms & 0xFFFF), (int) (ls >>> 16), (int) (ls & 0xFFFF)};
            }
        }
        return null;
    }

    static final String[] VERSION_KEYS = {
            "CompanyName", "FileDescription", "FileVersion", "InternalName", "OriginalFilename",
            "ProductName", "ProductVersion"
    };

    /**
     * Extracts text entries of the version resource. Each entry is a String structure:
     * wLength, wValueLength, wType, UTF-16 key + NUL, padding to 4 bytes, UTF-16 value + NUL.
     * Resource data is DWORD-aligned within the section, so alignment is computed on the section offset.
     */
    static Map<String, String> findVersionStrings(byte[] rsrc) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : VERSION_KEYS) {
            byte[] k = utf16z(key);
            int at = indexOf(rsrc, k, 6);
            if (at < 0) continue;
            int structStart = at - 6;
            int wValueLength = u16(rsrc, structStart + 2);
            int valueAt = (at + k.length + 3) & ~3;
            StringBuilder v = new StringBuilder();
            for (int i = valueAt; i + 1 < rsrc.length && v.length() < 128; i += 2) {
                char c = (char) u16(rsrc, i);
                if (c == 0) break;
                v.append(c);
            }
            if (wValueLength > 0 || v.length() > 0) out.put(key, v.toString().trim());
        }
        return out;
    }

    private static byte[] utf16z(String s) {
        byte[] b = new byte[(s.length() + 1) * 2];
        for (int i = 0; i < s.length(); i++) {
            b[2 * i] = (byte) s.charAt(i);
            b[2 * i + 1] = (byte) (s.charAt(i) >> 8);
        }
        return b;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i + needle.length <= hay.length; i += 2) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static String[] hashes(RandomAccessFile raf) throws IOException {
        MessageDigest sha1;
        MessageDigest sha256;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        raf.seek(0);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = raf.read(buf)) > 0) {
            sha1.update(buf, 0, n);
            sha256.update(buf, 0, n);
        }
        return new String[] {hex(sha1.digest()), hex(sha256.digest())};
    }

    private static byte[] readAt(RandomAccessFile raf, long offset, int size) throws IOException {
        byte[] out = new byte[size];
        raf.seek(offset);
        raf.readFully(out);
        return out;
    }

    private static String sectionName(byte[] b, int off) {
        int len = 0;
        while (len < 8 && b[off + len] != 0) len++;
        return new String(b, off, len, StandardCharsets.US_ASCII);
    }

    static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF)) | ((long) (b[off + 1] & 0xFF)) << 8
                | ((long) (b[off + 2] & 0xFF)) << 16 | ((long) (b[off + 3] & 0xFF)) << 24;
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
