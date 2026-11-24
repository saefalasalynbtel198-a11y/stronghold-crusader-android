package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Private game copy (noexec fix) and the exit analysis of the game's Wine log. */
public class PrivateGameCopyTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File source() throws Exception {
        File src = tmp.newFolder("external", "game");
        Files.write(new File(src, "Stronghold Crusader.exe").toPath(), "MZ game code".getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(src, "binkw32.dll").toPath(), "MZ bink".getBytes(StandardCharsets.US_ASCII));
        new File(src, "gm").mkdirs();
        byte[] big = new byte[3 * 1024 * 1024 + 17];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        Files.write(new File(src, "gm/body.gm1").toPath(), big);
        return src;
    }

    @Test
    public void copiesEverythingVerifiedAndLeavesTheSourceAlone() throws Exception {
        File src = source(), dst = new File(tmp.getRoot(), "internal/game-private");
        GameFolderManifest before = GameFolderManifest.snapshot(src);
        PrivateGameCopy.Result r = PrivateGameCopy.sync(src, dst, -1, null);
        assertTrue(r.error, r.ok);
        assertEquals(3, r.files);
        assertEquals(3, r.copied);
        for (String f : new String[] {"Stronghold Crusader.exe", "binkw32.dll", "gm/body.gm1"}) {
            assertEquals(f, PrivateGameCopy.sha256(new File(src, f)), PrivateGameCopy.sha256(new File(dst, f)));
        }
        assertTrue(new File(dst, PrivateGameCopy.MANIFEST).isFile());
        assertTrue(before.compareTo(GameFolderManifest.snapshot(src)).unchanged());   // source only read
        assertFalse(new File(dst, "gm/body.gm1.shc-partial").exists());
    }

    @Test
    public void secondRunReusesTheCopyAndReverifiesExecutables() throws Exception {
        File src = source(), dst = new File(tmp.getRoot(), "copy");
        assertTrue(PrivateGameCopy.sync(src, dst, -1, null).ok);
        PrivateGameCopy.Result r = PrivateGameCopy.sync(src, dst, -1, null);
        assertTrue(r.ok);
        assertEquals(0, r.copied);
        assertEquals(3, r.reused);
        assertEquals(2, r.executablesVerified);
        assertEquals(0, PrivateGameCopy.bytesToCopy(src, dst));
    }

    @Test
    public void damagedExecutableInTheCopyIsReplaced() throws Exception {
        File src = source(), dst = new File(tmp.getRoot(), "copy");
        assertTrue(PrivateGameCopy.sync(src, dst, -1, null).ok);
        Files.write(new File(dst, "binkw32.dll").toPath(), "MZ bunk".getBytes(StandardCharsets.US_ASCII));   // same size
        PrivateGameCopy.Result r = PrivateGameCopy.sync(src, dst, -1, null);
        assertTrue(r.ok);
        assertEquals(1, r.copied);
        assertEquals(PrivateGameCopy.sha256(new File(src, "binkw32.dll")), PrivateGameCopy.sha256(new File(dst, "binkw32.dll")));
    }

    @Test
    public void changedSourceFileIsCopiedAgainAndGameWrittenFilesAreKept() throws Exception {
        File src = source(), dst = new File(tmp.getRoot(), "copy");
        assertTrue(PrivateGameCopy.sync(src, dst, -1, null).ok);
        Files.write(new File(dst, "stronghold_crusader.cfg").toPath(), "written by the game".getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(src, "gm/body.gm1").toPath(), "new content".getBytes(StandardCharsets.US_ASCII));
        PrivateGameCopy.Result r = PrivateGameCopy.sync(src, dst, -1, null);
        assertTrue(r.ok);
        assertEquals(1, r.copied);
        assertEquals(1, r.extraInCopy);
        assertTrue(new File(dst, "stronghold_crusader.cfg").isFile());
        assertEquals("new content", new String(Files.readAllBytes(new File(dst, "gm/body.gm1").toPath()), StandardCharsets.US_ASCII));
    }

    @Test
    public void notEnoughSpaceFailsBeforeCopying() throws Exception {
        File src = source(), dst = new File(tmp.getRoot(), "copy");
        PrivateGameCopy.Result r = PrivateGameCopy.sync(src, dst, 1024, null);
        assertFalse(r.ok);
        assertTrue(r.error, r.error.startsWith("not enough internal storage"));
        assertFalse(new File(dst, "gm/body.gm1").exists());
    }

    @Test
    public void missingSourceFails() {
        PrivateGameCopy.Result r = PrivateGameCopy.sync(new File(tmp.getRoot(), "none"), new File(tmp.getRoot(), "copy"), -1, null);
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("imported game folder missing"));
    }

    @Test
    public void executableExtensions() {
        assertTrue(PrivateGameCopy.isExecutable("Stronghold Crusader.EXE"));
        assertTrue(PrivateGameCopy.isExecutable("mss32.dll"));
        assertTrue(PrivateGameCopy.isExecutable("miles/mssds3d.m3d"));
        assertTrue(PrivateGameCopy.isExecutable("x/a.asi"));
        assertFalse(PrivateGameCopy.isExecutable("gm/body.gm1"));
    }

    // ---- exit analysis -----------------------------------------------------------------------------------------

    @Test
    public void exitAnalysisReadsRelaySehAndNoexecLines() {
        String log = String.join("\n",
                "0138:trace:loaddll:build_module Loaded L\"D:\\\\Stronghold Crusader.exe\" at 00400000: native",
                "0138:err:virtual:map_image_into_view failed to set 60000020 protection on L\"\\\\??\\\\D:\\\\x.exe\" section .text, noexec filesystem?",
                "0138:Call KERNEL32.CreateFileA(0019f8a0 \"D:\\\\data\\\\missing.dat\",80000000,00000001,00000000,00000003,00000080,00000000) ret=0045a1b2",
                "0138:Ret  KERNEL32.CreateFileA() retval=ffffffff ret=0045a1b2",
                "0138:Call KERNEL32.CreateFileA(0019f8a0 \"D:\\\\gm\\\\body.gm1\",80000000,00000001,00000000,00000003,00000080,00000000) ret=0045a1b2",
                "0138:Call user32.CreateWindowExA(00000000,004c1234 \"FFwinClass\",004c1240 \"Crusader\",80000000,00000000,00000000,00000400,00000300,00000000,00000000,00400000,00000000) ret=00401234",
                "0138:Ret  KERNEL32.CreateFileA() retval=000000a4 ret=0045a1b2",
                "0138:Call user32.MessageBoxA(00010020,004c2000 \"Unable to initialise the display\",004c2100 \"Stronghold Crusader\",00000010) ret=00402345",
                "0138:trace:seh:dispatch_exception code=c0000005 (EXCEPTION_ACCESS_VIOLATION) flags=0 addr=0x401000",
                "0138:Call KERNEL32.ExitProcess(00000000) ret=00403456");
        GameExitAnalysis a = GameExitAnalysis.parse(log);
        assertTrue(a.relayActive);
        assertEquals(1, a.noexecSections);
        assertEquals(1, a.windowsCreated);
        assertEquals(List.of("\"D:\\\\data\\\\missing.dat\""), a.failedOpens);
        assertEquals(1, a.messageBoxes.size());
        assertTrue(a.messageBoxes.get(0), a.messageBoxes.get(0).contains("Unable to initialise the display"));
        assertEquals(1, a.exits.size());
        assertTrue(a.exits.get(0), a.exits.get(0).contains("ExitProcess(00000000) called from 00403456"));
        assertEquals(1, a.exceptions.size());
        String all = String.join("\n", a.lines());
        assertTrue(all, all.contains("Windows created: 1"));
        assertTrue(all, all.contains("noexec storage): 1"));
    }

    @Test
    public void exitAnalysisSaysSoWhenThereIsNoRelayTrace() {
        GameExitAnalysis a = GameExitAnalysis.parse("0138:trace:loaddll:build_module Loaded L\"x\" at 1: native\n");
        assertFalse(a.relayActive);
        assertTrue(a.lines().get(1).startsWith("Relay trace: none"));
        assertFalse(GameExitAnalysis.parse(null).relayActive);
    }
}
