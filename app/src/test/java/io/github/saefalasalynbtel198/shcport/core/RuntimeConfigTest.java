package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.Test;

public class RuntimeConfigTest {

    @Test
    public void launchPlanUsesBox64WineDriveDAndTheDisplay() {
        RuntimeLayout rt = new RuntimeLayout(new File("/data/app/files/runtime"));
        File prefix = new File("/data/app/files/prefix/wine");
        File game = new File("/storage/game");
        WineLaunchPlan p = WineLaunchPlan.build(rt, prefix, game, "Stronghold Crusader.exe", new File("/tmp"), 37,
                java.util.Collections.<String>emptyList());
        assertEquals(java.util.Arrays.asList("/data/app/files/runtime/bin/box64", "/data/app/files/runtime/wine/bin/wine",
                "D:\\Stronghold Crusader.exe"), p.argv);
        assertEquals(":37", p.env.get("DISPLAY"));
        assertEquals(prefix.getPath(), p.env.get("WINEPREFIX"));
        assertEquals("glibc.pthread.rseq=0", p.env.get("GLIBC_TUNABLES"));
        assertEquals("ARM64 (Android)", p.env.get("BOX64_CPUNAME"));     // same as the device-verified Wine tests
        assertTrue(p.env.get("WINEDLLOVERRIDES").endsWith(";ddraw=n"));  // cnc-ddraw only, no silent fallback
        assertEquals("C:\\shc-port\\ddraw.ini", p.env.get("CNC_DDRAW_CONFIG_FILE"));
        assertTrue(p.env.get("WINEDEBUG").contains("+loaddll"));
        assertTrue(p.env.get("WINEDEBUG").contains("+seh"));
        assertTrue(p.env.get("WINEDEBUG").contains("+relay"));
        assertEquals("1", p.env.get("BOX64_SHOWSEGV"));
        // the relay trace is limited to the calls that explain an exit (no slowdown from tracing everything)
        java.util.List<String> reg = p.relayArgv();
        assertEquals(p.argv.subList(0, 2), reg.subList(0, 2));
        assertEquals(java.util.Arrays.asList("reg", "add", "HKCU\\Software\\Wine\\Debug", "/v", "RelayInclude"), reg.subList(2, 7));
        assertTrue(WineLaunchPlan.RELAY_INCLUDE.contains("ExitProcess;"));
        assertTrue(WineLaunchPlan.RELAY_INCLUDE.contains("CreateWindowExA;"));
        assertEquals(game.getPath(), p.dosDeviceLinks.get("/data/app/files/prefix/wine/dosdevices/d:"));
        assertEquals(game, p.workingDirectory);
        // nothing of the port goes into the game folder
        assertEquals(new File(prefix, "drive_c/windows/syswow64/ddraw.dll"), p.ddrawTarget);
        assertEquals(new File(prefix, "drive_c/shc-port/ddraw.ini"), p.ddrawIni);
        assertEquals(rt.cncDdraw(), p.ddrawSource);
        assertEquals(java.util.Arrays.asList("/data/app/files/runtime/bin/box64", "/data/app/files/runtime/wine/bin/wine",
                "/rt/win/x.exe", "ddraw"), p.argvFor("/rt/win/x.exe", java.util.Collections.singletonList("ddraw")));
    }

    @Test
    public void launchPlanRejectsPathTraversal() {
        RuntimeLayout rt = new RuntimeLayout(new File("/r"));
        for (String bad : new String[] {"..\\evil.exe", "a/b.exe", "C:x.exe", "game.bat", ""}) {
            try {
                WineLaunchPlan.build(rt, new File("/p"), new File("/g"), bad, new File("/t"), 37, java.util.Collections.<String>emptyList());
                fail("expected IllegalArgumentException for " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void ddrawIniUsesGdiAndTheStrongholdSections() {
        String ini = new CncDdrawConfig().set("maxfps", "30").toIni();
        assertTrue(ini.contains("[ddraw]\r\n"));
        assertTrue(ini.contains("renderer=gdi\r\n"));      // Xvfb has no OpenGL
        assertTrue(ini.contains("fullscreen=true\r\nwindowed=false\r\n"));
        assertTrue(ini.contains("savesettings=0\r\n"));
        assertTrue(ini.contains("maxfps=30\r\n"));
        assertTrue(ini.contains("[Stronghold Crusader]\r\nresolutions=2\r\nstronghold_hack=true\r\nadjmouse=true\r\n"));
        assertTrue(ini.contains("[Stronghold_Crusader_Extreme]\r\n"));
        assertTrue(!ini.contains("\n\n") || ini.contains("\r\n\r\n"));
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
