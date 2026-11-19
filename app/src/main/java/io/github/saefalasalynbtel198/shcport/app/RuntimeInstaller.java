package io.github.saefalasalynbtel198.shcport.app;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

import io.github.saefalasalynbtel198.shcport.core.RuntimeArchive;

/**
 * Installs a runtime package (shc-runtime-*.zip from the runtime-components workflow) selected by the
 * user. The zip is copied to app cache, validated and extracted into a staging folder, and only then
 * swapped in, so a failed install never breaks a working runtime.
 */
public final class RuntimeInstaller {

    public interface Progress { void update(String message); }

    private final Context context;
    private final AppPaths paths;

    public RuntimeInstaller(Context context, AppPaths paths) {
        this.context = context.getApplicationContext();
        this.paths = paths;
    }

    /** Blocking; call from a background thread. Returns the installed runtime version. */
    public String install(Uri zipUri, Progress progress) throws IOException {
        File internal = paths.internalRoot();
        File tmpZip = new File(context.getCacheDir(), "runtime-import.zip");
        File staging = new File(internal, "runtime.staging");
        File target = paths.runtimeDir();
        try {
            progress.update("Copying package…");
            long copied = 0;
            try (InputStream in = context.getContentResolver().openInputStream(zipUri);
                 OutputStream out = new FileOutputStream(tmpZip)) {
                if (in == null) throw new IOException("Cannot open the selected file");
                byte[] buf = new byte[1 << 18];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    copied += n;
                }
            }
            RuntimeArchive.Manifest m = RuntimeArchive.readManifest(tmpZip);
            long free = new StatFs(internal.getPath()).getAvailableBytes();
            // Uncompressed size is typically 2–3× the zip; require a generous margin.
            if (free < copied * 4) {
                throw new IOException(String.format(Locale.ROOT,
                        "Not enough internal storage: about %d MB needed, %d MB free.", (copied * 4) >> 20, free >> 20));
            }
            if (staging.exists()) AppPaths.deleteTree(staging, internal);
            progress.update("Extracting runtime " + m.version + "…");
            RuntimeArchive.extract(tmpZip, staging, (path, bytes) -> {
                if ((bytes >> 20) % 25 == 0) progress.update(String.format(Locale.ROOT, "Extracting… %d MB", bytes >> 20));
            });
            if (target.exists()) AppPaths.deleteTree(target, internal);
            if (!staging.renameTo(target)) throw new IOException("Cannot move runtime into place");
            return m.version;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmpZip.delete();
            if (staging.exists()) {
                try {
                    AppPaths.deleteTree(staging, internal);
                } catch (IOException ignored) {
                    // left for the next install attempt to clean up
                }
            }
        }
    }

    public void uninstall() throws IOException {
        File target = paths.runtimeDir();
        if (target.exists()) AppPaths.deleteTree(target, paths.internalRoot());
    }
}
