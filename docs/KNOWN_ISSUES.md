# Known issues and limitations

## Blocking gameplay

1. **The game cannot be started yet.** The Wine + Box64 runtime is not bundled (BUILD 2–3). The *Start game* button
   is disabled and says why.
2. **No supported game copy is available to the project.** The supplied archive is a modified repack of the
   SecuROM disc release (see SOURCE_ARCHIVE_AUDIT.md §6). A DRM-free HD 1.41 copy is required for testing.

## Functional limitations (BUILD 1)

* Touch controls are exercised on a test surface only; nothing is injected into a game yet.
* Save management covers file export/import only; in-game save/load is untested.
* DirectPlay multiplayer, WinHelp `.hlp` files and the map editor are out of scope for now.
* No hardware mouse/keyboard pass-through yet.

## Engineering debt

* GitHub Actions are pinned to major tags (`@v4`, `@v3`, `@v2`), not commit SHAs — pin once SHAs can be verified.
* No Gradle wrapper jar in the repository (CI installs Gradle 8.11.1 via `gradle/actions/setup-gradle`).
* `CNC_DDRAW_SHA256` is empty until the first `runtime-components.yml` run records it.
* cnc-ddraw defaults in `CncDdrawConfig` are untuned.
* Whether the runtime can execute from app storage with targetSdk 35 must be decided in BUILD 3
  (alternatives: ship binaries as `lib*.so` in `nativeLibraryDir`, or targetSdk 28 for sideload builds).
