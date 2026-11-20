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
    /** Freestanding static seccomp probe (no glibc); see runtime/sysprobe. */
    public File sysprobe() { return new File(root, "bin/shc-sysprobe"); }
    /** glibc test program: start-up, threads, fork, faccessat, fchmodat; see runtime/glibc-android. */
    public File glibctest() { return new File(root, "bin/shc-glibctest"); }
    /** ptrace process watcher (runtime r5+): reports how each process of a tree ends; see runtime/sysprobe. */
    public File ptraceWatch() { return new File(root, "bin/shc-ptrace-watch"); }
    public File wine() { return new File(root, "wine/bin/wine"); }
    public File wineBinDir() { return new File(root, "wine/bin"); }
    public File peSmoke32() { return new File(root, "win/shc-pe-smoke-i686.exe"); }
    public File peSmoke64() { return new File(root, "win/shc-pe-smoke-x86_64.exe"); }
    public File cncDdraw() { return new File(root, "cnc-ddraw/ddraw.dll"); }
    public File versionFile() { return new File(root, "RUNTIME_VERSION"); }

    public boolean looksInstalled() {
        return loader().isFile() && box64().isFile() && wine().isFile() && versionFile().isFile();
    }
}
