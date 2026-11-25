#!/usr/bin/env bash
# APK signing in CI with the deterministic SHC key (docs/SIGNING.md). Never prints the seed or key material.
#
#   signing.sh setup          needs SHC_SIGNING_SEED (GitHub Actions secret), RUNNER_TEMP, GITHUB_ENV. Installs the
#                             pinned Python packages (hash-checked) into a temporary venv, derives the key, checks it
#                             against the committed certificate and writes a temporary keystore for Gradle.
#                             If no certificate is committed yet (first run): prints the new PUBLIC certificate for
#                             committing and fails on purpose.
#   signing.sh verify <apk>   exactly one signer, and it is the pinned certificate
#   signing.sh cleanup        removes the temporary keystore and venv
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
dir="${RUNNER_TEMP:?RUNNER_TEMP not set}/shc-signing"
fail() { echo "::error::$*"; exit 1; }
pin() {
  local v
  v=$(grep -v '^[[:space:]]*#' "$repo/signing/certificate-sha256.txt" 2> /dev/null | tr -d ' :\r\n' | tr 'A-F' 'a-f')
  [[ "$v" =~ ^[0-9a-f]{64}$ ]] || fail "No signing certificate pinned in signing/certificate-sha256.txt (docs/SIGNING.md)."
  want="$v"
}

case "${1:-}" in
  setup)
    [ -n "${SHC_SIGNING_SEED:-}" ] || fail "The GitHub Actions secret SHC_SIGNING_SEED is not available (not created yet, or a pull request from a fork). No APK is signed with any other key. See docs/SIGNING.md."
    umask 077
    rm -rf "$dir"; mkdir -p "$dir"
    python3 -m venv "$dir/venv" > /dev/null
    "$dir/venv/bin/pip" install -q --disable-pip-version-check --require-hashes --only-binary=:all: \
      -r "$repo/scripts/ci/signing/requirements.txt" || fail "installing the pinned signing packages failed"
    if [ ! -f "$repo/signing/shc-signing-cert.pem" ]; then
      "$dir/venv/bin/python" "$repo/scripts/ci/signing/shc_signing.py" bootstrap "$dir/shc-signing-cert.pem"
      fail "First run: the signing certificate above was created from SHC_SIGNING_SEED. It is public; commit it as signing/shc-signing-cert.pem and pin its SHA-256 in signing/certificate-sha256.txt. No APK is built in this run."
    fi
    "$dir/venv/bin/python" "$repo/scripts/ci/signing/shc_signing.py" keystore "$dir/keystore"
    ;;
  verify)
    apk="${2:?usage: signing.sh verify <apk>}"
    pin
    bt=$(ls -d "${ANDROID_HOME:?ANDROID_HOME not set}"/build-tools/* | sort -V | tail -n1)
    out=$("$bt/apksigner" verify --print-certs "$apk") || fail "APK signature verification failed: $apk"
    n=$(grep -c '^Signer #[0-9]* certificate SHA-256 digest:' <<<"$out" || true)
    [ "$n" -eq 1 ] || fail "$apk has $n signers, expected exactly 1"
    got=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$out" | tr 'A-F' 'a-f')
    [ "$got" = "$want" ] || fail "$apk is signed with certificate $got, not the pinned SHC certificate $want."
    echo "$apk: signed with the pinned SHC certificate $got"
    ;;
  cleanup)
    rm -rf "$dir"
    echo "Temporary signing keystore removed."
    ;;
  *)
    echo "usage: signing.sh setup | verify <apk> | cleanup" >&2; exit 2 ;;
esac
