# BUILD 3 phase 3 — display, game window, touch input: implementation plan

Status: **proposal, awaiting owner approval.** Nothing below is implemented yet. Start Game stays disabled until
the game's own window is shown on the Android 16 phone and responds to touch, verified on the device.

Starting point (verified on the device, 2026-11-21): app 0.3.1-dev.1 + runtime r5 run Box64 → Wine 11.0 → Windows
programs (32- and 64-bit) on the Xiaomi 25053PC47G. What is missing is a display: Wine reports
`nodrv_CreateWindow … no driver could be loaded`, so no window can exist yet.

## 1. Which display driver

| Option | How it works | Verdict |
|---|---|---|
| A. **Xvfb + app renderer** (recommended) | Debian's Xvfb (virtual X server) runs in the runtime like Box64 (patched glibc). Wine's normal X11 driver draws into it. Xvfb keeps its screen in a memory-mapped file (`-fbdir`); the app maps that file and draws it on an Android `SurfaceView`. Touch input goes back as XTEST events from the app over the X11 socket. | Standard, MIT-licensed parts; Box64 already wraps the X11 libraries Wine loads (libX11, libXext, libXrender, libXrandr, libXi, libXcursor, libXfixes, libXcomposite, libXxf86vm, xcb). Small app-side code. Cost: one extra frame copy, CPU rendering only. Good fit for a 2D DirectDraw game. |
| B. Embed Termux:X11's X server | Full X server built with the NDK, draws straight to an Android surface. | Faster presentation, proven with Box64/Wine. But GPL-3.0 (the app would have to become GPL-3.0) and a larger integration (its own process and loader). Fallback if A is too slow. |
| C. Own X server in Java/C (Winlator-style) | Implement the X11 protocol subset Wine needs. | Months of work; no. |
| D. Wine's Android driver (`wineandroid.drv`) | Wine built *for* Android, driver talks to Java in the same process. | Needs Wine compiled for bionic with JNI in-process; incompatible with x86-64 Wine under Box64. No. |
| E. Wine's Wayland driver | Needs a Wayland compositor in the app. | Writing a compositor is option C again. No. |

Rendering chain with option A:

```
Stronghold Crusader (DirectDraw)
 → cnc-ddraw ddraw.dll (renderer=gdi first; opengl later if it helps)
 → Wine GDI → winex11.drv → libX11 (aarch64, wrapped by Box64)
 → Xvfb (aarch64, patched glibc), screen in <app>/files/x11/fb/Xvfb_screen0 (mmap)
 → app: GLES texture on SurfaceView, aspect-correct scaling (existing ViewportTransform)
touch → existing TouchMouseMapper → InputAction → XTEST FakeInput over the X11 socket → Wine → game
```

## 2. Android constraints that shape it (from phases 1–2)

* **No `/tmp`:** X11 normally uses `/tmp/.X11-unix/X0`. Plan: Xvfb with `-nolisten unix` and the Linux abstract
  socket only (`@/tmp/.X11-unix/X0` is just a name, no directory needed); the app connects with `LocalSocket`
  (abstract namespace). Must be proven in step 3.0. Fallbacks: binary path patch (as done for Wine), or loopback TCP
  (needs the INTERNET permission).
* **System V shared memory is blocked by seccomp** (`shmget`, `shmat`). If Xvfb advertises MIT-SHM, Wine calls
  `shmget` and is killed. Xvfb starts with `-extension MIT-SHM`; the strace allowlist check must stay clean.
* **XKB keymap compilation:** Xvfb runs `xkbcomp` through `/bin/sh` at start and writes the result to a temp
  directory. Needs `xkbcomp` with the patched loader and a writable directory, or a precompiled keymap. Proven in 3.0.
* **Seccomp in general:** every new program (Xvfb, xkbcomp) goes through the same qemu strace check against
  Android's arm64 app allowlist (`runtime/android/`) before it reaches the phone.

## 3. Steps, each with a gate

