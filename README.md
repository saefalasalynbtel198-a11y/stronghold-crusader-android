# Stronghold Crusader — Android port (unofficial)

A dedicated Android launcher that will run the **original** Stronghold Crusader HD 1.41 on ARM64 phones and
tablets through Wine (x86-64 WoW64) + Box64, with touch controls that map to the game's own mouse mechanics.

> **Status: BUILD 2 (0.2.0-dev.1) — development build. The game cannot be played yet.**
> The app imports and validates your game files, manages saves, tests touch controls, installs the Wine + Box64
> runtime package and self-tests it on the device. Rendering the game (display server) is BUILD 3.
> See [docs/EXECUTION_TRACKER.md](docs/EXECUTION_TRACKER.md).

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
| Target / compile SDK | 28 / 35 (targetSdk 28 needed to execute the runtime; sideload only) |
| Device requirement | ARM64 (arm64-v8a) for the runtime |
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
