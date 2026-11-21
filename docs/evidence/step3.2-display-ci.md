# BUILD 3 step 3.2 — in-app display: CI evidence (not a device result)

Commit `db597a0` on `feature/build3-step3.2-display` (PR #7). App 0.3.2-dev.1 (code 7); runtime unchanged (r6).

| Workflow / job | Result | Kind |
|---|---|---|
| [Android build 44918002122](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44918002122): unit tests (56, incl. 7 new display tests), lint, APK build, APK verification, asset check | PASS | Linux |
| same run: Emulator smoke test (x86_64, API 30): app start, background/foreground, **Display Test screen opens; same activity instance resumed after screen off/on; no crash** (no runtime on the emulator) | PASS | x86-64 emulator |
| same run: Display path under qemu-aarch64 (runtime r6 artifact + the app's own `X11DisplayPlan`, Wine env and `XwdFramebuffer`/`DisplayTestPattern` reading a read-only mapping); results below | PASS | emulated ARM64 Linux |
| [Runtime components 44918002119](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44918002119): r6 rebuilt from unchanged sources, all 8 jobs incl. Xvfb/GDI/cnc-ddraw display test | PASS | Linux / emulated ARM64 |

Display path under qemu (app code):
* Xvfb started with the app's exact command line from `runtime/x11` (abstract socket `@/tmp/.X11-unix/X37` after 2 s);
  xdpyinfo: MIT-SHM absent
* framebuffer: 1024x768, 32 bpp, stride 4096, LSB first, masks ff0000/00ff00/0000ff (BGRX → the renderer's path)
* pixels: red, green, blue, yellow at 9 points per quadrant
* motion: frame counter 41 → 802 in 30 s (about 25 fps under qemu); moving square moved
* stop: Xvfb exits on SIGTERM and removes (unlinks, does not truncate) its framebuffer file
* seccomp: 17 process traces within Android's arm64 app allowlist

APK: `app-debug.apk` 224,445 bytes, SHA-256 `fcacd4011a339f991bc7d2b272b5ff99c7ecf860d3d77c816307ed31e570a301`,
debug-signed; contains `assets/shc-display-test.exe` (SHA-256 `27806c11…2737c395`).

Not shown by CI (device only): Xvfb under Android's SELinux and real seccomp, the app's LocalSocket connection
to the abstract socket, the GLES renderer on a real GPU, frame rates, CPU and memory on the phone.
