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

    /** True once the game can actually be launched; false until the display server lands in BUILD 3. */
    public boolean canLaunchGame() { return false; }

    public String describe() {
        if (isInstalled()) {
            return "Runtime package installed at " + layout.root + ". Use Runtime → Run self-test to check this "
                    + "device. Starting the game needs the display server, which arrives in BUILD 3.";
        }
        return "Not installed. Open Runtime → Install runtime package… and select shc-runtime-*.zip "
                + "(from the \"Runtime components\" GitHub Actions run).";
    }
}
