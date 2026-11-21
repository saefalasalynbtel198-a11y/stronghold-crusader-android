#!/usr/bin/env bash
# Emulator smoke test: install, launch, survive background/foreground, no fatal exceptions.
# With an old APK (the owner's 0.3.5-dev.1, package ...shcport) it first installs that one and checks that the new
# side-by-side app (package ...shcport.dev) installs next to it, cannot read its data, and leaves it unchanged.
# Usage: smoke-test.sh <apk> <output-dir> [old-apk]
set -euo pipefail
APK="${1:?apk}"
OUT="${2:-smoke-test-output}"
OLD_APK="${3:-}"
PKG="io.github.saefalasalynbtel198.shcport.dev"
OLD_PKG="io.github.saefalasalynbtel198.shcport"
ACT="io.github.saefalasalynbtel198.shcport.app.MainActivity"   # class names are the same in both apps
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
apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2> /dev/null | sort -V | tail -n1)
old_state() {   # version, signing certificate and file listing of the old app
  adb shell dumpsys package "$OLD_PKG" | grep -m1 versionName | tr -d '\r '
  adb pull "$(adb shell pm path "$OLD_PKG" | sed -n 's/^package://p' | tr -d '\r')" "$OUT/old-installed.apk" > /dev/null
  "$apksigner" verify --print-certs "$OUT/old-installed.apk" | grep -m1 "certificate SHA-256"
  adb shell run-as "$OLD_PKG" ls -lR files | tr -d '\r'
}
if [ -n "$OLD_APK" ]; then
  echo "== side-by-side: the 0.3.5-dev.1 app first"
  adb install "$OLD_APK" || fail "installing the 0.3.5 APK failed"
  adb shell am start -W -n "$OLD_PKG/$ACT" > /dev/null; sleep 5
  adb shell run-as "$OLD_PKG" sh -c 'mkdir -p files/side-by-side && echo "data of the 0.3.5 app" > files/side-by-side/marker.txt' \
    || fail "cannot write the marker into the 0.3.5 app's data"
  adb shell am force-stop "$OLD_PKG"
  old_state > "$OUT/old-before.txt"
  head -2 "$OUT/old-before.txt"
fi
adb install -r "$APK" || fail "install failed"
if [ -n "$OLD_APK" ]; then
  adb shell pm list packages | tr -d '\r' | grep -E "^package:$OLD_PKG(\.dev)?$" | sort | tee "$OUT/packages.txt"
  [ "$(wc -l < "$OUT/packages.txt")" -eq 2 ] || fail "the two apps are not installed side by side"
  if adb shell run-as "$PKG" ls "/data/data/$OLD_PKG/files" > "$OUT/cross-read.txt" 2>&1 && ! grep -qi "denied\|No such" "$OUT/cross-read.txt"; then
    fail "the new app can read the 0.3.5 app's data"
  fi
  echo "new app reading the 0.3.5 app's data: $(head -c 120 "$OUT/cross-read.txt" | tr -d '\r')"
fi
adb logcat -c

adb shell am start -W -n "$PKG/$ACT" | tee "$OUT/am-start.txt"
grep -q "Status: ok" "$OUT/am-start.txt" || fail "activity did not start"
sleep 6
pid=$(app_pid) || fail "app process is not running after launch"

# Lifecycle: background, then foreground again.
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell am start -W -n "$PKG/$ACT" > /dev/null
sleep 3
pid2=$(app_pid) || fail "app process died across background/foreground"

adb shell screencap -p /sdcard/smoke.png && adb pull /sdcard/smoke.png "$OUT/screenshot.png" > /dev/null || true

# Display test screen (BUILD 3 step 3.2): opens (debug-only extra), GL surface comes up, survives pause/resume
# (screen off/on keeps the same activity instance) without a runtime installed.
adb shell am start -S -W -n "$PKG/$ACT" --es shc.open display-test > "$OUT/am-start-display.txt"   # -S: cold start, so onCreate reads the extra
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

# Game launch test screen (BUILD 3 step 3.3, same activity in game mode): opens; not started (no runtime, no game here)
adb shell am start -S -W -n "$PKG/$ACT" --es shc.open game-launch > "$OUT/am-start-game.txt"
sleep 5
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity|ResumedActivity" | tee "$OUT/game-resumed.txt"
grep -q "DisplayTestActivity" "$OUT/game-resumed.txt" || fail "game launch test screen did not open"
app_pid > /dev/null || fail "app process died on the game launch test screen"
adb shell uiautomator dump /sdcard/game-ui.xml > /dev/null 2>&1 && adb pull /sdcard/game-ui.xml "$OUT/game-ui.xml" > /dev/null 2>&1 || true
if [ -s "$OUT/game-ui.xml" ]; then
  grep -q "Game launch test" "$OUT/game-ui.xml" || fail "game launch test screen does not show its title"
fi
adb shell screencap -p /sdcard/game.png && adb pull /sdcard/game.png "$OUT/game-screen.png" > /dev/null || true
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt"; then
  fail "crash or ANR detected"
fi
grep -q "ShcPort.*Started" "$OUT/logcat.txt" || fail "application start log line not found"
if [ -n "$OLD_APK" ]; then
  echo "== side-by-side: the 0.3.5-dev.1 app afterwards"
  old_state > "$OUT/old-after.txt"
  diff "$OUT/old-before.txt" "$OUT/old-after.txt" || fail "the 0.3.5 app changed (version, certificate or data)"
  adb shell run-as "$OLD_PKG" cat files/side-by-side/marker.txt | grep -q "data of the 0.3.5 app" || fail "0.3.5 marker lost"
  adb shell am start -W -n "$OLD_PKG/$ACT" | grep -q "Status: ok" || fail "the 0.3.5 app does not start any more"
  echo "Side-by-side passed: $(head -1 "$OUT/old-after.txt"), $(sed -n 2p "$OUT/old-after.txt"); data unchanged; still starts."
fi
echo "Smoke test passed (pid $pid → $pid2)."
