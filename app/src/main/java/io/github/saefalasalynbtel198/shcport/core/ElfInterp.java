package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Reads and rewrites the ELF interpreter (PT_INTERP) of a 64-bit little-endian executable in place.
 *
 * <p>Box64 is linked in CI with a long placeholder interpreter path. At install time the app writes the
 * absolute path of the packaged, Android-patched glibc loader into it, so the kernel can execute Box64
 * directly — and every process Box64 re-executes for an x86-64 child (wineserver, wine helpers) also
 * starts on the patched glibc. Only the placeholder (or the same final path) is ever overwritten.
 */
public final class ElfInterp {

    public static final String PLACEHOLDER_PREFIX = "/SHC_PT_INTERP_PLACEHOLDER";
    private static final int PT_INTERP = 3;

    private ElfInterp() {}

    /** Location of the PT_INTERP string: {offset, size including the NUL padding}. */
    static long[] locate(RandomAccessFile f) throws IOException {
        byte[] h = new byte[64];
        f.seek(0);
        f.readFully(h);
        if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') throw new IOException("not an ELF file");
        if (h[4] != 2 || h[5] != 1) throw new IOException("not a 64-bit little-endian ELF file");
        long phoff = u64(h, 0x20);
        int phentsize = PeInspector.u16(h, 0x36);
        int phnum = PeInspector.u16(h, 0x38);
        if (phentsize < 56 || phnum > 128 || phoff + (long) phentsize * phnum > f.length()) {
            throw new IOException("bad program header table");
        }
        byte[] ph = new byte[phentsize];
        for (int i = 0; i < phnum; i++) {
            f.seek(phoff + (long) i * phentsize);
            f.readFully(ph);
            if ((int) PeInspector.u32(ph, 0) == PT_INTERP) {
                long off = u64(ph, 8);
                long size = u64(ph, 32);
                if (size <= 0 || size > 4096 || off + size > f.length()) throw new IOException("bad PT_INTERP");
                return new long[] {off, size};
            }
        }
        throw new IOException("no PT_INTERP segment");
    }

    public static String read(File elf) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(elf, "r")) {
            long[] loc = locate(f);
            byte[] b = new byte[(int) loc[1]];
            f.seek(loc[0]);
            f.readFully(b);
            int n = 0;
            while (n < b.length && b[n] != 0) n++;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        }
    }

    /**
     * Writes {@code interpreter} into PT_INTERP. Allowed only if the current value is the CI placeholder
     * or already equals {@code interpreter}.
     */
    public static void patch(File elf, String interpreter) throws IOException {
        byte[] want = interpreter.getBytes(StandardCharsets.UTF_8);
        if (!interpreter.startsWith("/") || interpreter.indexOf('\0') >= 0) throw new IOException("interpreter must be an absolute path");
        String current = read(elf);
        if (current.equals(interpreter)) return;
        if (!current.startsWith(PLACEHOLDER_PREFIX)) {
            throw new IOException("unexpected ELF interpreter '" + current + "' (runtime package r4 or newer required)");
        }
        try (RandomAccessFile f = new RandomAccessFile(elf, "rw")) {
            long[] loc = locate(f);
            if (want.length + 1 > loc[1]) {
                throw new IOException("interpreter path too long (" + want.length + " bytes, room for " + (loc[1] - 1) + ")");
            }
            byte[] out = new byte[(int) loc[1]];
            System.arraycopy(want, 0, out, 0, want.length);
            f.seek(loc[0]);
            f.write(out);
        }
    }

    private static long u64(byte[] b, int off) {
        return (PeInspector.u32(b, off) & 0xffffffffL) | (PeInspector.u32(b, off + 4) << 32);
    }
}
