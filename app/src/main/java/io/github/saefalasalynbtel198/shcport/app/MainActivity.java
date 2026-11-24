package io.github.saefalasalynbtel198.shcport.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

import io.github.saefalasalynbtel198.shcport.BuildConfig;
import io.github.saefalasalynbtel198.shcport.core.GameReadiness;
import io.github.saefalasalynbtel198.shcport.core.PrivateGameCopy;
import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;
import io.github.saefalasalynbtel198.shcport.core.WinePrefixSetup;

/** Launcher home: import, validation status, runtime status and entry points to the other screens. */
public final class MainActivity extends Activity implements GameImporter.Listener {

    private static final int REQ_IMPORT_GAME = 1;
    private static final int REQ_EXPORT_SAVES = 2;
    private static final int REQ_IMPORT_SAVES = 3;

    private ShcApplication app;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView gameStatus;
    private TextView runtimeStatus;
    private TextView importStatus;
    private ProgressBar progress;
    private Button importButton;
    private Button cancelButton;
    private Button playButton;
    private TextView playReason;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Debug builds only: lets the CI emulator test open the (not exported) display test screen.
        String open = getIntent().getStringExtra("shc.open");
        if (BuildConfig.DEBUG && savedInstanceState == null && "play".equals(open)) {
            startActivity(new android.content.Intent(this, GameActivity.class));
        }
        if (BuildConfig.DEBUG && savedInstanceState == null && ("display-test".equals(open) || "game-launch".equals(open))) {
            startActivity(new android.content.Intent(this, DisplayTestActivity.class)
                    .putExtra(DisplayTestActivity.EXTRA_MODE, "game-launch".equals(open) ? DisplayTestActivity.MODE_GAME : DisplayTestActivity.MODE_DISPLAY));
        }
        app = (ShcApplication) getApplication();

        LinearLayout col = Ui.column(this);
        col.addView(Ui.title(this, "Stronghold Crusader — Android port"));
        col.addView(Ui.muted(this, "Development build " + BuildConfig.VERSION_NAME
                + ". Unofficial community project, not affiliated with Firefly Studios. "
                + "You need your own legally obtained copy of Stronghold Crusader (for example from GOG or Steam)."));

        col.addView(Ui.heading(this, "1. Game files"));
        gameStatus = Ui.body(this, "Checking…");
        col.addView(gameStatus);
        importStatus = Ui.muted(this, "");
        col.addView(importStatus);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(android.view.View.GONE);
        col.addView(progress);
        importButton = Ui.button(this, "Import game folder…", v -> pickFolder(REQ_IMPORT_GAME));
        cancelButton = Ui.button(this, "Cancel import", v -> app.importer().cancel());
        col.addView(Ui.row(this, importButton, cancelButton,
                Ui.button(this, "Re-check", v -> refresh()),
                Ui.button(this, "Copy build fingerprint", v -> copyFingerprint()),
                Ui.button(this, "Delete imported game", v -> confirmDelete())));

        col.addView(Ui.heading(this, "2. Runtime"));
        runtimeStatus = Ui.body(this, "");
        col.addView(runtimeStatus);
        col.addView(Ui.button(this, "Runtime: install / self-test", v -> startActivity(new Intent(this, RuntimeActivity.class))));

        col.addView(Ui.heading(this, "3. Play"));
        playButton = Ui.button(this, "Start Game", v -> startGame());
        playButton.setEnabled(false);
        playReason = Ui.muted(this, "Checking…");
        col.addView(playButton);
        col.addView(playReason);
        col.addView(Ui.muted(this, "Controls: tap = left click, long press = right click, drag = drag/select, "
                + "two fingers = scroll the map, pinch = mouse wheel. Side keys: Esc, Enter, Space, Right (one finger holds "
                + "the right button: right click, camera view), Keys (soft keyboard), Report, Exit. "
                + "Troubleshooting: Runtime → Game launch test (diagnostic)."));

        col.addView(Ui.heading(this, "Tools"));
        col.addView(Ui.row(this,
                Ui.button(this, "Touch controls test", v -> startActivity(new Intent(this, TouchTestActivity.class))),
                Ui.button(this, "Control settings", v -> startActivity(new Intent(this, SettingsActivity.class))),
                Ui.button(this, "Diagnostics", v -> startActivity(new Intent(this, DiagnosticsActivity.class)))));
        col.addView(Ui.row(this,
                Ui.button(this, "Export saves…", v -> pickFolder(REQ_EXPORT_SAVES)),
                Ui.button(this, "Import saves…", v -> pickFolder(REQ_IMPORT_SAVES))));

