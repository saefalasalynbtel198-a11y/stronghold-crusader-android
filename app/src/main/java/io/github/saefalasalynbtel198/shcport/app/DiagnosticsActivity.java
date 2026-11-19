package io.github.saefalasalynbtel198.shcport.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

import io.github.saefalasalynbtel198.shcport.BuildConfig;

/** Device facts that decide runtime compatibility, plus the last crash report. */
public final class DiagnosticsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String report = buildReport();
        LinearLayout col = Ui.column(this);
        col.addView(Ui.title(this, "Diagnostics"));
        col.addView(Ui.button(this, "Copy report to clipboard", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("SHC port diagnostics", report));
                Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show();
            }
        }));
        col.addView(Ui.body(this, report));
        setContentView(Ui.scroll(this, col));
    }

    private String buildReport() {
        AppPaths p = ((ShcApplication) getApplication()).paths();
        StringBuilder sb = new StringBuilder();
        sb.append("App: ").append(BuildConfig.APPLICATION_ID).append(' ').append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("SoC: ").append(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE).append('\n');
        sb.append("ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        boolean has64 = Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");
        sb.append("arm64-v8a: ").append(has64 ? "yes" : "NO — the runtime requires a 64-bit ARM device").append('\n');
        sb.append("32-bit ARM execution: ").append(Build.SUPPORTED_32_BIT_ABIS.length > 0
                ? "available" : "not available (the planned WoW64 runtime does not need it)").append('\n');

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ConfigurationInfo ci = am.getDeviceConfigurationInfo();
            sb.append("OpenGL ES: ").append(ci.getGlEsVersion()).append('\n');
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append(String.format(Locale.ROOT, "RAM: %d MB total, %d MB available\n", mi.totalMem >> 20, mi.availMem >> 20));
        }
        sb.append(String.format(Locale.ROOT, "Free space (game storage): %d MB\n",
                new StatFs(p.gameStorageRoot().getPath()).getAvailableBytes() >> 20));
        sb.append(String.format(Locale.ROOT, "Free space (internal): %d MB\n",
                new StatFs(p.internalRoot().getPath()).getAvailableBytes() >> 20));
        sb.append("Game folder: ").append(p.gameDir()).append(p.gameDir().isDirectory() ? "" : " (not imported)").append('\n');
        sb.append("Runtime: ").append(new RuntimeStatus(p).isInstalled() ? "installed" : "not installed").append('\n');
        sb.append("Saves found: ").append(new SaveManager(this, p).listSaves().size()).append('\n');

        File crash = p.crashLog();
        sb.append("\nLast crash report:\n");
        if (crash.isFile()) {
            try {
                sb.append(new String(Files.readAllBytes(crash.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                sb.append("(unreadable: ").append(e.getMessage()).append(")");
            }
        } else {
            sb.append("(none)");
        }
        return sb.toString();
    }
}
