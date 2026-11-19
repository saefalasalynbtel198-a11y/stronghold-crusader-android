package io.github.saefalasalynbtel198.shcport.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lists, exports and imports save games (*.sav) through the Storage Access Framework. */
public final class SaveManager {

    private final Context context;
    private final AppPaths paths;

    public SaveManager(Context context, AppPaths paths) {
        this.context = context.getApplicationContext();
        this.paths = paths;
    }

    /** Folder new saves are imported into: the first existing candidate, else the game's own saves folder. */
    public File primarySaveDir() {
        for (File d : paths.saveDirCandidates()) if (d.isDirectory()) return d;
        return new File(paths.gameDir(), "saves");
    }

    public List<File> listSaves() {
        List<File> out = new ArrayList<>();
        for (File d : paths.saveDirCandidates()) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) if (isSave(f.getName()) && f.isFile()) out.add(f);
        }
        return out;
    }

    static boolean isSave(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".sav");
    }

    /** Copies every save file into the chosen folder. Returns the number of files written. */
    public int exportTo(Uri treeUri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        int count = 0;
        for (File f : listSaves()) {
            Uri doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", f.getName());
            if (doc == null) throw new IOException("Cannot create " + f.getName());
            try (InputStream in = new FileInputStream(f); OutputStream out = resolver.openOutputStream(doc)) {
                if (out == null) throw new IOException("Cannot write " + f.getName());
                copy(in, out);
            }
            count++;
        }
        return count;
    }

    /** Copies *.sav files from the chosen folder into {@link #primarySaveDir()}. Existing files are kept. */
    public int importFrom(Uri treeUri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        File dest = primarySaveDir();
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Cannot create " + dest);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        int count = 0;
        try (Cursor c = resolver.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) throw new IOException("Cannot list the selected folder");
            while (c.moveToNext()) {
                String name = c.getString(1);
                if (!GameImporter.isSafeName(name) || !isSave(name)) continue;
                File out = new File(dest, name);
                if (out.exists()) out = new File(dest, "imported-" + System.currentTimeMillis() + "-" + name);
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                try (InputStream in = resolver.openInputStream(doc); OutputStream os = new FileOutputStream(out)) {
                    if (in == null) throw new IOException("Cannot read " + name);
                    copy(in, os);
                }
                count++;
            }
        }
        return count;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
