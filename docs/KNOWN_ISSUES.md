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
  glibc registers `rseq`, which Android's app filter blocks. Mitigation since 0.2.1-dev.1: `GLIBC_TUNABLES=glibc.pthread.rseq=0`
  for every runtime process. If the on-device probe shows further blocked syscalls, the fallback is an
  Android-patched glibc (Termux glibc-packages, which Winlator credits).
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
