# Evidence: BUILD 3 phase 3 step 3.0 — display feasibility (CI only)

Run: [Display feasibility 44905986966](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44905986966),
commit on `feature/build3-phase3-display` (PR #5), 2026-11-21 12:51–13:07 UTC, `ubuntu-24.04` runner.
**Emulated ARM64 Linux (qemu-aarch64 user mode), not Android.** The app and the runtime package are unchanged.

Inputs:
* runtime r5, the device-verified package, downloaded from run 44823423675; `sha256sum -c` of the zip: OK
* Debian trixie arm64 `xvfb`, `x11-xkb-utils` (xkbcomp), `xkb-data`, `x11-utils` (xdpyinfo), extracted only
* `shc-gui-smoke.exe` (i686, SHA-256 `62a5afc4943cb958c5b7fe97fd217a653848c6fe97c1922ed2286ad86c20c110`)
* `libshc-android-shim.so` (aarch64 test preload, SHA-256 `e77b80774b00c7d726898d51878cf4f30adcabbe7ee059e6191592687ec62fb7`)

Every aarch64 program started through r5's Android-patched glibc loader; Box64 executed directly (PT_INTERP set by
the app's `ElfInterp`). Xvfb in a private mount namespace with `/tmp`, `/var`, `/usr/lib/xorg`, `/usr/share/X11`
empty and read-only.

| Check | Result |
|---|---|
| Xvfb starts, abstract socket only | PASS — `@/tmp/.X11-unix/X0` after 2 s; no `/tmp/.X11-unix` directory; flags `-nolisten tcp -nolisten unix -extension MIT-SHM -extension GLX -fp built-ins -fbdir`; three path literals patched (`/usr/bin`→`.`, `/var/lib/xkb/`→`./`, `/tmp`→`.`), lock file `./.X0-lock` |
| Native ARM64 X client | PASS — xdpyinfo: X.Org, 1024x768, 21 extensions incl. XTEST, RANDR, XKEYBOARD; no MIT-SHM |
| GDI window through Box64 + Wine 11.0 X11 driver | PASS — window 1024x768 at 0,0; framebuffer quadrants (255,0,0) (0,255,0) (0,0,255) (255,255,0) at 9 points each; exit 42 |
| DirectDraw through cnc-ddraw v7.0.0.0, renderer=gdi | PASS — `ddraw.dll` loaded from the test directory; SetCooperativeLevel, SetDisplayMode(1024,768,16), CreateSurface(primary) all S_OK; 980 frames written; framebuffer (255,0,255) (0,255,255) (255,255,255) (132,130,132); exit 42 |
| Android seccomp (18 process traces: Xvfb, xkbcomp, all Wine processes) | PASS — no syscall outside the arm64 app allowlist, **with the test preload** (below) |
| Android paths (Xvfb, xkbcomp) | PASS — nothing under `/usr`, `/var`, `/tmp` used; only `/bin/sh` (exists on the device: Box64's popen ran through it in the r4/r5 device reports) |

What the earlier runs of this test found (all fixed inside the test, nothing in the app or runtime):

1. Run 44902732251: `nodrv_CreateWindow`. wineboot left Wine processes running without a display
   (`wineserver -w` exit 124, also seen in the r5 qemu job); the desktop process (explorer.exe) decides the display
   driver for later programs. Stopping them (`wineserver -k`) before the GUI test fixed it.
2. Same run: Xvfb's `Popen()` child calls `setgid(getgid())`/`setuid(getuid())` before running xkbcomp
   (xserver `os/utils.c`). Android traps both even for the own id (device probe). Lock file in `/tmp` (`-nolock` is
   root-only); `/usr/lib/xorg/protocol.txt` read (optional: warning only, `dix/registry.c`).
3. Run 44904670045: GDI and DirectDraw PASS, seccomp FAIL only on `shmget/shmat/shmdt/shmctl` in the Wine GUI
   process: `winex11.drv/bitblt.c create_shm_image` tries a SysV shared-memory image for every window surface
   without checking for MIT-SHM, then falls back. On Android the first `shmget` would kill the game process.

The test preload (`runtime/spike/display/shc-android-shim.c`) returns success for setuid/setgid to the caller's own
ids and makes `shmget` fail with ENOSYS; run 4 shows Wine's fallback then works and no blocked syscall remains. The
permanent form is a runtime r6 decision (glibc patch or shipped preload).
