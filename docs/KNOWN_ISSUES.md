# Known issues and limitations

## Blocking gameplay

1. **The game cannot be started yet.** The runtime can be installed and self-tested (BUILD 2), but there is no
   display server yet (BUILD 3). The *Start game* button is disabled and says why.
2. **No supported game copy is available to the project.** The supplied archive is a modified repack of the
   SecuROM disc release (see SOURCE_ARCHIVE_AUDIT.md §6). A DRM-free HD 1.41 copy is required for testing.

## Functional limitations (BUILD 1)

* Touch controls are exercised on a test surface only; nothing is injected into a game yet.
* Save management covers file export/import only; in-game save/load is untested.
* DirectPlay multiplayer, WinHelp `.hlp` files and the map editor are out of scope for now.
* No hardware mouse/keyboard pass-through yet.

## BUILD 2 risks (to be answered by the on-device self-test)

* **glibc vs. Android seccomp — confirmed on a device.** Box64 was killed by SIGSYS (exit 159) on Android 16 because
  glibc registers `rseq`, which Android's app filter blocks. The tunable (0.2.1) was not enough: the device
  also blocks `set_robust_list`, which glibc calls in every process. Since 0.2.2-dev.1 the runtime ships an
  Android-patched glibc (runtime/glibc-android). If that still fails on a device, the next option is a Box64 built
  against Android's own libc (bionic), as Winlator's bionic edition does.
* **Kernel page size.** Some newer devices use 16 KB pages; Box64 and Wine must cope (Box64 supports
  non-4K page sizes on other platforms; untested here).
* **Self-test proves execution only.** Passing all three steps does not mean the game renders; that needs the
  display server (BUILD 3).
* The runtime package must be downloaded from GitHub Actions and selected manually (artifact downloads are a
  zip containing the package zip — extract once on the PC/phone first).

## Engineering debt

* GitHub Actions are pinned to major tags (`@v4`, `@v3`, `@v2`), not commit SHAs — pin once SHAs can be verified.
* No Gradle wrapper jar in the repository (CI installs Gradle 8.11.1 via `gradle/actions/setup-gradle`).
* `CNC_DDRAW_SHA256` is empty until the first `runtime-components.yml` run records it.
* cnc-ddraw defaults in `CncDdrawConfig` are untuned.
* targetSdk is 28 from BUILD 2 (decision in ARCHITECTURE.md); the app cannot be published on Google Play.

## Android seccomp (app filter)

* Android traps syscalls outside its app allowlist with SIGSYS. The runtime's glibc is patched for those found so
  far (`runtime/glibc-android/README.md`): `rseq`, `set_robust_list`, `faccessat2`, `fchmodat2`, `clone3`,
  `accept` (0.3.1 / r5).
* System V IPC (`shmget`, `shmat`, `semget`, `msgget`) and POSIX message queues are blocked for apps and are not
  patched. X11 MIT-SHM uses `shmget`: the phase 3 display path must work without it.
* CI checks the Wine start-up path against the allowlist (`runtime/android/`), but only under qemu on Linux;
  the device remains the authority.

## Runtime r6 notes

* CI artifacts expire: r5 (run 44823423675) on 2026-12-20, r6 (run 44911670217) on 2026-12-21. Keep a downloaded
  copy of r5 as the fallback, or ask for a GitHub pre-release to hold both.
* `runtime/glibc-android/README.md` does not yet list the setuid/setgid/shmget hunks (kept unchanged so the r6
  package stays byte-identical to the tested one); the patch file in the package is complete. Updated with the next
  runtime change.
* Xvfb is a patched copy of Debian's binary (three path strings); if Debian changes the binary layout the patch
  step fails the build instead of producing a broken package.

## Game launch test (0.3.6 – 0.3.7)

