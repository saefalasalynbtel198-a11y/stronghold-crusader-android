package io.github.saefalasalynbtel198.shcport.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Environment additions for the game when game sound is on: ALSA reads the app's configuration (the SHC plugin as the
 * default device), and Wine uses its ALSA driver only (its PulseAudio driver has no library on Android and is disabled
 * so it is not probed first). Everything else of the launch stays as in {@link WineLaunchPlan}.
 */
public final class GameSoundEnv {
    public static final String NO_PULSE = "winepulse.drv=d";

    private GameSoundEnv() {}

    public static Map<String, String> forConfig(String asoundConf) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("ALSA_CONFIG_PATH", asoundConf);
        e.put("WINEDLLOVERRIDES", WineLaunchPlan.DLL_OVERRIDES + ";" + NO_PULSE);
        return e;
    }
}
