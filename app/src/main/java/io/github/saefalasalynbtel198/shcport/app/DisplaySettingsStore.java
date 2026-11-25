package io.github.saefalasalynbtel198.shcport.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import io.github.saefalasalynbtel198.shcport.core.DisplayResolution;

/**
 * Persists the Display Resolution setting (SharedPreferences "display", key "mode"). Nothing stored means the
 * default 1024×768, so existing installations keep the 0.5.1 display. Applied at the next Start Game.
 */
final class DisplaySettingsStore {
    private final SharedPreferences prefs;

    DisplaySettingsStore(Context context) {
        prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE);
    }

    DisplayResolution.Mode load() {
        return DisplayResolution.Mode.fromKey(prefs.getString("mode", DisplayResolution.DEFAULT.key));
    }

    void save(DisplayResolution.Mode mode) {
        prefs.edit().putString("mode", mode.key).apply();
    }

    void restoreDefault() {
        prefs.edit().remove("mode").apply();
    }

    /** Total RAM of the device in bytes (0 if unknown), used by the Auto setting. */
    static long totalMemory(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 0;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem;
    }
}
