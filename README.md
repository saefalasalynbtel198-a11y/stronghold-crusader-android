# Stronghold Crusader — Android port (unofficial)

A dedicated Android launcher that will run the **original** Stronghold Crusader HD 1.41 on ARM64 phones and
tablets through Wine (x86-64 WoW64) + Box64, with touch controls that map to the game's own mouse mechanics.

> **Status: BUILD 1 (0.1.0-dev.1) — development build. The game cannot be played yet.**
> The app installs, imports and validates your game files, manages saves and lets you test the touch controls.
> The Windows runtime that actually starts the game is the next milestone. See [docs/EXECUTION_TRACKER.md](docs/EXECUTION_TRACKER.md).

Not affiliated with Firefly Studios. No game files are included — you need your own copy of
**Stronghold Crusader HD** (GOG or Steam).

## Documentation

| Document | Content |
|---|---|
| [SOURCE_ARCHIVE_AUDIT](docs/SOURCE_ARCHIVE_AUDIT.md) | What the supplied archive contains, and why HD 1.41 is required |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | Chosen runtime stack and evaluated alternatives |
| [BUILD_INSTRUCTIONS](docs/BUILD_INSTRUCTIONS.md) | Local builds, CI, downloading/installing the APK, signed releases |
| [ASSET_IMPORT_GUIDE](docs/ASSET_IMPORT_GUIDE.md) | Importing your game folder and saves |
| [TOUCH_CONTROLS](docs/TOUCH_CONTROLS.md) | Gesture mapping and settings |
| [ANDROID_TEST_REPORT](docs/ANDROID_TEST_REPORT.md) | What has and has not been tested |
| [KNOWN_ISSUES](docs/KNOWN_ISSUES.md) | Limitations |
| [RELEASE_NOTES](docs/RELEASE_NOTES.md) | Per-version changes |
| [EXECUTION_TRACKER](docs/EXECUTION_TRACKER.md) | Current milestone, blockers, next task |

## Quick facts

| | |
|---|---|
| Package | `io.github.saefalasalynbtel198.shcport` |
| Minimum Android | 8.0 (API 26) |
| Target ABI | none in BUILD 1; arm64-v8a once the runtime is bundled |
| Permissions | none |
| CI artifact | `Stronghold-Crusader-Android-APK` |

## Repository layout

```
app/src/main/java/.../core   pure-Java logic (PE inspection, validation, touch mapping, launch plan) + unit tests
app/src/main/java/.../app    Android UI, import, saves, diagnostics
runtime/                     pinned runtime component versions and build/fetch scripts
scripts/ci/                  APK verification, emulator smoke test, repository hygiene
.github/workflows/           android-build, release, runtime-components
docs/                        project documentation
```
