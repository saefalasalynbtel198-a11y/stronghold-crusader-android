#!/usr/bin/env bash
# Emulator smoke test: install, launch, survive background/foreground, no fatal exceptions.
# Usage: smoke-test.sh <apk> <output-dir>
set -euo pipefail
APK="${1:?apk}"
OUT="${2:-smoke-test-output}"
PKG="io.github.saefalasalynbtel198.shcport"
mkdir -p "$OUT"

fail() {
  echo "::error::$*"
  adb logcat -d > "$OUT/logcat.txt" || true
  # Print the evidence into the job log as well (artifacts are not always reachable).
  echo "----- logcat: crashes, ActivityManager and app lines (last 120) -----"
  grep -E "AndroidRuntime|FATAL|ShcPort|shcport|ActivityManager|libc  *:|DEBUG  *:" "$OUT/logcat.txt" | tail -n 120 || true
  echo "----- process list -----"
  adb shell ps -A | grep -i shcport || echo "(no shcport process)"
  exit 1
}

# pidof can race with process start-up on slow emulators; poll for up to 10 s.
app_pid() {
  local i p
  for i in 1 2 3 4 5 6 7 8 9 10; do
    p=$(adb shell pidof "$PKG" | tr -d '\r' || true)
    [ -n "$p" ] && { echo "$p"; return 0; }
    sleep 1
  done
  return 1
}

adb wait-for-device
adb shell getprop ro.build.version.release > "$OUT/android-version.txt"
adb install -r "$APK" || fail "install failed"
adb logcat -c

adb shell am start -W -n "$PKG/.app.MainActivity" | tee "$OUT/am-start.txt"
grep -q "Status: ok" "$OUT/am-start.txt" || fail "activity did not start"
sleep 6
pid=$(app_pid) || fail "app process is not running after launch"

# Lifecycle: background, then foreground again.
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell am start -W -n "$PKG/.app.MainActivity" > /dev/null
sleep 3
pid2=$(app_pid) || fail "app process died across background/foreground"

adb shell screencap -p /sdcard/smoke.png && adb pull /sdcard/smoke.png "$OUT/screenshot.png" > /dev/null || true

# Display test screen (BUILD 3 step 3.2): opens (debug-only extra), GL surface comes up, survives pause/resume
# (screen off/on keeps the same activity instance) without a runtime installed.
adb shell am start -S -W -n "$PKG/.app.MainActivity" --es shc.open display-test > "$OUT/am-start-display.txt"   # -S: cold start, so onCreate reads the extra
sleep 5
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity|ResumedActivity" | tee "$OUT/display-resumed-1.txt"
grep -q "DisplayTestActivity" "$OUT/display-resumed-1.txt" || fail "display test screen did not open"
adb shell input keyevent KEYCODE_SLEEP
sleep 3
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
sleep 4
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity|ResumedActivity" | tee "$OUT/display-resumed-2.txt"
grep -q "DisplayTestActivity" "$OUT/display-resumed-2.txt" || fail "display test screen not resumed after screen off/on"
app_pid > /dev/null || fail "app process died on the display test screen"
adb shell screencap -p /sdcard/display.png && adb pull /sdcard/display.png "$OUT/display-screen.png" > /dev/null || true
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt"; then
  fail "crash or ANR detected"
fi
grep -q "ShcPort.*Started" "$OUT/logcat.txt" || fail "application start log line not found"
echo "Smoke test passed (pid $pid → $pid2)."
