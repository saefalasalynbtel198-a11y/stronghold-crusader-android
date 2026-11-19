# Source archive audit

Audit date: 2026-11-19. Input: `Stronghold_Crusader.part1.rar`, `.part2.rar`, `.part3.rar` supplied by the project owner.
No file from this archive is committed to the repository.

## 1. Archive structure

| Property | Value |
|---|---|
| Format | RAR 5, 3 volumes (350 MiB + 350 MiB + 1.5 MB) |
| Content | 4,042 files, 14 folders, 1.25 GB unpacked, single top folder `Stronghold Crusader/` |
| Extraction | Lossless with the official unrar library (`node-unrar-js`); the Ubuntu 24.04 `7zip` package (no RAR codec) and `unar` 1.10 could not extract it |
| Source code | **None.** Only compiled binaries and data files |

Top level: two game executables, middleware DLLs, cursors, `readme.doc`, plus the folders
`aiv/ binks/ fx/ gfx/ gm/ help/ maps/ mapsextreme/` and a `#readme#` folder (see §6).

## 2. Edition and version

* `readme.doc` identifies the product as **Stronghold Crusader Extreme, v1.2e** (2008 release containing both *Crusader* and *Crusader Extreme*).
* Both executables report `FileVersion "1, 1, 0, 1"` (string table) and `1.0.0.1` (fixed version block), PE timestamp 2008-05-12.
* Both executables contain SecuROM sections (`Stext`, `Sdata`, `.securom`, …): this is the **disc-era SecuROM-protected build**, not the DRM-free HD 1.41 release.

## 3. Executables

| File | Size | Arch | SHA-256 |
|---|---|---|---|
| `Stronghold Crusader.exe` | 51,318,784 | PE32 x86, GUI | `30dba2a6af3ae4bad6c0d9ca17650f1c3b609fe0f7f42dbe6c09265a03a5fcdb` |
| `Stronghold_Crusader_Extreme.exe` | 63,377,408 | PE32 x86, GUI | `65ddf6809ba105b8426c8edd3a81c3e2dc09047acddc8e42390862dc61eb08b8` |
| `#readme#/Stronghold Crusader.exe` | 13,053,952 | PE32 x86, GUI | `1001fdabfbbfa02145ba3f00f34dc1b2b35772e01b065e97426dc5e03788064c` |
| `#readme#/Stronghold_Crusader_Extreme.exe` | 13,250,560 | PE32 x86, GUI | `070687b35313c1bcb7a5b28971b5776098eb6841c63f8bb0488dfee3a70dc2e6` |
| `JopieK_s_CrusaderTrainer___1_1_.0.exe` | 1,055,232 | PE32 x86 | third-party cheat trainer, not part of the game |

Imports of the game executables (identical for both): `ddraw` (DirectDraw rendering), `mss32` (Miles audio),
`binkw32` (Bink video), `dplayx` (DirectPlay multiplayer), `wsock32`, `winmm`, `user32`, `gdi32`, `kernel32`,
`advapi32`, `shell32`, `shfolder`, `comdlg32`, `ole32`, `oleaut32`.

## 4. Middleware

| File | Identification | SHA-256 |
|---|---|---|
| `Mss32.dll` | Miles Sound System 6.1c | `2a515199cc7a2e1ccd8d756efdaab07cedd5c3052309ef1c7499c2560e2097bd` |
| `binkw32.dll` (+ identical copy `binkw32 (2).dll`) | RAD Bink | `ee68dcf51e8e3b482f9fb5ef617f44a2d9e970d03f3ed21fe26d40cd63c57a48` |
| `CrusaderGDF.dll`, `CrusaderExtremeGDF.dll`, `GameuxInstallHelper.dll` | Windows Vista Game Explorer metadata | — |
| `unicows.dll`, `shfolder.dll` | Microsoft redistributables for Win9x/compat | — |

All of these are 32-bit Windows DLLs that run inside the emulated Windows process; none needs an Android replacement.

## 5. Asset inventory

| Folder | Content | Files | Size |
|---|---|---|---|
| `gm/` | `.gm1` sprite/animation archives | 199 | 71 MB |
| `gfx/` | `.tgx` images | 452 | 51 MB |
| `fx/` | `.wav` effects (+ `fx/speech/` 1,931 voice lines) | 2,741 `.wav` total | 672 MB |
| `fx/music/` | `.raw` music tracks | 81 | (in fx) |
| `binks/` | `.bik` cut-scenes | 122 | 15 MB |
| `maps/` | Crusader maps | 125 | 102 MB |
| `mapsextreme/` | Extreme maps | 143 | 126 MB |
| `aiv/` | AI castle layouts | 128 | 7.2 MB |
| `help/` | WinHelp `.hlp` (not viewable on modern Windows/Wine without WinHlp32) | 2 | — |

