# Android test report

Report date: 2026-11-19 · Build: 0.1.0-dev.1 (BUILD 1) · Environment: cloud workspace without Android SDK access.

## Executed

| Test | Where | Result |
|---|---|---|
| Core unit tests (33): PE inspector, build classifier, directory validator, touch mapper, viewport, launch plan, ddraw.ini, settings clamping | JDK 21 `javac` + minimal JUnit-compatible runner | **33 passed, 0 failed** |
| PE inspector + classifier on the audited archive's 3 game executables | same | All identified as x86 PE32 with SecuROM sections → `UNSUPPORTED` (expected) |
| Directory validator on the audited archive's asset tree | same | `VALID`, both executables found, no warnings |
| Shell syntax of CI and runtime scripts (`bash -n`) | workspace | passed |
| Workflow YAML parse | workspace (PyYAML) | passed |

A first run of the unit tests failed 4 tests due to a bug in a test helper (temp-file prefix too short); fixed and re-run green.

## Not executed (and why)

| Test | Reason | Will run in |
|---|---|---|
| Gradle dependency resolution, compilation of Android classes, lint | Workspace cannot reach Google Maven / Gradle distribution servers | `android-build.yml` |
| APK packaging and metadata/signature/ABI verification | same | `android-build.yml` → `verify-apk.sh` |
| Launch, background/foreground lifecycle, crash check | needs an emulator | `android-build.yml` → emulator smoke test (x86_64, API 30) |
| Box64 cross-compile, Wine/cnc-ddraw download | same network restriction | `runtime-components.yml` |
| SAF import, save export/import on a real device | needs a device | manual |
| Game initialisation, rendering, audio, input injection, save/load in-game | runtime not built; supported game build not available | BUILD 2–4 |

**No real-device testing has been performed.** No CI run has happened yet because this session had no access
to the GitHub repository (see EXECUTION_TRACKER.md).

## Known bugs

None confirmed. Android-layer code is unverified until the first CI run.

## Performance observations

None yet (no runtime).