        setContentView(Ui.scroll(this, col));
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.importer().setListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        app.importer().setListener(null);
        super.onPause();
    }

    private void pickFolder(int requestCode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivityForResult(i, requestCode);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No document picker available on this device.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri tree = data.getData();
        if (requestCode == REQ_IMPORT_GAME) {
            importStatus.setText("Scanning selected folder…");
            app.importer().start(tree);
            updateButtons();
        } else if (requestCode == REQ_EXPORT_SAVES || requestCode == REQ_IMPORT_SAVES) {
            final boolean export = requestCode == REQ_EXPORT_SAVES;
            new Thread(() -> {
                String msg;
                try {
                    SaveManager sm = new SaveManager(this, app.paths());
                    int n = export ? sm.exportTo(tree) : sm.importFrom(tree);
                    msg = String.format(Locale.ROOT, "%s %d save file(s).", export ? "Exported" : "Imported", n);
                } catch (Exception e) {
                    msg = "Save transfer failed: " + e.getMessage();
                }
                final String m = msg;
                main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            }, "save-transfer").start();
        }
    }

    private void refresh() {
        updateButtons();
        RuntimeStatus rs = new RuntimeStatus(app.paths());
        runtimeStatus.setText(rs.describe());
        if (app.importer().isRunning()) return;
        gameStatus.setText("Checking imported files…");
        final File gameDir = app.paths().gameDir();
        new Thread(() -> {
            final GameInstallation inst = GameInstallation.inspect(gameDir);
            final GameReadiness ready = GameReadiness.evaluate(facts(inst, rs));
            main.post(() -> show(inst, ready));
        }, "inspect-game").start();
    }

    private void show(GameInstallation inst, GameReadiness ready) {
        if (isFinishing()) return;
        lastInstallation = inst;
        gameStatus.setText(inst.present ? "Imported to " + app.paths().gameDir() + "\n\n" + inst.describe() : inst.describe());
        playButton.setEnabled(ready.canStart);
        android.util.Log.i(ShcApplication.TAG, "Start Game " + (ready.canStart ? "enabled" : "disabled") + ": " + ready.reason);
        playButton.setText(app.playLife.active() ? "Return to the game" : "Start Game");
        playReason.setText(ready.reason + "\n" + String.join("\n", ready.checklist));
    }

    /** Facts for {@link GameReadiness}; reads files: background thread. */
    private GameReadiness.Facts facts(GameInstallation inst, RuntimeStatus rs) {
        AppPaths p = app.paths();
        RuntimeLayout rt = new RuntimeLayout(p.runtimeDir());
        GameReadiness.Facts f = new GameReadiness.Facts();
        f.runtimeInstalled = rs.isInstalled();
        f.runtimeHasX11 = rt.hasX11();
        f.runtimeHasCncDdraw = rt.cncDdraw().isFile();
        f.runtimeHasDdrawTest = rt.guiSmoke().isFile();
        f.gameImported = inst.present;
        GameInstallation.Executable c = inst.launchCandidate();
        f.launchExe = c == null ? null : c.name;
        f.prefixComplete = WinePrefixSetup.isComplete(p.prefixDir());
        f.importRunning = app.importer().isRunning();
        f.sessionRunning = app.playLife.active();
        if (inst.present) {
            try {
                f.bytesToCopy = PrivateGameCopy.bytesToCopy(p.gameDir(), p.privateGameDir());
                File parent = p.privateGameDir().getParentFile();
                if (parent != null) f.freeInternal = new android.os.StatFs(parent.getPath()).getAvailableBytes();
            } catch (Exception ignored) {
                // unknown: the launch checks the space again before copying
            }
        }
        return f;
    }

    private void startGame() {
        startActivity(new Intent(this, GameActivity.class));
    }

    private GameInstallation lastInstallation;

    private void copyFingerprint() {
        GameInstallation inst = lastInstallation;
        if (inst == null || !inst.present) {
            Toast.makeText(this, "Import the game first.", Toast.LENGTH_LONG).show();
            return;
        }
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SHC build fingerprint", inst.fingerprints()));
            Toast.makeText(this, "Build fingerprint copied.", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateButtons() {
        boolean running = app.importer().isRunning();
        importButton.setEnabled(!running);
        cancelButton.setEnabled(running);
        progress.setVisibility(running ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete imported game?")
                .setMessage("This removes the copied game files from this device. Save games inside the game "
                        + "folder are deleted too — export them first if you want to keep them. Your original files "
                        + "are not touched.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                    String msg = "Imported game deleted.";
                    try {
                        File dir = app.paths().gameDir();
                        if (dir.exists()) AppPaths.deleteTree(dir, app.paths().gameStorageRoot());
                    } catch (Exception e) {
                        msg = "Delete failed: " + e.getMessage();
                    }
                    final String m = msg;
                    main.post(() -> {
                        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                        refresh();
                    });
                }, "delete-game").start())
                .show();
    }

    @Override
    public void onProgress(long copiedBytes, long totalBytes, String currentFile) {
        updateButtons();
        int permille = totalBytes > 0 ? (int) (copiedBytes * 1000 / totalBytes) : 0;
        progress.setProgress(permille);
        importStatus.setText(String.format(Locale.ROOT, "Copying %d / %d MB — %s",
                copiedBytes >> 20, totalBytes >> 20, currentFile));
    }

    @Override
    public void onFinished(boolean success, String message) {
        importStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        refresh();
    }
}
