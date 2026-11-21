#!/usr/bin/env bash
# BUILD 3 phase 3, step 3.0: display feasibility test (CI only; changes neither the app nor the runtime).
#
# Uses the unchanged runtime r5 package plus, in a separate directory, Debian trixie arm64 Xvfb + xkbcomp +
# xkb-data. Everything aarch64 runs under qemu-aarch64 with r5's Android-patched glibc loader, exactly as the
# app starts programs (loader --library-path …; Box64 executed directly with the PT_INTERP set by the app's code).
#
#   1. Xvfb :0 1024x768x24, abstract socket only (no /tmp), no MIT-SHM (shmget is blocked on Android), no GLX,
#      built-in fonts, framebuffer in a file (-fbdir); its XKB and lock-file paths patched to its own directory;
#      run in a private mount namespace where /tmp, /var, /usr/lib/xorg and /usr/share/X11 are empty and
#      read-only (Android has none of them), with libshc-android-shim.so preloaded (see its header: setuid/
#      setgid to the caller's own ids become no-ops; shmget fails with ENOSYS)
#   2. wineboot --init (no display), then shc-gui-smoke.exe gdi   -> check 4 colour blocks in the framebuffer
#      (Wine GUI processes also get the preload: Wine's X11 driver calls shmget for every window surface)
#   3. shc-gui-smoke.exe ddraw with r5's cnc-ddraw ddraw.dll + ddraw.ini (renderer=gdi) -> check 4 colour blocks
#   4. every syscall of every process (Xvfb, xkbcomp, Box64/Wine) checked against Android's arm64 app allowlist
#   5. every file path Xvfb/xkbcomp opened or executed outside the test directories listed (Android has no
#      /usr, /var, /tmp)
# Linux emulation, NOT Android: no seccomp enforcement, no SELinux, different kernel. The device test follows.
#
# Usage: run-feasibility.sh <shc-runtime-r5-*.zip> <shc-gui-smoke.exe> <libshc-android-shim.so> <work dir>
set -uo pipefail
ZIP="$(realpath "${1:?runtime zip}")"
EXE="$(realpath "${2:?shc-gui-smoke.exe}")"
SHIM="$(realpath "${3:?libshc-android-shim.so}")"
W="$(mkdir -p "${4:?work dir}" && realpath "$4")"
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
spike="$repo/runtime/spike/display"
core="$repo/app/src/main/java/io/github/saefalasalynbtel198/shcport/core"
# shellcheck source=/dev/null
. "$repo/runtime/versions.env"
rt="$W/runtime"; xroot="$W/xroot"; xlib="$W/xlib"; x11="$W/x11"; st="$W/strace"
rm -rf "$rt" "$x11" "$st" "$W/fb" "$W/prefix" "$W/gdi" "$W/dd"
mkdir -p "$x11" "$st" "$W/fb" "$W/gdi" "$W/dd" "$W/home" "$W/tmp" "$W/classes"
result() { echo "RESULT $1: $2" | tee -a "$W/results.txt"; }
: > "$W/results.txt"

echo "== runtime r5 (unchanged package), extracted and PT_INTERP set with the app's own code"
javac -d "$W/classes" -sourcepath "$repo/app/src/main/java" "$core/RuntimeArchive.java" "$core/ElfInterp.java" \
  "$core/RuntimeLayout.java" "$repo/scripts/ci/ExtractRuntime.java" || exit 1
java -cp "$W/classes" ExtractRuntime "$ZIP" "$rt" patch-interp || exit 1
loader="$rt/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"

echo "== Debian $DEBIAN_SUITE arm64: xvfb, x11-xkb-utils (xkbcomp), xkb-data (extract only)"
if [ ! -x "$xroot/usr/bin/Xvfb" ]; then
  sudo rm -rf "$xroot"
  sudo mmdebstrap --mode=root --variant=extract --architectures=arm64 \
    --keyring=/usr/share/keyrings/debian-archive-keyring.gpg --include=xvfb,x11-xkb-utils,xkb-data,x11-utils \
    "$DEBIAN_SUITE" "$xroot" "$DEBIAN_MIRROR" || exit 1
  sudo chown -R "$(id -u):$(id -g)" "$xroot"
fi
dpkg-query --admindir="$xroot/var/lib/dpkg" -W -f='${Package} ${Version}\n' xvfb x11-xkb-utils xkb-data xserver-common 2>/dev/null \
  | tee "$W/xpackages.txt"
