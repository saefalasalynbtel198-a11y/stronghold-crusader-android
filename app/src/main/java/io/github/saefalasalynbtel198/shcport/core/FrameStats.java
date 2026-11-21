package io.github.saefalasalynbtel198.shcport.core;

/** Events per second over a sliding one-second window (single-threaded use). */
public final class FrameStats {
    private final long[] times = new long[1024];
    private int head;
    private int count;
    private long total;

    public void add(long nowMs) {
        times[head] = nowMs;
        head = (head + 1) % times.length;
        if (count < times.length) count++;
        total++;
    }

    public long total() { return total; }

    /** Number of events in the last 1000 ms before {@code nowMs}. */
    public int perSecond(long nowMs) {
        int n = 0;
        for (int i = 1; i <= count; i++) {
            long t = times[(head - i + times.length) % times.length];
            if (nowMs - t > 1000) break;
            n++;
        }
        return n;
    }
}
