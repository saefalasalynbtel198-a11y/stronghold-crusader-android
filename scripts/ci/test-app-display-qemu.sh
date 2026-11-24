#!/usr/bin/env bash
# BUILD 3 step 3.2, CI part: the display path with the app's own code (scripts/ci/DisplayCli.java -> core/), on
# the unchanged runtime r6, under qemu-aarch64. Linux emulation, NOT Android (no SELinux; seccomp only as below).
#   0. regressions of the two device failures:
#      a. Android forbids apps hard links (SELinux); deny-link makes link/linkat fail with EACCES: the old command
#         line (lock file) must fail with the device message "Linking lock file ... Permission denied"
#      b. Android's /system/bin/sh is mksh, which closes descriptors above 2 opened by "exec" on exec: the 0.3.3
#         wrapper (exec 3>&1; exec Xvfb -displayfd 3) must fail with "Cannot write display number to fd 3"
#   1. the app's XvfbStarter starts the runtime's Xvfb with the app's argv (loader, no shell, -displayfd 1), as the
#      app user, hard links denied, /tmp /var /usr/lib/xorg /usr/share/X11 empty and read-only; display :37
#   2. occupied displays: Xvfb refuses the busy :37 -> :38; busy displays skipped -> :39, :40; all busy -> error
#   P. the display test's Wine prefix stage with the app's code, before Xvfb: the device failure of 0.3.4 reproduced
#      (fresh app data, no parent directory), then fresh prefix created, then an initialised prefix kept
#   3. xdpyinfo; shc-display-test.exe with the app's Wine environment and DISPLAY, in that prefix
#   4. framebuffer read with the app's XWD parser through a read-only mapping: 4 quadrants, counter, motion
#   4b. step 3.3: the app's game launch code (WineLaunchPlan, GameSetup, GameFolderManifest) with a stand-in game
#      (the runtime's DirectDraw test program copied under the game's file name): D: drive, cnc-ddraw native from
#      syswow64, ddraw.ini via CNC_DDRAW_CONFIG_FILE, DirectDraw colours in Xvfb, game folder unchanged
#   5. start/stop cycles with the app's code: stdout closed after the number, process ended, socket gone
#   6. no link syscall in any Xvfb started by the app's code; every syscall checked against Android's arm64 allowlist
# Usage: test-app-display-qemu.sh <shc-runtime-r6-*.zip> <shc-display-test.exe> <work dir>
set -uo pipefail
ZIP="$(realpath "${1:?runtime zip}")"
EXE="$(realpath "${2:?shc-display-test.exe}")"
W="$(mkdir -p "${3:?work dir}" && realpath "$3")"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
core="$repo/app/src/main/java/io/github/saefalasalynbtel198/shcport/core"
rt="$W/runtime"; st="$W/strace"; logs="$W/xvfb-logs"
rm -rf "$rt" "$st" "$logs" "$W"/fb-* "$W/appdata" "$W/classes"; mkdir -p "$st" "$logs" "$W/home" "$W/tmp" "$W/classes"
chmod 0777 "$logs"
result() { echo "RESULT $1: $2" | tee -a "$W/results.txt"; }
: > "$W/results.txt"
[ -z "${LD_PRELOAD:-}" ] || { echo "::error::LD_PRELOAD set"; exit 1; }

