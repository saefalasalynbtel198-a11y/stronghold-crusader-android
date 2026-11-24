package io.github.saefalasalynbtel198.shcport.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.IOException;
import java.util.Locale;

import io.github.saefalasalynbtel198.shcport.core.PcmSink;

/**
 * One game audio stream played by Android: an AudioTrack in streaming mode (16-bit PCM, the stream's rate and channels,
 * about 100 ms of buffer). Blocking writes pace the stream at the real output rate; paused, the track stops consuming
 * and the game's audio waits (the game itself keeps running).
 */
final class AudioTrackSink implements PcmSink {
    private AudioTrack track;
    private volatile boolean paused;
    private int rate, channels, bufferBytes;
    private volatile long bytes;
    volatile String error;

    AudioTrackSink(boolean startPaused) {
        this.paused = startPaused;
    }

    @Override
    public synchronized void open(int rate, int channels) throws IOException {
        this.rate = rate;
        this.channels = channels;
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("Android cannot play " + rate + " Hz, " + channels + " ch");
        bufferBytes = Math.max(min * 2, rate * channels * 2 / 10);      // at least 100 ms
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            error = e.toString();
            throw new IOException("AudioTrack: " + e.getMessage());
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("AudioTrack not initialised");
        bufferBytes = track.getBufferSizeInFrames() * channels * 2;
        if (!paused) track.play();
    }

    @Override
    public void write(byte[] pcm, int off, int len) throws IOException {
        AudioTrack t = track;
        while (len > 0) {
            if (t == null) throw new IOException("closed");
            int w = t.write(pcm, off, len, AudioTrack.WRITE_BLOCKING);
            if (w < 0) throw new IOException("AudioTrack write error " + w);
            if (w == 0) {
                // paused with a full buffer, or released: wait a little instead of spinning
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                t = track;
                continue;
            }
            off += w;
            len -= w;
            bytes += w;
        }
    }

    synchronized void setPaused(boolean p) {
        paused = p;
        if (track == null) return;
        try {
            if (p) track.pause();
            else track.play();
        } catch (IllegalStateException e) {
            error = e.toString();
        }
    }

    @Override
    public synchronized void close() {
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.release();
            } catch (Exception ignored) {
                // closing anyway
            }
        }
        track = null;
    }

    synchronized String describe() {
        if (track == null) return rate == 0 ? "not opened" : "closed";
        int frames = channels > 0 ? bufferBytes / (channels * 2) : 0;
        return String.format(Locale.ROOT, "%d Hz, %d ch, 16-bit, buffer %d frames (%d ms), %s, %d underruns, %.1f s played",
                rate, channels, frames, rate > 0 ? frames * 1000 / rate : 0, paused ? "paused" : "playing",
                track.getUnderrunCount(), rate > 0 ? bytes / (double) (rate * channels * 2) : 0);
    }
}
