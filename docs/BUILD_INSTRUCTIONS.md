# Build instructions

## Requirements

* JDK 17
* Android SDK: `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`
* Gradle 8.11.1 (the repository has no Gradle wrapper jar yet; install Gradle or let CI provide it)

No Android NDK is needed for BUILD 1 (no native code).

## Local build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
gradle :app:testDebugUnitTest      # unit tests (pure Java core)
gradle :app:lintDebug              # lint, fails on errors
gradle :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
BUILD_TOOLS=35.0.0 bash scripts/ci/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

| Workflow | Trigger | Output |
|---|---|---|
| `android-build.yml` | push to `main`, pull requests, manual | Unit tests, lint, debug APK, APK verification, emulator smoke test; artifact **Stronghold-Crusader-Android-APK** |
| `release.yml` | tag `v*` or manual with a tag | GitHub Release with APK + `.sha256` |
| `runtime-components.yml` | manual, or changes under `runtime/` | Box64 aarch64 binary, Wine WoW64 + cnc-ddraw downloads (experimental) |

Download the APK: repository → **Actions** → latest green *Android build* run → **Artifacts** →
*Stronghold-Crusader-Android-APK* (zip containing `app-debug.apk`).

## Install on a device

1. Enable installing unknown apps for your browser/file manager.
2. Open `app-debug.apk` and confirm. Or: `adb install -r app-debug.apk`.

## Signed releases

Create a key once (keep it safe; it cannot be recovered):

```bash
keytool -genkeypair -v -keystore release.jks -alias shc -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks > release.jks.b64
```

Add repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `SHC_KEYSTORE_BASE64` | contents of `release.jks.b64` |
| `SHC_KEYSTORE_PASSWORD` | keystore password |
| `SHC_KEY_ALIAS` | `shc` |
| `SHC_KEY_PASSWORD` | key password |

Then bump `appVersionName`/`appVersionCode` in `app/build.gradle`, commit, and push a tag that matches:
`git tag v0.1.0-dev.1 && git push origin v0.1.0-dev.1`. Without the secrets the release workflow publishes the
debug-signed APK as a pre-release. Never commit keystores or passwords (`.gitignore` blocks `*.jks`/`*.keystore`).

## Troubleshooting

| Symptom | Fix |
|---|---|
| `SDK location not found` | set `ANDROID_HOME` or create `local.properties` with `sdk.dir=…` |
| Lint fails | open `app/build/reports/lint-results-debug.html` (uploaded as the *build-logs* artifact in CI) |
| `verify-apk.sh: native ABIs …` | update `EXPECTED_ABIS` in the workflow when native code is added |
| Release job: tag does not match | tag must be `v` + `appVersionName` |
