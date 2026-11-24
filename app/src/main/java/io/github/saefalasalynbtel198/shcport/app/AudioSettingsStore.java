package io.github.saefalasalynbtel198.shcport.app;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.saefalasalynbtel198.shcport.core.AudioSettings;

/** Persists {@link AudioSettings} in SharedPreferences. */
final class AudioSettingsStore {
    private final SharedPreferences prefs;

    AudioSettingsStore(Context context) {
        prefs = context.getSharedPreferences("audio", Context.MODE_PRIVATE);
    }

    AudioSettings load() {
        AudioSettings d = new AudioSettings();
        AudioSettings s = new AudioSettings();
        s.enabled = prefs.getBoolean("enabled", d.enabled);
        s.volume = prefs.getInt("volume", d.volume);
        s.muted = prefs.getBoolean("muted", d.muted);
        return s.sanitized();
    }

    void save(AudioSettings in) {
        AudioSettings s = in.sanitized();
        prefs.edit().putBoolean("enabled", s.enabled).putInt("volume", s.volume).putBoolean("muted", s.muted).apply();
    }
}
