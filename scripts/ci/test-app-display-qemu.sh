#!/usr/bin/env bash
# BUILD 3 step 3.2, CI part: the display path with the app's own code (scripts/ci/DisplayCli.java -> core/), on
# the unchanged runtime r6, under qemu-aarch64. Linux emulation, NOT Android (no seccomp/SELinux enforcement).
#   1. Xvfb with the app's command line and working directory (X11DisplayPlan), in a mount namespace where
#      /tmp, /var, /usr/lib/xorg and /usr/share/X11 are empty and read-only; readiness = abstract socket
#   2. xdpyinfo with the app's command line
#   3. wineboot (no display), stop leftover Wine processes, then shc-display-test.exe with the app's Wine
#      environment and DISPLAY
#   4. framebuffer read with the app's XWD parser through a read-only mapping, as the renderer does:
#      4 quadrants, frame counter increasing, moving square moved
#   5. every syscall of every process checked against Android's arm64 app allowlist
# Usage: test-app-display-qemu.sh <shc-runtime-r6-*.zip> <shc-display-test.exe> <work dir>
set -uo pipefail
ZIP="$(realpath "${1:?runtime zip}")"
EXE="$(realpath "${2:?shc-display-test.exe}")"
W="$(mkdir -p "${3:?work dir}" && realpath "$3")"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
core="$repo/app/src/main/java/io/github/saefalasalynbtel198/shcport/core"
rt="$W/runtime"; st="$W/strace"; fb="$W/fb-session1"
rm -rf "$rt" "$st" "$fb" "$W/prefix" "$W/classes"; mkdir -p "$st" "$W/home" "$W/tmp" "$W/classes"
result() { echo "RESULT $1: $2" | tee -a "$W/results.txt"; }
: > "$W/results.txt"
[ -z "${LD_PRELOAD:-}" ] || { echo "::error::LD_PRELOAD set"; exit 1; }

javac -d "$W/classes" -sourcepath "$repo/app/src/main/java" "$core/RuntimeArchive.java" "$core/ElfInterp.java" \
  "$core/RuntimeLayout.java" "$core/X11DisplayPlan.java" "$core/XwdFramebuffer.java" "$core/DisplayTestPattern.java" \
  "$repo/scripts/ci/ExtractRuntime.java" "$repo/scripts/ci/DisplayCli.java" || exit 1
cli() { java -cp "$W/classes" DisplayCli "$@"; }
java -cp "$W/classes" ExtractRuntime "$ZIP" "$rt" patch-interp || exit 1
cat "$rt/RUNTIME_VERSION"
D=37
mapfile -t XVFB < <(cli xvfb-argv "$rt" $D "$fb")
XCWD=$(cli xvfb-cwd "$rt"); SOCK=$(cli socket $D)
echo "app's Xvfb command: ${XVFB[*]} (cwd $XCWD)"
mkdir -p "$fb"

echo "== 1. Xvfb as the app starts it"
printf '%s\0' "${XVFB[@]}" > "$W/xvfb.argv0"
export SPIKE_CWD="$XCWD" SPIKE_ARGV="$W/xvfb.argv0" SPIKE_ST="$st"
sudo --preserve-env unshare --mount -- bash -c '
  for d in /tmp /var /usr/lib/xorg /usr/share/X11; do [ -d "$d" ] && mount -t tmpfs -o ro,size=4k,mode=0555 none "$d"; done
  cd "$SPIKE_CWD"
  mapfile -d "" -t A < "$SPIKE_ARGV"
  exec setpriv --reuid="$SUDO_UID" --regid="$SUDO_GID" --init-groups env -u LD_LIBRARY_PATH \
    QEMU_STRACE=1 QEMU_LOG_FILENAME="$SPIKE_ST/xvfb.%d" "${A[@]}"' > "$W/xvfb.log" 2>&1 &
up=0
for i in $(seq 1 300); do
  grep -q "@$SOCK" /proc/net/unix && { up=1; break; }
  [ "$i" -gt 15 ] && ! pgrep -u "$(id -u)" -f "Xvfb :$D" > /dev/null && break
  sleep 1
