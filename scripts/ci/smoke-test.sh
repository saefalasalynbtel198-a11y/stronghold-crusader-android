#!/usr/bin/env bash
# Emulator smoke test: install, launch, survive background/foreground, no fatal exceptions.
# With the owner's old 0.3.5-dev.1 APK: checks that the new APK cannot update it (different signing key, so the one-time
# uninstall on the phone is expected), then removes it ON THE EMULATOR ONLY. With an update APK (same package, version
# code + 1, built on another runner with the same SHC key): installs it over the new app and checks the app's data stays.
# Usage: smoke-test.sh <apk> <output-dir> [old-apk] [update-apk]
set -euo pipefail
APK="${1:?apk}"
OUT="${2:-smoke-test-output}"
OLD_APK="${3:-}"
UPDATE_APK="${4:-}"
PKG="io.github.saefalasalynbtel198.shcport"
ACT="io.github.saefalasalynbtel198.shcport.app.MainActivity"
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
installed_cert() {
  adb pull "$(adb shell pm path "$PKG" | sed -n 's/^package://p' | tr -d '\r')" "$OUT/installed.apk" > /dev/null
  "$apksigner" verify --print-certs "$OUT/installed.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
pkg_field() { adb shell dumpsys package "$PKG" | grep -m1 "$1" | tr -d '\r' | sed 's/^ *//'; }
if [ -n "$OLD_APK" ]; then
  echo "== the 0.3.5-dev.1 APK cannot be updated by the new APK (expected: different signing key)"
  adb install "$OLD_APK" > /dev/null || fail "installing the 0.3.5 APK failed"
  echo "installed: $(pkg_field versionName), certificate $(installed_cert)"
  if adb install -r "$APK" > "$OUT/update-over-0.3.5.txt" 2>&1; then
    fail "the new APK installed over 0.3.5 although the keys differ (unexpected)"
  fi
  grep -o "INSTALL_FAILED_UPDATE_INCOMPATIBLE[^]]*" "$OUT/update-over-0.3.5.txt" | head -1 || cat "$OUT/update-over-0.3.5.txt"
  grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" "$OUT/update-over-0.3.5.txt" || fail "unexpected install error (see update-over-0.3.5.txt)"
  adb uninstall "$PKG" > /dev/null   # emulator only
fi
adb install "$APK" || fail "install failed"
echo "installed: $(pkg_field versionName), $(pkg_field versionCode | cut -d' ' -f1), certificate $(installed_cert)"
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

# Main screen: Start Game disabled with its reason (no runtime and no game in the emulator). The main screen logs its
# decision; the button itself is below the emulator's visible area, which uiautomator does not dump.
adb shell am start -S -W -n "$PKG/$ACT" > /dev/null
sleep 6
adb logcat -d -s ShcPort:I > "$OUT/main-start-game.txt"
grep "Start Game" "$OUT/main-start-game.txt" | tail -1
grep "Start Game" "$OUT/main-start-game.txt" | tail -1 | grep -q "Start Game disabled: Install the runtime" \
  || fail "Start Game not disabled with the runtime reason"

# Game screen (Start Game): opens, the start fails cleanly without a runtime and says why; the app stays alive
adb shell am start -S -W -n "$PKG/$ACT" --es shc.open play > "$OUT/am-start-play.txt"
sleep 8
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity|ResumedActivity" | tee "$OUT/play-resumed.txt"
grep -q "GameActivity" "$OUT/play-resumed.txt" || fail "game screen did not open"
app_pid > /dev/null || fail "app process died on the game screen"
adb shell uiautomator dump /sdcard/play-ui.xml > /dev/null 2>&1 && adb pull /sdcard/play-ui.xml "$OUT/play-ui.xml" > /dev/null 2>&1 || true
if [ -s "$OUT/play-ui.xml" ]; then
  grep -q "runtime not installed" "$OUT/play-ui.xml" || fail "game screen does not report the missing runtime"
fi
adb shell screencap -p /sdcard/play.png && adb pull /sdcard/play.png "$OUT/play-screen.png" > /dev/null || true
adb shell input keyevent KEYCODE_HOME
sleep 3
app_pid > /dev/null || fail "app process died after leaving the game screen"
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt"; then
  fail "crash or ANR detected"
fi
grep -q "ShcPort.*Started" "$OUT/logcat.txt" || fail "application start log line not found"
if [ -n "$UPDATE_APK" ]; then
  echo "== update test: install the second, independently built APK over this one; the app's data must stay"
  adb shell am force-stop "$PKG"
  adb shell run-as "$PKG" sh -c 'mkdir -p files/update-test && echo "written before the update" > files/update-test/marker.txt' \
    || fail "cannot write test data into the app"
  before_files=$(adb shell run-as "$PKG" ls -R files | tr -d '\r' | sort | sha256sum | cut -c1-16)
  first_before=$(pkg_field firstInstallTime); cert_before=$(installed_cert); code_before=$(pkg_field versionCode | cut -d' ' -f1)
  adb install -r "$UPDATE_APK" > "$OUT/update.txt" 2>&1 || { cat "$OUT/update.txt"; fail "the update APK did not install over the app"; }
  first_after=$(pkg_field firstInstallTime); cert_after=$(installed_cert); code_after=$(pkg_field versionCode | cut -d' ' -f1)
  after_files=$(adb shell run-as "$PKG" ls -R files | tr -d '\r' | sort | sha256sum | cut -c1-16)
  marker=$(adb shell run-as "$PKG" cat files/update-test/marker.txt | tr -d '\r')
  echo "before: $code_before, $first_before, certificate $cert_before"
  echo "after:  $code_after, $first_after, certificate $cert_after"
  [ "$code_before" != "$code_after" ] || fail "version code did not change (not an update)"
  [ "$first_before" = "$first_after" ] || fail "firstInstallTime changed: the app was reinstalled, not updated"
  [ "$cert_before" = "$cert_after" ] || fail "certificate changed across the update"
  [ "$marker" = "written before the update" ] || fail "app data lost in the update"
  [ "$before_files" = "$after_files" ] || fail "app files differ after the update"
  adb shell am start -W -n "$PKG/$ACT" | grep -q "Status: ok" || fail "the updated app does not start"
  echo "Update test passed: $code_before -> $code_after, same certificate, same first install time, app data kept, app starts."
fi
echo "Smoke test passed (pid $pid → $pid2)."
