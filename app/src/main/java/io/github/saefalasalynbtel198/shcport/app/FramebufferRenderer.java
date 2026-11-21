package io.github.saefalasalynbtel198.shcport.app;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import io.github.saefalasalynbtel198.shcport.core.DisplayTestPattern;
import io.github.saefalasalynbtel198.shcport.core.FrameStats;
import io.github.saefalasalynbtel198.shcport.core.ViewportTransform;
import io.github.saefalasalynbtel198.shcport.core.XwdFramebuffer;

/**
 * Draws Xvfb's framebuffer file on a GLSurfaceView (GL thread only, except {@link #setSource} and {@link #stats}).
 *
 * <p>The file is mapped read-only (MAP_SHARED, so the X server's writes are visible without reading the file);
 * each frame where the picture changed, the mapped pixels are handed straight to glTexSubImage2D: the only copy
 * is the driver's upload into the texture. Rows use the file's stride (texture width = stride / 4; texture
 * coordinates select the visible width). The shader swaps Xvfb's B,G,R,X byte order to RGB. The picture is
 * scaled with the aspect ratio kept (letterboxing, {@link ViewportTransform}).
 *
 * <p>Memory safety: the mapping stays valid until unmapped by the GC even if Xvfb exits (it unlinks, never
 * truncates, its file) and every session uses a new file, so no read can fault. The X server keeps drawing while
 * a frame is uploaded, so a frame can occasionally mix two images (tearing); Xvfb offers no frame lock.
 */
final class FramebufferRenderer implements GLSurfaceView.Renderer {

    /** Snapshot for the UI thread. */
    static final class Stats {
        volatile int displayFps;
        volatile int uploadsPerSecond;
        volatile int contentFps;
        volatile long lastCounter = -1;
        volatile long droppedFrames;
        volatile long surfaceCreations;
        volatile String format = "";
        volatile String pixelCheck = "not checked yet";
        volatile boolean pixelOk;
        volatile long firstOkMs = -1;
        volatile long framesSinceResume;
        volatile String error;
        volatile int viewWidth;
        volatile int viewHeight;
        volatile long uploadMicros;
    }

    final Stats stats = new Stats();
    private volatile File pendingSource;
    private volatile boolean sourceChanged;

    private MappedByteBuffer mapped;
    private ByteBuffer pixels;
    private XwdFramebuffer fb;
    private int program = -1;
    private int texture = -1;
    private boolean textureAllocated;
    private int viewW = 1;
    private int viewH = 1;
    private long lastSignature = Long.MIN_VALUE;
    private long lastUploadMs;
    private long lastCheckMs;
    private long lastCounterSeen = -1;
    private final FrameStats drawn = new FrameStats();
    private final FrameStats uploads = new FrameStats();
    private final FrameStats content = new FrameStats();
    private FloatBuffer quad;
    private int aPos;
    private int aTex;
    private float texRight = 1f;

    /** Called from any thread; null stops drawing (and releases the mapping on the GL thread). */
    void setSource(File framebufferFile) {
        pendingSource = framebufferFile;
        sourceChanged = true;
    }

    void markResumed() { stats.framesSinceResume = 0; }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        stats.surfaceCreations++;
        program = buildProgram();
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        textureAllocated = false;      // a new EGL context has no textures
        lastSignature = Long.MIN_VALUE;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewW = Math.max(1, width);
        viewH = Math.max(1, height);
        stats.viewWidth = viewW;
        stats.viewHeight = viewH;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = SystemClock.uptimeMillis();
        if (sourceChanged) {
            sourceChanged = false;
            map(pendingSource);
        }
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (fb == null || program < 0) {
            drawn.add(now);
            stats.displayFps = drawn.perSecond(now);
            return;
        }
        if (!textureAllocated) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fb.strideWidth(), fb.height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            textureAllocated = true;
            lastSignature = Long.MIN_VALUE;
        }
        // Upload when the picture changed (counter strip, moving band) or at least every 250 ms.
        long counter = DisplayTestPattern.decodeCounter(fb, mapped);
        long sig = DisplayTestPattern.bandSignature(fb, mapped) * 31 + counter;
        if (sig != lastSignature || now - lastUploadMs >= 250) {
            long t0 = System.nanoTime();
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, fb.strideWidth(), fb.height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            stats.uploadMicros = (System.nanoTime() - t0) / 1000;
            lastSignature = sig;
            lastUploadMs = now;
            uploads.add(now);
        }
        if (counter >= 0 && counter != lastCounterSeen) {
            if (lastCounterSeen >= 0) {
                long step = (counter - lastCounterSeen) & 0xffff;
                if (step > 1 && step < 1000) stats.droppedFrames += step - 1;
                for (long i = 0; i < Math.min(step, 1000); i++) content.add(now);
            }
            lastCounterSeen = counter;
            stats.lastCounter = counter;
        }
        ViewportTransform vt = new ViewportTransform(fb.width, fb.height, viewW, viewH);
        GLES20.glViewport(Math.round(vt.offsetX), Math.round(vt.offsetY), Math.round(fb.width * vt.scale),
                Math.round(fb.height * vt.scale));
        GLES20.glUseProgram(program);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        drawn.add(now);
        stats.framesSinceResume++;
        stats.displayFps = drawn.perSecond(now);
        stats.uploadsPerSecond = uploads.perSecond(now);
        stats.contentFps = content.perSecond(now);
        if (now - lastCheckMs >= 1000) {
            lastCheckMs = now;
            DisplayTestPattern.Check c = DisplayTestPattern.checkQuadrants(fb, mapped);
            stats.pixelOk = c.ok;
            stats.pixelCheck = c.detail;
            if (c.ok && stats.firstOkMs < 0) stats.firstOkMs = now;
        }
    }

    private void map(File f) {
        mapped = null;     // released by the GC; the file stays valid until then (never truncated)
        pixels = null;
        fb = null;
        textureAllocated = false;
        stats.error = null;
        if (f == null) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            XwdFramebuffer parsed = XwdFramebuffer.parse(m);
            if (!parsed.isBgrx()) throw new IllegalArgumentException("unsupported pixel layout: " + parsed);
            ByteBuffer p = m.duplicate();
            p.position(parsed.pixelOffset);
            p.limit((int) (parsed.pixelOffset + parsed.imageBytes()));
            pixels = p.slice();
            mapped = m;
            fb = parsed;
            stats.format = parsed.toString();
            texRight = (float) parsed.width / parsed.strideWidth();
            quad = quadBuffer(texRight);
        } catch (Exception e) {
            stats.error = "framebuffer: " + e.getMessage();
        }
    }

    private static FloatBuffer quadBuffer(float u) {
        float[] v = {   // x, y, u, v (row 0 of the image at the top)
                -1f, -1f, 0f, 1f,
                1f, -1f, u, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, u, 0f};
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    private int buildProgram() {
        String vs = "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;"
                + "void main(){ vTex = aTex; gl_Position = vec4(aPos, 0.0, 1.0); }";
        String fs = "precision mediump float; varying vec2 vTex; uniform sampler2D uTex;"
                + "void main(){ vec4 c = texture2D(uTex, vTex); gl_FragColor = vec4(c.b, c.g, c.r, 1.0); }";
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            stats.error = "shader link: " + GLES20.glGetProgramInfoLog(p);
            return -1;
        }
        aPos = GLES20.glGetAttribLocation(p, "aPos");
        aTex = GLES20.glGetAttribLocation(p, "aTex");
        quad = quadBuffer(texRight);
        return p;
    }

    private int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) stats.error = "shader compile: " + GLES20.glGetShaderInfoLog(s);
        return s;
    }
}
