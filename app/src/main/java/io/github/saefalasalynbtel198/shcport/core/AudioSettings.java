package io.github.saefalasalynbtel198.shcport.core;

/**
 * The player's sound settings. Music and effects have their own sliders in the game's options; the game mixes both
 * into one output, so the app offers sound on/off, master volume and mute on top of them.
 */
public final class AudioSettings {
    /** Off: the game starts exactly as in 0.4.1 (no sound output is offered to Wine). Applies at the next Start Game. */
    public boolean enabled = true;
    /** 0..100 % of the game's own level. */
    public int volume = 100;
    public boolean muted;

    public AudioSettings sanitized() {
        AudioSettings s = new AudioSettings();
        s.enabled = enabled;
        s.volume = Math.max(0, Math.min(100, volume));
        s.muted = muted;
        return s;
    }

    /** Linear gain applied to the samples (0 when muted). */
    public float gain() {
        return muted ? 0f : Math.max(0, Math.min(100, volume)) / 100f;
    }
}
