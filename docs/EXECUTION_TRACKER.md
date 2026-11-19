# Execution tracker

Last updated: 2026-11-20 · Repository: saefalasalynbtel198-a11y/stronghold-crusader-android (private, default branch `main`)

## Milestones

| # | Milestone | Status | Evidence / blocker |
|---|---|---|---|
| 1 | Repository and previous BUILD audit | COMPLETED | `main` held only the initial commit (README, .gitignore); BUILD 1/2 had never been pushed; no PRs, runs or releases. Branch `claude/access-check` (1 temporary file) left untouched. |
| 2 | BUILD 1 — launcher, core, CI | COMPLETED (merge pending) | PR #1; run [44613894366](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44613894366) green: 33 unit tests, lint, APK verified, emulator smoke test passed. Merge into `main` needs the owner (agent merge was refused by its permission policy). |
| 3 | BUILD 2 — runtime package, installer, on-device self-test | IN PROGRESS | PR #2 (stacked on BUILD 1). CI results below once available. Real-device self-test needs an ARM64 phone. |
| 4 | Complete Android application (AR/EN, themes, game launch screen) | NOT STARTED | |
| 5 | Runtime integration: display server, Wine prefix, cnc-ddraw, audio | NOT STARTED | Needs BUILD 2 device result |
| 6 | Touch controls injected into the game | NOT STARTED | Mapper done and unit-tested; injection needs milestone 5 |
| 7–9 | APK build / automated tests / emulator tests | COMPLETED for BUILD 1 | see milestone 2 |
| 10 | Actual game execution | BLOCKED | No legal HD 1.41 copy available; runtime display path not built |
| 11–13 | Fixes, release, delivery | NOT STARTED | Release workflow present, not yet run |

## Milestone 2 — BUILD 1 details

* Branch `feature/build1-launcher`, head `5735e89`. Commits rebased onto `main` (`81d0130`): `a6cc74b` … `eef8db0`, fix `5735e89`.
* Files: 59 project files; `.gitignore` merged with the repository's original (Android Studio entries kept); `README.md` replaced.
* CI failure → fix: first run [44613462106](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44613462106) failed in
  `android-actions/setup-android@v3` (its licence step left 7 of 8 SDK licences unaccepted). Fixed by using the runner's
  preinstalled SDK (`5735e89`); setup-java moved to v5.
* Tests in the green run: 33/33 unit tests passed; `lintDebug` passed; `assembleDebug` passed; APK verification passed;
  emulator (x86_64, API 30, Google default image): install `Success`, `am start` `Status: ok`, cold start 1649 ms,
  survives background/foreground, no FATAL EXCEPTION.
* APK (artifact `Stronghold-Crusader-Android-APK`): `app-debug.apk`, 58,888 bytes, version 0.1.0-dev.1-debug (1),
  minSdk 26 / targetSdk 35, no native code, debug-signed,
  SHA-256 `a298406270c46961cc511c9ea63057418bba5e4c667777f34583e7cb5ecc7a1b`.
* Not verified: real-device install; SAF import with real files; game start (not possible in BUILD 1).

## Remaining blockers (owner action)

1. Merge PR #1 (then PR #2 retargets to `main` automatically when its base branch is merged and deleted, or retarget it manually).
2. Legal game copy: Stronghold Crusader HD 1.41 (GOG/Steam). The supplied archive is a modified SecuROM repack and is not used.
3. An ARM64 Android device to run the BUILD 2 self-test and paste the report.
4. For a production-signed release: repository secrets `SHC_KEYSTORE_BASE64`, `SHC_KEYSTORE_PASSWORD`, `SHC_KEY_ALIAS`, `SHC_KEY_PASSWORD` (see BUILD_INSTRUCTIONS.md). Without them releases are debug-signed pre-releases.

## Technical decisions

| Decision | Reason |
|---|---|
| WoW64 Wine on Box64 (not Box86) | No 32-bit ARM dependency; supported by Box64 docs |
| targetSdk 28 from BUILD 2 | Android blocks exec and dlopen from app storage for targetSdk ≥ 29; sideload only |
| Runtime as separate zip package | Size, independent updates, licence separation |
| Start everything via the packaged glibc loader | Android has no glibc loader at /lib |
| Debian bookworm userland first; Termux-patched glibc as fallback | Unpatched glibc may trip Android seccomp; device self-test step 1 decides |
| Runner's preinstalled Android SDK | setup-android@v3 licence handling broke the build |

## Next action

BUILD 2 CI on PR #2: Android build (0.2.0-dev.1 APK) and runtime-components (Box64 cross-build, Wine/cnc-ddraw
checksums, Debian arm64 userland, package assembly, qemu-aarch64 self-test). Fix failures until green.
