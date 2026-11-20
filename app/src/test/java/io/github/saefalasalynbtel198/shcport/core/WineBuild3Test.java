package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

public class WineBuild3Test {

    /** Minimal ELF64 LE with one PT_LOAD and one PT_INTERP holding {@code interp} padded to {@code room} bytes. */
    static File elf(String interp, int room) throws IOException {
        int phoff = 64, phent = 56, phnum = 2, interpOff = phoff + phent * phnum;
        ByteBuffer b = ByteBuffer.allocate(interpOff + room).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {0x7f, 'E', 'L', 'F', 2, 1, 1});
        b.putLong(0x20, phoff);
        b.putShort(0x36, (short) phent);
        b.putShort(0x38, (short) phnum);
        b.putInt(phoff, 1);                       // PT_LOAD
        b.putInt(phoff + phent, 3);               // PT_INTERP
        b.putLong(phoff + phent + 8, interpOff);  // p_offset
        b.putLong(phoff + phent + 32, room);      // p_filesz
        byte[] s = interp.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < s.length; i++) b.put(interpOff + i, s[i]);
        File f = File.createTempFile("elf-test", ".bin");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f;
    }

    @Test
    public void patchesPlaceholderInterpreter() throws IOException {
        File f = elf(ElfInterp.PLACEHOLDER_PREFIX + "_____________________________________________", 240);
        String loader = "/data/user/0/io.github.saefalasalynbtel198.shcport/files/runtime/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1";
        ElfInterp.patch(f, loader);
        assertEquals(loader, ElfInterp.read(f));
        ElfInterp.patch(f, loader); // idempotent
        assertEquals(loader, ElfInterp.read(f));
    }

    @Test
    public void refusesForeignInterpreterAndTooLongPaths() throws IOException {
        try {
            ElfInterp.patch(elf("/lib/ld-linux-aarch64.so.1", 32), "/x/ld.so");
            fail("expected IOException for non-placeholder interpreter");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("r4"));
        }
        try {
            ElfInterp.patch(elf(ElfInterp.PLACEHOLDER_PREFIX, 40), "/a/very/long/path/that/does/not/fit/into/forty/bytes/ld.so");
            fail("expected IOException for too-long path");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("too long"));
        }
    }

    @Test
    public void rejectsNonElf() throws IOException {
        File f = File.createTempFile("not-elf", ".bin");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[128]);
        }
        try {
            ElfInterp.read(f);
            fail("expected IOException");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void wineTestPlanRunsBox64DirectlyWithoutDisplay() {
        RuntimeLayout rt = new RuntimeLayout(new File("/rt"));
        List<WineTestPlan.Step> s = WineTestPlan.steps(rt, new File("/p/wine"), new File("/t"), new File("/g"), "Stronghold Crusader.exe");
        assertEquals(8, s.size());
        for (WineTestPlan.Step step : s) {
            assertEquals("/rt/bin/box64", step.argv.get(0));        // direct exec, no explicit loader
            assertEquals("", step.env.get("DISPLAY"));
            assertEquals("glibc.pthread.rseq=0", step.env.get("GLIBC_TUNABLES"));
        }
        assertEquals(java.util.Arrays.asList("/rt/bin/box64", "/rt/wine/bin/wine", "wineboot", "--init"), s.get(1).argv);
        assertEquals(WineTestPlan.Check.NONE, s.get(1).postCheck);
        assertTrue(s.get(1).prefixInit);
        assertEquals("ARM64 (Android)", s.get(1).env.get("BOX64_CPUNAME"));
        assertEquals(java.util.Arrays.asList("/rt/bin/box64", "/rt/wine/bin/wineserver", "-w"), s.get(2).argv);
        assertEquals(WineTestPlan.Check.PREFIX_CREATED, s.get(2).postCheck);
        assertEquals(WineTestPlan.Check.WINESERVER_DIR, s.get(3).postCheck);
        assertEquals("/rt/win/shc-pe-smoke-i686.exe", s.get(5).argv.get(2));
        assertTrue(s.get(5).outputOk(42, "...\npe-smoke ok\n"));
        assertTrue(!s.get(5).outputOk(0, "pe-smoke ok"));
        assertTrue(!s.get(5).outputOk(-1, "pe-smoke ok"));
        assertEquals("D:\\Stronghold Crusader.exe", s.get(6).argv.get(2));
        assertTrue(!s.get(6).required);
        assertEquals("-k", s.get(7).argv.get(2));
        // without a launchable game the game step is omitted
        assertEquals(7, WineTestPlan.steps(rt, new File("/p/wine"), new File("/t"), new File("/g"), null).size());
    }

    @Test
    public void wineCommandsRunUnderTheProcessWatcherWhenTheRuntimeHasIt() throws IOException {
        File root = java.nio.file.Files.createTempDirectory("shc-rt").toFile();
        File watch = new File(root, "bin/shc-ptrace-watch");
        assertTrue(watch.getParentFile().mkdirs());
        assertTrue(watch.createNewFile());
        RuntimeLayout rt = new RuntimeLayout(root);
        List<WineTestPlan.Step> s = WineTestPlan.steps(rt, new File("/p/wine"), new File("/t"), new File("/g"), null);
        List<String> init = s.get(1).argv;
        assertEquals(rt.loader().getPath(), init.get(0));            // watcher is a glibc program: via the loader
        assertEquals("--library-path", init.get(1));
        assertEquals(watch.getPath(), init.get(3));
        assertEquals("--", init.get(4));
        assertEquals(java.util.Arrays.asList(rt.box64().getPath(), rt.wine().getPath(), "wineboot", "--init"),
                init.subList(5, init.size()));
        assertEquals(rt.box64().getPath(), s.get(2).argv.get(0));    // wineserver -w is not traced
        assertTrue(s.get(4).argv.contains(watch.getPath()));          // PE smoke tests are traced

        List<WineTestPlan.Step> alt = WineTestPlan.alternativeInit(rt, new File("/p/wine"), new File("/t"));
        assertEquals(2, alt.size());
        assertTrue(alt.get(0).background);
        assertEquals(java.util.Arrays.asList(rt.box64().getPath(), new File(rt.wineBinDir(), "wineserver").getPath(), "-f", "-d1"),
                alt.get(0).argv);
        assertEquals(WineTestPlan.Check.WINESERVER_DIR, alt.get(0).postCheck);
        assertTrue(alt.get(1).prefixInit);
        assertTrue(alt.get(1).argv.contains("wineboot"));
        WineTestPlan.Step server = WineTestPlan.appServer(rt, new File("/p/wine"), new File("/t"), "n", "l.log", "-f", "-p");
        assertTrue(server.background);
        assertEquals("-p", server.argv.get(server.argv.size() - 1));
    }
}
