package io.github.saefalasalynbtel198.shcport.core;

/**
 * Order of the display test start (BUILD 3 step 3.2): runtime and test program, then a usable Wine prefix, then
 * Xvfb, then the Windows test program. Each stage runs only if the one before it succeeded, so Xvfb is never
 * started without a prefix and the test program never without a ready display.
 */
public final class DisplayStartOrder {

    public enum Stage { RUNTIME, PREFIX, XVFB, TEST_PROGRAM }

    public interface Stages {
        boolean runtime() throws InterruptedException;

        boolean prefix() throws InterruptedException;

        boolean xvfb() throws InterruptedException;

        boolean testProgram() throws InterruptedException;
    }

    private DisplayStartOrder() {}

    /** Runs the stages in order; returns the stage that failed, or null if all succeeded. */
    public static Stage run(Stages s) throws InterruptedException {
        if (!s.runtime()) return Stage.RUNTIME;
        if (!s.prefix()) return Stage.PREFIX;
        if (!s.xvfb()) return Stage.XVFB;
        if (!s.testProgram()) return Stage.TEST_PROGRAM;
        return null;
    }
}
