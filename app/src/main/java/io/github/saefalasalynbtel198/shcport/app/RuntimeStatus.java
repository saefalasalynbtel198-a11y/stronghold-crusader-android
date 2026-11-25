package io.github.saefalasalynbtel198.shcport.app;

import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;

/**
 * Reports whether the Wine/Box64 runtime package is installed. Installing it and running the
 * on-device self-test happens on the Runtime screen (BUILD 2). Starting the game needs BUILD 3.
 */
public final class RuntimeStatus {

    private final RuntimeLayout layout;

    public RuntimeStatus(AppPaths paths) { this.layout = new RuntimeLayout(paths.runtimeDir()); }

    public boolean isInstalled() { return layout.looksInstalled(); }

    /** True if the runtime has what the game launch needs: Xvfb, cnc-ddraw and the DirectDraw test program (r6+). */
    public boolean canLaunchGame() { return isInstalled() && layout.hasX11() && layout.cncDdraw().isFile() && layout.guiSmoke().isFile(); }

    public String describe() {
        if (isInstalled()) {
            return "Runtime package installed at " + layout.root + (canLaunchGame() ? " (display server, cnc-ddraw: present)."
                    : ". This runtime cannot start the game (no display server or cnc-ddraw): install runtime r6 or newer.");
        }
        return "Not installed. Open Runtime → Install runtime package… and select shc-runtime-*.zip "
                + "(from the \"Runtime components\" GitHub Actions run).";
    }
}
