package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Start Game readiness and the one-session lifecycle (duplicate launch, exit, relaunch, unexpected exit). */
public class GameSessionRulesTest {

    private static GameReadiness.Facts ready() {
        GameReadiness.Facts f = new GameReadiness.Facts();
        f.runtimeInstalled = f.runtimeHasX11 = f.runtimeHasCncDdraw = f.runtimeHasDdrawTest = true;
        f.gameImported = true;
        f.launchExe = "Stronghold Crusader.exe";
        f.prefixComplete = true;
        f.bytesToCopy = 0;
        f.freeInternal = 10L << 30;
        return f;
    }

    @Test
    public void startGameEnabledWhenEverythingIsThere() {
        GameReadiness r = GameReadiness.evaluate(ready());
        assertTrue(r.canStart);
        assertEquals("Ready.", r.reason);
        for (String l : r.checklist) assertTrue(l, l.startsWith("OK"));
    }

    @Test
    public void eachMissingRequirementDisablesStartGame() {
        GameReadiness.Facts f = ready();
        f.runtimeInstalled = false;
        assertBlocked(f, "Install the runtime");
        f = ready();
        f.runtimeHasCncDdraw = false;
        assertBlocked(f, "runtime is too old");
        f = ready();
        f.runtimeHasX11 = false;
        assertBlocked(f, "runtime is too old");
        f = ready();
        f.gameImported = false;
        f.launchExe = null;
        assertBlocked(f, "Import your game folder");
        f = ready();
        f.launchExe = null;
        assertBlocked(f, "No executable");
        f = ready();
        f.bytesToCopy = 900L << 20;
        f.freeInternal = 1000L << 20;                 // 900 MB + 256 MB margin > 1000 MB
        assertBlocked(f, "Not enough internal storage");
        f = ready();
        f.importRunning = true;
        assertBlocked(f, "import");
    }

    @Test
    public void missingPrefixIsOnlyANote() {
        GameReadiness.Facts f = ready();
        f.prefixComplete = false;
        GameReadiness r = GameReadiness.evaluate(f);
        assertTrue(r.canStart);
        assertTrue(String.join("\n", r.checklist), String.join("\n", r.checklist).contains("NOTE    Wine prefix: created on the first start"));
    }

    @Test
    public void unknownSpaceDoesNotBlock() {
        GameReadiness.Facts f = ready();
        f.bytesToCopy = -1;
        f.freeInternal = -1;
        assertTrue(GameReadiness.evaluate(f).canStart);
    }

    @Test
    public void runningSessionMeansReturnToIt() {
        GameReadiness.Facts f = ready();
        f.sessionRunning = true;
        GameReadiness r = GameReadiness.evaluate(f);
        assertTrue(r.canStart);
        assertTrue(r.reason, r.reason.contains("return to it"));
    }

    private static void assertBlocked(GameReadiness.Facts f, String reasonPart) {
        GameReadiness r = GameReadiness.evaluate(f);
        assertFalse(r.reason, r.canStart);
        assertTrue(r.reason, r.reason.toLowerCase(java.util.Locale.ROOT).contains(reasonPart.toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    public void secondStartIsRefusedWhileASessionExists() {
        SessionLifecycle l = new SessionLifecycle();
        assertTrue(l.tryStart());
        assertFalse(l.tryStart());                    // duplicate launch
        l.started(l.generation());
        assertEquals(SessionLifecycle.Phase.RUNNING, l.phase());
        assertFalse(l.tryStart());
        assertTrue(l.active());
    }

    @Test
    public void exitThenRelaunch() {
        SessionLifecycle l = new SessionLifecycle();
        assertTrue(l.tryStart());
        l.started(1);
        assertTrue(l.beginStop());
        assertFalse(l.beginStop());                   // only one caller stops the processes
        assertFalse(l.tryStart());                    // not while stopping
        assertFalse(l.active());
        l.stopped();
        assertTrue(l.tryStart());                     // launch again without restarting anything
        assertEquals(2, l.generation());
    }

    @Test
    public void unexpectedExitEndsTheSessionAndAllowsCleanupAndRestart() {
        SessionLifecycle l = new SessionLifecycle();
        l.tryStart();
        l.started(1);
        l.ended(1, "game EXITED after 30 s, code 0");
        assertEquals(SessionLifecycle.Phase.ENDED, l.phase());
        assertTrue(l.active());                       // the screen shows why it ended
        assertTrue(l.endReason().contains("code 0"));
        assertFalse(l.tryStart());                    // clean up first
        assertTrue(l.beginStop());
        l.stopped();
        assertTrue(l.tryStart());
    }

    @Test
    public void staleEventsOfAnOlderSessionAreIgnored() {
        SessionLifecycle l = new SessionLifecycle();
        l.tryStart();
        l.started(1);
        l.beginStop();
        l.stopped();
        l.tryStart();                                 // generation 2
        l.ended(1, "old");                            // late report of session 1
        l.started(1);
        assertEquals(SessionLifecycle.Phase.STARTING, l.phase());
        l.started(2);
        assertEquals(SessionLifecycle.Phase.RUNNING, l.phase());
    }

    @Test
    public void failedStartEnds() {
        SessionLifecycle l = new SessionLifecycle();
        l.tryStart();
        l.ended(1, "DirectDraw pre-check FAILED");
        assertEquals(SessionLifecycle.Phase.ENDED, l.phase());
        assertTrue(l.beginStop());
        l.stopped();
        assertEquals(SessionLifecycle.Phase.IDLE, l.phase());
    }

    @Test
    public void playModeUsesTheVerifiedLaunchWithoutRelay() {
        WineLaunchPlan p = WineLaunchPlan.build(new RuntimeLayout(new java.io.File("/r")), new java.io.File("/p"),
                new java.io.File("/data/files/game-private"), "Stronghold Crusader.exe", new java.io.File("/t"), 37,
                java.util.Collections.<String>emptyList());
        java.util.Map<String, String> env = p.envWith(WineLaunchPlan.WINEDEBUG_PLAY);
        assertFalse(env.get("WINEDEBUG").contains("relay"));
        assertEquals(WineLaunchPlan.WINEDEBUG_PRECHECK, WineLaunchPlan.WINEDEBUG_PLAY);   // the setting that passed on the phone
        assertTrue(env.get("WINEDLLOVERRIDES").endsWith("ddraw=n"));
        assertEquals("C:\\shc-port\\ddraw.ini", env.get("CNC_DDRAW_CONFIG_FILE"));
        assertEquals(":37", env.get("DISPLAY"));
        assertEquals("D:\\Stronghold Crusader.exe", p.argv.get(2));
        assertEquals("/data/files/game-private", p.workingDirectory.getPath());
    }
}
