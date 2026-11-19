package io.github.saefalasalynbtel198.shcport.app;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.saefalasalynbtel198.shcport.BuildConfig;

/** Installs a crash recorder and holds process-wide state that must survive activity recreation. */
public final class ShcApplication extends Application {

    static final String TAG = "ShcPort";

    private AppPaths paths;
    private GameImporter importer;

    @Override
    public void onCreate() {
        super.onCreate();
        paths = new AppPaths(this);
        importer = new GameImporter(this, paths);
        installCrashRecorder();
        Log.i(TAG, "Started " + BuildConfig.VERSION_NAME + " on " + Build.MANUFACTURER + " " + Build.MODEL
                + " (SDK " + Build.VERSION.SDK_INT + ")");
    }

    public AppPaths paths() { return paths; }

    public GameImporter importer() { return importer; }

    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                File out = paths.crashLog();
                try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
                     PrintWriter pw = new PrintWriter(w)) {
                    pw.println("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date()));
                    pw.println("app: " + BuildConfig.APPLICATION_ID + " " + BuildConfig.VERSION_NAME
                            + " (" + BuildConfig.VERSION_CODE + ")");
                    pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL + ", SDK " + Build.VERSION.SDK_INT);
                    pw.println("thread: " + thread.getName());
                    error.printStackTrace(pw);
                }
            } catch (Throwable ignored) {
                // Never mask the original crash.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
