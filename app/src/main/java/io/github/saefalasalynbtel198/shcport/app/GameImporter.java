package io.github.saefalasalynbtel198.shcport.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Copies a user-selected game folder (Storage Access Framework tree URI) into app storage.
 *
 * <p>The copy goes to a staging folder first and replaces the previous import only after every
 * file was copied, so an interrupted import never leaves a half-written game folder behind.
 * Runs on its own thread; progress is delivered on the main thread.
 */
public final class GameImporter {

    public interface Listener {
        void onProgress(long copiedBytes, long totalBytes, String currentFile);
        void onFinished(boolean success, String message);
    }

    /** Files that are never part of the game and need not be copied. */
    private static final String[] SKIPPED_NAMES = {"thumbs.db", "desktop.ini", ".ds_store"};
    private static final String[] SKIPPED_EXTENSIONS = {".url", ".lnk"};

    private static final class Entry {
        final String docId;
        final String relPath;
        final long size;

        Entry(String docId, String relPath, long size) {
            this.docId = docId;
            this.relPath = relPath;
            this.size = size;
        }
    }

    private final Context context;
    private final AppPaths paths;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile Listener listener;
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private volatile long lastCopied, lastTotal;
    private volatile String lastFile = "";

    GameImporter(Context context, AppPaths paths) {
        this.context = context.getApplicationContext();
        this.paths = paths;
    }

    public boolean isRunning() { return running; }

    public void setListener(Listener l) {
        this.listener = l;
        if (l != null && running) l.onProgress(lastCopied, lastTotal, lastFile);
    }

    public void cancel() { cancelRequested = true; }

    public synchronized void start(final Uri treeUri) {
        if (running) return;
        running = true;
        cancelRequested = false;
        new Thread(() -> {
            boolean ok = false;
            String msg;
            try {
                msg = runImport(treeUri);
                ok = true;
            } catch (Exception e) {
                Log.e(ShcApplication.TAG, "Import failed", e);
                msg = "Import failed: " + e.getMessage();
            } finally {
                running = false;
            }
            final boolean success = ok;
            final String message = msg;
            main.post(() -> {
                Listener l = listener;
                if (l != null) l.onFinished(success, message);
            });
        }, "game-import").start();
    }

    private String runImport(Uri treeUri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);

        List<Entry> files = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        walk(resolver, treeUri, rootId, "", files, dirs);
        long total = 0;
        for (Entry e : files) total += Math.max(0, e.size);
        if (files.isEmpty()) throw new IOException("The selected folder is empty.");

        File staging = paths.gameStagingDir();
        if (staging.exists()) AppPaths.deleteTree(staging, paths.gameStorageRoot());
        if (!staging.mkdirs()) throw new IOException("Cannot create " + staging);

        long free = new StatFs(staging.getPath()).getAvailableBytes();
        if (free < total + 64L * 1024 * 1024) {
            throw new IOException(String.format(Locale.ROOT,
                    "Not enough free space: need %d MB, available %d MB.", total >> 20, free >> 20));
        }

        for (String d : dirs) {
            File dir = new File(staging, d);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        }

        byte[] buf = new byte[1 << 18];
        long copied = 0;
        for (Entry e : files) {
            if (cancelRequested) {
                AppPaths.deleteTree(staging, paths.gameStorageRoot());
                throw new IOException("Cancelled by user.");
            }
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, e.docId);
            File out = new File(staging, e.relPath);
            try (InputStream in = resolver.openInputStream(doc);
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) throw new IOException("Cannot open " + e.relPath);
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    copied += n;
                }
            }
            publish(copied, total, e.relPath);
        }

        File target = paths.gameDir();
        if (target.exists()) AppPaths.deleteTree(target, paths.gameStorageRoot());
        if (!staging.renameTo(target)) throw new IOException("Cannot move staging folder into place.");
        return String.format(Locale.ROOT, "Imported %d files (%d MB).", files.size(), copied >> 20);
    }

    private void walk(ContentResolver resolver, Uri treeUri, String docId, String rel,
                      List<Entry> files, List<String> dirs) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
        };
        try (Cursor c = resolver.query(children, projection, null, null, null)) {
            if (c == null) throw new IOException("Cannot list folder " + (rel.isEmpty() ? "/" : rel));
            while (c.moveToNext()) {
                if (cancelRequested) throw new IOException("Cancelled by user.");
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                if (!isSafeName(name) || isSkipped(name)) continue;
                String childRel = rel.isEmpty() ? name : rel + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(childRel);
                    walk(resolver, treeUri, id, childRel, files, dirs);
                } else {
                    files.add(new Entry(id, childRel, size));
                }
            }
        }
    }

    static boolean isSafeName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\0') < 0;
    }

    static boolean isSkipped(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String s : SKIPPED_NAMES) if (lower.equals(s)) return true;
        for (String s : SKIPPED_EXTENSIONS) if (lower.endsWith(s)) return true;
        return false;
    }

    private void publish(final long copied, final long total, final String file) {
        lastCopied = copied;
        lastTotal = total;
        lastFile = file;
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onProgress(copied, total, file);
        });
    }
}
