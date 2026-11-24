# Audio and display resolution: investigation (no code changed)

Status 2026-11-24, on top of the 0.4.1 baseline (BASELINE_0.4.1.md). Findings come from the pinned component sources
and build logs; items marked **device** still need a check on the phone.

## Audio

### The current audio path

```
Stronghold Crusader → Miles Sound System (mss32.dll, the game's own) → DirectSound / winmm (Wine, i386 PE)
  → mmdevapi (Wine) → Wine audio driver: winepulse.drv → winepulse.so, or winealsa.drv → winealsa.so
  → winealsa.so (x86_64, emulated by Box64) calls libasound.so.2
  → Box64's built-in libasound wrapper → native arm64 libasound (Debian, in runtime r6)
  → /dev/snd/* (ALSA kernel devices)
```

What is in place (verified):

* Wine 11.0 (`wine-11.0-amd64-wow64.tar.xz`, SHA-256 checked) contains `winealsa.drv`/`winealsa.so`,
  `winepulse.drv`/`winepulse.so`, `dsound.dll`, `winmm.dll`, `mmdevapi.dll` (32- and 64-bit).
* Box64 v0.3.8 in r6 is built with its libasound wrapper (`wrappedlibasound.c` in the build log), so Wine's ALSA calls run
  in native arm64 code.
* Runtime r6 includes Debian's arm64 `libasound.so.2` (package `libasound2t64`).
* Runtime r6 has no PulseAudio client library and Box64 was built without a libpulse wrapper, so `winepulse` cannot
  load; Wine falls back to ALSA.

The missing piece: **an output that Android lets an app use.** ALSA's kernel devices (`/dev/snd/*`) belong to Android's
audio server; regular apps (SELinux domain `untrusted_app`) cannot open them. Android apps play sound only through
Android's audio APIs (AAudio, OpenSL ES, AudioTrack). So the game's sound reaches libasound and stops there. The game
itself does not stop: it reaches the menu and runs. **device**: the expected sign is an ALSA open error (or an SELinux
denial for `/dev/snd`) in the game's Wine log and logcat; the 0.4.1 Report would show it.

Installing another library alone would not help: whatever Wine uses must end in an Android audio API inside the app.

### Options

| Option | How | Runtime r6 files | Effort / risk |
|---|---|---|---|
| **A. ALSA plugin + app audio bridge (recommended)** | A small native ALSA PCM plugin (arm64, glibc, built in CI against Debian's alsa-lib) that sends PCM over a local socket to the app; the app plays it with AAudio/AudioTrack. Wine keeps its ALSA driver, Box64 keeps its native libasound wrapper. Selected with `ALSA_CONFIG_PATH` pointing to an `asound.conf` in app storage. | Unchanged: plugin and config ship in the APK and are copied to app storage; only the game's environment gains `ALSA_CONFIG_PATH` | Medium. This is the approach Winlator-type apps use. Volume, mute, pause and Android audio-focus handling live in the app. |
| B. PulseAudio server in the app | A PulseAudio server built for Android with an AAudio/OpenSL sink, plus winepulse | New runtime: libpulse (x86_64 for Box64 to emulate, or a rebuilt Box64 with a libpulse wrapper) | High: rebuilds runtime parts, larger package, more processes. |
| C. ALSA's built-in "file" plugin to a pipe | No native code | Unchanged | Not reliable: no playback clock (the game would write audio as fast as possible or stall). |
| D. Own Wine audio driver | A Wine driver talking to the app | New Wine build | High; replaces a verified component. |

Plan for A (when approved):

1. Bridge: an app-side Java/NDK audio thread reading 16-bit PCM from an abstract socket, played through AAudio (or
   AudioTrack); master volume and mute applied there; audio focus (calls, other apps) pauses and resumes it.
2. Plugin: ALSA ioplug PCM (`libasound_module_pcm_shc.so`): accepts the game's format (Miles usually opens 22.05/44.1 kHz
   16-bit stereo through DirectSound; Wine's mixer converts), paces by the bridge's consumption, reports delay.
3. Session: started with the game, stopped with it; the Report shows backend, rate, channels, buffer, underruns.
4. Tests: plugin + bridge in CI (a Linux ALSA client and a Windows tone program under Box64/Wine writing a known
   signal; the bridge side checks the PCM arrives non-silent with the right rate/format, simultaneous streams mixed by
   Wine, pause/resume, shutdown). This proves the pipeline up to the bridge, not the phone's speaker: that is a device
   test.

Music and effects volume: the game has its own music/SFX sliders in its options and plays both through the same
DirectSound output, so the app can provide master volume, mute and audio on/off. Separate app sliders for music and
effects would conflict with the game's own and are not reliably separable at the bridge.

## Display resolution

Facts (from `core/X11DisplayPlan`, `core/CncDdrawConfig` and cnc-ddraw v7.0.0.0 `src/dd.c`):

* **X screen (Xvfb)**: fixed 1024×768×24. This is what the app draws and what touch is mapped to.
* **Game render resolution**: chosen in the game's own options. cnc-ddraw (`resolutions=2`, full list) offers the
  game its list (640×480, 800×600, 1024×768, 1280×1024, 1280×720, …) plus the modes Wine reports for the X screen.
* **cnc-ddraw** (fullscreen, GDI renderer) scales the game's image to the X screen on the CPU, keeping the aspect ratio
  (it adjusts mouse coordinates the other way: `adjmouse=true`).
* **Android**: the app scales the X screen to the phone with the aspect kept (black bars), on the GPU.

So "resolution" has two independent knobs:

1. The game's render resolution: already selectable in the game's options. Lower is faster (less CPU work for the
   game and cnc-ddraw). 1024×768 matches the X screen 1:1 with no cnc-ddraw scaling.
2. The X screen size: an app setting that would need Xvfb restarted, so it applies at the next Start Game. Larger
   (1280×960) gives a sharper picture for game modes above 1024×768, at a CPU cost for every frame (GDI copy, framebuffer
   upload). A widescreen X screen (for example 1280×720 for a 16:9 game mode) would fill the phone's width.

What a "Display & Resolution" screen can honestly offer: the X screen size (1024×768 default and fallback; 640×480,
800×600, 1280×960; Automatic = the largest 4:3 size that fits the phone, capped for performance) and the scaling (keep
aspect; stretch optional). It must say that the game's own resolution is set in the game's options. Touch mapping keeps
working if `ViewportTransform` and `X11Input` use the active X screen size instead of the constant. Each size has to be
checked end to end in CI (Xvfb size, cnc-ddraw scaling, pixel and input checks) before it is offered, and on the phone
for performance.

Both changes touch parts the owner asked to keep unchanged until the gameplay evaluation (Xvfb settings, game
configuration), so they wait for the owner's decision.
