package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** The DirectDraw pre-check verdict (0.3.7 device regression: the +loaddll line was outside the 2 MB that was read). */
public class DdrawPrecheckTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String LOAD_NATIVE =
            "0024:trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\ddraw.dll\" at 10000000: native";
    private static final String PROGRAM = String.join("\n",
            "gui-smoke: mode ddraw, 10 s",
            "gui-smoke: ddraw.dll = C:\\windows\\system32\\ddraw.dll",
            "gui-smoke: SetCooperativeLevel 0x00000000",
            "gui-smoke: SetDisplayMode(1024,768,16) 0x00000000",
            "gui-smoke: CreateSurface(primary) 0x00000000",
            "gui-smoke: ddraw primary written (1024x768, pitch 2048, 16 bpp)",
            "gui-smoke: done, 212 frames written");

    @Test
    public void passesWithNativeLoadProgramReportAndPixels() {
        DdrawPrecheck r = DdrawPrecheck.evaluate(LOAD_NATIVE + "\n" + PROGRAM + "\n", 42, true, "OK: 4 colours");
        assertTrue(r.summary(), r.ok);
        assertTrue(r.nativeLoad);
        assertEquals(212, r.frames);
        assertTrue(r.summary(), r.summary().startsWith("PASS (exit 42; Wine: loaded native (cnc-ddraw)"));
    }

    @Test
    public void findsTheLoadLineAtTheStartOfALogLargerThan2Mb() throws Exception {
        File log = tmp.newFile("precheck.log");
        try (OutputStream o = new FileOutputStream(log)) {
            o.write((LOAD_NATIVE + "\n").getBytes(StandardCharsets.UTF_8));
            byte[] relay = "0024:Call user32.PeekMessageA(0022fe00,00000000,00000000,00000000,00000001) ret=00401234\n"
                    .getBytes(StandardCharsets.UTF_8);
            for (long n = 0; n < 3L * 1024 * 1024; n += relay.length) o.write(relay);   // > 2 MB of relay trace
            o.write((PROGRAM + "\n").getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(log.length() > 3_000_000);
        // the 0.3.7 check: only the last 2 MB
        assertNull(WineLaunchPlan.loadLine(tailOf(log, 2_000_000), "ddraw.dll", true));
        // the fixed check: the whole log
        DdrawPrecheck r = DdrawPrecheck.evaluate(log, 42, true, "OK");
        assertTrue(r.summary(), r.ok);
        assertEquals(1, r.loadLineNumber);
    }

    @Test
    public void builtinLoadFails() {
        String builtin = LOAD_NATIVE.replace(": native", ": builtin");
        DdrawPrecheck r = DdrawPrecheck.evaluate(builtin + "\n" + PROGRAM, 42, true, "OK");
        assertFalse(r.ok);
        assertTrue(r.summary(), r.summary().contains("loaded NOT native"));
    }

    @Test
    public void pixelsAndExitAloneAreNotEnough() {
        DdrawPrecheck r = DdrawPrecheck.evaluate(LOAD_NATIVE + "\ngui-smoke: done, 5 frames written\n", 42, true, "OK");
        assertFalse(r.ok);
        assertTrue(r.summary(), r.summary().contains("CreateSurface(primary) = 0x00000000"));
        assertTrue(r.summary(), r.summary().contains("32-bit system directory"));
        DdrawPrecheck noPixels = DdrawPrecheck.evaluate(LOAD_NATIVE + "\n" + PROGRAM, 42, false, null);
        assertFalse(noPixels.ok);
        DdrawPrecheck noLine = DdrawPrecheck.evaluate(PROGRAM, 42, true, "OK");
        assertFalse(noLine.ok);
        assertTrue(noLine.summary(), noLine.summary().contains("NO +loaddll line for ddraw.dll"));
        DdrawPrecheck badExit = DdrawPrecheck.evaluate(LOAD_NATIVE + "\n" + PROGRAM, 9, true, "OK");
        assertFalse(badExit.ok);
    }

    @Test
    public void programLinesGluedToWineOutputAreStillRead() {
        String glued = PROGRAM.replace("gui-smoke: CreateSurface", "0024:fixme:x:y something gui-smoke: CreateSurface");
        assertTrue(DdrawPrecheck.evaluate(LOAD_NATIVE + "\n" + glued, 42, true, "OK").ok);
    }

    @Test
    public void precheckEnvironmentIsThe036One() {
        WineLaunchPlan p = WineLaunchPlan.build(new RuntimeLayout(new File("/r")), new File("/p"), new File("/g"),
                "Stronghold Crusader.exe", new File("/t"), 37, java.util.Collections.<String>emptyList());
        assertEquals("err+all,fixme-all,+loaddll", p.envWith(WineLaunchPlan.WINEDEBUG_PRECHECK).get("WINEDEBUG"));
        assertFalse(WineLaunchPlan.WINEDEBUG_TOOL.contains("relay"));
        assertFalse(WineLaunchPlan.WINEDEBUG_NO_RELAY.contains("relay"));
        assertEquals(WineLaunchPlan.WINEDEBUG, p.env.get("WINEDEBUG"));                      // game: unchanged
        assertEquals(p.env.get("WINEDLLOVERRIDES"), p.envWith("x").get("WINEDLLOVERRIDES"));  // only WINEDEBUG differs
    }

    private static String tailOf(File f, int max) throws Exception {
        byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
        int n = Math.min(all.length, max);
        return new String(all, all.length - n, n, StandardCharsets.UTF_8);
    }
}
