# Step 3.2 fix: Wine prefix creation in the display test

## Device result (owner, app 0.3.4-dev.1, runtime r6, Xiaomi 25053PC47G, Android 16)

```
Creating the Wine prefix (first run, can take minutes)
wineboot --init: exit 1
wine: chdir to /data/user/0/io.github.saefalasalynbtel198.shcport/files/prefix/wine : No such file or directory
wineserver -w: exit 0
wineserver -k: exit 1
FAILED: Wine prefix could not be created (run the Wine tests)
```

Xvfb was not started (the display test stops before it when the prefix is missing).

## Root cause

* Wine creates only the prefix directory itself. In ntdll (`dlls/ntdll/unix/server.c`, `setup_config_dir`), when
  `chdir(WINEPREFIX)` fails with ENOENT, Wine calls `mkdir(WINEPREFIX)` once, ignores its result, and calls `chdir`
  again; if that fails it prints `wine: chdir to <prefix> : No such file or directory` (fatal_perror) and exits 1.
  `mkdir` is not recursive: with WINEPREFIX = `files/prefix/wine` it fails when `files/prefix` does not exist.
* The display test (since 0.3.2-dev.1, commit a40061a) never creates `files/prefix`. The Wine tests
  (`prefix.getParentFile().mkdirs()`) and the runtime self-test (`prefix.mkdirs()`) do. So the display test only
  worked while an earlier self-test or Wine test had left `files/prefix` in the app data; the 0.3.2 and 0.3.3 device
  runs passed this stage for that reason.
* Not caused by the XvfbStarter refactor: 0.3.3 and 0.3.4 changed no line of the prefix code (checked with git).
* What removed `files/prefix` on the device is not visible in the report; no code of the app deletes it (the Wine
  tests delete only an incomplete `files/prefix/wine`). Clearing the app's storage or reinstalling the app (which
  also needs the runtime installed again) removes it. CI debug APKs are signed with the CI runner's debug key; from
  0.3.5 the APK check prints the certificate digest so a changed key, which forces an uninstall, can be seen.
* Further differences to the device-verified Wine tests: no explicit working directory for Wine (Android's process
  default), no removal of an incomplete prefix or stale wineserver directories, success judged only by `system.reg`
  after `wineserver -k` (wineboot's exit code was printed but not checked), no second way with an app-started
  wineserver.

## Fix (0.3.5-dev.1; app only, runtime r6 unchanged)

* The display test runs in a fixed order (core/DisplayStartOrder): runtime → Wine prefix → Xvfb → test program;
  each stage only after the previous one succeeded.
* The prefix stage uses the Wine tests' own code (WineTestRunner.ensurePrefix) and steps
  (WineTestPlan.prefixInitSteps, the same Step objects the Wine tests run):
  * leftover Wine processes stopped; an incomplete prefix removed; stale wineserver directories removed;
  * `files/prefix` created and checked (core/WinePrefixSetup): is a directory, a probe file can be written and
    deleted, the prefix path is a directory if it exists; owner, mode and app uid in the report;
  * wineboot --init (exit 0 required) and wineserver -w (exit 0 required) in the runtime directory, with
    `system.reg` and `drive_c/windows` checked; the app-started-wineserver way if the first fails;
  * wineserver -k afterwards; success only if every step passed and the prefix is complete;
  * a complete prefix is kept without wineboot.
* The report contains the prefix stage: paths, existence, creation result, write test, owner/mode, every command
  with exit code and output (full output if it fails).

## Tests

* Unit tests (WinePrefixSetupTest): fresh app data (parent created, prefix left for Wine), existing directories
  kept, parent or prefix path being a file, read-only parent, completeness check, the display test's prefix steps
  identical to the Wine tests' steps (argv, environment, working directory, expected exits, checks), start order
  (no Xvfb and no test program after a prefix failure).
* CI qemu job, with the app's code (DisplayCli prefix-init → WinePrefixSetup + WineTestPlan.prefixInitSteps; the
  ptrace process watcher is left out because qemu-user has no ptrace):
  * fresh app data without the parent directory (0.3.4 behaviour): device error reproduced;
  * fresh app data with the fix: prefix created, wineboot and wineserver -w exit 0;
  * second run: initialised prefix kept;
  * no Wine process left; then Xvfb, the Windows test program in that prefix, pixels, motion, cycles.

Linux/qemu, not Android: not confirmed until the owner's phone runs it.
