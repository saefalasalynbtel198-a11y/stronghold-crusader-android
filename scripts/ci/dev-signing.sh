#!/usr/bin/env bash
# Permanent development signing key for CI builds (docs/DEV_SIGNING.md). Never prints secret values.
#
#   dev-signing.sh setup          needs SHC_DEV_KEYSTORE_BASE64, SHC_DEV_KEYSTORE_PASSWORD, SHC_DEV_KEY_ALIAS,
#                                 SHC_DEV_KEY_PASSWORD (GitHub Actions secrets) and RUNNER_TEMP; decodes the keystore
#                                 into $RUNNER_TEMP/shc-dev-signing (mode 0600), checks that its certificate is the
#                                 pinned one, and exports SHC_DEV_KEYSTORE_FILE for the Gradle step
#   dev-signing.sh verify <apk>   the APK has exactly one signer and it is the pinned certificate
#   dev-signing.sh cleanup        removes the decoded keystore
#
# Fails with a clear message when a secret is missing, the fingerprint is not pinned, the keystore cannot be read
# or the certificate differs. There is no fallback to any other key.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
pin_file="$repo/signing/dev-certificate-sha256.txt"
dir="${RUNNER_TEMP:?RUNNER_TEMP not set}/shc-dev-signing"
fail() { echo "::error::$*"; exit 1; }

# sets $want (not in a subshell, so that fail's message reaches the log)
load_expected() {
  [ -f "$pin_file" ] || fail "Pinned development certificate fingerprint missing: signing/dev-certificate-sha256.txt"
  local raw
  raw=$(grep -v '^[[:space:]]*#' "$pin_file" | tr -d ' :\r\n')
  want=$(tr 'A-F' 'a-f' <<<"$raw")
  [[ "$want" =~ ^[0-9a-f]{64}$ ]] || fail "No development certificate fingerprint pinned in signing/dev-certificate-sha256.txt (found '${raw:0:20}'). The owner must generate the key and pin its SHA-256 (docs/DEV_SIGNING.md)."
}

case "${1:-}" in
  setup)
    missing=()
    for v in SHC_DEV_KEYSTORE_BASE64 SHC_DEV_KEYSTORE_PASSWORD SHC_DEV_KEY_ALIAS SHC_DEV_KEY_PASSWORD; do
      [ -n "${!v:-}" ] || missing+=("$v")
    done
    [ ${#missing[@]} -eq 0 ] || fail "Development signing secrets not available: ${missing[*]}. No APK is built with any other key. Add them under Settings > Secrets and variables > Actions (docs/DEV_SIGNING.md). Pull requests from forks never receive secrets."
    load_expected
    command -v keytool > /dev/null || fail "keytool not found (JDK required)"
    umask 077
    rm -rf "$dir"; mkdir -p "$dir"
    printf '%s' "$SHC_DEV_KEYSTORE_BASE64" | base64 -d > "$dir/dev.keystore" 2> /dev/null \
      || { rm -rf "$dir"; fail "SHC_DEV_KEYSTORE_BASE64 is not valid base64"; }
    # the key's certificate (DER) without printing anything secret; passwords are read from the environment
    if ! keytool -exportcert -keystore "$dir/dev.keystore" -alias "$SHC_DEV_KEY_ALIAS" \
         -storepass:env SHC_DEV_KEYSTORE_PASSWORD -file "$dir/dev-cert.der" > /dev/null 2>&1; then
      rm -rf "$dir"; fail "The development keystore cannot be read with SHC_DEV_KEYSTORE_PASSWORD and SHC_DEV_KEY_ALIAS (wrong password, alias or file)."
    fi
    got=$(sha256sum "$dir/dev-cert.der" | cut -d' ' -f1)
    rm -f "$dir/dev-cert.der"
    if [ "$got" != "$want" ]; then
      rm -rf "$dir"
      fail "The development keystore's certificate is $got, but $want is pinned. Refusing to sign with a different key."
    fi
    echo "SHC_DEV_KEYSTORE_FILE=$dir/dev.keystore" >> "${GITHUB_ENV:?GITHUB_ENV not set}"
    echo "Development key ready: certificate SHA-256 $got (matches the pinned fingerprint)."
    ;;
  verify)
    apk="${2:?usage: dev-signing.sh verify <apk>}"
    load_expected
    bt="${ANDROID_HOME:?ANDROID_HOME not set}/build-tools/${BUILD_TOOLS:-35.0.0}"
    out=$("$bt/apksigner" verify --print-certs "$apk") || fail "APK signature verification failed"
    n=$(grep -c '^Signer #[0-9]* certificate SHA-256 digest:' <<<"$out" || true)
    [ "$n" -eq 1 ] || fail "APK has $n signers, expected exactly 1"
    got=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$out" | tr 'A-F' 'a-f')
    [ "$got" = "$want" ] || fail "APK is signed with certificate $got, not the pinned development certificate $want."
    echo "APK signed with the pinned development certificate $got."
    [ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "Development signing: certificate SHA-256 \`$got\` (pinned) — OK" >> "$GITHUB_STEP_SUMMARY"
    ;;
  cleanup)
    rm -rf "$dir"
    echo "Development keystore removed from the runner."
    ;;
  *)
    echo "usage: dev-signing.sh setup | verify <apk> | cleanup" >&2; exit 2 ;;
esac
