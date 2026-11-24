# Release notes

## 0.5.1 — smoother game sound (fix of a slight stutter in 0.5.0)

* Device result of 0.5.0 (owner): music and effects play with no lag; touch controls unchanged; a very slight
  stutter. The Report showed Android's output never ran dry (0 underruns, 160 ms buffer, 30 s played).
* Cause: Wine inserts a period of silence (about 10 ms) whenever it finds nothing left in its ALSA device
  (winealsa `alsa_write_data` lead-in). The plugin reported audio as played as soon as Android's output accepted it, so
  when Wine's audio thread was a little late on the busy phone, the device looked empty although Android still had up
  to 160 ms of sound queued, and Wine added silence. Reproduced in a test (late writer + Wine's rule: 24 gaps in 5 s).
* Fix (plugin only): while the stream runs, the last period written is not reported as played, so Wine never sees an
  empty device while Android still plays; with the same late writer: 0 gaps.
* Nothing else changed: touch controls, display, launch path, runtime r6 files, sound settings.
* CI: a regression test runs the plugin with the app's receiver (on time, late writer, two streams, no app).
* 0.5.0 must be uninstalled first (debug key of each build differs).

## 0.5.0 — game sound (test build; touch controls unchanged from 0.4.1)

* Device result of 0.4.1 (owner): playable — menus, starting a game, unit selection by tap and drag, move orders,
  building placement and construction. 0.4.1 is the recorded baseline (docs/BASELINE_0.4.1.md).
* Why there was no sound: Wine sends the game's sound through its ALSA driver to runtime r6's native libasound, which
  needs ALSA sound devices; Android does not let apps open them (docs/AUDIO_AND_RESOLUTION_INVESTIGATION.md).
* New: a small ALSA plugin (arm64, in the APK) hands the sound to the app, which plays it through Android (AudioTrack,
  one per stream the game opens). The game is paced by the real output; if the app is not listening the plugin keeps
  time by the clock, so the game never waits for sound.
* Settings → Sound: Game sound on/off (next Start Game; off = launch exactly as 0.4.1), Volume, Mute (live). Music and
  effects keep their own sliders in the game's options.
* Sound pauses while the game screen is not shown or another app takes the audio focus (call, alarm) and resumes
  after; it stops with Exit. Report shows the sound streams (rate, channels, buffer, underruns).
* Runtime r6 files unchanged: the plugin and its configuration are installed into app storage; the game's environment
  gains ALSA_CONFIG_PATH and winepulse.drv=d (Wine's ALSA driver only) when sound is on.
* Touch input, click timing, gestures and the display are unchanged.
* Not verified on a phone yet. 0.4.1 must be uninstalled first (debug key of each build differs).

## 0.4.1 — taps click in the game (fix of 0.4.0's touch input)

* Device result of 0.4.0 (owner, Stronghold Crusader HD 1.41): Start Game, intro and main menu work; the cursor
  follows the finger exactly and Esc/Enter/Space/Keys work, but tapping "Crusader" did nothing.
* Cause: a tap sent left press and left release to the X server at the same instant (0 ms apart). The CI test
  window reads Windows messages, which queue both, so it saw the click; the game reads the mouse button state once
  per frame and sees the button already up. cnc-ddraw (which hides mouse messages while its cursor lock is off) was
  checked in its v7.0.0.0 source: the moving cursor shows the lock was on, so it was not the cause.
* Fix: every click holds the button like a real mouse click (120 ms, adjustable in Control settings: "Click: button
  held for"): tap = move → left press → hold → release; long press = right click with the same hold; a very short drag
  is extended to the hold time before release. Drag still holds the left button from the moment the finger leaves the
  tap radius until it lifts.
* New side key **Right**: while on, one finger holds the right mouse button for the whole touch (tap = right click,
  drag = right-button drag, for the camera interface). Tap it again for normal left clicks.
* CI now checks on the Windows side (test program under Wine): one left click per tap held ≥ 80 ms at the tapped
  pixel, drag with left held, long press = right click without a left click, right-button drag, a real double click,
  cancel releases, a second finger does not click, no button left held; a polling thread shows the button state as a
  per-frame game sees it.
* No runtime change (r6). 0.4.0 must be uninstalled first (debug key of each build differs).

## 0.4.0 — Start Game: playable game screen with touch and keyboard input (development build)

* Device result of 0.3.8 (owner, Xiaomi 25053PC47G, Android 16, DRM-free 2018 build): DirectDraw pre-check PASS
  (native cnc-ddraw, CreateSurface OK); the real Stronghold Crusader ran from the private copy, showed its loading
  screen and reached the "Enter Your Title" menu (9 Wine processes, ~700 MB). It could not be controlled.
* **Start Game** on the main screen is enabled when the runtime (r6: Xvfb, cnc-ddraw, DirectDraw check), the imported
  game (an allowed executable) and internal storage for the private copy are present. It opens the new game screen.
* Game screen: the same launch path as the device-verified game launch test (private SHA-256-verified copy, Wine
  prefix, Xvfb, cnc-ddraw GDI, `D:\<exe>`), without its diagnostic extras (no relay trace, no snapshots; the DirectDraw
  pre-check runs until it has passed once for this runtime, cnc-ddraw and app version). Full screen, 4:3 kept
  (letterboxed).
* Input: a small X11 client in the app sends XTEST events to Xvfb; Wine passes them to the game as Windows mouse and
  keyboard messages. Tap = left click, long press = right click, drag = left drag, two-finger drag = arrow keys
  (scroll), pinch = wheel (the existing, unit-tested touch mapper with the renderer's letterbox geometry). Side keys:
  Esc, Enter, Space, Keys (soft keyboard; typed text goes to the game), Report, Exit. Hardware keyboards work.
* Session: one game at a time (a second Start Game returns to it); the game keeps running while the screen is paused;
  Exit Game stops Wine and Xvfb; an ended game shows why and offers Start again.
* Not included: sound (Android does not let apps open ALSA devices; Wine needs another audio backend in the runtime).
* No runtime change (r6). 0.3.8 must be uninstalled first (debug key of each build differs).

## 0.3.8 — fix of the DirectDraw pre-check regression of 0.3.7 (development build, not yet a playable game)

* Device result of 0.3.7 (owner, two builds: GOG 2012 and DRM-free 2018): `DirectDraw pre-check FAILED — exit 42`,
  pixels OK, "NO native load of ddraw.dll in the log"; the game was not started.
* Cause (regression of 0.3.7, not the game build): 0.3.7 added `+seh,+relay` to the Wine debug setting shared by the
  pre-check, and ran `reg add RelayInclude` (the relay filter) under that same setting with a 120 s limit. With an
  unfiltered relay trace the pre-check log grows far beyond 2 MB, and the app looked for Wine's `ddraw.dll ... native`
  line only in the last 2 MB. Exit 42 and the colours show the test itself had worked.
* Fix: the pre-check uses the 0.3.6 setting again (`err+all,fixme-all,+loaddll`, device PASS in 0.3.6); reg.exe runs
  without +relay (up to 600 s, exit code checked); the game gets +relay only if the filter was set. The verdict reads
  the whole log and additionally requires the test program's own report (ddraw.dll from the 32-bit system directory,
  SetDisplayMode and CreateSurface(primary) = 0x00000000, frames written). The report lists that evidence.
* No runtime change (r6). Start Game stays disabled. 0.3.7 must be uninstalled first (debug key of each build differs).

## 0.3.7 — fix attempt for the real-game launch failure of 0.3.6 (development build, not yet a playable game)

* Device report of 0.3.6 (owner, Xiaomi 25053PC47G, Android 16): DirectDraw pre-check PASS; the real game exited after
  about 2 s with exit code 0. Wine logged `map_image_into_view failed to set 60000020 protection ... noexec filesystem?`
  for `Stronghold Crusader.exe`, `binkw32.dll` and `mss32.dll`.
* Verified cause of those errors: the game was loaded from Android shared storage (`/storage/emulated/0/...`), which
  does not allow executable mappings. Wine's own test programs, run from internal storage, never show this error.
* Fix: the game launch test now runs the game from a **private copy in the app's internal storage**
  (`files/game-private`), made file by file through a temporary file, each file checked with SHA-256 against the
  original before it is used. The copy is reused on later launches (only new or changed files are copied again; EXE/DLL
  files are re-checked every time). Free space is checked first. Wine's D: points to the private copy. The imported
  game folder is only read and is still compared before and after the launch.
* Diagnostics for the clean exit: a Wine relay trace limited to ExitProcess/TerminateProcess, message boxes,
  window creation, CreateFile, process start and DirectDrawCreate; exception trace (+seh); Box64 crash messages. The
  report now begins with "Why the game ended / what it did" and the last 60 lines of the game's Wine log.
* No runtime change (r6). Start Game stays disabled. 0.3.6 must be uninstalled first (debug key of each build differs).

## 0.3.6 — SHC Port: game launch test (development build, not yet a playable game)

* App "SHC Port", package `io.github.saefalasalynbtel198.shcport`, version 0.3.6, signed with the standard Android
  debug key. 0.3.5 must be uninstalled first (different signing key); then install runtime r6 from its zip and import
  the game folder again (the Wine prefix is created automatically).
* Runtime → **Game launch test**: starts the imported Stronghold Crusader through Box64 → Wine → cnc-ddraw (GDI) →
  X11 → Xvfb → the app's screen, after a DirectDraw pre-check; the game folder is checked before and after. No input
  and no sound yet. Start Game stays disabled until the game is verified on a phone.
* Includes the device-verified display test (step 3.2) and its fixes. No runtime change (r6).

## 0.3.6-dev.1 — BUILD 3 step 3.3: game launch test (development build, not a playable game)

* Runtime → **Game launch test**: starts the imported game through the step 3.2 display path (Box64 → Wine →
  cnc-ddraw with the GDI renderer → X11 → Xvfb → this screen), after a DirectDraw pre-check with the runtime's own
  test program. Shows the game process, whether cnc-ddraw was loaded, picture activity, FPS, CPU/memory; saves
  PNG snapshots of the X screen; Copy report.
* The game files are only read: drive D: is a link in the Wine prefix, cnc-ddraw and its ddraw.ini are placed in the
  prefix. The game folder is recorded before the launch and compared after it.
* No input (step 3.4) and no sound (phase 4) yet. Start Game stays disabled. No runtime change (r6).

## 0.3.5-dev.1 — BUILD 3 step 3.2 fix: Wine prefix in the display test (development build, not a playable game)

* **Device result (owner, 2026-11-21): display test PASSED** on Xiaomi 25053PC47G, Android 16 (Xvfb :37, Wine window, pixel check, ~27 fps, pause/resume; Wine tests 6/6, self-test 4/4).

* Fix for the device failure of 0.3.4-dev.1 (`wine: chdir to .../files/prefix/wine : No such file or directory`):
  Wine creates only the last directory of WINEPREFIX; the display test never created `files/prefix` (the Wine tests
  and the self-test do). The display test now creates and checks it first.
* The display test creates the prefix with the Wine tests' own steps and checks (wineboot --init and wineserver -w
  must exit 0, `system.reg` and `drive_c/windows` must exist), in the runtime directory, and stops Wine again.
  Xvfb starts only after the prefix is ready.
* Report: prefix path, existence, creation result, write test, owner/mode, every Wine command with exit code.
* APK check prints the signing certificate's SHA-256.
* Xvfb changes of 0.3.3/0.3.4 kept. No runtime change (r6). Start Game stays disabled.

## 0.3.4-dev.1 — BUILD 3 step 3.2 fix: Xvfb readiness on stdout (development build, not a playable game)

* Fix for the device failure of 0.3.3-dev.1 (`Cannot write display number to fd 3`): Android's `/system/bin/sh`
  (mksh) closes descriptors above 2 that a script opens with `exec`, so descriptor 3 never reached Xvfb. Xvfb is
  now started directly through the runtime loader (no shell) with `-displayfd 1`: it writes the display number to
  its stdout pipe and closes it; its messages stay on stderr, which goes to the log separately.
* The app accepts only one line of digits that equals the display it started (:37–:40); anything else on stdout is
  reported and Xvfb is stopped. Output after the number is recorded in the report.
* Report: the exact argv of Xvfb, each display tried and why, and whether Xvfb closed stdout after the number.
* Hard-link fix of 0.3.3 kept (no lock file). No runtime change (r6). Start Game stays disabled.

## 0.3.3-dev.1 — BUILD 3 step 3.2 fix: Xvfb lock file on Android (development build, not a playable game)

* Fix for the device failure of 0.3.2-dev.1 (`Linking lock file (./.X37-lock) in place failed: Permission
  denied`): Android's SELinux policy forbids apps to create hard links, and Xvfb's lock file is made with `link()`.
  Xvfb is now started with its documented `-displayfd` option (through `/system/bin/sh`, which only redirects file
  descriptors), which turns off the lock file and reports the display number once Xvfb accepts connections.
* The display is chosen safely without a lock file: a display whose abstract socket answers is skipped; a display
  Xvfb itself refuses as in use moves on to the next (:37–:40); any other Xvfb error is reported, not skipped.
  Lock files left by 0.3.2-dev.1 are removed (they were never valid).
* Display test report: working directory check before Xvfb starts (path, owner, mode, write/delete test, hard-link
  test) and an SELinux/seccomp/crash section from logcat.
* No runtime change (r6). Start Game stays disabled.

## 0.3.2-dev.1 — BUILD 3 step 3.2: in-app display test (development build, not a playable game)

* Runtime → **Display test**: starts the runtime's Xvfb (r6), runs a Windows test program through Box64 + Wine into
  it, and draws Xvfb's real framebuffer on screen (1024×768, letterboxed). Shows Xvfb and Wine status, display and
  Wine frame rates, frame counter, an automatic colour check, CPU and memory, pause/resume behaviour; Copy report.
* Needs runtime r6 (no runtime change). Import, runtime install, self-test and Wine tests unchanged.
* Start Game stays disabled.

## Runtime r6 — BUILD 3 phase 3 step 3.1 (CI-tested, not yet device-tested)

* glibc Android patch: `setuid`/`setgid` to the process's current IDs succeed without the (trapped) syscall;
  `shmget` fails with ENOSYS instead of being trapped. All earlier fixes (including `accept` → `accept4`) unchanged.
* New in the package: `x11/` (Xvfb with three path strings patched, xkbcomp and its wrapper, xkb data, xdpyinfo)
  and `win/shc-gui-smoke.exe`. Mesa/LLVM are not included (GLX is not used).
* Works with app 0.3.1-dev.1 (self-test, Wine tests). The display itself needs the next app build (step 3.2).
* Runtime r5 stays the known-good fallback.

## 0.3.1-dev.1 + runtime r5 — BUILD 3 fix (development build, not a playable game)

* Fix: wineserver was killed by Android's seccomp filter at its first `accept()` (Android allows only
  `accept4` on arm64), so `wineboot --init` failed with `recvmsg: Connection reset by peer`. Runtime r5's glibc
  uses `accept4`. Box64 unchanged.
* Wine tests report more evidence: environment (uid, seccomp, SELinux), a process watcher showing how every
  Wine process ends (`[watch]` lines), the wineserver directory state after a failure, a second attempt with a
  wineserver started by the app, and SELinux/seccomp lines from logcat.
* Self-test: the seccomp probe also checks `accept`/`accept4`; the glibc test also checks `accept`.
* Start Game stays disabled.

## 0.3.0-dev.1 — BUILD 3 phase 1–2 (development build, not a playable game)

* Requires runtime package **r4** (remove r3 first). r4 adds: Wine patched to keep its wineserver directory inside
  the prefix (Android has no `/tmp`), Box64 whose ELF interpreter is set at install time to the packaged patched
  glibc loader (so Wine's child processes also run on it), and two Windows PE smoke programs (32- and 64-bit).
* Runtime screen: *Run Wine tests (BUILD 3)* — create the Wine prefix, check the wineserver directory, run the PE
  smoke programs (file I/O, %TEMP%, child process, exit code 42), then one diagnostic attempt at the game
  executable without a display. Report saved to the app's logs.
* Start Game stays disabled: there is no display path yet (phase 3).

## 0.2.2-dev.1 — BUILD 2 fix 2 (development build, not a playable game)

* Runtime r3 ships glibc 2.41 rebuilt with an Android seccomp patch: unmodified glibc was killed by SIGSYS on
  Android 16 because it calls `set_robust_list` (and `rseq`, `faccessat2`) — the rseq tunable alone was not enough.
* Self-test: new required step "glibc start-up, threads, fork"; Box64 without the tunable is a diagnostic.
* Game import: build fingerprint (version strings, build date, sections, hashes) and *Copy build fingerprint*.

## 0.2.1-dev.1 — BUILD 2 fix (development build, not a playable game)

* Fix: Box64 was killed by Android's seccomp filter (SIGSYS, exit 159) because glibc registers `rseq` at start-up.
  All runtime processes now run with `GLIBC_TUNABLES=glibc.pthread.rseq=0`. Requires runtime package **r2**.
* Self-test: new static seccomp probe and a with/without comparison for Box64; exit codes are decoded
  (e.g. "killed by signal 31 (SIGSYS …)"). Diagnostic steps are labelled and do not stop the test.

## 0.2.0-dev.1 — BUILD 2 (development build, not a playable game)

* **Runtime screen:** install `shc-runtime-*.zip` (Box64 + Wine 11.0 WoW64 + cnc-ddraw + Debian ARM64
  libraries, built by the *Runtime components* workflow) and run a three-step on-device self-test:
  glibc loader → Box64 → Wine. The report is shown, saved to the app's logs and can be copied.
* targetSdk lowered to 28 so the runtime may execute from app storage (sideload only; see ARCHITECTURE.md).
* Game launch still unavailable (display server: BUILD 3).

## 0.1.0-dev.1 — BUILD 1 (development build, not a playable game)

This build installs and runs on Android 8.0+ but **cannot start Stronghold Crusader yet**: the Wine + Box64
runtime is not included.

Included:

* Import of your own game folder through the Android file picker (no permissions), with progress, cancel and
  atomic replacement.
* Game build detection from the executable (hash + PE inspection); HD 1.41 is the supported target; SecuROM
  disc builds are reported as unsupported.
* Asset completeness check (executables, Miles/Bink DLLs, gm/gfx/fx/maps, optional music/speech/videos).
* Save-game export/import.
* Touch-control test surface using the final gesture → mouse/keyboard mapper; adjustable sensitivity.
* Diagnostics screen (ABIs, 32-bit support, GLES version, RAM, storage, last crash report).

Not included: runtime, rendering, audio, gameplay. See KNOWN_ISSUES.md.
