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
        assertEquals(3, steps.size());
        assertEquals("/rt/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", steps.get(1).argv.get(0));
        assertEquals("/rt/bin/box64", steps.get(1).argv.get(3));
        assertEquals("/rt/wine/bin/wine", steps.get(2).argv.get(4));
        assertTrue(steps.get(2).passed(0, "wine-11.0\n"));
        assertTrue(!steps.get(2).passed(1, "wine-11.0\n"));
        assertTrue(!steps.get(1).passed(0, "Segmentation fault"));
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
