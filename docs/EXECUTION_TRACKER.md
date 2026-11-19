# Execution tracker

Last updated: 2026-11-19

## Current milestone

**BUILD 1 / Milestones 2–3 (architecture + Android project)** — code complete, awaiting first CI run.

## Completed

- [x] Source archive audit (`SOURCE_ARCHIVE_AUDIT.md`), including provenance finding (modified SecuROM repack).
- [x] Architecture decision: Wine x86-64 WoW64 + Box64 + cnc-ddraw in a dedicated launcher (`ARCHITECTURE.md`).
- [x] Pure-Java core with 33 unit tests passing locally: `PeInspector`, `GameBuildClassifier`,
      `GameDirectoryValidator`, `TouchMouseMapper`, `ViewportTransform`, `WineLaunchPlan`, `CncDdrawConfig`, `ControlSettings`.
- [x] Android app: import via SAF (staged, atomic, cancellable), validation/version display, save export/import,
      touch-control test surface, settings, diagnostics, crash recorder.
- [x] Workflows: `android-build.yml` (tests, lint, APK, verification, emulator smoke test, artifact upload),
      `release.yml` (signed or debug pre-release), `runtime-components.yml` (Box64 cross-build, Wine/cnc-ddraw fetch).
- [x] Documentation set.

## In progress / not yet verified

- [ ] First CI run of `android-build.yml` — Android-layer code has never been compiled (no SDK access in the authoring session).
- [ ] First CI run of `runtime-components.yml`.

## Failed tasks

- Pushing to GitHub from the authoring session: access to the repository was denied in that session.
- Local APK build: the authoring workspace cannot reach dl.google.com / maven.google.com / services.gradle.org.

## Outstanding blockers (need the owner)

1. **Repository access / push.** Either grant the agent session access to
   `saefalasalynbtel198-a11y/stronghold-crusader-android`, or push the delivered bundle yourself (see below).
2. **A supported game copy.** Stronghold Crusader HD 1.41 (GOG or Steam). The audited archive cannot be used.

## Technical decisions

| Decision | Reason |
|---|---|
| WoW64 Wine on Box64 (not Box86) | No 32-bit ARM dependency; supported by Box64 docs |
| Platform APIs only, Java | Minimal dependencies; core testable on plain JVM |
| One-finger drag = left drag by default | Wall drawing / box select need it |
| Separate runtime workflow | Experimental status visible, never masks the APK build |
| Game data in app-specific external storage | Size; no permissions needed |

## Next executable task

1. Push branch, open PR, run `android-build.yml`; fix any compile/lint errors until green; record run URL and APK size here.
2. Run `runtime-components.yml`; record Box64 binary size and cnc-ddraw SHA-256 (pin it in `runtime/versions.env`).
3. BUILD 2: assemble an ARM64 glibc userland (mmdebstrap `--arch=arm64 --variant=extract` of libc6, libx11-6,
   libgl1/mesa, libasound2, libfreetype6, libpulse0), package box64 + wine + userland as a downloadable
   runtime archive, install it from the app, and run `box64 wine --version` on a device.

## Latest successful build

None yet.

## Latest APK artifact

None yet.

## Commits

See `git log`; hashes are reported in the session summary that delivered each change.

## How to resume

```bash
git clone https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android
cd stronghold-crusader-android
cat docs/EXECUTION_TRACKER.md     # start from "Next executable task"
gradle :app:testDebugUnitTest      # with JDK 17 + Android SDK 35
```
