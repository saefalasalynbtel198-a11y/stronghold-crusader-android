package io.github.saefalasalynbtel198.shcport.core;

/**
 * The life of the one game session the app allows: IDLE → STARTING → RUNNING → STOPPING → IDLE. A second start
 * while a session exists is refused (the caller shows the running session instead); an exit of the game process
 * moves the session to ENDED, from where it can be cleaned up and started again. Thread-safe.
 */
public final class SessionLifecycle {

    public enum Phase { IDLE, STARTING, RUNNING, STOPPING, ENDED }

    private Phase phase = Phase.IDLE;
    private int generation;
    private String endReason = "";

    public synchronized Phase phase() { return phase; }

    public synchronized int generation() { return generation; }

    public synchronized String endReason() { return endReason; }

    /** True if the caller may start a new session (and it now counts as STARTING); false if one already exists. */
    public synchronized boolean tryStart() {
        if (phase != Phase.IDLE) return false;
        phase = Phase.STARTING;
        generation++;
        endReason = "";
        return true;
    }

    /** The start stages succeeded and the game process runs. */
    public synchronized void started(int gen) {
        if (gen == generation && phase == Phase.STARTING) phase = Phase.RUNNING;
    }

    /** The start failed or the game process ended by itself (not through {@link #beginStop}). */
    public synchronized void ended(int gen, String reason) {
        if (gen != generation || phase == Phase.IDLE || phase == Phase.STOPPING) return;
        phase = Phase.ENDED;
        endReason = reason == null ? "" : reason;
    }

    /** True if the caller should now stop the processes (only one caller gets true). */
    public synchronized boolean beginStop() {
        if (phase == Phase.IDLE || phase == Phase.STOPPING) return false;
        phase = Phase.STOPPING;
        return true;
    }

    /** Processes are stopped and resources released: a new session may start. */
    public synchronized void stopped() {
        phase = Phase.IDLE;
    }

    /** A session exists that the screen should show (starting, running, or ended but not yet cleaned up). */
    public synchronized boolean active() {
        return phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.ENDED;
    }
}
