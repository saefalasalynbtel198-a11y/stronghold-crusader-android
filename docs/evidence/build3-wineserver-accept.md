# Evidence: wineserver killed by Android's seccomp filter at accept()

Environment: emulated ARM64 Linux (qemu-aarch64 8.2 user mode on x86-64 Ubuntu 24.04), **not Android**.
Box64 v0.3.8 (same build recipe as the runtime, PT_INTERP set with the app's `ElfInterp`), Android-patched glibc
2.41, Wine 11.0 WoW64 (Kron4ek, SHA-256 39574efa…0314) with the wineserver-directory patch. Command, as in the
app: `box64 wine wineboot --init` with WINEPREFIX in a fresh directory; wine starts wineserver itself.
Every syscall of every process recorded with `QEMU_STRACE=1`, checked with `runtime/android/check-strace.py`
against `runtime/android/arm64-app-syscalls.txt` (Android's arm64 app allowlist computed from AOSP bionic;
agrees with all 30 syscalls the device probe reported on the Xiaomi 25053PC47G / Android 16).

## Before the fix (glibc r4): 21 processes, one syscall outside the allowlist

```
trace/p.4374: 58 distinct syscalls, 0 outside the Android app allowlist
trace/p.4382: 63 distinct syscalls, 1 outside the Android app allowlist
   accept x1, first at line 25658: 4385 accept(11,0x00004000013adb80,[110]) = 24
trace/p.4389: 58 distinct syscalls, 0 outside the Android app allowlist
trace/p.4403: 56 distinct syscalls, 0 outside the Android app allowlist
trace/p.4410: 56 distinct syscalls, 0 outside the Android app allowlist
trace/p.4415: 51 distinct syscalls, 0 outside the Android app allowlist
trace/p.4420: 47 distinct syscalls, 0 outside the Android app allowlist
trace/p.4433: 49 distinct syscalls, 0 outside the Android app allowlist
trace/p.4446: 58 distinct syscalls, 0 outside the Android app allowlist
trace/p.4452: 48 distinct syscalls, 0 outside the Android app allowlist
trace/p.4460: 46 distinct syscalls, 0 outside the Android app allowlist
trace/p.4469: 46 distinct syscalls, 0 outside the Android app allowlist
trace/p.4481: 51 distinct syscalls, 0 outside the Android app allowlist
trace/p.4491: 47 distinct syscalls, 0 outside the Android app allowlist
trace/p.4504: 47 distinct syscalls, 0 outside the Android app allowlist
trace/p.4515: 47 distinct syscalls, 0 outside the Android app allowlist
trace/p.4529: 46 distinct syscalls, 0 outside the Android app allowlist
trace/p.4538: 54 distinct syscalls, 0 outside the Android app allowlist
trace/p.4548: 54 distinct syscalls, 0 outside the Android app allowlist
trace/p.4554: 46 distinct syscalls, 0 outside the Android app allowlist
trace/p.4560: 0 distinct syscalls, 0 outside the Android app allowlist
```

Context in the wineserver daemon (the forked child; the fd is its listening socket):

```
4385 rt_sigprocmask(SIG_SETMASK,0x00004000007f8658,NULL,8) = 0
4385 rt_sigprocmask(SIG_SETMASK,0x00004000007f89f8,NULL,8) = 0
4385 accept(11,0x00004000013adb80,[110]) = 24
4385 fcntl(24,F_SETFL,O_RDONLY|O_NONBLOCK) = 0
4385 rt_sigprocmask(SIG_BLOCK,0x000000003619ed40,0x00004000007f89f8,8) = 0
```

On Android this syscall is trapped (SIGSYS) and wineserver dies with the client's connection still pending
in the listen queue, which the client sees as `recvmsg: Connection reset by peer` — the device report's error.

## After the fix (glibc `accept` → `accept4(…, 0)`): 21 processes, none outside the allowlist

```
trace/p.7373: 58 distinct syscalls, 0 outside the Android app allowlist
trace/p.7398: 64 distinct syscalls, 0 outside the Android app allowlist
trace/p.7405: 59 distinct syscalls, 0 outside the Android app allowlist
trace/p.7413: 56 distinct syscalls, 0 outside the Android app allowlist
trace/p.7420: 56 distinct syscalls, 0 outside the Android app allowlist
trace/p.7428: 51 distinct syscalls, 0 outside the Android app allowlist
trace/p.7434: 47 distinct syscalls, 0 outside the Android app allowlist
trace/p.7445: 49 distinct syscalls, 0 outside the Android app allowlist
trace/p.7472: 58 distinct syscalls, 0 outside the Android app allowlist
trace/p.7485: 48 distinct syscalls, 0 outside the Android app allowlist
trace/p.7493: 44 distinct syscalls, 0 outside the Android app allowlist
trace/p.7502: 46 distinct syscalls, 0 outside the Android app allowlist
trace/p.7515: 51 distinct syscalls, 0 outside the Android app allowlist
trace/p.7524: 47 distinct syscalls, 0 outside the Android app allowlist
trace/p.7537: 47 distinct syscalls, 0 outside the Android app allowlist
trace/p.7549: 47 distinct syscalls, 0 outside the Android app allowlist
trace/p.7561: 46 distinct syscalls, 0 outside the Android app allowlist
trace/p.7571: 54 distinct syscalls, 0 outside the Android app allowlist
trace/p.7579: 54 distinct syscalls, 0 outside the Android app allowlist
trace/p.7586: 46 distinct syscalls, 0 outside the Android app allowlist
trace/p.7592: 0 distinct syscalls, 0 outside the Android app allowlist
```

```
7401 rt_sigprocmask(SIG_SETMASK,0x00004000007f89c8,NULL,8) = 0
7401 accept4(11,70368764812112,70368764812108,0,0,0) = 24
7401 fcntl(24,F_SETFL,O_RDONLY|O_NONBLOCK) = 0
```

Prefix created: `system.reg` (3.1 MB), `user.reg`, `userdef.reg`, `drive_c`; `wineserver -w` exit 0.

## PE smoke tests after the fix: 64-bit and 32-bit (WoW64) exit 42, "pe-smoke ok"; 17 processes

```
trace/i686.7856: 55 distinct syscalls, 0 outside the Android app allowlist
trace/i686.7869: 52 distinct syscalls, 0 outside the Android app allowlist
trace/i686.7874: 45 distinct syscalls, 0 outside the Android app allowlist
trace/i686.7881: 51 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7662: 57 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7670: 64 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7677: 54 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7682: 56 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7689: 52 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7734: 46 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7747: 45 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7756: 47 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7767: 47 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7779: 47 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7792: 45 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7815: 51 distinct syscalls, 0 outside the Android app allowlist
trace/x86_64.7842: 46 distinct syscalls, 0 outside the Android app allowlist
```
