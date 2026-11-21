# Release notes

## 0.3.6-dev.2 — side-by-side development app with a permanent development key (not a playable game)

* Debug builds are now a separate app: application ID `io.github.saefalasalynbtel198.shcport.dev`, label
  "SHC Port DEV", with their own app storage. They install next to the 0.3.5-dev.1 app (`...shcport`), which is not
  touched. The development app needs its own runtime installation and game import.
* Signed only with the owner's permanent development key from GitHub Actions secrets, pinned by certificate
  fingerprint; CI builds no APK without it (docs/DEV_SIGNING.md).
* Game launch test (step 3.3) unchanged from 0.3.6-dev.1. Start Game stays disabled. No runtime change (r6).

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
