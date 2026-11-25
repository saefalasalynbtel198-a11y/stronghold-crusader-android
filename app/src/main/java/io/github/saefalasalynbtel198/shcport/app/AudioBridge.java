package io.github.saefalasalynbtel198.shcport.app;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.github.saefalasalynbtel198.shcport.core.AudioSettings;
import io.github.saefalasalynbtel198.shcport.core.AudioStreamPump;

/**
 * Game sound for one game session. The game's audio goes Wine → winealsa → (Box64) native libasound → the SHC ALSA
 * plugin (asset audio/libasound_module_pcm_shc.so, selected through ALSA_CONFIG_PATH) → this server's socket →
 * {@link AudioStreamPump} → {@link AudioTrackSink} → Android. One AudioTrack per stream the game opens.
 */
final class AudioBridge {
    static final String PLUGIN_ASSET = "audio/libasound_module_pcm_shc.so";
    private static int counter;

    final String socketName;
    private final Supplier<AudioSettings> settings;
    private LocalServerSocket server;
    private Thread acceptor;
    private volatile boolean closed;
    private volatile boolean paused;
    private final List<Stream> streams = new ArrayList<>();
    private int opened;
    volatile String status = "not started";

    private static final class Stream {
        LocalSocket socket;
        AudioStreamPump pump;
        AudioTrackSink sink;
        int id;
    }

    AudioBridge(Supplier<AudioSettings> settings) {
        synchronized (AudioBridge.class) { counter++; }
        this.socketName = "shc-audio-" + android.os.Process.myPid() + "-" + counter;
        this.settings = settings;
    }

    /**
     * Copies the plugin into app storage (only if it changed), writes the ALSA configuration and returns it, or null if
     * this APK has no plugin (the game then starts without sound output, as before 0.5.0).
     */
    static File prepareConfig(Context context, AppPaths paths, String socketName) {
        File dir = new File(paths.internalRoot(), "audio");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File lib = new File(dir, "libasound_module_pcm_shc.so");
        try (InputStream in = context.getAssets().open(PLUGIN_ASSET)) {
            byte[] data = readAll(in);
            if (!lib.isFile() || lib.length() != data.length || !java.util.Arrays.equals(readAll(new java.io.FileInputStream(lib)), data)) {
                File tmp = new File(dir, lib.getName() + ".tmp");
                try (OutputStream o = new FileOutputStream(tmp)) {
                    o.write(data);
                }
                if (!tmp.renameTo(lib)) throw new IOException("cannot install " + lib);
            }
        } catch (IOException e) {
            return null;
        }
        File conf = new File(dir, "asound.conf");
        String text = "# SHC Port game sound (written at each Start Game)\n"
                + "pcm_type.shc {\n  lib \"" + lib.getPath() + "\"\n}\n"
                + "pcm.!default {\n  type shc\n  socket \"@" + socketName + "\"\n}\n";
        try (OutputStream o = new FileOutputStream(conf)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
        return conf;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    void start() throws IOException {
        server = new LocalServerSocket(socketName);    // abstract namespace, as the plugin's "@name"
        status = "listening on @" + socketName;
        acceptor = new Thread(this::acceptLoop, "audio-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!closed) {
            LocalSocket c;
            try {
                c = server.accept();
            } catch (IOException e) {
                return;
            }
            if (closed) {
                closeQuietly(c);
                return;
            }
            Stream s = new Stream();
            s.socket = c;
            s.pump = new AudioStreamPump(settings);
            s.sink = new AudioTrackSink(paused);
            synchronized (streams) {
                s.id = ++opened;
                streams.add(s);
            }
            Thread t = new Thread(() -> {
                try {
                    s.pump.run(c.getInputStream(), c.getOutputStream(), s.sink);
                } catch (IOException e) {
                    // the pump's status keeps the reason
                } finally {
                    closeQuietly(c);
                    synchronized (streams) { streams.remove(s); }
                    status = "stream " + s.id + " " + s.pump.status;
                }
            }, "audio-stream-" + s.id);
            t.setDaemon(true);
            t.start();
        }
    }

    /** Pauses or resumes all output (game screen in the background, or Android took the audio focus). */
    void setPaused(boolean p) {
        paused = p;
        synchronized (streams) {
            for (Stream s : streams) s.sink.setPaused(p);
        }
    }

    void close() {
        closed = true;
        try {
            // LocalServerSocket.accept() does not return on close(): wake it with a connection first
            LocalSocket wake = new LocalSocket();
            wake.connect(new LocalSocketAddress(socketName));
            wake.close();
        } catch (IOException ignored) {
            // not listening any more
        }
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        List<Stream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams);
            streams.clear();
        }
        for (Stream s : all) {
            s.pump.stop();
            closeQuietly(s.socket);
            s.sink.close();
        }
        status = "closed";
    }

    List<String> describe() {
        List<String> l = new ArrayList<>();
        AudioSettings a = settings.get();
        l.add("Sound: " + (a.muted ? "muted" : a.volume + " %") + ", server @" + socketName + (paused ? ", paused" : "")
                + "; " + opened + " stream(s) opened by the game; last: " + status);
        synchronized (streams) {
            for (Stream s : streams) {
                l.add("  stream " + s.id + ": " + s.sink.describe() + "; peak " + s.pump.peak
                        + (s.sink.error != null ? "; error " + s.sink.error : ""));
            }
        }
        return l;
    }

    private static void closeQuietly(LocalSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
