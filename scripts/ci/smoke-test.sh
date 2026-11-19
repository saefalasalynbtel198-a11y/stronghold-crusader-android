#!/usr/bin/env bash
# Emulator smoke test: install, launch, survive background/foreground, no fatal exceptions.
# Usage: smoke-test.sh <apk> <output-dir>
set -euo pipefail
APK="${1:?apk}"
OUT="${2:-smoke-test-output}"
PKG="io.github.saefalasalynbtel198.shcport"
mkdir -p "$OUT"

fail() { echo "::error::$*"; adb logcat -d > "$OUT/logcat.txt" || true; exit 1; }

adb wait-for-device
adb shell getprop ro.build.version.release > "$OUT/android-version.txt"
adb install -r "$APK" || fail "install failed"
adb logcat -c

adb shell am start -W -n "$PKG/.app.MainActivity" | tee "$OUT/am-start.txt"
grep -q "Status: ok" "$OUT/am-start.txt" || fail "activity did not start"
sleep 6
pid=$(adb shell pidof "$PKG" | tr -d '\r' || true)
[ -n "$pid" ] || fail "app process is not running after launch"

# Lifecycle: background, then foreground again.
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell am start -W -n "$PKG/.app.MainActivity" > /dev/null
sleep 3
pid2=$(adb shell pidof "$PKG" | tr -d '\r' || true)
[ -n "$pid2" ] || fail "app process died across background/foreground"

adb shell screencap -p /sdcard/smoke.png && adb pull /sdcard/smoke.png "$OUT/screenshot.png" > /dev/null || true
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt"; then
  fail "crash or ANR detected"
fi
grep -q "ShcPort.*Started" "$OUT/logcat.txt" || fail "application start log line not found"
echo "Smoke test passed (pid $pid → $pid2)."
