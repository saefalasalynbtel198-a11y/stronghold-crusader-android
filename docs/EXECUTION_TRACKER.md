# Execution tracker

Last updated: 2026-11-20 (BUILD 2 device report 2: glibc patched for Android seccomp) · Repository: saefalasalynbtel198-a11y/stronghold-crusader-android (private, default branch `main`)

## Milestones

| # | Milestone | Status | Evidence / blocker |
|---|---|---|---|
| 1 | Repository and previous BUILD audit | COMPLETED | `main` held only the initial commit (README, .gitignore); BUILD 1/2 had never been pushed; no PRs, runs or releases. Branch `claude/access-check` (1 temporary file) left untouched. |
| 2 | BUILD 1 — launcher, core, CI | COMPLETED (merge pending) | PR #1; run [44613894366](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44613894366) green: 33 unit tests, lint, APK verified, emulator smoke test passed. Merge into `main` needs the owner (agent merge was refused by its permission policy). |
| 3 | BUILD 2 — runtime package, installer, on-device self-test | FAILED on device twice → fix IN PROGRESS | Report 1: Box64 SIGSYS. Report 2 (0.2.1): rseq tunable insufficient; probe shows set_robust_list, faccessat2 and others blocked. Fix: Android-patched glibc (0.2.2-dev.1 / runtime r3), awaiting CI and device re-test. |
| 3d | BUILD 2 device acceptance | COMPLETED (owner-reported) | Owner, 2026-11-20, Xiaomi 25053PC47G Android 16: patched glibc, threads/robust mutex/fork, Box64 v0.3.8, `wine --version` PASS with runtime r3 / app 0.2.2-dev.1. |
| 5a | BUILD 3 phase 1–2: Wine prefix + Windows PE smoke test | IN PROGRESS | Branch `feature/build3-wine` (PR #3), app 0.3.0-dev.1, runtime r4. Native x86-64 Linux CI test of the patched Wine PASSED (run [44755631555](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44755631555)). Android device test pending — not COMPLETED until the device report passes. |
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

## Milestone 3b — Android device failure and fix (0.2.1-dev.1, runtime r2)

* Device report (owner, 2026-11-20): glibc loader `--version` PASS (103 ms); `box64 --version` FAIL, exit 159, 101 ms.
* Analysis: 159 = 128 + 31 → killed by SIGSYS. `ld.so --version` returns before glibc's thread start-up; Box64 is the
  first program to run it. glibc ≥ 2.35 registers `rseq` there; `rseq` is not in Android's app seccomp allowlist
  (bionic SECCOMP_ALLOWLIST_COMMON lists clone3, openat2, faccessat2, … but not rseq), so the filter traps it.
* Reproduced on Linux (not Android) with a filter that traps rseq: glibc programs die with exit 159;
  with `GLIBC_TUNABLES=glibc.pthread.rseq=0` they run, threads included (`runtime/scripts/test-sysprobe.sh`, in CI).
* Changes: rseq tunable in every runtime command (`RuntimeProbe.baseEnv`, `WineLaunchPlan`); new freestanding
  static probe `bin/shc-sysprobe` that maps blocked syscalls on the device itself; self-test now runs
  loader → probe [diag] → Box64 defaults [diag] → Box64 (rseq off) → Wine and decodes signal exits.
* CI (commit `c6c90f2`): APK run [44728590081](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44728590081)
  green — `app-debug.apk` 72,924 bytes, 0.2.1-dev.1-debug (3), SHA-256 `0ce27ddabf99db21800a9d2bac48eef7668b071862caa75f5e2c61f38a8d87a9`,
  emulator smoke test passed. Runtime run [44728590041](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44728590041)
  green — seccomp diagnosis test (exit 159 reproduced, tunable fix confirmed), package
  `shc-runtime-r2-box64-v0.3.8-wine-11.0-cncddraw-v7.0.0.0-trixie.zip` (180,296,526 bytes), hermetic qemu self-test
  (loader, probe, Box64, `wine-11.0`) passed.
* Not verified until the owner re-runs the self-test on the device: that Box64 now starts on Android, and whether
  other syscalls are blocked too (the probe step will list them).

## Milestone 3c — device report 2 and the patched glibc (0.2.2-dev.1, runtime r3)

* Device report 2 (owner): loader PASS; probe PASS; Box64 default FAIL 159; Box64 with rseq tunable FAIL 159.
  Probe BLOCKED list: rseq, set_robust_list, get_robust_list, faccessat2, openat2, futex_waitv, io_uring_setup,
  set_mempolicy_home_node, setuid, mount.
* Root cause: glibc 2.41 calls `set_robust_list` in `__tls_init_tp` (every process) and in `_Fork`; the rseq tunable
  cannot prevent that. Unmodified glibc cannot run inside an Android 16 app process on this device.
* Reproduced on Linux with a filter trapping exactly the device's blocked set: unpatched glibc + tunable → 159.
* Fix: rebuild glibc 2.41 from source (pinned SHA-256) with `runtime/glibc-android/android-seccomp.patch`
  (8 changes, existing glibc fallback paths: no robust-list syscall, rseq default off, no faccessat2/fchmodat2,
  no clone3 in threads and posix_spawn). Local test: patched glibc runs start-up, threads, robust mutex, fork,
  posix_spawn, faccessat, fchmodat under the device's blocked set (`test-glibc-android.sh`), also in CI.
* The package replaces Debian's glibc with the patched build; new required self-test step `shc-glibctest`.
* CI (commit `3c95ee9`): runtime run [44741982979](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44741982979) green —
  glibc aarch64 + x86_64 built from pinned source; x86_64 patched glibc passed under the device's blocked set
  (glibctest, threads, subprocess; control child on unpatched glibc killed by SIGSYS); unpatched glibc + tunable
  reproduced exit 159; package `shc-runtime-r3-box64-v0.3.8-wine-11.0-cncddraw-v7.0.0.0-trixie-glibc2.41android.zip`
  (180,782,868 bytes, 31 patched glibc objects); qemu: loader, probe, glibctest, Box64, `wine-11.0` PASS.
  APK run [44741983009](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44741983009) green — 75,376 bytes,
  0.2.2-dev.1-debug (4), SHA-256 `381249ba77e1868352968ffd899f43a6d1c0930f8fd986b7a6537621ca694e9b`, emulator smoke test passed.
* Local check: remaining `set_robust_list` instructions in the aarch64 libc are only in `start_thread` and `_Fork`,
  both behind the never-true availability flag.
* NOT verified: anything on the Android device. Acceptance = device self-test steps 3, 5 and 6 PASS.
* Requirement found for BUILD 3: every glibc child process must be started via the packaged loader.
* Also (task 3): build fingerprint (text version strings, PE date, sections, hashes) + *Copy build fingerprint*;
  unverified DRM-free builds are labelled as such and not auto-accepted. GOG executable hashes still needed.

## Milestone 5a — BUILD 3 phase 1–2 (0.3.0-dev.1, runtime r4)

* Found by reading upstream Wine 11.0 source: wineserver (`server/request.c:647`) and ntdll
  (`dlls/ntdll/unix/server.c:1311`) hard-code `/tmp/.wine-%u`; only `__ANDROID__` builds use `<prefix>/.wineserver`.
  Android apps have no `/tmp`. Both sites run with the prefix as current directory, so the package replaces the
  13-byte pattern with the same-length `.wineserver%u` (`patch-wine-tmpdir.py`, exactly 2 files, 1 hit each).
* Box64 must re-execute itself for x86-64 children (wineserver). It is now linked with a placeholder PT_INTERP that
  the app replaces at install time with the packaged loader path (`ElfInterp`), so every child runs on patched glibc.
* New: Windows PE smoke programs (mingw, 32-bit and 64-bit): file I/O, %TEMP%, CreateProcess + exit code, exit 42.
* App: *Run Wine tests*: Box64 direct exec → `wineboot --init` → wineserver dir in prefix → PE smoke 64-bit →
  PE smoke 32-bit (WoW64) → game executable launch attempt [diagnostic, no display] → `wineserver -k`.
* CI evidence (native x86-64 Linux, not Android; run 44755631555, job "Wine smoke test"): patched Wine 11.0
  `wineboot --init` exit 0; after `wineserver -w` the prefix holds system.reg (3.2 MB), user.reg, userdef.reg and
  drive_c; the wineserver directory is `<prefix>/.wineserver1001/server-801-…` and `/tmp/.wine-1001` was not used;
  PE smoke 64-bit and 32-bit (WoW64) both exit 42: file write/read, %TEMP% file, CreateProcess child exit 7,
  ddraw.dll loads. Expected display errors only (`nodrv_CreateWindow`: no display driver yet).
* Bug found by that test and fixed: `wineboot` returns while wineserver still runs, and the registry is only written
  when the server exits. The app plan now runs `wineserver -w` after `wineboot --init` and checks the prefix there.
* GOG executables (owner fingerprints): no public hash record; build date 2012-10-02 and version strings 1,1,0,1
  match GOG's 2012 HD release, not 1.41(a) (GOGDB). Labelled "reported by owner, not independently verified", UNKNOWN.

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

1. Owner: on the Android 16 phone install app 0.3.0-dev.1 and runtime r4 (*Remove runtime*, then *Install runtime
   package…*), run *Run self-test*, then *Run Wine tests (BUILD 3)*, and send both reports.
2. Agent, after a passing device report: phase 3 — display path (X server for Wine's X11 driver, or Wine's Android
   driver), first window on the device, cnc-ddraw rendering test. Start Game stays disabled until then.
3. Owner (independent): merge PRs #1–#3, push tags; update the GOG copy to 1.41(a) via GOG Galaxy or the GOG
   offline installer and send the new fingerprint.
