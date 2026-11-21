#!/usr/bin/env bash
# Verifies a built APK: existence, size, package metadata, signature, native ABIs and absence of game data.
# Usage: verify-apk.sh <apk> [expected-version-name]
# Env:   EXPECTED_ABIS  space-separated ABI list the APK must contain exactly ("" = no native code)
#        BUILD_TOOLS    Android build-tools version (default 35.0.0)
set -euo pipefail

APK="${1:?usage: verify-apk.sh <apk> [expected-version-name]}"
EXPECTED_VERSION="${2:-}"
EXPECTED_PACKAGE="io.github.saefalasalynbtel198.shcport"
EXPECTED_ABIS="${EXPECTED_ABIS:-}"
BT="${ANDROID_HOME:?ANDROID_HOME not set}/build-tools/${BUILD_TOOLS:-35.0.0}"

fail() { echo "::error::$*"; exit 1; }

[ -f "$APK" ] || fail "APK not found: $APK"
size=$(stat -c %s "$APK")
[ "$size" -gt 10240 ] || fail "APK is suspiciously small ($size bytes)"
unzip -tq "$APK" >/dev/null || fail "APK is not a valid zip archive"
listing=$(unzip -Z1 "$APK")   # captured first: grep -q on a pipe can break the writer under pipefail
grep -qx 'AndroidManifest.xml' <<<"$listing" || fail "APK has no AndroidManifest.xml"
grep -q '^classes.*\.dex$' <<<"$listing" || fail "APK has no dex code"

badging=$("$BT/aapt2" dump badging "$APK")
pkg=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")
vname=$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<<"$badging")
vcode=$(sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")
minsdk=$(sed -nE "s/^(minSdkVersion|sdkVersion):'([^']*)'.*/\2/p" <<<"$badging" | head -n1)
target=$(sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" <<<"$badging")
launch=$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<<"$badging")

[ "$pkg" = "$EXPECTED_PACKAGE" ] || fail "package is '$pkg', expected '$EXPECTED_PACKAGE'"
[ -n "$launch" ] || fail "no launchable activity"
if [ -n "$EXPECTED_VERSION" ]; then
  case "$vname" in "$EXPECTED_VERSION"*) ;; *) fail "versionName '$vname' does not start with '$EXPECTED_VERSION'";; esac
fi

"$BT/apksigner" verify --print-certs "$APK" > apk-signature.txt || fail "APK signature verification failed"

abis=$(sed -n 's#^lib/\([^/]*\)/.*#\1#p' <<<"$listing" | sort -u | tr '\n' ' ' | sed 's/ $//')
want=$(tr ' ' '\n' <<<"$EXPECTED_ABIS" | sed '/^$/d' | sort -u | tr '\n' ' ' | sed 's/ $//')
[ "$abis" = "$want" ] || fail "native ABIs are '${abis:-none}', expected '${want:-none}'"

# The only Windows program allowed is the project's own display test (built by CI from app/winsrc/).
if leaked=$(grep -Ei '\.(exe|gm1|tgx|bik|aiv|sav)$' <<<"$listing" | grep -v '^assets/shc-display-test\.exe$'); then
  fail "APK contains game data files: $leaked"
fi

summary=$(cat <<SUM
| Field | Value |
|---|---|
| File | \`$(basename "$APK")\` |
| Size | $size bytes ($((size / 1024)) KiB) |
| Package | \`$pkg\` |
| Version | $vname ($vcode) |
| minSdk / targetSdk | $minsdk / $target |
| Launch activity | \`$launch\` |
| Native ABIs | ${abis:-none} |
| SHA-256 | \`$(sha256sum "$APK" | cut -d' ' -f1)\` |
| Signer | $(grep -m1 'certificate DN' apk-signature.txt | sed 's/.*DN: //') |
| Signer certificate SHA-256 | \`$(grep -m1 'certificate SHA-256 digest' apk-signature.txt | sed 's/.*digest: //')\` |
SUM
)
echo "$summary"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && { echo "### APK verification passed"; echo; echo "$summary"; } >> "$GITHUB_STEP_SUMMARY"
echo "APK verification passed."
