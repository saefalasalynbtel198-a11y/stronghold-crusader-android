# glibc for Android app processes

Android applies a seccomp filter to every app process (and its children). Syscalls outside the allowlist are
trapped with `SECCOMP_RET_TRAP`: the process receives SIGSYS and dies (exit 159), instead of getting `ENOSYS`.
Unmodified glibc 2.41 makes several such calls, so every glibc program — including Box64 — is killed before
`main()` on the test device (Xiaomi 25053PC47G, Android 16 / SDK 36).

Blocked on that device (reported by `bin/shc-sysprobe`): `rseq`, `set_robust_list`, `get_robust_list`,
`faccessat2`, `openat2`, `futex_waitv`, `io_uring_setup`, `set_mempolicy_home_node` (+ `setuid`, `mount`).
`clone3` and `fchmodat2` were allowed there but are blocked on older Android releases.

`accept` (aarch64 syscall 202) is also outside the arm64 app allowlist: bionic implements `accept()` with
`accept4`, so the filter generated from bionic's `SYSCALLS.TXT` has only `accept4`. glibc calls `accept`
directly. This killed wineserver on the device at its first client connection (BUILD 3: wine reported
`recvmsg: Connection reset by peer`). Found by tracing every syscall of the device's Wine path under
qemu-aarch64 and checking it against the allowlist computed from AOSP bionic (`SYSCALLS.TXT` +
`SECCOMP_ALLOWLIST_*` − `SECCOMP_BLOCKLIST_*`); that computed list agrees with all 30 syscalls the device probe
reported. `accept` was the only syscall on the whole Wine start-up path (21 processes) outside it.

`android-seccomp.patch` (LGPL-2.1-or-later, like glibc) makes glibc take code paths it already has for kernels
or architectures without those syscalls. No new behaviour is invented:

| File | glibc call | Change |
|---|---|---|
| `kernel-features.h` | `__ASSUME_SET_ROBUST_LIST` | not assumed (as on arm/mips/sparc) |
| `nptl/dl-tls_init_tp.c` | `set_robust_list` at start-up | not called; `__nptl_set_robust_list_avail` stays false (user-space robust lists) |
| `nptl/_Fork.c` | `set_robust_list` in fork child | only if available (never) |
| `nptl/dl-tunables.list` | `rseq` registration | tunable default 0 (`glibc.pthread.rseq`) |
| `linux/faccessat.c` | `faccessat2` | pre-5.8-kernel path (`faccessat` / `fstatat` emulation) |
| `linux/fchmodat.c` | `fchmodat2` | pre-6.6-kernel `O_PATH` fallback |
| `linux/clone-internal.c` | `clone3` (threads, fork helpers) | start with `clone3_supported = 0` → `clone` |
| `linux/spawni.c` | `clone3` (`posix_spawn`) | `clone` fallback directly |
| `linux/accept.c` | `accept` | `accept4(fd, addr, len, 0)` — the same operation; glibc already has this path for architectures without `accept` |

Tests:
* `runtime/scripts/test-sysprobe.sh` — unpatched glibc dies with exit 159 under the device's blocked set, even
  with `GLIBC_TUNABLES=glibc.pthread.rseq=0` (reproduces device report 2).
* `runtime/scripts/test-glibc-android.sh` — patched glibc (x86-64 build of the same patch) runs start-up,
  threads, robust mutex, fork, posix_spawn, faccessat, fchmodat and accept on a Unix socket under that set
  (the simulated set includes `accept`).
* On the device: self-test step "glibc start-up, threads, fork" (`bin/shc-glibctest`, now also accept), and the
  seccomp probe lines `accept (bad fd)` (expected BLOCKED) and `accept4 (bad fd)` (expected allowed).

Not patched, because nothing on the current path uses them, but blocked for apps and relevant later:
System V IPC (`shmget`, `shmat`, `semget`, `msgget`, …) and POSIX message queues. X11's MIT-SHM extension uses
`shmget`, so the display path (phase 3) must not depend on it.

Consequence for the runtime: every glibc program must be started through the packaged loader
(`ld-linux-aarch64.so.1 --library-path …`). Android has no system glibc; a child exec'd directly cannot start.
