package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Wine prefix directory handling and the start order of the display test (BUILD 3 step 3.2). */
public class WinePrefixSetupTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void freshAppDataGetsThePrefixParent() throws Exception {
        File files = tmp.newFolder("files");                 // Android's getFilesDir(): exists, nothing else
        File prefix = new File(files, "prefix/wine");
        WinePrefixSetup.ParentCheck c = WinePrefixSetup.ensureParent(prefix);
        assertTrue(c.report, c.ok);
        assertTrue(new File(files, "prefix").isDirectory());
        assertFalse(prefix.exists());                        // Wine creates the prefix directory itself
        assertTrue(c.report, c.report.contains("does not exist"));
        assertTrue(c.report, c.report.contains("created now (mkdirs)"));
        assertTrue(c.report, c.report.contains("write test: ok"));
        assertFalse(new File(files, "prefix/.shc-prefix-write-test").exists());
    }

    @Test
    public void existingParentAndPrefixAreKept() throws Exception {
        File prefix = tmp.newFolder("files", "prefix", "wine");
        new File(prefix, "keep.txt").createNewFile();
        WinePrefixSetup.ParentCheck c = WinePrefixSetup.ensureParent(prefix);
        assertTrue(c.report, c.ok);
        assertTrue(c.report, c.report.contains("existed"));
        assertTrue(new File(prefix, "keep.txt").isFile());
    }

    @Test
    public void parentThatIsAFileFails() throws Exception {
        File files = tmp.newFolder("files");
        assertTrue(new File(files, "prefix").createNewFile());
        WinePrefixSetup.ParentCheck c = WinePrefixSetup.ensureParent(new File(files, "prefix/wine"));
        assertFalse(c.ok);
        assertTrue(c.report, c.report.contains("COULD NOT BE CREATED"));
    }

    @Test
    public void prefixThatIsAFileFails() throws Exception {
        File parent = tmp.newFolder("files", "prefix");
        try (FileOutputStream o = new FileOutputStream(new File(parent, "wine"))) { o.write(1); }
        WinePrefixSetup.ParentCheck c = WinePrefixSetup.ensureParent(new File(parent, "wine"));
        assertFalse(c.ok);
        assertTrue(c.report, c.report.contains("not a directory"));
    }

    @Test
    public void readOnlyParentFails() throws Exception {
        File parent = tmp.newFolder("files", "prefix");
        assertTrue(parent.setWritable(false, false));
        try {
            Assume.assumeFalse("root ignores directory permissions", new File(parent, "probe").createNewFile());
            WinePrefixSetup.ParentCheck c = WinePrefixSetup.ensureParent(new File(parent, "wine"));
            assertFalse(c.ok);
            assertTrue(c.report, c.report.contains("write test: FAILED"));
        } finally {
            parent.setWritable(true, false);
        }
    }

    @Test
    public void completeMeansRegistryAndWindowsDirectory() throws Exception {
        File prefix = tmp.newFolder("p");
        assertFalse(WinePrefixSetup.isComplete(prefix));
        assertTrue(new File(prefix, "system.reg").createNewFile());
        assertFalse(WinePrefixSetup.isComplete(prefix));     // what 0.3.2-0.3.4 accepted
        assertTrue(new File(prefix, "drive_c/windows").mkdirs());
        assertTrue(WinePrefixSetup.isComplete(prefix));
        assertFalse(WinePrefixSetup.isComplete(new File(prefix, "missing")));
    }

    @Test
    public void displayTestUsesTheWineTestPrefixSteps() {
        RuntimeLayout rt = new RuntimeLayout(new File("/rt"));
        File prefix = new File("/data/files/prefix/wine");
        File t = new File("/data/files/tmp");
        List<WineTestPlan.Step> init = WineTestPlan.prefixInitSteps(rt, prefix, t);
        List<WineTestPlan.Step> all = WineTestPlan.steps(rt, prefix, t, new File("/g"), null);
        assertEquals(2, init.size());
        for (int i = 0; i < init.size(); i++) {               // identical to steps 2 and 3 of the Wine tests
            WineTestPlan.Step a = init.get(i), b = all.get(i + 1);
            assertEquals(b.name, a.name);
            assertEquals(b.argv, a.argv);
            assertEquals(b.env, a.env);
            assertEquals(b.cwd, a.cwd);
            assertEquals(b.expectedExit, a.expectedExit);
            assertEquals(b.postCheck, a.postCheck);
            assertEquals(b.prefixInit, a.prefixInit);
        }
        WineTestPlan.Step boot = init.get(0), wait = init.get(1);
        assertTrue(boot.argv.contains("wineboot") && boot.argv.contains("--init"));
        assertEquals(Integer.valueOf(0), boot.expectedExit);  // wineboot errors are not hidden
        assertEquals(Integer.valueOf(0), wait.expectedExit);
        assertEquals(WineTestPlan.Check.PREFIX_CREATED, wait.postCheck);
        assertEquals(new File("/rt"), boot.cwd);               // explicit, existing working directory
        assertEquals(new File("/rt"), wait.cwd);
        assertEquals(prefix.getPath(), boot.env.get("WINEPREFIX"));
        assertEquals("", boot.env.get("DISPLAY"));
        WineTestPlan.Step stop = WineTestPlan.stopStep(rt, prefix, t);
        WineTestPlan.Step last = all.get(all.size() - 1);
        assertEquals(last.argv, stop.argv);
        assertEquals(last.env, stop.env);
    }

    // ---- start order ---------------------------------------------------------------------------------------

    static final class Recorder implements DisplayStartOrder.Stages {
        final List<String> calls = new ArrayList<>();
        boolean runtimeOk = true, prefixOk = true, xvfbOk = true, programOk = true;

        @Override public boolean runtime() { calls.add("runtime"); return runtimeOk; }
        @Override public boolean prefix() { calls.add("prefix"); return prefixOk; }
        @Override public boolean xvfb() { calls.add("xvfb"); return xvfbOk; }
        @Override public boolean testProgram() { calls.add("program"); return programOk; }
    }

    @Test
    public void xvfbStartsOnlyAfterThePrefix() throws Exception {
        Recorder r = new Recorder();
        assertNull(DisplayStartOrder.run(r));
        assertEquals(List.of("runtime", "prefix", "xvfb", "program"), r.calls);

        r = new Recorder();
        r.prefixOk = false;
        assertEquals(DisplayStartOrder.Stage.PREFIX, DisplayStartOrder.run(r));
        assertEquals(List.of("runtime", "prefix"), r.calls);     // no Xvfb, no Wine program

        r = new Recorder();
        r.runtimeOk = false;
        assertEquals(DisplayStartOrder.Stage.RUNTIME, DisplayStartOrder.run(r));
        assertEquals(List.of("runtime"), r.calls);

        r = new Recorder();
        r.xvfbOk = false;
        assertEquals(DisplayStartOrder.Stage.XVFB, DisplayStartOrder.run(r));
        assertEquals(List.of("runtime", "prefix", "xvfb"), r.calls);
    }
}