| Step | What | Evidence required (gate) |
|---|---|---|
| **3.0** Feasibility in CI (no app change) | CI job: Debian trixie arm64 Xvfb + X libs + xkbcomp in the userland; Xvfb under qemu with the flags above; `box64 wine shc-gui-smoke.exe` (new test PE: opens a window, paints known colour blocks with GDI, then with DirectDraw through cnc-ddraw, exits 42); the job reads the `-fbdir` file and checks the pixels; strace allowlist check of Xvfb + Wine. | Pixels found in the framebuffer, no syscall outside Android's allowlist. Report to owner before 3.1. |
| **3.1** Runtime r6 | Userland adds xvfb, xkbcomp/xkb-data, X11 libs, fonts config; `shc-gui-smoke.exe`; cnc-ddraw kept as is. Box64 unchanged, glibc unchanged unless the check finds another blocked syscall. | CI green including the new checks. |
| **3.2** Display on the phone | App: `X11Display` (starts/stops Xvfb as an app child, waits for the socket), `DisplayActivity` (SurfaceView + GLES renderer mapping the framebuffer, frame counter), Runtime screen button **Run display test (BUILD 3 phase 3)**: runs the GUI smoke PE and shows it live. Report adds: Xvfb log, framebuffer pixel check (automatic, not a visual claim), saved screenshot PNG, measured fps. | Owner's phone: report PASS + screenshot showing the colour blocks. |
| **3.3** Input | Small X11 client in Java (connection setup, XTEST `FakeInput`, cursor position) over the abstract socket; connects the existing `TouchMouseMapper` (tap = left click, double tap, long press = right click, drag = left drag/box select, two-finger drag = arrow keys, pinch = wheel) through `ViewportTransform`; on-screen keys (Esc, Space, Enter, keyboard toggle). Test PE `shc-input-echo.exe` draws what it receives. | Owner's phone: input test shows clicks/drags/keys at the touched positions (report lists received events). |
| **3.4** Game window (diagnostic, Start Game still off) | cnc-ddraw `ddraw.dll`/`ddraw.ini` placed in the app's private game copy (the owner's original files outside the app are never touched), `WINEDLLOVERRIDES=ddraw=n,b`; Xvfb screen = game resolution (1024×768 first); launched from the Runtime screen as **Game display test**. Investigate cnc-ddraw renderers: gdi first; opengl via Mesa software GLX only if gdi is too slow or wrong. | Owner's phone: main menu visible (screenshot from the framebuffer) and menu clicks work. |
| **3.5** Enable Start Game | Only after 3.4 passes on the phone: Start Game opens `DisplayActivity` with the game; build verification gate unchanged (UNKNOWN builds still need owner override as today). | Owner confirms menu + entering a game + interaction on the phone. "Playable" is not claimed from a menu screenshot. |

Phase 4 (audio, polish) follows after 3.5.

## 4. What stays as it is

Game import (`GameImporter`, build classifier, fingerprint copy), runtime install/remove, self-test and Wine tests
stay unchanged and keep running from the Runtime screen. Existing core classes are reused, not rewritten:
`TouchMouseMapper`, `InputAction`, `ViewportTransform`, `ControlSettings`, `CncDdrawConfig` (its renderer default
changes from opengl to gdi only if 3.0/3.4 show gdi is the one that works), `WineLaunchPlan`.

## 5. Risks

* **Speed:** Box64 + Wine GDI + software X server on a phone CPU is unmeasured. 3.2 measures fps; if the game is too
  slow, option B (Termux:X11, GPL-3.0 decision for the owner) or cnc-ddraw opengl with a GPU path is next.
* **The owner's game build** is the 2012 GOG build (1,1,0,1), still UNKNOWN in the classifier; it may behave
  differently from 1.41. Not changed by this plan; results will name the exact build.
* **Abstract socket / XKB** may need patches (see section 2); 3.0 decides before any app work.
* **Runtime size** grows (estimate +20–40 MB for Xvfb, X libraries, keymaps, fonts config).

## 6. Owner decisions needed

1. Approve option A (Xvfb + app renderer) and the step order above, or ask for option B (Termux:X11, GPL-3.0).
2. Confirm the first target resolution (1024×768, letterboxed on the phone) — wider HD resolutions can follow.
