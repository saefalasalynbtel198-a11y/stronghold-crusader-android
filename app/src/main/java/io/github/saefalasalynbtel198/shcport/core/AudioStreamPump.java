package io.github.saefalasalynbtel198.shcport.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

/**
 * One audio stream from the ALSA plugin (app/nativesrc/alsa-shc/pcm_shc.c) to a {@link PcmSink}: reads the header
 * ("SHC1", rate, channels, bits), then 16-bit PCM; applies the player's gain; after each block the sink has accepted,
 * sends back the total number of accepted frames (little-endian u64). Those counters are the plugin's playback
 * position, so the game is paced by the real output. Runs until the plugin closes the stream or {@link #stop}.
 */
public final class AudioStreamPump {
    public static final int MAX_RATE = 192000;

    private final Supplier<AudioSettings> settings;
    public volatile int rate, channels;
    public volatile long acceptedFrames;
    public volatile long peak;            // highest absolute sample value sent to the sink (after gain), for diagnostics
    public volatile String status = "waiting for the header";
    private volatile boolean stopped;

    public AudioStreamPump(Supplier<AudioSettings> settings) {
        this.settings = settings;
    }

    public void stop() { stopped = true; }

    /** Blocking; returns when the stream ends. The sink is closed on return. */
    public void run(InputStream in, OutputStream out, PcmSink sink) throws IOException {
        try {
            byte[] h = new byte[16];
            readFully(in, h);
            if (h[0] != 'S' || h[1] != 'H' || h[2] != 'C' || h[3] != '1') throw new IOException("not an SHC audio stream");
            rate = le32(h, 4);
            channels = le32(h, 8);
            int bits = le32(h, 12);
            if (rate < 1000 || rate > MAX_RATE || channels < 1 || channels > 2 || bits != 16) {
                throw new IOException("unsupported format " + rate + " Hz, " + channels + " ch, " + bits + " bit");
            }
            sink.open(rate, channels);
            status = "playing " + rate + " Hz, " + channels + " ch, 16-bit";
            int frameBytes = channels * 2;
            byte[] buf = new byte[4096 - 4096 % frameBytes];
            byte[] ack = new byte[8];
            int have = 0;
            while (!stopped) {
                int r = in.read(buf, have, buf.length - have);
                if (r < 0) break;
                have += r;
                int whole = have - have % frameBytes;
                if (whole == 0) continue;
                applyGain(buf, whole, settings.get().gain());
                sink.write(buf, 0, whole);
                acceptedFrames += whole / frameBytes;
                long v = acceptedFrames;
                for (int i = 0; i < 8; i++) ack[i] = (byte) (v >>> (8 * i));
                try {
                    out.write(ack);
                    out.flush();
                } catch (IOException closed) {
                    break;                       // the game closed the stream: a normal end
                }
                System.arraycopy(buf, whole, buf, 0, have - whole);
                have -= whole;
            }
            status = "ended after " + acceptedFrames + " frames";
        } catch (IOException e) {
            status = "ended: " + e.getMessage();
            throw e;
        } finally {
            sink.close();
        }
    }

    private void applyGain(byte[] b, int len, float gain) {
        long pk = peak;
        if (gain >= 0.999f) {
            for (int i = 0; i + 1 < len; i += 2) pk = Math.max(pk, Math.abs((short) ((b[i] & 0xff) | b[i + 1] << 8)));
        } else {
            for (int i = 0; i + 1 < len; i += 2) {
                int v = Math.round((short) ((b[i] & 0xff) | b[i + 1] << 8) * gain);
                b[i] = (byte) v;
                b[i + 1] = (byte) (v >> 8);
                pk = Math.max(pk, Math.abs(v));
            }
        }
        peak = pk;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) throw new EOFException("stream closed before the header");
            n += r;
        }
    }
}
