package io.github.saefalasalynbtel198.shcport.app;

import java.io.File;

/**
 * Reports whether the Wine/Box64 runtime package is present.
 *
 * <p>BUILD 1 does not bundle the runtime, so {@link #isInstalled()} is false and the launcher says
 * so instead of pretending to start the game. The runtime package layout is defined in
 * runtime/README.md and produced by the runtime-components workflow.
 */
public final class RuntimeStatus {

    private final File runtimeDir;

    public RuntimeStatus(AppPaths paths) { this.runtimeDir = paths.runtimeDir(); }

    public boolean isInstalled() {
        return new File(runtimeDir, "bin/box64").isFile()
                && new File(runtimeDir, "wine/bin/wine").isFile()
                && new File(runtimeDir, "RUNTIME_VERSION").isFile();
    }

    public String describe() {
        if (isInstalled()) return "Runtime present at " + runtimeDir;
        return "Not installed. This development build does not yet include the Wine + Box64 runtime, "
                + "so the game cannot be started from it. Import, validation, save management and the "
                + "touch-control test surface are functional.";
    }
}
