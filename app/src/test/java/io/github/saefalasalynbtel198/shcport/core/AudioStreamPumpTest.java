package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The app's audio receiver: header, PCM pass-through, gain, mute, counters, misaligned reads, bad streams. */
public class AudioStreamPumpTest {

    static byte[] header(int rate, int ch, int bits, String magic) {
        byte[] h = new byte[16];
        for (int i = 0; i < 4; i++) h[i] = (byte) magic.charAt(i);
        for (int i = 0; i < 4; i++) {
            h[4 + i] = (byte) (rate >>> (8 * i));
            h[8 + i] = (byte) (ch >>> (8 * i));
            h[12 + i] = (byte) (bits >>> (8 * i));
        }
        return h;
    }

    static byte[] pcm(short... v) {
        byte[] b = new byte[v.length * 2];
        for (int i = 0; i < v.length; i++) { b[2 * i] = (byte) v[i]; b[2 * i + 1] = (byte) (v[i] >> 8); }
        return b;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    /** Records what the pump gives it. */
    static final class Rec implements PcmSink {
        int rate, ch;
        final ByteArrayOutputStream got = new ByteArrayOutputStream();
        final List<Integer> writes = new ArrayList<>();
        boolean closed;

        @Override public void open(int rate, int channels) { this.rate = rate; this.ch = channels; }

        @Override public void write(byte[] b, int off, int len) { got.write(b, off, len); writes.add(len); }

        @Override public void close() { closed = true; }
    }

    /** Delivers the input a few bytes at a time (reads split frames). */
    static final class Trickle extends InputStream {
        final byte[] d;
        int p;
        final int step;
        Trickle(byte[] d, int step) { this.d = d; this.step = step; }
        @Override public int read() { return p < d.length ? d[p++] & 0xff : -1; }
        @Override public int read(byte[] b, int off, int len) {
            if (p >= d.length) return -1;
            int n = Math.min(Math.min(len, step), d.length - p);
            System.arraycopy(d, p, b, off, n);
            p += n;
            return n;
        }
    }

    private static long[] acks(byte[] out) {
        long[] a = new long[out.length / 8];
        for (int i = 0; i < a.length; i++) {
            long v = 0;
            for (int k = 7; k >= 0; k--) v = v << 8 | (out[8 * i + k] & 0xff);
            a[i] = v;
        }
        return a;
    }

    @Test
    public void passesPcmThroughAndCountsAcceptedFrames() throws IOException {
        AudioSettings s = new AudioSettings();
        AudioStreamPump p = new AudioStreamPump(() -> s);
        Rec sink = new Rec();
        byte[] data = pcm((short) 1000, (short) -1000, (short) 32767, (short) -32768, (short) 5, (short) -5);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.run(new ByteArrayInputStream(concat(header(48000, 2, 16, "SHC1"), data)), out, sink);
        assertEquals(48000, sink.rate);
        assertEquals(2, sink.ch);
        assertArrayEquals(data, sink.got.toByteArray());          // unchanged at 100 %
        long[] a = acks(out.toByteArray());
        assertEquals(3, a[a.length - 1]);                          // 3 stereo frames accepted
        assertEquals(3, p.acceptedFrames);
        assertEquals(32768, p.peak);
        assertTrue(sink.closed);
    }

    @Test
    public void splitFramesAreReassembledAndCountersGrow() throws IOException {
        AudioSettings s = new AudioSettings();
        AudioStreamPump p = new AudioStreamPump(() -> s);
        Rec sink = new Rec();
        short[] v = new short[200];
        for (int i = 0; i < v.length; i++) v[i] = (short) (i * 100);
        byte[] data = pcm(v);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.run(new Trickle(concat(header(44100, 2, 16, "SHC1"), data), 7), out, sink);  // 7-byte reads split frames
        assertArrayEquals(data, sink.got.toByteArray());
        for (int len : sink.writes) assertEquals(0, len % 4);      // only whole frames reach the sink
        long[] a = acks(out.toByteArray());
        for (int i = 1; i < a.length; i++) assertTrue(a[i] > a[i - 1]);
        assertEquals(100, a[a.length - 1]);
    }

    @Test
    public void volumeAndMuteScaleTheSamples() throws IOException {
        AudioSettings s = new AudioSettings();
        s.volume = 50;
        Rec sink = new Rec();
        new AudioStreamPump(() -> s).run(new ByteArrayInputStream(concat(header(22050, 1, 16, "SHC1"),
                pcm((short) 20000, (short) -20000, (short) 3))), new ByteArrayOutputStream(), sink);
        assertArrayEquals(pcm((short) 10000, (short) -10000, (short) 2), sink.got.toByteArray());
        s.muted = true;
        Rec m = new Rec();
        new AudioStreamPump(() -> s).run(new ByteArrayInputStream(concat(header(22050, 1, 16, "SHC1"),
                pcm((short) 20000, (short) -20000))), new ByteArrayOutputStream(), m);
        assertArrayEquals(pcm((short) 0, (short) 0), m.got.toByteArray());
        assertEquals(0f, s.gain(), 0f);
    }

    @Test
    public void rejectsForeignOrUnsupportedStreams() {
        for (byte[] h : new byte[][] {header(48000, 2, 16, "XXXX"), header(48000, 6, 16, "SHC1"),
                header(48000, 2, 24, "SHC1"), header(500, 2, 16, "SHC1")}) {
            Rec sink = new Rec();
            try {
                new AudioStreamPump(AudioSettings::new).run(new ByteArrayInputStream(h), new ByteArrayOutputStream(), sink);
                fail("accepted a bad header");
            } catch (IOException expected) {
                assertTrue(sink.closed);
            }
        }
    }

    @Test
    public void soundEnvironmentKeepsTheGamesOverrides() {
        java.util.Map<String, String> e = GameSoundEnv.forConfig("/data/files/audio/asound.conf");
        assertEquals("/data/files/audio/asound.conf", e.get("ALSA_CONFIG_PATH"));
        String o = e.get("WINEDLLOVERRIDES");
        assertTrue(o, o.startsWith(WineLaunchPlan.DLL_OVERRIDES));   // ddraw=n (cnc-ddraw) etc. unchanged
        assertTrue(o, o.contains("ddraw=n"));
        assertTrue(o, o.endsWith(";winepulse.drv=d"));
        assertEquals(2, e.size());                                   // nothing else of the launch changes
    }

    @Test
    public void sanitizedSettingsClampVolume() {
        AudioSettings s = new AudioSettings();
        s.volume = 250;
        assertEquals(100, s.sanitized().volume);
        s.volume = -4;
        assertEquals(0, s.sanitized().volume);
        assertEquals(0f, s.gain(), 0f);
    }

    @Test
    public void pacedSinkConsumesInRealTime() throws Exception {
        PacedRecordingSink k = new PacedRecordingSink(null, 50);
        k.open(8000, 1);
        byte[] chunk = new byte[1600];                              // 100 ms at 8 kHz mono
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) k.write(chunk, 0, chunk.length); // 500 ms of audio, 50 ms buffer
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // the first 100 ms block fills the empty buffer at once, each further block waits for the one before it
        assertTrue("took " + ms + " ms", ms >= 350 && ms < 900);
        assertEquals(4000, k.frames());
        Thread.sleep(300);                                           // idle: the device drains and underruns
        long t1 = System.nanoTime();
        for (int i = 0; i < 3; i++) k.write(chunk, 0, chunk.length);
        long ms2 = (System.nanoTime() - t1) / 1_000_000;
        assertTrue("after idle took " + ms2 + " ms", ms2 >= 150);    // paced again, not let through as a burst
        k.setPaused(true);
        long before = k.playedFrames();
        Thread.sleep(150);
        assertEquals(before, k.playedFrames());                      // paused: nothing played
    }
}
