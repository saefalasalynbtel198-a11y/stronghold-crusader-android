# Release notes

## 0.1.0-dev.1 — BUILD 1 (development build, not a playable game)

This build installs and runs on Android 8.0+ but **cannot start Stronghold Crusader yet**: the Wine + Box64
runtime is not included.

Included:

* Import of your own game folder through the Android file picker (no permissions), with progress, cancel and
  atomic replacement.
* Game build detection from the executable (hash + PE inspection); HD 1.41 is the supported target; SecuROM
  disc builds are reported as unsupported.
* Asset completeness check (executables, Miles/Bink DLLs, gm/gfx/fx/maps, optional music/speech/videos).
* Save-game export/import.
* Touch-control test surface using the final gesture → mouse/keyboard mapper; adjustable sensitivity.
* Diagnostics screen (ABIs, 32-bit support, GLES version, RAM, storage, last crash report).

Not included: runtime, rendering, audio, gameplay. See KNOWN_ISSUES.md.