rm -rf "$xlib"; mkdir -p "$xlib"
for d in "$xroot/lib/aarch64-linux-gnu" "$xroot/usr/lib/aarch64-linux-gnu"; do [ -d "$d" ] && cp -a "$d/." "$xlib/"; done
LP="$rt/lib/aarch64-linux-gnu:$xlib"     # r5 first: its Android-patched glibc wins

echo "== Xvfb copy with XKB paths pointed at its working directory"
python3 -I "$spike/patch-xvfb-paths.py" "$xroot/usr/bin/Xvfb" "$x11/Xvfb" || exit 1
chmod 0755 "$x11/Xvfb"
cat > "$x11/xkbcomp" <<EOF
#!/bin/sh
# Xvfb runs this through /bin/sh to compile the keymap; starts Debian's xkbcomp with the runtime's loader.
exec "$loader" --library-path "$LP" "$xroot/usr/bin/xkbcomp" "\$@"
EOF
chmod 0755 "$x11/xkbcomp"
xkbdir="$xroot/usr/share/X11/xkb"   # must be writable: xkbcomp changes into it and writes the compiled keymap there

echo "== 1. Xvfb"
cp "$SHIM" "$x11/libshc-android-shim.so"
export SPIKE_X11="$x11" SPIKE_LOADER="$loader" SPIKE_LP="$LP" SPIKE_FB="$W/fb" SPIKE_XKB="$xkbdir" SPIKE_ST="$st"
sudo --preserve-env unshare --mount -- bash -c '
  for d in /tmp /var /usr/lib/xorg /usr/share/X11; do
    [ -d "$d" ] && mount -t tmpfs -o ro,size=4k,mode=0555 none "$d"
  done
  cd "$SPIKE_X11"
  exec setpriv --reuid="$SUDO_UID" --regid="$SUDO_GID" --init-groups \
    env QEMU_STRACE=1 QEMU_LOG_FILENAME="$SPIKE_ST/xvfb.%d" \
    "$SPIKE_LOADER" --preload ./libshc-android-shim.so --library-path "$SPIKE_LP" ./Xvfb :0 \
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
DISPLAY=:0 timeout 300 "$loader" --library-path "$LP" "$xroot/usr/bin/xdpyinfo" > "$W/xdpyinfo.log" 2>&1
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
  (cd "$dir" && DISPLAY=:0 LD_PRELOAD="$x11/libshc-android-shim.so" WINEDLLOVERRIDES="$WINEDLLOVERRIDES$extra" WINEDEBUG="err+all,warn+x11drv,warn+winediag,fixme-all" \
     QEMU_STRACE=1 QEMU_LOG_FILENAME="$st/$mode.%d" \
     exec timeout 2400 "$box64" "$wine" shc-gui-smoke.exe "$mode" 60 > "$log" 2>&1) &
  local pid=$!
  for i in $(seq 1 240); do
    if python3 -I "$spike/check-fb.py" "$W/fb/Xvfb_screen0" "$mode" > "$W/$mode-pixels.txt" 2>&1; then ok=1; break; fi
    kill -0 $pid 2>/dev/null || break
    sleep 10
  done
  python3 -I "$spike/check-fb.py" "$W/fb/Xvfb_screen0" "$([ $ok -eq 1 ] && echo "$mode" || echo none)" "$W/$mode.png" \
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
if [ $sc -eq 0 ]; then result seccomp "PASS ($n process traces, no syscall outside Android's arm64 app allowlist)"
else result seccomp "FAIL (syscalls outside the allowlist, listed above)"; fi

echo "== 5. Files Xvfb and xkbcomp used outside the test directories"
python3 -I "$spike/check-paths.py" "$W" "$st"/xvfb.* > "$W/paths-check.txt"; pc=$?
cat "$W/paths-check.txt"
if [ $pc -eq 0 ]; then result paths "PASS (nothing under /usr, /var, /tmp opened successfully by Xvfb/xkbcomp)"
else result paths "FAIL (Xvfb/xkbcomp depend on paths that do not exist on Android, listed above)"; fi

rm -rf "$st"   # hundreds of MB; the checks above keep the findings
echo "== Summary"; cat "$W/results.txt"
! grep -q FAIL "$W/results.txt"
