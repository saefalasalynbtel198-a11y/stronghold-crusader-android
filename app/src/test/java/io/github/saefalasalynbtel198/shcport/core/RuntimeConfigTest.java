package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.Test;

public class RuntimeConfigTest {

    @Test
    public void launchPlanUsesBox64WineAndDriveD() {
        File runtime = new File("/data/app/files/runtime");
        File prefix = new File("/data/app/files/prefix/wine");
        File game = new File("/storage/game");
        WineLaunchPlan p = WineLaunchPlan.build(runtime, prefix, game, "Stronghold Crusader.exe", new File("/tmp"));
        assertEquals("/data/app/files/runtime/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", p.argv.get(0));
        assertEquals("--library-path", p.argv.get(1));
        assertEquals("/data/app/files/runtime/lib/aarch64-linux-gnu", p.argv.get(2));
        assertEquals("/data/app/files/runtime/bin/box64", p.argv.get(3));
        assertEquals("/data/app/files/runtime/wine/bin/wine", p.argv.get(4));
        assertEquals("D:\\Stronghold Crusader.exe", p.argv.get(5));
        assertEquals("/data/app/files/runtime/lib/aarch64-linux-gnu", p.env.get("LD_LIBRARY_PATH"));
        assertEquals("glibc.pthread.rseq=0", p.env.get("GLIBC_TUNABLES"));
        assertEquals(prefix.getPath(), p.env.get("WINEPREFIX"));
        assertTrue(p.env.get("WINEDLLOVERRIDES").startsWith("ddraw=n,b"));
        assertEquals(game.getPath(), p.dosDeviceLinks.get("/data/app/files/prefix/wine/dosdevices/d:"));
        assertEquals(game, p.workingDirectory);
    }

    @Test
    public void launchPlanRejectsPathTraversal() {
        try {
            WineLaunchPlan.build(new File("/r"), new File("/p"), new File("/g"), "..\\evil.exe", new File("/t"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void ddrawIniHasSectionAndCrlf() {
        String ini = new CncDdrawConfig().set("maxfps", "30").toIni();
        assertTrue(ini.contains("[ddraw]\r\n"));
        assertTrue(ini.contains("renderer=opengl\r\n"));
        assertTrue(ini.contains("maxfps=30\r\n"));
    }

    @Test
    public void ddrawIniRejectsInjection() {
        try {
            new CncDdrawConfig().set("a", "b\n[x]");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void settingsAreClamped() {
        ControlSettings s = new ControlSettings();
        s.longPressMs = 5;
        s.pinchStep = Float.NaN;
        ControlSettings c = s.sanitized();
        assertEquals(200, c.longPressMs);
        assertEquals(0.05f, c.pinchStep, 0.0001f);
        assertEquals(5, s.longPressMs); // original untouched
    }

    @Test
    public void viewportLetterboxes() {
        ViewportTransform v = new ViewportTransform(800, 600, 1600, 1600);
        assertEquals(2.0f, v.scale, 0.0001f);
        assertEquals(200f, v.offsetY, 0.0001f);
        assertEquals(0, v.toGameY(100));
        assertEquals(599, v.toGameY(1500));
        assertEquals(400, v.toGameX(800));
    }
}
