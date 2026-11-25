package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/** BUILD 3 step 3.3: game setup inside the prefix only, game folder manifest, frame probe. */
public class GameLaunchTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File runtime, prefix, game;

    private WineLaunchPlan plan() throws Exception {
        runtime = tmp.newFolder("runtime");
        new File(runtime, "cnc-ddraw").mkdirs();
        Files.write(new File(runtime, "cnc-ddraw/ddraw.dll").toPath(), "MZ cnc-ddraw stand-in".getBytes(StandardCharsets.US_ASCII));
        prefix = tmp.newFolder("files", "prefix", "wine");
        new File(prefix, "dosdevices").mkdirs();
        new File(prefix, "drive_c/windows/syswow64").mkdirs();
        Files.write(new File(prefix, "drive_c/windows/syswow64/ddraw.dll").toPath(), "wine builtin".getBytes(StandardCharsets.US_ASCII));
        game = tmp.newFolder("game");
        Files.write(new File(game, "Stronghold Crusader.exe").toPath(), "MZ game".getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(game, "binkw32.dll").toPath(), "MZ bink".getBytes(StandardCharsets.US_ASCII));
        new File(game, "gm").mkdirs();
        Files.write(new File(game, "gm/body.gm1").toPath(), new byte[1234]);
        return WineLaunchPlan.build(new RuntimeLayout(runtime), prefix, game, "Stronghold Crusader.exe",
                new File(tmp.getRoot(), "tmp"), 37, Collections.<String>emptyList());
    }

    @Test
    public void setupWritesOnlyIntoThePrefix() throws Exception {
        WineLaunchPlan p = plan();
        GameFolderManifest before = GameFolderManifest.snapshot(game);
        GameSetup.Result r = GameSetup.prepare(p, new CncDdrawConfig());
        assertTrue(r.report, r.ok);
        File d = new File(prefix, "dosdevices/d:");
        assertTrue(Files.isSymbolicLink(d.toPath()));
        assertEquals(game.toPath(), Files.readSymbolicLink(d.toPath()));
        assertEquals(GameSetup.sha256(new File(runtime, "cnc-ddraw/ddraw.dll")), GameSetup.sha256(p.ddrawTarget));
        String ini = new String(Files.readAllBytes(p.ddrawIni.toPath()), StandardCharsets.US_ASCII);
        assertTrue(ini.contains("renderer=gdi"));
        assertTrue(GameFolderManifest.snapshot(game).files.keySet().equals(before.files.keySet()));
        assertTrue(before.compareTo(GameFolderManifest.snapshot(game)).unchanged());
        // repeated setup (second launch) works and still leaves the game folder alone
        assertTrue(GameSetup.prepare(p, new CncDdrawConfig()).ok);
        assertTrue(before.compareTo(GameFolderManifest.snapshot(game)).unchanged());
    }

    @Test
    public void setupFailsWithoutSyswow64OrDll() throws Exception {
        WineLaunchPlan p = plan();
        Files.delete(new File(runtime, "cnc-ddraw/ddraw.dll").toPath());
        GameSetup.Result r = GameSetup.prepare(p, new CncDdrawConfig());
        assertFalse(r.ok);
        assertTrue(r.report, r.report.contains("cnc-ddraw ddraw.dll: FAILED"));
    }

    @Test
    public void setupRefusesToReplaceARealDirectoryAsDriveD() throws Exception {
        WineLaunchPlan p = plan();
        assertTrue(new File(prefix, "dosdevices/d:").mkdirs());
        GameSetup.Result r = GameSetup.prepare(p, new CncDdrawConfig());
        assertFalse(r.ok);
        assertTrue(r.report, r.report.contains("not a symbolic link"));
    }

    @Test
    public void setupReportsAMissingExecutable() throws Exception {
        WineLaunchPlan p = plan();
        Files.delete(new File(game, "Stronghold Crusader.exe").toPath());
        assertFalse(GameSetup.prepare(p, new CncDdrawConfig()).ok);
    }

    @Test
    public void manifestDetectsAddedChangedAndRemovedFiles() throws Exception {
        plan();
        GameFolderManifest before = GameFolderManifest.snapshot(game);
        assertEquals(3, before.files.size());
        assertTrue(before.files.get("Stronghold Crusader.exe").sha256 != null);
        assertEquals(null, before.files.get("gm/body.gm1").sha256);
        assertTrue(before.summary(), before.summary().startsWith("3 files"));

        Files.write(new File(game, "Stronghold Crusader.exe").toPath(), "MZ gamf".getBytes(StandardCharsets.US_ASCII)); // same size
        Files.write(new File(game, "ddraw.ini").toPath(), "x".getBytes(StandardCharsets.US_ASCII));
        Files.delete(new File(game, "gm/body.gm1").toPath());
        GameFolderManifest.Diff d = before.compareTo(GameFolderManifest.snapshot(game));
        assertFalse(d.unchanged());
        assertEquals(Collections.singletonList("ddraw.ini"), d.added);
        assertEquals(Collections.singletonList("gm/body.gm1"), d.removed);
        assertEquals(Collections.singletonList("Stronghold Crusader.exe"), d.changed);  // hash, not only size
        assertTrue(d.toString(), d.toString().startsWith("CHANGED"));
    }

    @Test
    public void frameProbeTellsBlackStillAndChangingApart() {
        int w = 64, h = 48;
        int[] black = new int[w * h];
        int[] picture = new int[w * h];
        for (int i = 0; i < picture.length; i++) picture[i] = (i % w) < w / 2 ? 0x8040c0 : 0x000000;
        int[] moved = picture.clone();
        moved[5 * w + 40] = 0xffffff;
        FrameProbe a = probe(w, h, black), b = probe(w, h, picture), c = probe(w, h, moved);
        assertEquals(0.0, a.nonBlack, 1e-9);
        assertEquals(0.5, b.nonBlack, 0.02);
        assertEquals(2, b.distinctColours);
        assertFalse(b.differsFrom(probe(w, h, picture)));
        assertTrue(c.differsFrom(b));
        assertNotEquals(a.signature, b.signature);
    }

    private static FrameProbe probe(int w, int h, int[] px) {
        ByteBuffer file = DisplayCoreTest.xwd(w, h, w * 4, px);   // stride in bytes
        return FrameProbe.sample(XwdFramebuffer.parse(file), file);
    }

    @Test
    public void ddrawLoadIsRecognisedFromWinesLoaddllTrace() {
        String log = "0130:trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\dwmapi.dll\" at 7B1E0000: builtin\n"
                + "gui-smoke: ddraw.dll = C:\\windows\\system32\\ddraw.dll\n"
                + "0130:trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\ddraw.dll\" at 10000000: native\n";
        String l = WineLaunchPlan.loadLine(log, "ddraw.dll", true);
        assertTrue(l, l != null && l.endsWith(": native"));
        String builtin = log.replace(": native", ": builtin");
        assertEquals(null, WineLaunchPlan.loadLine(builtin, "ddraw.dll", true));      // Wine's own ddraw: not cnc-ddraw
        assertTrue(WineLaunchPlan.loadLine(builtin, "ddraw.dll", false).endsWith(": builtin"));
        assertEquals(null, WineLaunchPlan.loadLine("Loaded L\"C:\\\\x\\\\myddraw.dll\" at 1: native", "ddraw.dll", true));
        assertEquals(null, WineLaunchPlan.loadLine(null, "ddraw.dll", true));
    }
}
