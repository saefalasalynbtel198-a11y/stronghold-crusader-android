# Runtime package (planned)

The game is a 32-bit x86 Windows program. On an ARM64 Android device it needs:

| Layer | Component | License | Status |
|---|---|---|---|
| x86-64 → ARM64 CPU translation | Box64 (`BOX64_REF` in `versions.env`) | MIT | Cross-compiled in CI (`runtime-components.yml`); not yet on-device tested |
| Windows API, 32-bit via WoW64 | Wine x86-64 WoW64 build (Kron4ek) | LGPL-2.1+ | Download + checksum verified in CI |
| DirectDraw → OpenGL | cnc-ddraw | MIT | Download in CI; checksum recorded on first run |
| ARM64 glibc userland for Box64 and Wine's Unix side | Debian trixie arm64 libraries via `mmdebstrap --variant=extract` | various FOSS (copyright files shipped) | Scripted (`build-userland.sh`); not yet run |
| Display server Wine can draw into | X11 server inside the app (approach used by Winlator) | — | **Not started** |
| Audio output | Wine → PulseAudio/ALSA → Android AAudio | — | **Not started** |

Why WoW64 rather than Box86: Box86 needs 32-bit ARM execution, which newer ARM cores no longer
provide; Box64's own Wine guide lists the x86-64 WoW64 Wine build as the configuration that runs
32-bit Windows programs with Box64 alone.

## Package format

`assemble-runtime.sh` stages the tree below and `package_runtime.py` writes `shc-runtime-<version>.zip`.
Zip cannot hold symlinks or exec bits, so they are listed in `SHC_RUNTIME_MANIFEST` (`x <path>`, `l <path> <target>`);
`core/RuntimeArchive.java` validates every path (no zip-slip, no links leaving the root) and recreates them on device.

## On-device layout

```
files/runtime/
  RUNTIME_VERSION
  bin/box64
  lib/aarch64-linux-gnu/...   glibc + X11/GL/audio client libraries (ARM64)
  lib/x86_64-linux-gnu/...    x86-64 libraries Box64 cannot wrap natively
  wine/bin/wine ...           Wine x86-64 WoW64 build
files/prefix/wine/            Wine prefix (drive_c, dosdevices/d: → imported game folder)
```

`RuntimeStatus.java` checks for `bin/box64`, `wine/bin/wine` and `RUNTIME_VERSION`;
`WineLaunchPlan.java` builds the command line and environment for this layout.

## Building inputs locally

```bash
sudo apt-get install gcc-aarch64-linux-gnu cmake ninja-build file mmdebstrap unzip xz-utils
bash runtime/scripts/build-box64.sh
bash runtime/scripts/fetch-components.sh
sudo bash runtime/scripts/build-userland.sh
bash runtime/scripts/assemble-runtime.sh     # → runtime/out/shc-runtime-*.zip
```

Outputs go to `runtime/out/` (ignored by git).
