#!/usr/bin/env bash
# Display test of the runtime package (r6+): BUILD 3 phase 3. Everything comes from the package itself: Xvfb,
# xkbcomp, xkb data, xdpyinfo, Box64, Wine, cnc-ddraw, shc-gui-smoke.exe, the Android-patched glibc.
# No preload library of any kind: the test refuses to run with LD_PRELOAD set and checks the logs for it.
#
#   1. Xvfb :0 1024x768x24 from <runtime>/x11 (cwd), abstract socket only, no MIT-SHM, no GLX, built-in fonts,
#      framebuffer in a file (-fbdir); in a private mount namespace where /tmp, /var, /usr/lib/xorg and
#      /usr/share/X11 are empty and read-only (Android has none of them)
#   2. native X client (xdpyinfo); wineboot --init (no display); then shc-gui-smoke.exe gdi -> 4 colour blocks
#   3. shc-gui-smoke.exe ddraw with the package's cnc-ddraw ddraw.dll (renderer=gdi) -> 4 colour blocks
#   4. every syscall of every process checked against Android's arm64 app allowlist; Wine's X11 driver must not
#      have reached the kernel's shmget (glibc returns ENOSYS; Wine falls back to ordinary images)
#   5. files Xvfb and xkbcomp used outside the runtime and test directories
# Linux emulation (qemu-aarch64 user mode), NOT Android: no seccomp enforcement, no SELinux, different kernel.
#
# Usage: test-display-qemu.sh <shc-runtime-*.zip> <work dir>
set -uo pipefail
ZIP="$(realpath "${1:?runtime zip}")"
W="$(mkdir -p "${2:?work dir}" && realpath "$2")"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
tools="$repo/runtime/display"
core="$repo/app/src/main/java/io/github/saefalasalynbtel198/shcport/core"
rt="$W/runtime"; st="$W/strace"
rm -rf "$rt" "$st" "$W/fb" "$W/prefix" "$W/gdi" "$W/dd" "$W/classes"
mkdir -p "$st" "$W/fb" "$W/gdi" "$W/dd" "$W/home" "$W/tmp" "$W/classes"
result() { echo "RESULT $1: $2" | tee -a "$W/results.txt"; }
: > "$W/results.txt"

echo "== no preload library anywhere"
if [ -n "${LD_PRELOAD:-}" ]; then echo "::error::LD_PRELOAD is set ($LD_PRELOAD)"; exit 1; fi
# (wine's own wine-preloader is not a preload library and is allowed)
if unzip -l "$ZIP" | grep -iE "shim|libshc-android"; then echo "::error::package contains the test stand-in library"; exit 1; fi
echo "LD_PRELOAD unset; package has no preload library"

echo "== runtime package, extracted and PT_INTERP set with the app's own code"
javac -d "$W/classes" -sourcepath "$repo/app/src/main/java" "$core/RuntimeArchive.java" "$core/ElfInterp.java" \
  "$core/RuntimeLayout.java" "$repo/scripts/ci/ExtractRuntime.java" || exit 1
java -cp "$W/classes" ExtractRuntime "$ZIP" "$rt" patch-interp || exit 1
cat "$rt/RUNTIME_VERSION"
loader="$rt/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
LP="$rt/lib/aarch64-linux-gnu"
x11="$rt/x11"
xkbdir="$x11/share/X11/xkb"   # writable: xkbcomp writes the compiled keymap there
EXE="$rt/win/shc-gui-smoke.exe"
sha256sum "$EXE" "$x11/Xvfb" "$rt/cnc-ddraw/ddraw.dll" "$LP/libc.so.6"

echo "== 1. Xvfb"
export SPIKE_X11="$x11" SPIKE_LOADER="$loader" SPIKE_LP="$LP" SPIKE_FB="$W/fb" SPIKE_XKB="$xkbdir" SPIKE_ST="$st"
sudo --preserve-env unshare --mount -- bash -c '
  for d in /tmp /var /usr/lib/xorg /usr/share/X11; do
    [ -d "$d" ] && mount -t tmpfs -o ro,size=4k,mode=0555 none "$d"
  done
  cd "$SPIKE_X11"
  exec setpriv --reuid="$SUDO_UID" --regid="$SUDO_GID" --init-groups \
    env QEMU_STRACE=1 QEMU_LOG_FILENAME="$SPIKE_ST/xvfb.%d" \
    "$SPIKE_LOADER" --library-path "$SPIKE_LP" ./Xvfb :0 \
      -screen 0 1024x768x24 -fbdir "$SPIKE_FB" -nolisten tcp -nolisten unix -extension MIT-SHM -extension GLX \
      -ac -fp built-ins -xkbdir "$SPIKE_XKB"' > "$W/xvfb.log" 2>&1 &
