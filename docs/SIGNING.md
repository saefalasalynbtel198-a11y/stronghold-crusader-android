# APK signing key

> **Current state (owner's decision, 2026-11-21): SHC Port 0.3.6 is signed with the standard automatic Android debug
> key of the CI build machine.** No secret is used. That key differs between builds, so a later APK may again need the
> previous app to be uninstalled first. The seed-derived permanent key below is implemented
> (scripts/ci/signing.sh, scripts/ci/signing/) but is not used by CI; it can be switched on later if wanted.


Every APK of the app (`io.github.saefalasalynbtel198.shcport`, label "SHC Port") is signed with one permanent key.
Each new APK therefore installs over the previous one as an update and keeps the app's data (runtime, Wine prefix,
imported game, settings). The key is never stored anywhere: CI derives it on every build from one GitHub Actions
secret, `SHC_SIGNING_SEED`.

Before 0.3.6-dev.3, CI signed with the auto-generated debug key of each build machine, a new key on every run.
The installed 0.3.5-dev.1 (certificate `fcd0e71c…`) can therefore not be updated. It must be uninstalled once, then
the new app installed. From then on, updates keep working.

## Owner: the one manual step

1. In your password manager, generate a random password of **at least 32 characters** (40 or more is better) and save
   it as "SHC Port signing seed". **Keep it there permanently**: GitHub never shows a secret again, and without it no
   future APK can update the app.
2. In a browser (on a phone: desktop site), open
   `https://github.com/saefalasalynbtel198-a11y/stronghold-crusader-android/settings/secrets/actions` →
   **New repository secret** → Name `SHC_SIGNING_SEED` → paste the password exactly (no spaces or line breaks) →
   **Add secret**.
3. Never send the password to anyone, including in chat.

## Exact derivation

```
okm  = PBKDF2-HMAC-SHA256(password   = SHC_SIGNING_SEED as UTF-8 bytes,
                          salt       = "io.github.saefalasalynbtel198.shcport/android-signing/v1" (UTF-8),
                          iterations = 600000,
                          length     = 40 bytes)
d    = (big-endian integer of okm  mod  (n - 1)) + 1
       n = FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551 (order of P-256)
key  = ECDSA P-256 (secp256r1) private key with scalar d
```

Certificate: self-signed X.509 v3.
* Subject and issuer: `CN=SHC Port, O=saefalasalynbtel198`.
* Serial number: 1. Valid from 2026-01-01 00:00:00 UTC to 2055-12-31 00:00:00 UTC.
* Signature: SHA256withECDSA with deterministic RFC 6979 nonces.

It was created once ("bootstrap") and is committed as `signing/shc-signing-cert.pem`; its SHA-256 is pinned in
`signing/certificate-sha256.txt`. Android compares this certificate when it installs an update.

Reference implementation: `scripts/ci/signing/shc_signing.py`, with the exact Python packages pinned by hash in
`scripts/ci/signing/requirements.txt`. It was cross-checked during design: an independent JDK-only implementation of
the derivation gave the same key, and cryptography 45, 46 and 50 gave byte-identical certificates.

## What CI does (scripts/ci/signing.sh)

1. If `SHC_SIGNING_SEED` is missing (or a pull request comes from a fork, which never receives secrets): the build
   stops. There is no other key.
2. The seed must be at least 32 characters, with no spaces or line breaks at either end.
3. The key is derived and compared with the committed certificate. If it does not match (changed or mistyped secret),
   the build stops; it never produces an APK that could not update the installed app.
4. A temporary PKCS12 keystore (mode 0600, random password, masked) is written to the runner's temporary directory
   for Gradle, then deleted after the build, also when the build fails. Gradle refuses to build without it
   (`-PshcRequireSigningKey=true`).
5. The APK must have exactly one signer, and it must be the pinned certificate.
6. Two independent builds on separate runners must have the same certificate. The second build has version code + 1.
   On the emulator it is installed as an update over the first one; the app's data must be kept.

## Recovery

Needed to rebuild the key elsewhere (another CI system, a new workflow, a computer):
* the seed, from your password manager;
* the derivation above.

Run `scripts/ci/signing/shc_signing.py keystore <dir>` with `SHC_SIGNING_SEED` set; it needs
`signing/shc-signing-cert.pem`, which stays in the repository history and is also inside every APK. Or re-implement
the derivation in any language with PBKDF2 and P-256, then sign with that key and the committed certificate.

If the seed is lost, nobody can recover the key, by design: the next APK would need one more uninstall.
If the seed leaks, anyone could sign updates for the app: create a new seed and plan one reinstall.
