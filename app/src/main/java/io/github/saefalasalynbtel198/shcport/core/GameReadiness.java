package io.github.saefalasalynbtel198.shcport.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether the normal Start Game button may be enabled. Every check is one the device-verified launch path needs
 * (runtime r6 with Xvfb, cnc-ddraw and the DirectDraw test program; an imported game with an allowed executable;
 * internal storage for the private copy). The Wine prefix is not required in advance: the launch creates it with
 * the device-verified steps when it is missing (first start then takes longer).
 */
public final class GameReadiness {

    /** Facts gathered by the app (pure data, so the rules can be unit-tested). */
    public static final class Facts {
        public boolean runtimeInstalled, runtimeHasX11, runtimeHasCncDdraw, runtimeHasDdrawTest;
        public boolean gameImported;
        /** Name of the executable the launch would start (null: none allowed / asset check failed). */
        public String launchExe;
        public boolean prefixComplete;
        /** Bytes the private copy still has to copy, and free internal bytes (-1 = unknown). */
        public long bytesToCopy = -1, freeInternal = -1;
        public boolean importRunning;
        public boolean sessionRunning;
    }

    public final boolean canStart;
    /** First blocking reason, or a short "ready" line. */
    public final String reason;
    /** One line per check, "OK"/"MISSING"/"NOTE". */
    public final List<String> checklist;

    private GameReadiness(boolean canStart, String reason, List<String> checklist) {
        this.canStart = canStart;
        this.reason = reason;
        this.checklist = checklist;
    }

    public static GameReadiness evaluate(Facts f) {
        List<String> c = new ArrayList<>();
        String block = null;
        block = check(c, block, f.runtimeInstalled, "Runtime installed", "Install the runtime package (Runtime screen).");
        block = check(c, block, !f.runtimeInstalled || f.runtimeHasX11 && f.runtimeHasCncDdraw && f.runtimeHasDdrawTest,
                "Runtime has the display server, cnc-ddraw and the DirectDraw check (r6 or newer)",
                "This runtime is too old: install runtime r6 or newer.");
        block = check(c, block, f.gameImported, "Game imported", "Import your game folder first.");
        block = check(c, block, !f.gameImported || f.launchExe != null, "Game executable allowed" + (f.launchExe != null ? " (" + f.launchExe + ")" : ""),
                "No executable of the imported folder can be started (see the game details above).");
        boolean space = f.bytesToCopy < 0 || f.freeInternal < 0 || f.bytesToCopy + PrivateGameCopy.SPACE_MARGIN <= f.freeInternal;
        block = check(c, block, space, "Internal storage for the private game copy",
                String.format(Locale.ROOT, "Not enough internal storage: %d MB needed, %d MB free.",
                        (f.bytesToCopy + PrivateGameCopy.SPACE_MARGIN) >> 20, Math.max(0, f.freeInternal) >> 20));
        block = check(c, block, !f.importRunning, "No import running", "Wait until the import has finished.");
        c.add((f.prefixComplete ? "OK      " : "NOTE    ") + "Wine prefix" + (f.prefixComplete ? " ready" : ": created on the first start (takes a few minutes)"));
        if (f.sessionRunning) {
            c.add("NOTE    A game session is running: Start Game returns to it");
            return new GameReadiness(true, "The game is running — tap Start Game to return to it.", c);
        }
        return new GameReadiness(block == null, block == null ? "Ready." : block, c);
    }

    private static String check(List<String> c, String block, boolean ok, String what, String ifMissing) {
        c.add((ok ? "OK      " : "MISSING ") + what);
        return block != null || ok ? block : ifMissing;
    }
}