xvfb=$!
xvfb_alive() { pgrep -u "$(id -u)" -f "Xvfb :0" > /dev/null; }
up=0
for i in $(seq 1 300); do
  grep -q "@/tmp/.X11-unix/X0" /proc/net/unix && { up=1; break; }
  kill -0 $xvfb 2>/dev/null || break
  sleep 1
done
ls -la "$x11"
grep "X11-unix" /proc/net/unix | sed 's/^/socket: /'
ls -la "$W/fb"
if [ $up -eq 1 ]; then result xvfb "PASS (abstract socket @/tmp/.X11-unix/X0 after ${i}s, no /tmp/.X11-unix directory used)"
else result xvfb "FAIL (no socket; Xvfb alive: $(xvfb_alive && echo yes || echo no))"; fi
echo "-- Xvfb log --"; grep -v "^> \|Could not resolve keysym" "$W/xvfb.log" | head -40
echo "-- native ARM64 X client (xdpyinfo, same loader) --"
DISPLAY=:0 timeout 300 "$loader" --library-path "$LP" "$x11/bin/xdpyinfo" > "$W/xdpyinfo.log" 2>&1
xd=$?
grep -E "^name of display|^vendor string|^version number|dimensions|depths|number of extensions|MIT-SHM|XTEST|RANDR" "$W/xdpyinfo.log" | head -12
grep -A40 "^number of extensions" "$W/xdpyinfo.log" | grep -E "^ +[A-Z]" | tr -s ' ' | tr '\n' ' '; echo
if [ $xd -eq 0 ]; then result xclient "PASS (xdpyinfo connected over the abstract socket)"; else result xclient "FAIL (xdpyinfo exit $xd)"; cat "$W/xdpyinfo.log" | head; fi

# Same environment as the app's Wine tests (core/RuntimeProbe.baseEnv + WineTestPlan.wineEnv)
export GLIBC_TUNABLES=glibc.pthread.rseq=0 LD_LIBRARY_PATH="$rt/lib/aarch64-linux-gnu" \
       BOX64_LD_LIBRARY_PATH="$rt/lib/x86_64-linux-gnu" BOX64_PATH="$rt/wine/bin" BOX64_LOG=1 BOX64_NOBANNER=0 \
       BOX64_CPUNAME="ARM64 (Android)" HOME="$W/home" TMPDIR="$W/tmp" LC_ALL=C WINEPREFIX="$W/prefix" \
       WINEDLLOVERRIDES="mscoree,mshtml=d;winemenubuilder.exe=d" WINEDEBUG=err+all,fixme-all
box64="$rt/bin/box64"; wine="$rt/wine/bin/wine"; wineserver="$rt/wine/bin/wineserver"

echo "== 2a. wineboot --init (no display, as proven on the device)"
DISPLAY= timeout 1800 "$box64" "$wine" wineboot --init > "$W/wineboot.log" 2>&1; echo "wineboot exit $?"
timeout 300 "$box64" "$wineserver" -w; w=$?; echo "wineserver -w exit $w"
if [ $w -ne 0 ]; then
  # Processes started without a display (explorer.exe holds the desktop) would keep later programs on the
  # null display driver; stop them so the GUI test starts a fresh desktop with DISPLAY set.
  echo "Wine processes still running after wineboot:"; pgrep -a -f "wine/bin|\.exe" | grep -v pgrep | cut -c1-160
  timeout 120 "$box64" "$wineserver" -k; echo "wineserver -k exit $?"; sleep 5
fi
[ -f "$W/prefix/system.reg" ] || { result prefix "FAIL"; tail -30 "$W/wineboot.log"; }