done
if [ $up -eq 1 ]; then result xvfb "PASS (app command line; abstract socket @$SOCK after ${i}s)"
else result xvfb "FAIL"; cat "$W/xvfb.log"; fi
mapfile -t XD < <(cli xdpyinfo-argv "$rt" $D)
timeout 300 "${XD[@]}" > "$W/xdpyinfo.log" 2>&1 && result xdpyinfo "PASS ($(grep -c '^    ' "$W/xdpyinfo.log") extension lines; MIT-SHM $(grep -q 'MIT-SHM' "$W/xdpyinfo.log" && echo present || echo absent))" \
  || { result xdpyinfo FAIL; cat "$W/xdpyinfo.log"; }

echo "== 2. Wine with the app's environment"
mapfile -t ENVL < <(cli wine-env "$rt" "$W/prefix" "$W/tmp" $D)
for kv in "${ENVL[@]}"; do export "$kv"; done
export HOME="$W/home"
echo "DISPLAY=$DISPLAY WINEPREFIX=$WINEPREFIX"
box64="$rt/bin/box64"; wineserver="$rt/wine/bin/wineserver"
DISPLAY= timeout 1800 "$box64" "$rt/wine/bin/wine" wineboot --init > "$W/wineboot.log" 2>&1; echo "wineboot exit $?"
timeout 300 "$box64" "$wineserver" -w || { echo "stopping leftover Wine processes"; timeout 120 "$box64" "$wineserver" -k; sleep 5; }
mapfile -t WA < <(cli wine-argv "$rt" "$EXE")
(cd "$WINEPREFIX/drive_c" && QEMU_STRACE=1 QEMU_LOG_FILENAME="$st/wine.%d" exec timeout 2400 "${WA[@]}" 600 > "$W/display-test.log" 2>&1) &
wpid=$!

echo "== 3. framebuffer through the app's parser (read-only mapping)"
ok=0
for i in $(seq 1 120); do
  cli check "$fb/Xvfb_screen0" > "$W/check1.txt" 2>&1 && { ok=1; break; }
  kill -0 $wpid 2>/dev/null || break
  sleep 10
done
cat "$W/check1.txt"
if [ $ok -eq 1 ]; then result pixels "PASS (app's XwdFramebuffer + DisplayTestPattern: 4 quadrants, BGRX layout)"
else result pixels "FAIL"; fi
c1=$(sed -n 's/^counter //p' "$W/check1.txt"); b1=$(sed -n 's/^band //p' "$W/check1.txt"); t1=$(date +%s)
sleep 30
cli check "$fb/Xvfb_screen0" > "$W/check2.txt" 2>&1
c2=$(sed -n 's/^counter //p' "$W/check2.txt"); b2=$(sed -n 's/^band //p' "$W/check2.txt"); t2=$(date +%s)
echo "counter $c1 -> $c2 in $((t2 - t1)) s; band $b1 -> $b2"
if [ "${c1:--1}" -ge 0 ] && [ "${c2:--1}" -gt "${c1:--1}" ] && [ "$b1" != "$b2" ]; then
  result motion "PASS (frame counter $c1 -> $c2 in $((t2 - t1)) s under qemu, about $(( (c2 - c1) / (t2 - t1) )) fps; moving square moved)"
else result motion "FAIL (counter $c1 -> $c2, band $b1 -> $b2)"; fi
python3 -I "$repo/runtime/display/check-fb.py" "$fb/Xvfb_screen0" none "$W/display-test.png" > /dev/null 2>&1
grep "display-test:" "$W/display-test.log" | head -8

timeout 120 "$box64" "$wineserver" -k > /dev/null 2>&1; wait $wpid; echo "test program exit $?"
pkill -u "$(id -u)" -f "Xvfb :$D"; sleep 2
pgrep -u "$(id -u)" -f "Xvfb :$D" > /dev/null && result stop "FAIL (Xvfb still running)" || result stop "PASS (Xvfb stopped on SIGTERM)"
[ -e "$fb/Xvfb_screen0" ] && echo "framebuffer file still present after stop" || echo "framebuffer file removed by Xvfb on exit"

echo "== 4. Android seccomp check"
python3 -I "$repo/runtime/android/check-strace.py" "$repo/runtime/android/arm64-app-syscalls.txt" "$st"/* > "$W/seccomp-check.txt"; sc=$?
grep -v " 0 outside" "$W/seccomp-check.txt"
[ $sc -eq 0 ] && result seccomp "PASS ($(ls "$st" | wc -l) process traces within Android's arm64 app allowlist)" || result seccomp FAIL
rm -rf "$st"
echo "== Summary"; cat "$W/results.txt"
! grep -q FAIL "$W/results.txt"