The asset set is complete for the 2008 Extreme edition; `GameDirectoryValidator` reports it as valid.
Asset formats are the same family the HD 1.41 release uses, but the HD release is the supported source (see §8).

## 6. Provenance findings (important)

The archive is **not a pristine retail installation**:

* `#readme#/READ_ME.txt` describes the package as a "Full-Rip" repack and credits a cracking group.
* `#readme#/original.exe.rar` and the `#readme#/*.exe` files hold the SecuROM-wrapped executables; the
  top-level executables (51 MB / 63 MB, with a 31–44 MB `.data` section) are modified versions of them, and
  `Stronghold Crusader.rar` / `Stronghold_Crusader_Extreme.rar` contain further copies of those modified executables.
* The folder also contains a cheat trainer, a `.reg` file that deletes registry keys, download-site shortcut
  files and a saved web page.

Consequences for this project:

1. The modified executables cannot be used or tested by this project. The project rules forbid using
   cracked executables or bypassing copy protection, and the launcher will not treat them as supported.
2. The original SecuROM build cannot run under Wine on Android either: SecuROM depends on Windows
   kernel-level components that Wine does not provide.
3. **Required action for the owner:** obtain the DRM-free *Stronghold Crusader HD* (v1.41) from GOG or Steam
   and import that installation. It includes both Crusader and Crusader Extreme, costs about 10 USD at list
   price, and is the reference build of the OpenSHC reimplementation (SHA-1 `012e9d55dac04b23ed9a334c975d3a5b6287020b`
   for `Stronghold Crusader.exe` 1.41 Latin).

## 7. Feasibility summary

| Question | Answer |
|---|---|
| Can the PE32 x86 executable run natively on Android? | No. Android devices are ARM64 and do not run Windows binaries. |
| Is engine source available? | No. Firefly Studios has not released it. |
| Is there a complete open-source engine? | No. **OpenSHC** is a function-by-function reimplementation that builds as a DLL hooked into the original 1.41 executable — it still needs the Windows binary. **Sourcehold** targets Stronghold 1 and is frozen. |
| Compatibility-layer route | Yes, with Wine (Windows API) + Box64 (x86-64 → ARM64) + cnc-ddraw (DirectDraw → OpenGL). This is the stack Winlator uses to run many 32-bit Windows games on Android. |
| Missing dependencies | A legal HD 1.41 game copy; an ARM64 glibc userland; an in-app display server; audio bridge. |

## 8. Recommended architecture

Wine x86-64 **WoW64** build on **Box64**, with **cnc-ddraw** for DirectDraw, packaged inside a dedicated
Stronghold Crusader launcher app. Details and the evaluation of alternatives: [ARCHITECTURE.md](ARCHITECTURE.md).

## 9. Legal and licensing

* Game executables and assets are © Firefly Studios; they are imported by the user on-device and never
  committed, bundled or uploaded. CI fails if such files are tracked (`scripts/ci/check-no-proprietary-files.sh`)
  or found inside an APK (`scripts/ci/verify-apk.sh`).
* Miles and Bink DLLs are proprietary redistributables shipped with the game; they also stay on-device.
* Runtime components: Box64 (MIT), Wine (LGPL-2.1+; source offer/link required when distributed),
  cnc-ddraw (MIT). Winlator's repository metadata lists MIT, while several forks state GPL-3.0 — check
  the LICENSE file at the exact revision before reusing any Winlator code.
* This repository has no LICENSE file yet; choosing one is the owner's decision.

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Owner's copy is not a supported build | Blocks gameplay testing | Buy/install HD 1.41 (GOG/Steam) |
| WoW64 + Box64 performance for 32-bit games is below Box86 on some titles | Lower FPS | Game is 2D at 1024×768+; cnc-ddraw OpenGL path; measure on device |
| Android W^X rules for executing downloaded binaries (targetSdk ≥ 29) | Runtime may not start | Ship executables as `lib*.so` in `nativeLibraryDir`, or targetSdk 28 for sideload builds (decision in BUILD 3) |
| No in-app X server yet | No rendering | Largest remaining work item |
| DirectPlay multiplayer | LAN play likely unavailable | Out of scope for first playable build |
