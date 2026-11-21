# Evidence: runtime r6 (BUILD 3 phase 3 step 3.1) — CI only

Run: [Runtime components 44911670217](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44911670217)
(commit `e24f78b`, branch `feature/build3-runtime-r6`, PR #6), 8/8 jobs passed; app build
[44911670266](https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/actions/runs/44911670266) passed
(app unchanged, 0.3.1-dev.1). **Linux (native and qemu-aarch64), not Android.**

Package: `shc-runtime-r6-box64-v0.3.8-wine-11.0-cncddraw-v7.0.0.0-trixie-glibc2.41android.zip`, 187,244,579 bytes,
SHA-256 `da15d44c028e4bf3a13187696bdbd05aa153fe5691b3f4bd0e113fb60e2dc56a` (`sha256sum -c` OK in the display job).
Artifact `shc-runtime-package` (id 14710845090) zip digest `d9093796bfb46e78e76b537a0b5eb0ac7200b33f2861d51a4028f204f854ec5d`.

## glibc (android-seccomp.patch: 9 earlier files unchanged + setgid.c, setuid.c, shmget.c; 12 files applied)

* setuid/setgid: success without the syscall only if real, effective and saved IDs all already equal the
  requested ID; anything else goes to the kernel unchanged.
* shmget: -1/ENOSYS without the syscall; shmat/shmdt/shmctl, mmap, memfd, POSIX shm unchanged.

| Test | Where | Result |
|---|---|---|
| own-id setuid/setgid succeed | x86-64 under simulated Android filter (traps setuid/setgid/shm*), normal user; aarch64 qemu, root | ok |
| setuid/setgid to another id reach the kernel | x86-64 sim: trapped by seccomp; aarch64 root: changed by the kernel | ok |
| mixed IDs (different saved id) reach the kernel | aarch64 qemu as root | ok (skipped as normal user: cannot be created) |
| test detects a bypass | local check with a deliberately bypassing library: `FAIL ... success without any ID change (bypassed)` as root and as normal user | detected |
| shmget → ENOSYS, no syscall | both | ok |
| shared mmap across fork, memfd | both | ok |
| accept → accept4 (r5 fix) | both | `accept: ok` |

## Display test on the r6 package (no preload: LD_PRELOAD unset, no stand-in file, no Box64 preload warning)

| Check | Result |
|---|---|
| Xvfb from `x11/` (Android-like filesystem namespace) | PASS, abstract socket after 2 s |
| xdpyinfo (native ARM64 X client) | PASS |
| GDI window via Box64 + Wine 11 X11 driver | PASS: (255,0,0) (0,255,0) (0,0,255) (255,255,0); exit 42 |
| DirectDraw via cnc-ddraw v7.0.0.0 (renderer=gdi) | PASS: (255,0,255) (0,255,255) (255,255,255) (132,130,132); 1024x768x16; 1069 frames; exit 42 |
| Android seccomp allowlist, 21 process traces | PASS |
| Wine X11 shared-memory fallback | PASS: 0 shm* syscalls reached the kernel in all traces; window drawn |
| Android paths (Xvfb, xkbcomp) | PASS |

Also passed on r6: package self-test under qemu (glibc loader, probe, glibctest, Box64, `wine --version`), and
Box64 + Wine prefix creation under qemu with every syscall within the Android allowlist.

Component checksums (from the display job): `x11/Xvfb` `88c2c0a0…bab81`, `lib/aarch64-linux-gnu/libc.so.6`
`86e33d56…baf74e`, `win/shc-gui-smoke.exe` `1f16b22c…d488234`, `cnc-ddraw/ddraw.dll` `c7b25b41…b65133`.
