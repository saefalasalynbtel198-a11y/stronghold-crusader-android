# Display Resolution (0.6.0)

Status: implemented and CI-tested. **Not verified on the phone yet.** Recovery point: 0.5.1 (commit `6e1ef56`,
docs/BASELINE_0.5.1.md).

## The four sizes

| Size | What it is | Who sets it |
|---|---|---|
| Physical display | The phone's pixels (e.g. 2620×1280 landscape on the Xiaomi). | Hardware |
| Game view | The part of the screen beside the side buttons where the picture is drawn. | Android layout |
| X screen (Xvfb) | The virtual screen Wine and cnc-ddraw draw into. **This is what the setting chooses.** | Settings → Display Resolution |
| Game rendering resolution | The resolution Stronghold Crusader itself renders at. | **The game's own options** (saved by the game) |
| Displayed picture | The X screen scaled into the game view, aspect kept, centred, black bars elsewhere. | App (GLES renderer + `ViewportTransform`) |

cnc-ddraw scales the game's resolution onto the X screen (`maintas=true`: shape kept) and maps the mouse back
(`adjmouse=true`). The app scales the X screen onto the game view with the same rule and maps touches with the same
geometry, so a touch lands on the game pixel shown under the finger.

## Settings → Display Resolution

| Option | X screen | Notes |
|---|---|---|
| 640 × 480 | 640×480 | fastest; a 1024×768 game is scaled down (small text) |
| 800 × 600 | 800×600 | |
| 1024 × 768 (default) | 1024×768 | Exactly 0.5.1: same Xvfb command line and byte-identical ddraw.ini |
| 1280 × 960 | 1280×960 | sharper only if the game is set to 1280×960 too |
| Device resolution | the game-view size, landscape, width rounded down to a multiple of 8 (e.g. 2616×1280) | scaled down if above 4096×2160 or 3840×2160 pixels in total; heaviest |
| Auto | 1024×768; 800×600 on phones with less than 4 GB RAM | |

* Saved immediately, applied at the **next Start Game**. A running game is not touched.
* "Restore default display (1024 × 768)" removes the setting; nothing stored = 1024×768 (existing installs keep 0.5.1).
* The diagnostic Display test and Game launch test always use 1024×768.
* For any size other than 1024×768, that size is added to the resolutions DirectDraw offers the game
  (cnc-ddraw `inject_resolution`, both game sections), so it can be chosen in the game's options.

## Safe start and fallback (Start Game only)

1. The size is validated (≥ 640×480, ≤ 4096×2160, width multiple of 8, even height, pixel cap). Invalid → 1024×768.
2. Xvfb starts at that size; the app reads the framebuffer header and requires exactly that size. If Xvfb does not
   start or the size differs → Xvfb is stopped and restarted at 1024×768.
3. The DirectDraw pre-check runs once per screen size (its PASS is remembered per runtime, cnc-ddraw, app version
   **and screen size**). If it fails at a size other than 1024×768 → Xvfb restarted at 1024×768, pre-check again.
4. The game picture and touch input attach only after this, to the X screen the game really uses. A fallback is
   shown as a message on the game screen and in the Report ("Display Resolution: … FALLBACK: …").

Report line: `Display Resolution: setting …; X screen …; [FALLBACK: …;] device view WxH, picture WxH at (x,y), scale s`.

## Touch mapping

* `TouchMouseMapper` uses `ViewportTransform(X screen, game view)`; updated when the session's real X screen is known.
* A touch that **starts** in a black bar does nothing (no move, no click, no long press) until all fingers are up.
  A drag that starts in the picture and runs into a bar is clamped to the picture edge (unchanged behaviour).
* Gestures, click hold (120 ms), long press, double tap, two-finger scroll and pinch are unchanged.

## Tests

* Unit (`DisplayResolutionTest`, 12 tests; whole suite 150 tests): sizes for every option, Device rounding/portrait/
  limits, Auto, validation, default argv and ini identical to 0.5.1, other sizes' argv and ini, DirectDraw picture found
  at 640×480 … 2616×1280, exact touch mapping (corners, centre, edges) at every size on two phone views, bar touches
  ignored, gestures at 1280×960.
* CI under qemu (step 4d, `DisplayCli resolution`, runtime r6, Box64 + Wine + cnc-ddraw, Linux not Android):
  * X screen 1280×960 with the game at 1024×768, 1600×720 with the game at 1600×720 (injected mode), 640×480 with
    the game at 1024×768.
  * For each: Xvfb argv and framebuffer size, ini injection, the app's DirectDraw pre-check (first two), a
    DirectDraw test program in the game mode (mode offered and set, picture in shape), taps through the app's mapper
    on a 2400×1080 view arriving at the tapped game pixel, a bar touch sending nothing.
* Full regression: all earlier CI steps unchanged (display, input, sound, stand-in game, cycles, syscalls, emulator).

## Limitations

* The app cannot change the resolution saved in the game's own options. The game keeps rendering at that resolution
  and cnc-ddraw scales it; choose the same resolution in the game's options for a sharp 1:1 picture.
* Device resolution is not 4:3: menus designed for 4:3 may be placed differently by the game; it is the heaviest
  option (the game draws up to ~3.4× the pixels of 1024×768).
* Whether Stronghold Crusader HD 1.41 accepts a non-standard size such as 2616×1280 in its options is not verified.
* cnc-ddraw offers the game only modes that fit the X screen. At 640 × 480 or 800 × 600 the game's options will not
  list 1024 × 768; a 1024 × 768 already saved in the game is still set and scaled down (CI: SetDisplayMode OK,
  clicks at the right pixel), but whether the game itself keeps it or switches to a listed mode is not verified.
* Larger sizes use more memory: the X framebuffer is width × height × 4 bytes (1024×768 = 3 MB, 2616×1280 = 13 MB),
  plus the same again for the app's upload buffer.

## Device test checklist (owner)

Uninstall 0.5.1 first (each build's debug key differs). Keep runtime r6. Before each Start Game, choose the option in
Settings; after the game is running, use Report to copy the Display Resolution line.

1. Default (no change): Start Game; picture, touch, sound exactly as 0.5.1. Report says `setting 1024x768 (default)`.
2. 800 × 600: Start Game; picture in 4:3 with bars; menu taps hit the buttons under the finger; set 800×600 in the
   game's options and check again.
3. 640 × 480: as 2; the picture is smaller but taps still land correctly.
4. 1280 × 960: as 2, set 1280×960 in the game's options; check speed and sound (stutter?).
5. Device resolution: Report shows e.g. `X screen 2616x1280`; check that the game offers that size in its options,
   whether menus look right, speed, sound.
6. Auto: Report shows 1024×768 (the Xiaomi has more than 4 GB).
7. Touches in the black bars: nothing happens (no click, no cursor jump).
8. Pause/resume (home button, back to the app) at a non-default size: picture continues, touches still correct.
9. No black screen at any option; if a fallback message appears, send the Report.
10. Restore default display: back to 1024×768 at the next Start Game.
