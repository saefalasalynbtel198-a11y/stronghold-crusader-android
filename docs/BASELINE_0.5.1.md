# Known-good baseline: SHC Port 0.5.1 (playable, with game sound)

Recorded 2026-11-24 on the owner's instruction. Supersedes BASELINE_0.4.1.md as the current reference; the launch
path, display and touch control architecture described there are unchanged in 0.5.1 and remain the reference for
those parts. No further audio changes until the owner asks.

## Device verification (owner, Xiaomi 25053PC47G, Android 16, arm64-v8a, Stronghold Crusader HD 1.41)

0.5.1 on the phone:

* Background music and sound effects play correctly, no noticeable delay.
* A very minor, brief audio stutter may remain about every 10–20 seconds; barely noticeable, does not affect play.
  (0.5.0 stuttered slightly more often; 0.5.1 removed Wine's silent lead-ins. Not investigated further, by decision.)
* Touch controls fully functional and unaffected (as verified for 0.4.1: menus, unit selection by tap and drag, move
  orders, building tools, placement and construction).

## Checkpoint

| Item | Value |
|---|---|
| Code commit | `6e1ef56e9f90080302c911af370e4c758612c7b4` on `feature/build3-step3.3-game-launch` (PR #8, draft, not merged) |
| APK | `SHC-Port-0.5.1.apk`, version 0.5.1 (code 20), Android build run 45453201549, artifact 14950075096 (expires 2026-12-24) |
| APK SHA-256 | `b0b8a8cdcc169a370f1910939cceab2814bd572f9e322e51ce5465f8222533f2` |
| APK signer | standard debug key of that CI run, certificate SHA-256 `3bd0f98520374c61ff167c59715473148904bd58ebcb1eb4336948678a9a79fa` |
| Sound plugin (APK asset `audio/libasound_module_pcm_shc.so`, arm64) | SHA-256 `83f46e1faf5d3f4af2fbed819c46a76986b322de925c9ed6384a12e04887dd47` |
| Runtime | r6, unchanged (SHA-256 `da15d44c028e4bf3a13187696bdbd05aa153fe5691b3f4bd0e113fb60e2dc56a`) |
| Wine / cnc-ddraw | unchanged (see BASELINE_0.4.1.md) |

Recover: `git checkout 6e1ef56`; the CI of that commit rebuilds the same app (a new debug key per run, so a rebuilt APK
needs an uninstall first).

## What 0.5.1 adds to the 0.4.1 baseline

* Game sound: Wine (DirectSound → mmdevapi → winealsa) → Box64's libasound wrapper → runtime r6's native libasound →
  SHC ALSA plugin (`app/nativesrc/alsa-shc/pcm_shc.c`, built for arm64 against Debian trixie alsa-lib in CI) → local
  socket → `core/AudioStreamPump` → `app/AudioTrackSink` (AudioTrack, USAGE_GAME, ≥ 100 ms buffer) → Android.
* Plugin behaviour: 16-bit and 32-bit float input (float converted to 16-bit); position = frames Android's output has
  accepted, with the last period held back while running (no Wine silent lead-ins); clock fallback without the app.
* With sound on, the game's environment gains `ALSA_CONFIG_PATH` (app storage) and `winepulse.drv=d`; with sound off
  the launch is exactly as 0.4.1.
* Settings → Sound: Game sound on/off (next start), Volume, Mute (live). Sound pauses with the game screen and on
  audio-focus loss; Report lists the sound streams.

## Tests protecting it

All of BASELINE_0.4.1.md, plus: `AudioStreamPumpTest` (receiver, gain, mute, pacing, environment);
`scripts/ci/test-audio-plugin-native.sh` (plugin + receiver: on time, late writer with Wine's lead-in rule = 0 gaps,
two streams, no app); qemu step 4c (DirectSound → Wine → winealsa → Box64 → runtime libasound → plugin → receiver, both
tones, pause/resume; sound off without hang); syscall allowlist check including the sound processes.
