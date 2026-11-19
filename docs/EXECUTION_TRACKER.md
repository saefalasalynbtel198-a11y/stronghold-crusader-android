# Execution tracker

Last updated: 2026-11-20 (BUILD 2 CI verified) · Repository: saefalasalynbtel198-a11y/stronghold-crusader-android (private, default branch `main`)

## Milestones

| # | Milestone | Status | Evidence / blocker |
|---|---|---|---|
| 1 | Repository and previous BUILD audit | COMPLETED | `main` held only the initial commit (README, .gitignore); BUILD 1/2 had never been pushed; no PRs, runs or releases. Branch `claude/access-check` (1 temporary file) left untouched. |
| 2 | BUILD 1 — launcher, core, CI | COMPLETED (merge pending) | PR #1; run [44613894366](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44613894366) green: 33 unit tests, lint, APK verified, emulator smoke test passed. Merge into `main` needs the owner (agent merge was refused by its permission policy). |
| 3 | BUILD 2 — runtime package, installer, on-device self-test | IN PROGRESS (CI COMPLETED; device test BLOCKED) | PR #2. APK run [44626864244](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44626864244) green; runtime run [44626864246](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44626864246) green incl. hermetic qemu-aarch64 self-test (loader → Box64 → `wine-11.0`). Android device test needs an ARM64 phone. |
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

## Milestone 3 — BUILD 2 details

* Branch `feature/build2-runtime` (PR #2, base `feature/build1-launcher`), verified head `8db30a9`.
* Failures found and fixed in CI, in order:
  1. Wine fetch reported failure although the SHA-256 matched: `tar | grep -m1` + pipefail → list to file first (`bcdbe85`); same latent pattern removed from `verify-apk.sh`, `assemble-runtime.sh`.
  2. Box64 cross-link: `cannot find Scrt1.o / crti.o` → add `libc6-dev-arm64-cross` (`bcdbe85`).
  3. Debian userland: `NO_PUBKEY` → verify with `debian-archive-keyring` (`70cffb4`).
  4. Loader not found: extract variant has no merged /usr → merge lib/ + usr/lib/, relativise symlinks (`8836679`).
  5. qemu: Box64 `GLIBC_2.38/2.39 not found` against bookworm glibc 2.36 → Debian trixie, glibc 2.41 (`6e169d0`).
  6. Box64 used the runner's x86-64 `libgcc_s.so.1` (not present on Android) → ship Box64 `x64lib`, hermetic test hiding host x86-64 libraries (`8db30a9`).
  7. One emulator smoke-test run (44619862549) lost the app process after a successful cold start; not reproduced in 3 later runs. Smoke test now prints logcat on failure and polls `pidof` (`94e269e`). Cause unconfirmed.
* APK (artifact `Stronghold-Crusader-Android-APK`, run 44626864244): `app-debug.apk`, 71,904 bytes, 0.2.0-dev.1-debug (2), minSdk 26 / targetSdk 28, no native code, debug-signed, SHA-256 `9aec50c1aca6e0df2447fc1a10f8b0fe1789becd6d6e561dec09d24ef6289d96`. Emulator: install Success, cold start 1880 ms, background/foreground OK.
* Runtime package (artifact `shc-runtime-package`, run 44626864246): `shc-runtime-r1-box64-v0.3.8-wine-11.0-cncddraw-v7.0.0.0-trixie.zip`, 180,293,457 bytes, 4,338 files / 45 symlinks / 52 executables. Inputs: Box64 v0.3.8 (built in CI), Wine 11.0 WoW64 (SHA-256 pinned), cnc-ddraw v7.0.0.0 (SHA-256 pinned), Debian trixie arm64 libraries (apt signature-verified).
* qemu-aarch64 self-test (Linux ARM64 emulation, host x86-64 libraries hidden; **not Android**): glibc 2.41 loader PASS; `Box64 arm64 v0.3.8 with Dynarec` PASS; Box64 → Wine `wine-11.0` PASS, all x86-64 libraries from the package.
* Not verified: anything on Android ARM64 (seccomp, SELinux exec rules at targetSdk 28, page size), runtime install via the app UI with the real 180 MB package, game start.

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
| Debian trixie userland first; Termux-patched glibc as fallback | Unpatched glibc may trip Android seccomp; device self-test step 1 decides |
| Runner's preinstalled Android SDK | setup-android@v3 licence handling broke the build |

## Next action

1. Owner: merge PR #1, then PR #2; run the BUILD 2 self-test on an ARM64 phone (APK + `shc-runtime-package`) and paste the report.
2. Agent (independent of the device): milestone 4 — Arabic/English resources, light/dark themes, launch screen.
3. Agent, after the device report: milestone 5 — display server (X11) + Wine prefix init + first `notepad`/`winver`
   window on device, then the game executable.
