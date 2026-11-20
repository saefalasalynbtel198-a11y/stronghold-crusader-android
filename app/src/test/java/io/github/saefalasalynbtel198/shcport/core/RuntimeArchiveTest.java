package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public class RuntimeArchiveTest {

    private static File tempDir() throws IOException {
        File d = Files.createTempDirectory("rt-test").toFile();
        d.deleteOnExit();
        return d;
    }

    /** entries: name, content, name, content, ... (manifest given separately). */
    private static File zip(String manifest, String... entries) throws IOException {
        File z = File.createTempFile("runtime-test", ".zip");
        z.deleteOnExit();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(z))) {
            if (manifest != null) {
                zos.putNextEntry(new ZipEntry(RuntimeArchive.MANIFEST_NAME));
                zos.write(manifest.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            for (int i = 0; i < entries.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entries[i]));
                if (!entries[i].endsWith("/")) zos.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return z;
    }

    private static final String GOOD_MANIFEST = "format\t1\nversion\ttest-1\n"
            + "x\tbin/box64\n"
            + "l\tlib/aarch64-linux-gnu/libc.so\tlibc.so.6\n"
            + "l\tlib/ld-linux-aarch64.so.1\taarch64-linux-gnu/ld-linux-aarch64.so.1\n";

    @Test
    public void extractsFilesSymlinksAndExecBits() throws IOException {
        File z = zip(GOOD_MANIFEST,
                "bin/", "",
                "bin/box64", "ELF",
                "lib/aarch64-linux-gnu/libc.so.6", "libc",
                "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", "ld");
        File dest = new File(tempDir(), "runtime");
        RuntimeArchive.Manifest m = RuntimeArchive.extract(z, dest, null);
        assertEquals("test-1", m.version);
        assertTrue(new File(dest, "bin/box64").canExecute());
        assertTrue(Files.isSymbolicLink(new File(dest, "lib/aarch64-linux-gnu/libc.so").toPath()));
        assertEquals("libc", new String(Files.readAllBytes(new File(dest, "lib/aarch64-linux-gnu/libc.so").toPath()),
                StandardCharsets.UTF_8));
        assertEquals("ld", new String(Files.readAllBytes(new File(dest, "lib/ld-linux-aarch64.so.1").toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsZipSlip() throws IOException {
        expectFailure(zip("format\t1\nversion\tv\n", "../evil", "x"));
        expectFailure(zip("format\t1\nversion\tv\n", "a/../../evil", "x"));
        expectFailure(zip("format\t1\nversion\tv\n", "/abs", "x"));
    }

    @Test
    public void rejectsEscapingOrAbsoluteSymlinks() throws IOException {
        expectFailure(zip("format\t1\nversion\tv\nl\tlib/x\t../../etc/passwd\n"));
        expectFailure(zip("format\t1\nversion\tv\nl\tlib/x\t/system/bin/sh\n"));
    }

    @Test
    public void rejectsMissingManifestOrBadFormat() throws IOException {
        expectFailure(zip(null, "bin/box64", "x"));
        expectFailure(zip("format\t2\nversion\tv\n"));
        expectFailure(zip("version\tv\n"));
        expectFailure(zip("format\t1\nversion\tv\nx\tbin/missing\n"));
    }

    @Test
    public void refusesNonEmptyDestination() throws IOException {
        File dest = tempDir();
        assertTrue(new File(dest, "existing").createNewFile());
        try {
            RuntimeArchive.extract(zip(GOOD_MANIFEST, "bin/box64", "x"), dest, null);
            fail("expected IOException");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void probeStepsRunThroughTheGlibcLoader() {
        RuntimeLayout rt = new RuntimeLayout(new File("/rt"));
        java.util.List<RuntimeProbe.Step> steps = RuntimeProbe.steps(rt, new File("/p/wine"), new File("/t"));
        assertEquals(5, steps.size());
        // 1: loader, required
        assertEquals("/rt/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", steps.get(0).argv.get(0));
        assertTrue(steps.get(0).required);
        // 2: static seccomp probe, executed directly, diagnostic
        assertEquals("/rt/bin/shc-sysprobe", steps.get(1).argv.get(0));
        assertTrue(!steps.get(1).required);
        // 3: Box64 with glibc defaults: diagnostic and WITHOUT the rseq tunable
        assertTrue(!steps.get(2).required);
        assertTrue(!steps.get(2).env.containsKey("GLIBC_TUNABLES"));
        assertEquals("/rt/bin/box64", steps.get(2).argv.get(3));
        // 4: Box64 required, WITH the tunable
        assertTrue(steps.get(3).required);
        assertEquals("glibc.pthread.rseq=0", steps.get(3).env.get("GLIBC_TUNABLES"));
        // 5: Wine required
        assertEquals("/rt/wine/bin/wine", steps.get(4).argv.get(4));
        assertTrue(steps.get(4).required);
        assertTrue(steps.get(4).passed(0, "wine-11.0\n"));
        assertTrue(!steps.get(4).passed(1, "wine-11.0\n"));
        assertTrue(!steps.get(3).passed(159, ""));
    }

    @Test
    public void exitCodesAreDecoded() {
        assertTrue(RuntimeProbe.describeExit(159).contains("signal 31"));
        assertTrue(RuntimeProbe.describeExit(159).contains("SIGSYS"));
        assertTrue(RuntimeProbe.describeExit(139).contains("SIGSEGV"));
        assertEquals("exit code 1", RuntimeProbe.describeExit(1));
        assertEquals("timed out", RuntimeProbe.describeExit(-1));
    }

    private static void expectFailure(File zip) throws IOException {
        File dest = new File(tempDir(), "runtime");
        try {
            RuntimeArchive.extract(zip, dest, null);
            fail("expected IOException for " + zip);
        } catch (IOException expected) {
            // ok
        }
    }
}
