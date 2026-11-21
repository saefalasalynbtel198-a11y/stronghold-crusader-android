#!/usr/bin/env bash
# BUILD 3 step 3.2, CI part: the display path with the app's own code (scripts/ci/DisplayCli.java -> core/), on
# the unchanged runtime r6, under qemu-aarch64. Linux emulation, NOT Android (no seccomp/SELinux enforcement).
#   0. Android forbids apps to create hard links (SELinux); here link/linkat fail with EACCES (deny-link):
#      the old Xvfb command line (lock file) must reproduce the device error, the app's command line must not
#   1. Xvfb with the app's launch command (shell wrapper, -displayfd 3, cwd from X11DisplayPlan), in a mount
#      namespace where /tmp, /var, /usr/lib/xorg and /usr/share/X11 are empty and read-only; ready = the display
#      number on the -displayfd pipe, read with the app's parser; no link syscall at all
#   2. display in use: a second Xvfb on the same display is refused and classified as "in use" by the app's code
#   3. xdpyinfo; wineboot (no display); shc-display-test.exe with the app's Wine environment and DISPLAY
#   4. framebuffer read with the app's XWD parser through a read-only mapping: 4 quadrants, counter, motion
#   5. stop, then start again on the same display (no stale lock state)
#   6. every syscall of every process checked against Android's arm64 app allowlist
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
gcc -O2 -Wall -Werror -o "$W/deny-link" "$repo/scripts/ci/deny-link.c" || exit 1
XCWD=$(cli xvfb-cwd "$rt"); SOCK=$(cli socket $D)

# runs one Xvfb as the app user in the Android-like mount namespace with hard links denied
#   start_xvfb <argv file (NUL separated)> <stdout file> <stderr file> <strace prefix>
start_xvfb() {
  SPIKE_CWD="$XCWD" SPIKE_ARGV="$1" SPIKE_ST="$4" SPIKE_DENY="$W/deny-link" sudo --preserve-env unshare --mount -- bash -c '
    for d in /tmp /var /usr/lib/xorg /usr/share/X11; do [ -d "$d" ] && mount -t tmpfs -o ro,size=4k,mode=0555 none "$d"; done
    cd "$SPIKE_CWD"
    mapfile -d "" -t A < "$SPIKE_ARGV"
    exec setpriv --reuid="$SUDO_UID" --regid="$SUDO_GID" --init-groups env -u LD_LIBRARY_PATH \
      QEMU_STRACE=1 QEMU_LOG_FILENAME="$SPIKE_ST.%d" "$SPIKE_DENY" "${A[@]}"' > "$2" 2> "$3" &
}
xvfb_alive() { pgrep -u "$(id -u)" -f "Xvfb :$D" > /dev/null; }

echo "== 0. old command line (lock file) with hard links denied: must reproduce the device error"
mapfile -t OLD < <(cli xvfb-argv "$rt" $D "$fb" | grep -v -- "-displayfd" | grep -vx "3")
printf '%s\0' "${OLD[@]}" > "$W/old.argv0"
mkdir -p "$fb"
start_xvfb "$W/old.argv0" "$W/old.out" "$W/old.log" "$st/old"
for i in $(seq 1 120); do sleep 1; [ "$i" -gt 5 ] && ! xvfb_alive && break; done
grep "Linking lock file" "$W/old.log"
if grep -q "Linking lock file (./.X$D-lock) in place failed: Permission denied" "$W/old.log"; then
  result reproduce "PASS (old command fails exactly as on the device: Linking lock file (./.X$D-lock) in place failed: Permission denied)"
else result reproduce "FAIL (device error not reproduced)"; tail -20 "$W/old.log"; fi
pkill -u "$(id -u)" -f "Xvfb :$D"; sleep 1
rm -f "$XCWD/.X$D-lock" "$XCWD/.tX$D-lock"

echo "== 1. the app's launch command (-displayfd), hard links still denied"
mapfile -t LAUNCH < <(cli launch-argv /bin/sh "$rt" $D "$fb")
echo "app's launch command: ${LAUNCH[*]} (cwd $XCWD)"
printf '%s\0' "${LAUNCH[@]}" > "$W/xvfb.argv0"
start_xvfb "$W/xvfb.argv0" "$W/displayfd.out" "$W/xvfb.log" "$st/xvfb"
up=0
for i in $(seq 1 300); do
  r=$(cli ready "$(head -n1 "$W/displayfd.out" 2>/dev/null)")
  [ "$r" = "$D" ] && { up=1; break; }
  [ "$i" -gt 15 ] && ! xvfb_alive && break
  sleep 1
