# BUILD 3 step 3.3: launching the imported game through the step 3.2 display path

Status: implemented, CI-tested with a stand-in game; **not yet run on the owner's phone**. The real game cannot run
in CI (proprietary files). Start Game stays disabled; the game launch test is a diagnostic screen.

## Starting point

* Step 3.2 passed on the owner's phone (app 0.3.5-dev.1, runtime r6): Xvfb :37, Wine window, pixel check PASS,
  ~27 fps, pause/resume; Wine tests 6/6, self-test 4/4.
* Imported game: GOG "Stronghold Crusader.exe" and "Stronghold_Crusader_Extreme.exe", built 2012-10-02 (pre-1.41),
  classified UNKNOWN (owner-reported hashes, no SecuROM): a launch attempt is allowed, the build is not verified.
* The old, unused `WineLaunchPlan` would have copied ddraw.dll into the game folder and configured cnc-ddraw's
  OpenGL renderer. Both are unsuitable: the game files must not be modified, and Xvfb has no OpenGL.

## Design (app 0.3.6-dev.1; runtime r6 unchanged)

Runtime → Game launch test (the display test activity in game mode). Stages in order, each only after the previous
one succeeded (core/DisplayStartOrder):

1. **Runtime and game check**: runtime has Xvfb, cnc-ddraw and `win/shc-gui-smoke.exe`; the imported folder is
   inspected again (GameInstallation: executables, classification, asset check); only an allowed build is started.
   A manifest of the game folder is taken (core/GameFolderManifest: every file with size and mtime, .exe/.dll with
   SHA-256).
2. **Wine prefix**: the device-verified prefix stage of step 3.2 (WineTestRunner.ensurePrefix).
3. **Xvfb**: the step 3.2 XvfbStarter (`-displayfd 1`, :37–:40).
4. **Game setup, inside the prefix only** (core/GameSetup):
   * `dosdevices/d:` → the game folder (symlink, as in the Wine tests);
   * the runtime's cnc-ddraw v7.0.0.0 `ddraw.dll` → `C:\windows\syswow64\ddraw.dll`, SHA-256 compared with the
     runtime's copy;
   * `C:\shc-port\ddraw.ini` (core/CncDdrawConfig): `renderer=gdi`, `fullscreen=true`, `windowed=false`,
     `maintas=true`, `savesettings=0`, plus the `[Stronghold Crusader]`/`[Stronghold_Crusader_Extreme]` sections
     cnc-ddraw ships for these games (`resolutions=2`, `stronghold_hack=true`, `adjmouse=true`).
   cnc-ddraw finds the file through `CNC_DDRAW_CONFIG_FILE` (src/config.c, cfg_init), so nothing is created next to
   the game or in syswow64.
5. **DirectDraw pre-check**: the runtime's own `shc-gui-smoke.exe ddraw` (1024×768×16 primary surface, Lock/Unlock as
   the game) with the game's environment. Required: exit 42, `ddraw.dll` loaded from syswow64, its four colours in
   Xvfb's framebuffer. If it fails, the game is not started (the fault is in the path, not in the game).
6. **Game**: `box64 wine "D:\<exe>"`, working directory = the game folder (Wine maps it to `D:\`), environment
   = the step 3.2 Wine environment with DISPLAY, plus `WINEDLLOVERRIDES=...;ddraw=n` (cnc-ddraw only; a missing
   DLL fails visibly instead of falling back to Wine's DirectDraw), `CNC_DDRAW_CONFIG_FILE=C:\shc-port\ddraw.ini`,
   `WINEDEBUG=err+all,fixme-all,+loaddll`.
7. **Monitoring**: process state and exit code; cnc-ddraw load seen in the `+loaddll` log; picture (core/FrameProbe:
   share of non-black pixels, colours, change between samples); renderer uploads on any change of a 128×96 sample
   grid; PNG snapshots of the X screen at 15/30/60/120 s and then every 2 minutes (at most 12, in the app's logs);
   CPU/memory timeline.
8. **Stop**: wineserver -k, Xvfb stopped, game folder compared with the manifest: any added, removed or modified
   file is reported and marks the run FAILED.

No input (step 3.4) and no sound (phase 4) in this step.

## Risks

* Only the owner's phone can run the real game; CI covers the path with a stand-in.
* The 2012 build predates 1.41; cnc-ddraw's Stronghold settings were written for the HD releases.
* No audio driver for Wine on Android: Miles Sound System / DirectSound may fail or wait at start-up.
* Bink intro videos (`binkw32.dll`) under Box64: CPU load or crashes; they cannot be skipped without input.
* Game files on external storage (FUSE): slower reads; CPU-only rendering (cnc-ddraw GDI → X11, no MIT-SHM).
* If the game writes into its own folder, Android external storage cannot be made read-only for it: the manifest
  check reports it.
* Unknown registry expectations of this build: visible in the err:/+loaddll digest of the report.

## Tests

* Unit tests: launch plan (argv, environment, working directory, file locations outside the game folder, rejected
  file names), ddraw.ini content, setup (writes only into the prefix, repeated setup, missing DLL, D: that is a real
  directory, missing executable), manifest (added/removed/modified by hash), frame probe (black/still/changing).
* CI qemu job, step 4b: the app's WineLaunchPlan/GameSetup/GameFolderManifest via DisplayCli `game-launch` with a
  stand-in game folder (the runtime's DirectDraw test program copied as "Stronghold Crusader.exe", plus dummy data):
  setup, the pre-check as the app runs it, the D:\ launch, cnc-ddraw native from syswow64 in `+loaddll`, no
  ddraw.ini next to the game or in syswow64, colours in Xvfb, exit 42, game folder unchanged; its syscalls are part
  of the Android allowlist check.
* Emulator smoke test: the game launch test screen opens (not started: no runtime or game in the emulator).
