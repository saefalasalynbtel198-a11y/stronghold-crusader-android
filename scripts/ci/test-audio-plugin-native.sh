#!/bin/bash
# The SHC ALSA plugin (same source as the APK's arm64 build, built here for the runner) with the app's receiver
# (DisplayCli audio-bridge = core/AudioStreamPump + a paced 100 ms output), driven like Wine (alsa-client-test):
#   1. on time: continuous tone, no gap
#   2. late writes (80 ms late every ~0.5 s) with Wine's lead-in rule: no silence inserted (0.5.0 phone stutter)
#   3. float and 16-bit streams at the same time
#   4. no app listening: the writer still runs in real time (clock)
# Usage: test-audio-plugin-native.sh <work dir>
set -uo pipefail
W="$(mkdir -p "${1:?work dir}" && realpath "$1")"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
core="$repo/app/src/main/java/io/github/saefalasalynbtel198/shcport/core"
fail=0
result() { echo "RESULT $1: $2"; case "$2" in FAIL*) fail=1;; esac; }
gcc -shared -fPIC -DPIC -O2 -Wall -Werror -o "$W/libasound_module_pcm_shc.so" "$repo/app/nativesrc/alsa-shc/pcm_shc.c" -lasound || exit 1
gcc -O2 -Wall -Werror -o "$W/alsa-client-test" "$repo/scripts/ci/alsa-client-test.c" -lasound -lm || exit 1
mkdir -p "$W/classes"
javac -d "$W/classes" -sourcepath "$repo/app/src/main/java" "$core"/*.java "$repo/scripts/ci/DisplayCli.java" || exit 1
printf 'pcm_type.shc {\n  lib "%s"\n}\npcm.!default {\n  type shc\n  socket "%s"\n}\n' "$W/libasound_module_pcm_shc.so" "$W/a.sock" > "$W/asound.conf"

run() {  # run <name> <client args...>  (bridge + one or more clients separated by "+")
  local name="$1"; shift
  rm -rf "$W/$name"; mkdir -p "$W/$name"
  java -cp "$W/classes" DisplayCli audio-bridge "$W/a.sock" "$W/$name" 60 > "$W/$name.bridge.txt" 2>&1 &
  local bpid=$!
  sleep 2
  local args=() pids=()
  for a in "$@" +; do
    if [ "$a" = "+" ]; then
      SHC_AUDIO_DEBUG=1 ALSA_CONFIG_PATH="$W/asound.conf" timeout 60 "$W/alsa-client-test" "${args[@]}" >> "$W/$name.client.txt" 2>&1 &
      pids+=($!); args=()
    else args+=("$a"); fi
  done
  wait "${pids[@]}"
  sleep 2; touch "$W/$name/stop"; wait $bpid
  cat "$W/$name.client.txt"
}

run ontime 5 440 float
python3 -I "$repo/scripts/ci/check-audio.py" "$W/ontime" 4.5 440 | tee "$W/ontime.check"
[ ${PIPESTATUS[0]} -eq 0 ] && result plugin-ontime "PASS (continuous 440 Hz, no dropout)" || result plugin-ontime "FAIL"

run late 5 440 float late
leadins=$(grep -o "lead-ins [0-9]*" "$W/late.client.txt" | awk '{print $2}')
python3 -I "$repo/scripts/ci/check-audio.py" "$W/late" 4.5 440 | tee "$W/late.check"
if [ ${PIPESTATUS[0]} -eq 0 ] && [ "${leadins:-x}" = "0" ]; then
  result plugin-late-writer "PASS (writer 80 ms late every 0.5 s: 0 Wine-style silent lead-ins, no dropout: $(grep -o 'held back [0-9]* times' "$W/late.client.txt"))"
else result plugin-late-writer "FAIL (lead-ins ${leadins:-?})"; fi

run two 4 440 float + 4 880 s16
python3 -I "$repo/scripts/ci/check-audio.py" "$W/two" 3.5 440 880 | tee "$W/two.check"
[ ${PIPESTATUS[0]} -eq 0 ] && result plugin-two-streams "PASS (float 440 Hz and 16-bit 880 Hz streams at once, both intact)" || result plugin-two-streams "FAIL"

sed 's|socket ".*"|socket "/nonexistent/none.sock"|' "$W/asound.conf" > "$W/asound-noapp.conf"
out=$(ALSA_CONFIG_PATH="$W/asound-noapp.conf" timeout 30 "$W/alsa-client-test" 2 440 float 2>&1 | tail -1); echo "$out"
el=$(echo "$out" | sed -n 's/.* in \([0-9.]*\) s.*/\1/p')
if [ -n "$el" ] && awk -v e="$el" 'BEGIN{exit !(e >= 1.7 && e <= 2.6)}'; then result plugin-no-app "PASS (no app: 2 s of audio took $el s on the clock, no stall)"
else result plugin-no-app "FAIL ($out)"; fi
exit $fail