* 0.3.6 on the phone: the real game ended after about 2 s with exit code 0; Wine could not map its code sections
  executable because the game was on Android shared storage (noexec). 0.3.7 runs it from a SHA-256-verified private
  copy in internal storage (needs internal space for the whole game folder + 256 MB). Why the game chose to exit is not
  known yet; the 0.3.7 report's exit analysis is meant to show it. 0.3.7 is not yet run on the phone.
* 0.3.8 on the phone: the real game reached its "Enter Your Title" menu (no input in the launch test).
* 0.4.0 on the phone: menu reached, cursor and keys work, taps did not click (press/release sent together).
  0.4.1 holds each click 120 ms and adds a Right toggle: playable on the phone (menus, unit selection and orders,
  building). Comfort of the controls in long play is being evaluated by an experienced player.
* cnc-ddraw ignores mouse buttons while its cursor lock is off; if the lock is ever lost, the first click only
  re-locks it (a second tap then works).
* No sound: Wine's ALSA driver cannot open sound devices on Android (apps have no /dev/snd access). Needs an audio
  backend that Android allows (for example AAudio through a PulseAudio server for winepulse) in a future runtime.
* When the app is in the background, Android may freeze or stop it (no foreground service yet); return to the game
  through Start Game. If Android kills the app, the next start stops leftover Wine/Xvfb processes.
* The game screen's long press = right click uses the touch settings' long-press time (Control settings).
* Intro videos (Bink) run under Box64 and cannot be skipped yet.
* The imported 2012 GOG build is older than 1.41 and unverified (UNKNOWN).
* Android external storage cannot be made read-only for the game; changes are detected and reported, not prevented.

## Display test (0.3.2-dev.1 – 0.3.5-dev.1)

* 0.3.2–0.3.4 create the Wine prefix only if `files/prefix` already exists (left by the self-test or the Wine tests);
  with fresh app data wineboot fails with `chdir to .../prefix/wine : No such file or directory`. Fixed in 0.3.5-dev.1
  — CI-reproduced and CI-tested, not yet device-tested.
* Up to 0.3.6-dev.2, CI debug APKs were signed with a new key on every CI run (0.3.5-dev.1 `fcd0e71c…`), so Android
  refuses updates between them; uninstalling removes the runtime, the Wine prefix and the imported game copy. This is
  the likely reason `files/prefix` was missing before the 0.3.4 run. 0.3.6 is also debug-signed (owner's decision): 0.3.5-dev.1
  must be uninstalled first, and a later APK may need the same (docs/SIGNING.md).

* 0.3.3-dev.1 passes the hard-link check on the device but Xvfb fails with "Cannot write display number to fd 3"
  (Android's mksh closes fd 3 on exec); fixed in 0.3.4-dev.1 (`-displayfd 1`, no shell) — CI-reproduced and
  CI-tested, not yet device-tested.
* After writing the display number Xvfb closes its stdout; descriptor 1 can later be reused inside Xvfb (for example
  by a client connection). Xvfb itself writes its messages to stderr, and xkbcomp writes its result to a file, so
  nothing is expected on descriptor 1; the report shows any byte that arrives after the number.

* 0.3.2-dev.1 cannot start Xvfb on Android (hard links are forbidden to apps, Xvfb's lock file needs one); fixed
  in 0.3.3-dev.1 (`-displayfd`, no lock file) — CI-reproduced and CI-tested, not yet device-tested.
* Without a lock file, two Xvfb starting at the same instant on the same display are separated only by the
  abstract socket bind: the second one is refused by the kernel and the app moves to the next display.

* Tearing: Xvfb has no frame lock, so an upload can occasionally mix two frames. Memory access is safe (read-only
  mapping, new file per session, Xvfb unlinks rather than truncates its file).
* Latency is not measured end to end; the app uploads a changed picture at most one display frame after Xvfb holds
  it. Display FPS (screen refresh), uploads/s and Wine content FPS (counter strip) are reported separately.
* Display numbers :37–:40 are used because abstract sockets are shared by all apps; if all four are taken the test
  says so.
* The CI display job uses the r6 artifact of run 44911670217 (expires 2026-12-21).
