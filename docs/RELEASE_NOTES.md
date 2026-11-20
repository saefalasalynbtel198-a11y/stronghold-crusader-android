# Release notes

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
