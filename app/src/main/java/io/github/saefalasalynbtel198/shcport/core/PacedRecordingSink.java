package io.github.saefalasalynbtel198.shcport.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A {@link PcmSink} that behaves like an audio device for tests and CI: it consumes PCM at the stream's real rate with
 * a fixed output buffer (blocking when full, as AudioTrack does) and records what it received (WAV file on close).
 */
public final class PacedRecordingSink implements PcmSink {
    private final File wav;
    private final int bufferMs;
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private int rate, channels;
    private long frames;
    /** Play head (frames) and when it was last advanced: it moves at the rate, stops at what is queued and while paused. */
    private double head;
    private long lastNs;
    public volatile boolean paused;

    public PacedRecordingSink(File wav, int bufferMs) {
        this.wav = wav;
        this.bufferMs = bufferMs;
    }

    @Override
    public synchronized void open(int rate, int channels) {
        this.rate = rate;
        this.channels = channels;
        lastNs = System.nanoTime();
    }

    private void advance() {
        long now = System.nanoTime();
        if (!paused) head = Math.min(frames, head + (now - lastNs) * (double) rate / 1e9);
        lastNs = now;
    }

    /** Frames the "device" has played so far. */
    public synchronized long playedFrames() {
        advance();
        return (long) head;
    }

    public synchronized void setPaused(boolean p) {
        advance();
        paused = p;
    }

    @Override
    public void write(byte[] pcm, int off, int len) throws IOException {
        int frameBytes = channels * 2;
        long n = len / frameBytes;
        long cap = (long) rate * bufferMs / 1000;
        try {
            while (true) {
                synchronized (this) {
                    advance();
                    long queued = frames - (long) head;
                    if (queued == 0 || queued + n <= cap) {   // an empty buffer takes any block
                        data.write(pcm, off, len);
                        frames += n;
                        return;
                    }
                }
                Thread.sleep(2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    public synchronized long frames() { return frames; }

    public int rate() { return rate; }

    public int channels() { return channels; }

    public synchronized byte[] pcm() { return data.toByteArray(); }

    @Override
    public synchronized void close() {
        if (wav == null || rate == 0) return;
        byte[] pcm = data.toByteArray();
        try (OutputStream o = new FileOutputStream(wav)) {
            o.write(wavHeader(pcm.length, rate, channels));
            o.write(pcm);
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    static byte[] wavHeader(int dataLen, int rate, int channels) {
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le(h, 4, 36 + dataLen); put(h, 8, "WAVE"); put(h, 12, "fmt "); le(h, 16, 16);
        h[20] = 1; h[22] = (byte) channels; le(h, 24, rate); le(h, 28, rate * channels * 2);
        h[32] = (byte) (channels * 2); h[34] = 16; put(h, 36, "data"); le(h, 40, dataLen);
        return h;
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i); }

    private static void le(byte[] b, int o, int v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >>> (8 * i)); }
}
