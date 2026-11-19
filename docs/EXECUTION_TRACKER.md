# Execution tracker

Last updated: 2026-11-19

## Current milestone

* **BUILD 1 (0.1.0-dev.1)** — code complete on `feature/build1-launcher`. **Not complete**: no APK has been built
  by GitHub Actions yet. Acceptance = green `android-build.yml` run with a downloadable APK.
* **BUILD 2 (0.2.0-dev.1)** — code complete on `feature/build2-runtime` (stacked on BUILD 1). Acceptance = green
  `runtime-components.yml` producing `shc-runtime-*.zip`, green APK build, and a self-test report from a real
  ARM64 device.

## Completed

- [x] Source archive audit, including provenance finding (modified SecuROM repack — not usable).
- [x] Architecture: Wine x86-64 WoW64 + Box64 + cnc-ddraw in a dedicated launcher.
- [x] BUILD 1 app: SAF import (staged, atomic, cancellable), version detection, asset validation, saves,
      touch-control test surface, settings, diagnostics, crash recorder.
- [x] BUILD 1 CI: tests, lint, APK, APK verification, emulator smoke test, release workflow.
- [x] BUILD 2 decision: targetSdk 28 (Android blocks exec/dlopen from app storage for targetSdk ≥ 29).
- [x] BUILD 2 core: `RuntimeArchive` (safe zip + manifest extraction), `RuntimeLayout`, `RuntimeProbe`;
      `WineLaunchPlan` now starts through the glibc loader. 39 unit tests pass locally.
- [x] BUILD 2 packaging: `build-userland.sh` (Debian bookworm arm64), `assemble-runtime.sh`, `package_runtime.py`;
      `runtime-components.yml` gains a `package` job → artifact `shc-runtime-package`.
- [x] BUILD 2 app: Runtime screen (install package, three-step self-test, report saved/copyable, remove).

## Not yet verified

- [ ] Any Gradle/Android SDK compilation (authoring workspace has no SDK access).
- [ ] Any GitHub Actions run (authoring session had no repository access).
- [ ] Any on-device run.

## Failed tasks

- Push from the authoring session: GitHub access denied in that session.
- Local APK build: dl.google.com / maven.google.com / services.gradle.org unreachable from the workspace.

## Outstanding blockers (need the owner)

1. Apply BUILD 1 (`apply-to-your-repo.sh`), open the PR, and share the Actions run result — or reconnect
   repository access so the agent can push and iterate on CI.
2. A supported game copy: Stronghold Crusader HD 1.41 (GOG or Steam).
3. An ARM64 Android device for the BUILD 2 self-test (report via the *Copy report* button).

## Technical decisions

| Decision | Reason |
|---|---|
| WoW64 Wine on Box64 (not Box86) | No 32-bit ARM dependency; supported by Box64 docs |
| targetSdk 28 (BUILD 2) | Required to exec/mmap the runtime from app storage; sideload only |
| Runtime as separate zip package, not in the APK | Size (hundreds of MB), independent updates, licence separation |
| Start everything via the packaged glibc loader | Android has no glibc loader at /lib |
| Debian bookworm userland first; Termux-patched glibc as fallback | Unpatched glibc may trip Android seccomp — self-test step 1 tells |
| Platform APIs only, Java | Minimal dependencies; core testable on plain JVM |
| One-finger drag = left drag by default | Wall drawing / box select need it |

## Next executable tasks (in order)

1. BUILD 1: run `android-build.yml` via the PR; fix compile/lint/smoke-test failures until green; record run URL,
   APK size and SHA-256 here. Merge.
2. Rebase `feature/build2-runtime` on main, open PR; run `runtime-components.yml`; pin `CNC_DDRAW_SHA256`.
3. Install the APK and runtime package on an ARM64 device; run the self-test; record the report here.
   * Step 1 fails with SIGSYS/“Bad system call” → switch userland glibc to the Termux glibc-packages build.
   * Step 2 fails → check Box64 build flags/page size (report includes ABIs and SDK).
   * Step 3 fails → inspect Box64 log lines in the report (missing x86-64 libs → `lib/x86_64-linux-gnu`).
4. BUILD 3: in-app X11 display server (evaluate reusing Winlator's X server after a licence check at a pinned
   revision), Wine prefix initialisation, cnc-ddraw copy into the game folder, first game window.

## Latest successful build

None yet.

## Latest APK artifact

None yet.

## How to resume

```bash
git clone https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android
cd stronghold-crusader-android
git log --oneline --all | head
cat docs/EXECUTION_TRACKER.md     # start from "Next executable tasks"
```