javac -d "$W/classes" -sourcepath "$repo/app/src/main/java" "$core"/*.java \
  "$repo/scripts/ci/ExtractRuntime.java" "$repo/scripts/ci/DisplayCli.java" || exit 1
cli() { java -cp "$W/classes" DisplayCli "$@" 2> >(grep -v "^Picked up" >&2); }
java -cp "$W/classes" ExtractRuntime "$ZIP" "$rt" patch-interp || exit 1
cat "$rt/RUNTIME_VERSION"
gcc -O2 -Wall -Werror -o "$W/deny-link" "$repo/scripts/ci/deny-link.c" || exit 1
XCWD=$(cli xvfb-cwd "$rt")
ANDROID_LIKE=(sudo --preserve-env unshare --mount -- "$repo/scripts/ci/android-like-exec.sh" "$W/deny-link")
alive() { pgrep -u "$(id -u)" -f "x11/Xvfb :$1 " > /dev/null; }
listening() { grep -q "@$(cli socket "$1")\$" /proc/net/unix; }

echo "== 0a. old command line (lock file), hard links denied: must reproduce the first device error"
mkdir -p "$W/fb-old"
mapfile -t OLD < <(cli xvfb-argv "$rt" 37 "$W/fb-old" | sed '/^-displayfd$/,$d')
echo "old argv: ${OLD[*]}"
(cd "$XCWD" && QEMU_STRACE=1 QEMU_LOG_FILENAME="$st/repro-lock.%d" exec timeout 300 "${ANDROID_LIKE[@]}" "${OLD[@]}") > /dev/null 2> "$W/repro-lock.log"
grep "Linking lock file" "$W/repro-lock.log"
if grep -q "Linking lock file (./.X37-lock) in place failed: Permission denied" "$W/repro-lock.log"; then
  result repro-lock "PASS (old command fails as on the device: Linking lock file (./.X37-lock) in place failed: Permission denied)"
else result repro-lock "FAIL (device error not reproduced)"; tail -20 "$W/repro-lock.log"; fi
rm -f "$XCWD/.X37-lock" "$XCWD/.tX37-lock"

echo "== 0b. the 0.3.3 wrapper under mksh (Android's /system/bin/sh): must reproduce the second device error"
WRAP='exec 3>&1 1>/dev/null; exec "$@"'
for sh in dash mksh; do
  printf '%s: ' "$sh"; $sh -c "$WRAP" x sh -c '[ -e /proc/self/fd/3 ] && echo "fd 3 open after exec" >&2 || echo "fd 3 CLOSED after exec" >&2'
done
mksh -c "$WRAP" x sh -c '[ -e /proc/self/fd/3 ]' && fd3=open || fd3=closed
timeout 60 mksh -c "$WRAP" xvfb Xvfb :61 -nolisten tcp -displayfd 3 > "$W/repro-fd3.out" 2> "$W/repro-fd3.log"; rc=$?
grep "Cannot write display number" "$W/repro-fd3.log"
if [ $fd3 = closed ] && grep -q "Cannot write display number to fd 3" "$W/repro-fd3.log" && [ ! -s "$W/repro-fd3.out" ]; then
  result repro-fd3 "PASS (mksh closes fd 3 on exec; Xvfb behind the 0.3.3 wrapper exits $rc with: Cannot write display number to fd 3)"
else result repro-fd3 "FAIL (fd 3 $fd3 after mksh exec; Xvfb exit $rc)"; tail -10 "$W/repro-fd3.log"; fi
pkill -f "Xvfb :61" 2> /dev/null

echo "== P. Wine prefix stage of the display test (before Xvfb), app data layout files/prefix/wine"
files="$W/appdata/files"; prefix="$files/prefix/wine"
rm -rf "$W/appdata"; mkdir -p "$files"          # a fresh install: getFilesDir() exists, nothing below it
cli prefix-init "$rt" "$prefix" "$files/tmp" --no-parent > "$W/prefix-repro.txt"; rc=$?
grep -vE '^  \| (wine: created|0[0-9a-f]+:)' "$W/prefix-repro.txt" | head -20
if [ $rc -ne 0 ] && grep -q "chdir to $prefix : No such file or directory" "$W/prefix-repro.txt" \
   && grep -q 'wineboot --init.*exit 1 ' "$W/prefix-repro.txt"; then
  result repro-prefix "PASS (without the parent directory, as 0.3.4: wineboot exit 1, wine: chdir to .../prefix/wine : No such file or directory)"
else result repro-prefix "FAIL (device error not reproduced, exit $rc)"; fi
rm -rf "$W/appdata"; mkdir -p "$files"
t0=$(date +%s)
cli prefix-init "$rt" "$prefix" "$files/tmp" > "$W/prefix-fresh.txt"; rc=$?
grep -E '^(prefix|parent|step|result|\$)' "$W/prefix-fresh.txt"
if [ $rc -eq 0 ] && grep -q "^result CREATED" "$W/prefix-fresh.txt" && grep -q "created now (mkdirs)" "$W/prefix-fresh.txt" \
   && [ -f "$prefix/system.reg" ] && [ -d "$prefix/drive_c/windows" ]; then
  result prefix-fresh "PASS (fresh app data: files/prefix created, wineboot exit 0, wineserver -w exit 0, system.reg + drive_c/windows, $(( $(date +%s) - t0 )) s)"
else result prefix-fresh "FAIL (exit $rc)"; tail -40 "$W/prefix-fresh.txt"; fi
cli prefix-init "$rt" "$prefix" "$files/tmp" > "$W/prefix-again.txt"; rc=$?
cat "$W/prefix-again.txt"
if [ $rc -eq 0 ] && grep -q "^result KEPT" "$W/prefix-again.txt"; then
  result prefix-existing "PASS (initialised prefix kept, wineboot not run again)"
else result prefix-existing "FAIL (exit $rc)"; fi
pgrep -u "$(id -u)" -f "wine/bin/wine" > /dev/null && result prefix-stopped "FAIL (Wine processes left after the prefix stage)" \
  || result prefix-stopped "PASS (no Wine process left before Xvfb starts)"

echo "== 1. the app's XvfbStarter: argv without shell, -displayfd 1, Android-like environment"
QEMU_STRACE=1 QEMU_LOG_FILENAME="$st/xvfb-main.%d" cli start "$rt" "$W/fb-main" "$logs" 37 40 -- "${ANDROID_LIKE[@]}" | tee "$W/start-main.txt"
D=$(sed -n 's/^display //p' "$W/start-main.txt"); fb="$W/fb-main/$D"
if [ "$D" = 37 ] && grep -q '"-displayfd", "1"\]' "$W/start-main.txt" && ! grep -q "/bin/sh" "$W/start-main.txt" \
   && [ "$(ls -a "$XCWD" | grep -c -- "-lock")" -eq 0 ] && listening 37; then
  result xvfb "PASS (display 37 from the -displayfd 1 line on stdout after $(sed -n 's/^ready-ms //p' "$W/start-main.txt") ms; argv has no shell; no lock file; socket listening)"
else result xvfb "FAIL"; cat "$logs"/xvfb-37-*.log; fi

echo "== 2. occupied displays (:37 is held by step 1)"
occ() { QEMU_STRACE=1 QEMU_LOG_FILENAME="$st/xvfb-occ$1.%d" cli start "$rt" "$W/fb-occ$1" "$logs" 37 40 "${@:2}" -- "$W/deny-link" > "$W/occ$1.txt"; echo $?; }
r1=$(occ 1 --no-probe); r2=$(occ 2); r3=$(occ 3); r4=$(occ 4)
for i in 1 2 3 4; do grep -E "^(attempt|display|error|stdout)" "$W/occ$i.txt" | sed "s/^/  [$i] /"; done
d1=$(sed -n 's/^display //p' "$W/occ1.txt"); d2=$(sed -n 's/^display //p' "$W/occ2.txt"); d3=$(sed -n 's/^display //p' "$W/occ3.txt")
if [ "$r1$d1" = 038 ] && grep -q ":37 in use (Xvfb could not bind" "$W/occ1.txt" && [ "$r2$d2" = 039 ] && [ "$r3$d3" = 040 ] \
   && [ "$r4" = 2 ] && grep -q "error no free display between :37 and :40" "$W/occ4.txt" \
   && grep -q "Cannot establish any listening sockets" "$logs"/xvfb-37-*.log && grep -q "^stdout closed after the display number" "$W/occ1.txt"; then
  result occupied "PASS (Xvfb refused busy :37 -> :38; busy displays skipped -> :39, :40; all four busy -> 'no free display'; stdout closed after the number)"
else result occupied "FAIL (exit/display: $r1/$d1 $r2/$d2 $r3/$d3 $r4)"; fi
pkill -u "$(id -u)" -f "x11/Xvfb :(38|39|40) "; sleep 3
if ! alive 38 && ! alive 39 && ! alive 40 && ! listening 38 && ! listening 39 && ! listening 40; then
  result occupied-cleanup "PASS (:38-:40 stopped, sockets gone)"
else result occupied-cleanup "FAIL"; fi

mapfile -t XD < <(cli xdpyinfo-argv "$rt" $D)
timeout 300 "${XD[@]}" > "$W/xdpyinfo.log" 2>&1 && result xdpyinfo "PASS ($(grep -c '^    ' "$W/xdpyinfo.log") extension lines; MIT-SHM $(grep -q 'MIT-SHM' "$W/xdpyinfo.log" && echo present || echo absent))" \
  || { result xdpyinfo FAIL; cat "$W/xdpyinfo.log"; }

echo "== 3. Wine with the app's environment"
mapfile -t ENVL < <(cli wine-env "$rt" "$prefix" "$files/tmp" $D)
for kv in "${ENVL[@]}"; do export "$kv"; done
export HOME="$W/home"
echo "DISPLAY=$DISPLAY WINEPREFIX=$WINEPREFIX"
box64="$rt/bin/box64"; wineserver="$rt/wine/bin/wineserver"
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
grep "display-test:" "$W/display-test.log" | grep -v " input " | head -8

echo "== 4a. input: the app's X11 client (XTEST) -> Xvfb -> Wine -> the Windows test program"
cli input $D "$repo/scripts/ci/x11-abstract-bridge.py" | tee "$W/input.txt"; irc=${PIPESTATUS[0]}
sleep 20   # Wine under qemu delivers the messages with some delay
grep "display-test: input" "$W/display-test.log" | head -40 | tee "$W/input-received.txt"
want=("WM_LBUTTONDOWN 700,500" "WM_LBUTTONUP 700,500" "WM_LBUTTONDOWN 200,150" "WM_LBUTTONUP 320,260" "WM_RBUTTONDOWN 640,400" "WM_RBUTTONUP 640,400" "WM_MOUSEWHEEL")
miss=""; for w in "${want[@]}"; do grep -q "input $w" "$W/input-received.txt" || miss="$miss [$w]"; done
if [ $irc -eq 0 ] && [ -z "$miss" ]; then
  result input-mouse "PASS (app's X11Input + TouchMouseMapper: tap at 700,500 on a 1786x1228 view, drag 200,150->320,260, right click, wheel arrived as Windows messages at those coordinates)"
else result input-mouse "FAIL (client exit $irc; not received:$miss)"; fi
kmiss=""; for w in "WM_KEYDOWN 0x1b" "WM_KEYUP 0x1b" "WM_CHAR 0x61" "WM_CHAR 0x41" "WM_KEYDOWN 0x0d"; do grep -q "input $w" "$W/input-received.txt" || kmiss="$kmiss [$w]"; done
if [ $irc -eq 0 ] && [ -z "$kmiss" ]; then
  result input-keys "PASS (Escape, typed 'a'/'A' with Shift, Return arrived as WM_KEYDOWN/WM_CHAR)"
else result input-keys "FAIL (not received:$kmiss)"; fi

timeout 120 "$box64" "$wineserver" -k > /dev/null 2>&1; wait $wpid; echo "test program exit $?"

echo "== 4b. step 3.3 game launch path with a stand-in game (the runtime's DirectDraw program named like the game)"
sgame="$W/standin-game"; rm -rf "$sgame"; mkdir -p "$sgame/gm" "$sgame/maps"
cp "$rt/win/shc-gui-smoke.exe" "$sgame/Stronghold Crusader.exe"   # our own test program (runtime/pe-smoke), not game code
head -c 4096 /dev/urandom > "$sgame/gm/standin.gm1"; echo standin > "$sgame/maps/standin.map"
QEMU_STRACE=1 QEMU_LOG_FILENAME="$st/game.%d" cli game-launch "$rt" "$prefix" "$sgame" "Stronghold Crusader.exe" "$files/tmp" $D \
  "$fb/Xvfb_screen0" "$W/game-logs" | tee "$W/game-launch.txt"; rc=$?
grep -E '^\s+\| .*(loaddll.*(ddraw|Stronghold)|err:)' "$W/game-logs/game.log" 2>/dev/null | head -5
grep '^repro ' "$W/game-launch.txt" || true
if [ $rc -eq 0 ] && [ "$(grep -c '^check .*: PASS' "$W/game-launch.txt")" -ge 9 ]; then
  result game-standin "PASS (app's PrivateGameCopy: SHA-256 verified copy launched as D:, source unchanged; relay trace shows ExitProcess(42); WineLaunchPlan/GameSetup: D: link, cnc-ddraw in syswow64 loaded native, ini via CNC_DDRAW_CONFIG_FILE, pre-check and D:\\ launch drew the 4 DirectDraw colours, exit 42, game folder unchanged)"
else result game-standin "FAIL (exit $rc)"; fi
timeout 120 "$box64" "$wineserver" -k > /dev/null 2>&1
pkill -u "$(id -u)" -f "x11/Xvfb :$D "; sleep 3
alive $D && result stop "FAIL (Xvfb still running)" || result stop "PASS (Xvfb stopped on SIGTERM)"
[ -e "$fb/Xvfb_screen0" ] && echo "framebuffer file still present after stop" || echo "framebuffer file removed by Xvfb on exit"

echo "== 5. start/stop cycles with the app's code (stop = the app's SIGTERM, then SIGKILL after 3 s)"
QEMU_STRACE=1 QEMU_LOG_FILENAME="$st/xvfb-cycle.%d" cli cycle "$rt" "$W/fb-cycle" "$logs" 3 -- "$W/deny-link" | grep -v "^argv" | tee "$W/cycle.txt"
if [ "$(grep -c "^round . PASS" "$W/cycle.txt")" -eq 3 ] && ! alive 37 && ! listening 37; then
  result cycles "PASS (3 rounds on :37: ready, stdout closed after the number, process ended, socket gone)"
else result cycles "FAIL"; fi

echo "== 6. syscalls"
links=$(cat "$st"/xvfb-* 2>/dev/null | grep -cE " (link|linkat)\(")
[ "$links" -eq 0 ] && result no-link "PASS (0 link/linkat calls in $(ls "$st" | grep -c '^xvfb-') traces of Xvfb started by the app's code)" \
  || result no-link "FAIL ($links link calls)"
# the reproductions (step 0) exit on their errors by design and are not part of the app's path
python3 -I "$repo/runtime/android/check-strace.py" "$repo/runtime/android/arm64-app-syscalls.txt" $(ls "$st"/* | grep -v "/repro-") > "$W/seccomp-check.txt"; sc=$?
grep -v " 0 outside" "$W/seccomp-check.txt"
[ $sc -eq 0 ] && result seccomp "PASS ($(ls "$st" | grep -vc '^repro-') process traces within Android's arm64 app allowlist)" || result seccomp FAIL
rm -rf "$st"
echo "== Summary"; cat "$W/results.txt"
! grep -q FAIL "$W/results.txt"