# runs shc-gui-smoke in $1 (dir) with mode $2, waits for the colours, then for exit 42
gui_test() {
  local dir="$1" mode="$2" extra="$3" log="$W/$2.log" ok=0 code
  cp "$EXE" "$dir/shc-gui-smoke.exe"
  (cd "$dir" && DISPLAY=:0 WINEDLLOVERRIDES="$WINEDLLOVERRIDES$extra" WINEDEBUG="err+all,warn+x11drv,warn+winediag,fixme-all" \
     QEMU_STRACE=1 QEMU_LOG_FILENAME="$st/$mode.%d" \
     exec timeout 2400 "$box64" "$wine" shc-gui-smoke.exe "$mode" 60 > "$log" 2>&1) &
  local pid=$!
  for i in $(seq 1 240); do
    if python3 -I "$tools/check-fb.py" "$W/fb/Xvfb_screen0" "$mode" > "$W/$mode-pixels.txt" 2>&1; then ok=1; break; fi
    kill -0 $pid 2>/dev/null || break
    sleep 10
  done
  python3 -I "$tools/check-fb.py" "$W/fb/Xvfb_screen0" "$([ $ok -eq 1 ] && echo "$mode" || echo none)" "$W/$mode.png" \
    > "$W/$mode-pixels-final.txt" 2>&1
  [ $ok -eq 1 ] && cat "$W/$mode-pixels.txt" || cat "$W/$mode-pixels-final.txt"
  wait $pid; code=$?
  echo "-- program output ($mode) --"; grep "gui-smoke:" "$log"
  echo "-- Wine display/driver messages and Box64 library problems ($mode) --"
  grep -iE "x11|libx|xcb|display|winediag|nodrv|err:|Error|cannot|not found|warning" "$log" \
    | grep -v "BOX64_\|Using native(wrapped)\|Using emulated" | sort | uniq -c | sort -rn | head -40
  grep -E "Using (native\(wrapped\)|emulated) .*(X11|Xext|Xrender|Xrandr|Xi|Xcursor|Xfixes|Xcomposite|Xinerama|xcb)" "$log" | sort -u | head -20
  if [ $ok -eq 1 ] && [ $code -eq 42 ]; then result "$mode" "PASS (all 4 colour blocks found in the X framebuffer; exit 42)"
  else result "$mode" "FAIL (pixels $([ $ok -eq 1 ] && echo found || echo 'not found'), exit $code)"; fi
}

echo "== 2b. GDI window"
gui_test "$W/gdi" gdi ""

echo "== 3. DirectDraw through cnc-ddraw $CNC_DDRAW_TAG (renderer=gdi)"
cp "$rt/cnc-ddraw/ddraw.dll" "$W/dd/ddraw.dll"
cat > "$W/dd/ddraw.ini" <<'EOF'
[ddraw]
renderer=gdi
fullscreen=true
windowed=false
EOF
gui_test "$W/dd" ddraw ";ddraw=n,b"
grep -h "ddraw.dll = " "$W/ddraw.log" | sed 's/^/loaded: /'
ls -la "$W/dd"

timeout 300 "$box64" "$wineserver" -k >/dev/null 2>&1
pkill -u "$(id -u)" -f "Xvfb :0"; wait $xvfb 2>/dev/null

echo "== 4. Android seccomp check (every syscall of every process)"
python3 -I "$repo/runtime/android/check-strace.py" "$repo/runtime/android/arm64-app-syscalls.txt" "$st"/* \
  > "$W/seccomp-check.txt"; sc=$?
grep -v " 0 outside" "$W/seccomp-check.txt"
n=$(ls "$st" | wc -l)
shm=$(grep -h " shm\(get\|at\|dt\|ctl\)(" "$st"/* | wc -l)
pre=$(grep -l "cannot pre-load" "$W"/*.log 2>/dev/null | wc -l)
echo "kernel shm* calls in all traces: $shm; logs mentioning a preload: $pre"
if [ "$shm" -eq 0 ] && [ "$pre" -eq 0 ] && grep -q "RESULT gdi: PASS" "$W/results.txt"; then
  result shm-fallback "PASS (no shmget reached the kernel; Wine drew the window with ordinary images; no preload)"
else result shm-fallback "FAIL (kernel shm calls $shm, preload mentions $pre)"; fi
if [ $sc -eq 0 ]; then result seccomp "PASS ($n process traces, no syscall outside Android's arm64 app allowlist)"
else result seccomp "FAIL (syscalls outside the allowlist, listed above)"; fi

echo "== 5. Files Xvfb and xkbcomp used outside the test directories"
python3 -I "$tools/check-paths.py" "$W" "$st"/xvfb.* > "$W/paths-check.txt"; pc=$?
cat "$W/paths-check.txt"
if [ $pc -eq 0 ]; then result paths "PASS (nothing under /usr, /var, /tmp opened successfully by Xvfb/xkbcomp)"
else result paths "FAIL (Xvfb/xkbcomp depend on paths that do not exist on Android, listed above)"; fi

rm -rf "$st"   # hundreds of MB; the checks above keep the findings
echo "== Summary"; cat "$W/results.txt"
! grep -q FAIL "$W/results.txt"