done
links=$(cat "$st"/xvfb.* 2>/dev/null | grep -cE " (link|linkat)\(")
locks=$(ls -a "$XCWD" | grep -c "X$D-lock")
grep -q "@$SOCK" /proc/net/unix && sock=yes || sock=no
if [ $up -eq 1 ] && [ "$links" -eq 0 ] && [ "$locks" -eq 0 ] && [ $sock = yes ]; then
  result xvfb "PASS (ready line \"$D\" on the -displayfd pipe after ${i}s; abstract socket @$SOCK; 0 link calls; no lock file)"
else result xvfb "FAIL (ready=$up links=$links lockfiles=$locks socket=$sock)"; cat "$W/xvfb.log"; fi

echo "== 2. display in use: a second Xvfb on :$D"
mkdir -p "$W/fb-second"
mapfile -t SECOND < <(cli launch-argv /bin/sh "$rt" $D "$W/fb-second")
printf '%s\0' "${SECOND[@]}" > "$W/second.argv0"
start_xvfb "$W/second.argv0" "$W/second.out" "$W/second.log" "$st/second"
sleep 20
grep -E "Cannot establish|already" "$W/second.log" | head -3
cls=$(cli classify "$W/second.log")
if [ "$cls" = DISPLAY_IN_USE ] && [ -z "$(cat "$W/second.out")" ]; then
  result in-use "PASS (second Xvfb refused, app classifies it as $cls and would try :$((D + 1)))"
else result in-use "FAIL (classified $cls, reported '$(cat "$W/second.out")')"; fi

mapfile -t XD < <(cli xdpyinfo-argv "$rt" $D)
timeout 300 "${XD[@]}" > "$W/xdpyinfo.log" 2>&1 && result xdpyinfo "PASS ($(grep -c '^    ' "$W/xdpyinfo.log") extension lines; MIT-SHM $(grep -q 'MIT-SHM' "$W/xdpyinfo.log" && echo present || echo absent))" \
  || { result xdpyinfo FAIL; cat "$W/xdpyinfo.log"; }

echo "== 3. Wine with the app's environment"
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

echo "== 4. framebuffer through the app's parser (read-only mapping)"
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
xvfb_alive && result stop "FAIL (Xvfb still running)" || result stop "PASS (Xvfb stopped on SIGTERM)"
[ -e "$fb/Xvfb_screen0" ] && echo "framebuffer file still present after stop" || echo "framebuffer file removed by Xvfb on exit"

echo "== 5. start again on the same display (repeated start/stop, nothing stale)"
for round in 2 3; do
  mkdir -p "$W/fb-round$round"
  mapfile -t AGAIN < <(cli launch-argv /bin/sh "$rt" $D "$W/fb-round$round")
  printf '%s\0' "${AGAIN[@]}" > "$W/again.argv0"
  start_xvfb "$W/again.argv0" "$W/again$round.out" "$W/again$round.log" "$st/again$round"
  ok=0
  for i in $(seq 1 120); do
    [ "$(cli ready "$(head -n1 "$W/again$round.out" 2>/dev/null)")" = "$D" ] && { ok=1; break; }
    sleep 1
  done
  pkill -u "$(id -u)" -f "Xvfb :$D"; sleep 2
  if [ $ok -eq 1 ] && ! xvfb_alive; then result "restart-$round" "PASS (:$D ready again after ${i}s, stopped again)"
  else result "restart-$round" "FAIL"; tail -10 "$W/again$round.log"; fi
done

echo "== 6. Android seccomp check"
# the old-command run (step 0) is excluded: it exits on the lock error by design
python3 -I "$repo/runtime/android/check-strace.py" "$repo/runtime/android/arm64-app-syscalls.txt" $(ls "$st"/* | grep -v "/old\.") > "$W/seccomp-check.txt"; sc=$?
grep -v " 0 outside" "$W/seccomp-check.txt"
[ $sc -eq 0 ] && result seccomp "PASS ($(ls "$st" | wc -l) process traces within Android's arm64 app allowlist)" || result seccomp FAIL
rm -rf "$st"
echo "== Summary"; cat "$W/results.txt"
! grep -q FAIL "$W/results.txt"
