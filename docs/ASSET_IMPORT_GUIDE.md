# Importing your game files

The app contains **no game files**. You import your own legally obtained installation on the device.

## Supported source

**Stronghold Crusader HD 1.41** from GOG or Steam (DRM-free; includes Crusader and Crusader Extreme).

| Source | Status |
|---|---|
| HD 1.41 Latin (`Stronghold Crusader.exe` SHA-1 `012e9d55…6287020b`) | Target build (hash-verified) |
| Other HD 1.4x builds | Allowed, reported as "unverified hash" |
| 2008 disc release with SecuROM (v1.1/1.2e) | **Not supported** — the copy protection cannot run under Wine |
| Modified/cracked executables | **Not supported** |

## Steps

1. On a PC, install Stronghold Crusader HD from GOG or Steam.
2. Copy the whole installation folder (the one containing `Stronghold Crusader.exe`, `gm/`, `gfx/`, `fx/`, `maps/`)
   to the phone or tablet — USB cable, SD card or any file-sync app. About 1.2 GB.
3. Open the app → **Import game folder…** → select that folder → **Use this folder** → **Allow**.
4. Wait for the copy to finish (progress is shown; you can cancel; an interrupted import never replaces a
   previous complete import).
5. The app then shows, per executable, the detected build and whether it is supported, plus an asset check.

The app needs no storage permission. Files are copied into the app's own storage
(`Android/data/io.github.saefalasalynbtel198.shcport/files/game`). Thumbnails, `desktop.ini` and shortcut files are skipped.

## Save games

* **Export saves…** copies all `*.sav` files to a folder you choose.
* **Import saves…** copies `*.sav` files from a folder into the active save folder; existing saves are never overwritten.

## Removing

**Delete imported game** removes the copied files (after confirmation). Your original files are not touched.
Uninstalling the app also removes them.
