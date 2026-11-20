# Architecture

## Decision

**A dedicated Stronghold Crusader launcher app that runs the original HD 1.41 executable through
Wine (x86-64 WoW64 build) on Box64, with cnc-ddraw translating DirectDraw to OpenGL.**

The game's own code, rules, balance, AI, maps, graphics, audio and save format are used unchanged;
the app supplies a Windows-compatible environment, touch input, storage and lifecycle handling.

## Alternatives evaluated

| Approach | Verdict | Evidence |
|---|---|---|
| A. OpenSHC (open-source reimplementation) | Not viable as a standalone port today. Keep as a future option. | Builds as a DLL loaded into the original 1.41 executable and hooks reimplemented functions; it needs the Windows binary and UCP to run. |
| A′. Sourcehold engine | Not viable | Targets Stronghold 1, early stage, project frozen. |
| B1. Wine x86 + **Box86** | Rejected | Needs 32-bit ARM execution (armhf userland). Newer ARM64 cores no longer execute AArch32, so it would exclude current phones. |
| B2. Wine x86 + **Box64 BOX32** | Not now | Box64 documents BOX32 as highly experimental and unstable. |
| B3. Wine **x86-64 WoW64** + **Box64** | **Selected** | Box64's Wine guide: the WoW64 build runs both 32- and 64-bit Windows programs and needs no 32-bit environment. Same model Winlator uses. |
| B4. ARM64 Wine + FEX/WowBox64 (Hangover model) | Watch | WowBox64 appeared in Box64 0.3.6 and is labelled experimental; ARM64 Wine on Android has no maintained display driver. |
| C. Wrap an existing Android runtime (Winlator) | Rejected as a product; reuse parts | Wrapping another app's UI is out of scope. Its runtime pieces (X server, imagefs, launcher logic) may be reused after a license check. |
| D. Rewrite the engine | Rejected | Years of work and would not be "the original game". |

## Component map

```
┌──────────────────────── Android app (Java, no AndroidX) ────────────────────────┐
│ MainActivity ─ import (SAF) ─ validation ─ version detection ─ saves ─ status    │
│ TouchTestActivity / (future) GameActivity                                        │
│   └─ core.TouchMouseMapper → InputAction (mouse in game px, Windows VK codes)    │
│ core.WineLaunchPlan → argv/env for the runtime                                   │
│ core.CncDdrawConfig → ddraw.ini                                                  │
├──────────────────────── Runtime package (files/runtime) — NOT YET BUNDLED ───────┤
│ box64 (ARM64, glibc) ─ wine x86-64 WoW64 ─ cnc-ddraw ddraw.dll                   │
│ ARM64 glibc userland ─ in-app X11 server ─ audio bridge                          │
├──────────────────────── User data ───────────────────────────────────────────────┤
│ external files/game  ← imported HD 1.41 folder (never in git, never in the APK)  │
│ files/prefix/wine     Wine prefix, dosdevices/d: → game folder                   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## Platform choices

| Item | Choice | Reason |
|---|---|---|
| Language | Java 17, platform APIs only | Smallest dependency surface; the core is plain Java so it is unit-testable on any JVM |
| minSdk | 26 (Android 8.0) | `java.nio.file`, adaptive icons; Box64/Wine-on-Android stacks target 8.0+ |
| targetSdk | **28** (from BUILD 2; compileSdk 35) | Required to execute the runtime from app storage — see below. Sideload distribution only |
| ABIs | none in BUILD 1; **arm64-v8a only** once the runtime lands | Box64's dynarec targets ARM64; 32-bit ARM devices cannot run the stack |
| Graphics | cnc-ddraw OpenGL renderer → Mesa/Android GLES | The game is 2D DirectDraw; Vulkan adds nothing here |
| Permissions | none | Import via Storage Access Framework, data in app-specific storage |
| Storage | game data in app-specific external storage, runtime/prefix in internal storage | Game data is ~1.2 GB; symlinks and exec bits need internal storage |

## Executing the runtime on Android

Android's documented behaviour for apps targeting API 29+: untrusted apps cannot call `execve()` on files in
their home directory, and code should only be loaded from the APK. The SELinux policy change behind it also
removes `execute` on `app_data_file` (which blocks `dlopen()`/`PROT_EXEC` mappings of those files) for every
untrusted app domain except the targetSdk ≤ 28 ones. The runtime is hundreds of files (glibc
loader, ARM64 libraries, Wine) that the glibc loader maps executable from app storage, so it cannot run under
targetSdk ≥ 29. Termux stays on targetSdk 28 for the same reason.

Decision: **targetSdk 28**, with the runtime installed into internal app storage from a separate package
(`shc-runtime-*.zip`). Consequences: not publishable on Google Play (sideload/GitHub Releases only, which is the
project's distribution model anyway); lint `ExpiredTargetSdkVersion` is disabled deliberately.

Every runtime command is started as `lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 --library-path … bin/box64 …`
because Android has no `/lib/ld-linux-aarch64.so.1` for glibc programs.

## Input model (preserves original mechanics)

See [TOUCH_CONTROLS.md](TOUCH_CONTROLS.md). Every gesture produces ordinary Windows mouse/keyboard
events; nothing in the game is simplified or replaced.

## Delivery stages

| Build | Scope | Status |
|---|---|---|
| BUILD 1 (0.1.0-dev.1) | Installable app: import, validation, version detection, saves, touch-mapping test surface, diagnostics, CI | Code complete; CI run pending repository access |
| BUILD 2 (0.2.0-dev.1) | Runtime package assembled in CI, installed from the app, on-device self-test: glibc loader → `box64 --version` → `box64 wine --version` | Code complete on `feature/build2-runtime`; needs CI run + device test |
| BUILD 3 | X server + launch; game window renders | Not started |
| BUILD 4 | Touch input injected into Wine; audio | Not started |
| BUILD 5 | Performance, lifecycle (pause/resume of Wine), stability | Not started |
| BUILD 6 | Release candidate | Not started |
