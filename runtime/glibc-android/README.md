# glibc for Android app processes

Android applies a seccomp filter to every app process (and its children). Syscalls outside the allowlist are
trapped with `SECCOMP_RET_TRAP`: the process receives SIGSYS and dies (exit 159), instead of getting `ENOSYS`.
Unmodified glibc 2.41 makes several such calls, so every glibc program — including Box64 — is killed before
`main()` on the test device (Xiaomi 25053PC47G, Android 16 / SDK 36).

Blocked on that device (reported by `bin/shc-sysprobe`): `rseq`, `set_robust_list`, `get_robust_list`,
`faccessat2`, `openat2`, `futex_waitv`, `io_uring_setup`, `set_mempolicy_home_node` (+ `setuid`, `mount`).
`clone3` and `fchmodat2` were allowed there but are blocked on older Android releases.

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

Tests:
* `runtime/scripts/test-sysprobe.sh` — unpatched glibc dies with exit 159 under the device's blocked set, even
  with `GLIBC_TUNABLES=glibc.pthread.rseq=0` (reproduces device report 2).
* `runtime/scripts/test-glibc-android.sh` — patched glibc (x86-64 build of the same patch) runs start-up,
  threads, robust mutex, fork, posix_spawn, faccessat, fchmodat under that set.
* On the device: self-test step "glibc start-up, threads, fork" (`bin/shc-glibctest`).

Consequence for the runtime: every glibc program must be started through the packaged loader
(`ld-linux-aarch64.so.1 --library-path …`). Android has no system glibc; a child exec'd directly cannot start.
