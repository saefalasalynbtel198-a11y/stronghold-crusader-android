package io.github.saefalasalynbtel198.shcport.core;

import java.io.IOException;

/** Where one audio stream's PCM goes: Android's AudioTrack in the app, a recorder in tests. */
public interface PcmSink {
    /** Called once with the stream's format (16-bit signed little-endian, interleaved). */
    void open(int rate, int channels) throws IOException;

    /**
     * Accepts PCM; blocks while the output is full (that is what paces the game). Returns when all bytes are
     * accepted. len is a whole number of frames.
     */
    void write(byte[] pcm, int off, int len) throws IOException;

    void close();
}
