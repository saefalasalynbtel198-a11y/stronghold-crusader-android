# Step 3.2 fix: Xvfb lock file on Android 16

## Device result (owner, app 0.3.2-dev.1, runtime r6)

```
Fatal server error:
Linking lock file (./.X37-lock) in place failed: Permission denied
```
Xvfb exits with code 1 before Wine starts.

## Root cause

* Xvfb's `LockServer()` (os/utils.c) writes its PID to a temporary file `./.tX37-lock` and then creates the real
  lock `./.X37-lock` with `link()`. The `./` is the runtime's patched `/tmp` string; the working directory is
  `<runtime>/x11`, which is writable by the app (the temporary file is created successfully, and the error is the
  `link()` call, not the `open()`).
* Android's SELinux policy forbids hard links to untrusted apps (system/sepolicy, `app_neverallows.te`:
  "Do not allow untrusted apps to hard link to any files"; `neverallow all_untrusted_apps file_type:file link`).
  `link()` returns EACCES regardless of directory ownership or mode. An ordinary write test therefore passes in the
  same directory; only a hard-link test shows the problem.
* `-nolock` exists but is accepted only for root (`geteuid() == 0`), so it cannot be used.

## Fix (app only; runtime r6, Box64, Wine, glibc unchanged)

* Xvfb is started with its documented option `-displayfd 3` (Xserver(1)): the server writes the display number to
  fd 3 when it is ready for connections. In the X server source, `-displayfd` sets `nolock = TRUE`, so no lock file
  is created and `link()` is never called. With an explicit display number Xvfb binds only that display.
* Java's ProcessBuilder cannot pass an extra fd, so the app starts Xvfb through Android's own shell:
  `/system/bin/sh -c 'exec 3>&1 1>/dev/null; exec "$@"' xvfb <loader> ... Xvfb :37 ... -displayfd 3`. The shell only
  moves the stdout pipe to fd 3 and then replaces itself with Xvfb (same process). The app reads the ready line
  from the pipe and then also checks that the abstract socket accepts a connection.
* Display selection without lock files: a display whose abstract socket answers is skipped; if Xvfb refuses the
  display ("Cannot establish any listening sockets", "Server is already active", "Address already in use") the app
  tries the next one (:37–:40); every other Xvfb error is shown, not skipped. The abstract socket disappears when its
  process dies, so a crashed session leaves nothing stale; lock files written by 0.3.2-dev.1 are deleted.
* Diagnostics in the report before Xvfb starts: working directory, canonical path, owner/mode vs app uid, a
  create/write/sync/delete test and a hard-link test with `Os.link` (the same call Xvfb makes); after the session,
  logcat lines with avc/seccomp/SIGSYS/fatal signal/tombstone.

## Tests

* Unit tests (DisplayCoreTest): launch command contains `-displayfd 3` through the shell wrapper and no lock-related
  option; ready-line parser; failure classification, including that the device's lock-file message is reported as
  an error (OTHER), not as a busy display.
* CI qemu job (`scripts/ci/test-app-display-qemu.sh`), with a seccomp filter (`scripts/ci/deny-link.c`) that makes
  `link`/`linkat` fail with EACCES like Android's SELinux:
  0. the old command line reproduces the exact device message;
  1. the app's launch command starts Xvfb, the ready line arrives on fd 3, 0 link syscalls, no lock file;
  2. a second Xvfb on the busy display is refused and classified DISPLAY_IN_USE;
  3–4. Wine test program, pixels and motion as before;
  5. two further start/stop rounds on the same display;
  6. Android seccomp allowlist check of all traces.

These are Linux/qemu results, not Android. The fix is not confirmed until it runs on the owner's phone.
