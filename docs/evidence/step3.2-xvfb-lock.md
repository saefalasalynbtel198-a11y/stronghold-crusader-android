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
* 0.3.3-dev.1 passed descriptor 3 through Android's shell; that failed on the device (second section below).
  0.3.4-dev.1 uses `-displayfd 1` without a shell.
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

## Second device result (owner, app 0.3.3-dev.1, runtime r6)

```
create/write: ok
hard link: DENIED (EACCES) - expected on Android
delete: ok
...
Fatal server error:
Cannot write display number to fd 3
```

The hard-link diagnosis is confirmed on the device; Xvfb now gets as far as reporting readiness (it has bound its
socket and initialised screen and keyboard) and then fails.

### Root cause

* argv was `["/system/bin/sh", "-c", "exec 3>&1 1>/dev/null; exec \"$@\"", "xvfb", <loader>, ..., "-displayfd", "3"]`:
  `$0` = "xvfb", `"$@"` = the loader command. The script does run as intended up to the `exec`.
* Android's `/system/bin/sh` is mksh. mksh marks descriptors above 2 that are opened or duplicated by an `exec`
  redirection as close-on-exec ("not made available to other executed commands"); POSIX leaves this unspecified,
  dash and bash pass them on. So fd 3 exists inside the shell but is closed when the shell `exec`s the loader;
  Xvfb's `write(3, ...)` in NotifyParentProcess (os/connection.c) fails and it calls
  `FatalError("Cannot write display number to fd %d")`. The glibc loader and Xvfb do not close it; it never
  arrives.
* Why CI missed it: the CI ran the same wrapper with `/bin/sh` = dash.
* Reproduced here and in CI: `mksh -c 'exec 3>&1 1>/dev/null; exec "$@"' x sh -c '[ -e /proc/self/fd/3 ]'` → fd 3
  closed (dash: open); a native Xvfb behind the same mksh wrapper prints exactly
  `Fatal server error: (EE) Cannot write display number to fd 3`.

### Fix (0.3.4-dev.1)

* Xvfb is started directly by ProcessBuilder through the runtime loader (no shell) with `-displayfd 1`.
  Descriptors 0–2 are always passed on (ProcessBuilder sets them up in the child before exec). stdout is a pipe used
  only for the display number; stderr is appended to the Xvfb log (never merged into stdout).
* Xvfb writes "<n>\n" and closes stdout. The app (core/XvfbStarter, shared with the unit tests and CI) accepts
  exactly one line of 1–5 ASCII digits equal to the display it started; any other text, a number for another
  display, a line without "\n" or an over-long line is reported and the process stopped; bytes after the line are
  counted and shown in the report. Then a client connection to the abstract socket must succeed.
* Display choice, stop (SIGTERM, SIGKILL after 3 s) and stale-state handling are unchanged in behaviour and now in
  core/XvfbStarter.
* Considered and not chosen: keeping fd 3 by redirecting per command (`exec "$@" 3>&1`, which mksh passes on) — it
  works, but still depends on a shell's descriptor rules; `-displayfd 1` needs no shell at all.

### Tests

* Unit tests (XvfbStarterTest, DisplayCoreTest): exact argv (loader first, no shell, `-displayfd 1`); parsing (digits
  only; no spaces, "\r", sign, non-ASCII digits, more than 5 digits); reader outcomes (ready, invalid, closed,
  timeout, output after the line); start with scripted processes: first display, skipped busy displays, Xvfb
  refusing busy displays, all busy, lock-file and fd-3 errors reported (not skipped), wrong number, garbage,
  timeout, ready but not connectable, launch failure; stop escalation; repeated cycles.
* CI qemu job: device failure 1 (lock file, hard links denied) and device failure 2 (mksh wrapper) reproduced; the
  app's XvfbStarter with real processes: display 37 in the Android-like environment; occupied displays (Xvfb refuses
  :37 → :38, skipped → :39, :40, all busy → error) and cleanup; Wine pixels/motion; three start/stop cycles;
  0 link calls; Android seccomp allowlist.

Again Linux/qemu, not Android: not confirmed until it runs on the owner's phone.
