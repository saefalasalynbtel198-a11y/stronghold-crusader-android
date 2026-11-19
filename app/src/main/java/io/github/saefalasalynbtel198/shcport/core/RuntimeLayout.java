package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;

/**
 * Paths inside an installed runtime package (see runtime/README.md).
 *
 * <p>Box64 is a glibc program, and Android has no /lib/ld-linux-aarch64.so.1, so every runtime
 * command is started through the packaged glibc dynamic loader:
 * {@code ld-linux-aarch64.so.1 --library-path <lib> box64 ...}.
 */
public final class RuntimeLayout {

    public final File root;

    public RuntimeLayout(File root) { this.root = root; }

    public File loader() { return new File(root, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"); }
    public File nativeLibDir() { return new File(root, "lib/aarch64-linux-gnu"); }
    public File x64LibDir() { return new File(root, "lib/x86_64-linux-gnu"); }
    public File box64() { return new File(root, "bin/box64"); }
    public File wine() { return new File(root, "wine/bin/wine"); }
    public File wineBinDir() { return new File(root, "wine/bin"); }
    public File cncDdraw() { return new File(root, "cnc-ddraw/ddraw.dll"); }
    public File versionFile() { return new File(root, "RUNTIME_VERSION"); }

    public boolean looksInstalled() {
        return loader().isFile() && box64().isFile() && wine().isFile() && versionFile().isFile();
    }
}
