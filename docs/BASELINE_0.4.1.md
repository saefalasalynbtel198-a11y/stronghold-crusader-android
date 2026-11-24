# Known-good baseline: SHC Port 0.4.1 (first playable build)

Recorded 2026-11-24. This is the reference state for all later changes: anything that changes the behaviour described
here must be justified by a reported gameplay problem and checked against it.

## Device verification (owner, Xiaomi 25053PC47G, Android 16, arm64-v8a, Stronghold Crusader HD 1.41)

Verified by real gameplay on the phone with 0.4.1:

* Start Game → intro, loading, main menu; menus by touch ("Crusader", game mode, start).
* Unit selection by tap; group selection by drag; movement orders by tapping the destination.
* Building tools, building selection and placement; construction proceeds.
* Side keys Esc, Enter, Space, Keys (soft keyboard: player name typed); Exit.

Not yet verified on the device: Right toggle (right-button hold / camera interface), double tap = select all of a type,
long press = right click in game, two-finger scroll and pinch in game, pause/resume during play, Exit + relaunch,
extended play. No sound (see AUDIO_AND_RESOLUTION_INVESTIGATION.md).

## Checkpoint

| Item | Value |
|---|---|
| Code commit | `890ef28e26688d3dce8b60f4ee89c5a5e7438193` on `feature/build3-step3.3-game-launch` (PR #8, draft, not merged) |
| APK | `SHC-Port-0.4.1.apk`, version 0.4.1 (code 18), Android build run 45409648398, artifact 14928240232 (expires 2026-12-24) |
| APK SHA-256 | `955372f9e44bfe934154cd007c49a719d51dc6f71376418a2fdbd0ef819c8e3c` |
| APK signer | standard debug key of that CI run, certificate SHA-256 `00c6354cb6a082a63414cdb406084914fb334c7179f600f6ecd0bb7361ed7667` |
| Runtime | r6: `shc-runtime-r6-box64-v0.3.8-wine-11.0-cncddraw-v7.0.0.0-trixie-glibc2.41android.zip`, SHA-256 `da15d44c028e4bf3a13187696bdbd05aa153fe5691b3f4bd0e113fb60e2dc56a` (run 44911670217, artifact 14710845090) |
| Wine | Kron4ek `wine-11.0-amd64-wow64.tar.xz`, SHA-256 `39574efa1132c3ca0d5c77dd2eddbe4a49cca0d6cc2c290ff4924493a1c40314` |
| cnc-ddraw | v7.0.0.0 `ddraw.dll`, SHA-256 `c7b25b41c8be1e8c5c46fdf79dd5a06a455cc95bf0d6625436c8170b9bb65133` |

Recover the baseline: `git checkout 890ef28` (the commit the 0.4.1 APK was built from; it stays in the branch history).
A tag `v0.4.1-baseline` could not be pushed from the build session (tag pushes are not permitted there); the owner can
add it on GitHub if wanted. The CI of that commit rebuilds the same app (the debug signing key differs per run, so a
rebuilt APK needs an uninstall first).

## Launch path (unchanged since the device-verified 0.3.8)

Main screen → Start Game (enabled by `core/GameReadiness`) → `app/GameActivity` → `app/DisplayTestSession` in play mode:

1. Runtime check (Xvfb, cnc-ddraw, DirectDraw test program); leftover Xvfb/Wine processes stopped.
2. Imported game checked; `core/PrivateGameCopy` syncs it into `files/game-private` (SHA-256 per copied file,
   executables re-hashed every launch); the imported folder is only read and compared before/after.
3. Wine prefix (`files/prefix/wine`) created or kept (device-verified steps).
4. Xvfb `:37`–`:40`, `-screen 0 1024x768x24`, `-displayfd 1`, `-ac`, abstract socket only (`core/X11DisplayPlan`).
5. `core/GameSetup`: `dosdevices/d:` → private copy; cnc-ddraw → `C:\windows\syswow64\ddraw.dll`;
   `C:\shc-port\ddraw.ini` (`core/CncDdrawConfig`: renderer=gdi, fullscreen=true, windowed=false, maxfps=60,
   `[Stronghold Crusader]` resolutions=2, stronghold_hack=true, adjmouse=true).
6. DirectDraw pre-check (`core/DdrawPrecheck`), skipped once passed for this runtime/cnc-ddraw/app version.
7. `box64 wine "D:\<exe>"`, cwd = private copy, `WINEDLLOVERRIDES=...;ddraw=n`, `WINEDEBUG=err+all,fixme-all,+loaddll`.

One session per app (`core/SessionLifecycle`); it survives the screen being paused; Exit stops Wine and Xvfb.

## Display

Xvfb's framebuffer file (XWD, mapped read-only) → `app/FramebufferRenderer` (OpenGL ES 2) → `GLSurfaceView`, the 1024×768
picture scaled with the aspect kept and centred (`core/ViewportTransform`: scale = min(view/1024, view/768), black bars).
Touch uses the same `ViewportTransform`, so a touch maps to the game pixel shown under the finger.

## Control architecture (the part to change only on reported problems)

```
Android MotionEvent (GameActivity.PlaySurface)
  → core/TouchMouseMapper (gestures; screen px → game px via ViewportTransform)
  → List<core/InputAction>  (MOVE, BUTTON_DOWN/UP LEFT|RIGHT, WHEEL, KEY_DOWN/UP vk, PAUSE ms)
  → app/GameInput (one "game-input" thread, X11 connection over Xvfb's abstract socket)
  → core/X11Input (X11 wire protocol: XTEST FakeInput; keys through the server's keymap)
  → Xvfb → Wine (winex11) → Windows mouse/keyboard messages and button state → cnc-ddraw → game
```

Gestures (`TouchMouseMapper`):

| Touch | Sent to the game |
|---|---|
| Touch down | pointer moves to the finger (no button yet) |
| Tap (lift within the slop radius, before the long-press time) | move → left press → hold `clickHoldMs` → left release |
| Second tap within `doubleTapMs` of the first lift, within 2× slop | same click at the first tap's position (Windows double click) |
| Finger moves beyond `touchSlopPx` | left press at the start point, pointer follows, release on lift (held at least `clickHoldMs`) |
| Finger still for `longPressMs` | right click (move → right press → hold → release); the lift sends nothing |
| Right toggle on | one finger holds the right button for the whole touch (tap = right click, drag = right drag) |
| Second finger before the slop | no click; two-finger drag holds arrow keys (scroll), pinch = wheel notches |
| Second finger during a drag | ignored |
| Touch cancelled / screen paused | held button released (mapper CANCEL + `GameInput.releaseButtons`) |

Defaults (`core/ControlSettings`, persisted by `app/ControlSettingsStore` in SharedPreferences, editable in Control
settings): longPressMs 450, doubleTapMs 300, touchSlopPx 24, clickHoldMs 120, pinchStep 0.18, panThresholdPx 40,
oneFingerDragPansCamera false, invertPan false.

Why the click is held: the game reads the button state per frame; a press and release sent together (0.4.0) were
missed on the phone. Keep `clickHoldMs` and the PAUSE action unless gameplay feedback shows a problem.

Keys: side bar Esc / Enter / Space (keysym taps), Keys (soft keyboard → `X11Input.type`, Shift added when the keymap
needs it), hardware keyboards (`GameActivity.keysymFor`). Back = Exit dialog.

## Tests that protect this baseline

* Unit (131 at this commit): `TouchMouseMapperTest` (every gesture's exact action sequence, hold times, no stuck
  buttons), `X11InputTest` (wire protocol against an in-process X server; tap held ≥ 100 ms on the wire; letterboxed
  coordinates), `GameSessionRulesTest`, `DdrawPrecheckTest`, `PrivateGameCopyTest`, `RuntimeConfigTest` and others.
* CI qemu job (runtime r6 Box64 + Wine + Xvfb, Linux not Android): every gesture through the app's mapper and X11 client
  on a 1786×1228 view into a Windows test program (`scripts/ci/check-input-log.py`): one left click per tap held ≥ 80 ms
  at the tapped pixel, drag with left held, long press = right click without a left click, right-button drag, double
  click, cancel releases, second finger no click, keys; a reader polling the button state every 15 ms sees each click.
* Emulator smoke test: install, Start Game state, game screen opens and reports a missing runtime without crashing.
