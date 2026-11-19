# Touch controls

Implemented in `core/TouchMouseMapper.java` (unit-tested) and visualised on the **Touch controls test**
screen. Gestures are translated into ordinary Windows mouse and keyboard events, so the game's own
mechanics stay intact.

| Gesture | Sent to the game | In-game effect |
|---|---|---|
| Tap | move + left click | select unit/building, place building, give order |
| Two taps within the double-tap window | two left clicks at the **same pixel** (second tap snapped to the first) | Windows reports a double click → selects all units of that type |
| Long press (default 450 ms) | right click | deselect / cancel placement |
| One-finger drag | left button held while moving | box selection, drawing walls, dragging moats/ditches |
| Two-finger drag | arrow keys held while fingers are displaced past the threshold | scroll the map (joystick-style; release to stop) |
| Pinch | mouse-wheel notches at the pinch centre | zoom/scroll where the game supports the wheel |
| On-screen **Esc**, **Space** buttons | key press | menus / game shortcuts |

Design notes:

* One-finger drag is a left-button drag by default because wall drawing and box selection depend on it.
  Users who prefer one-finger scrolling can switch it in **Control settings** (this disables drag-select by finger).
* A second finger that lands before the first finger moved cancels the pending tap (no accidental click).
* A second finger during a left-button drag is ignored, so a stray touch never interrupts wall drawing.
* `CANCEL` events (e.g. a system gesture) always release a held mouse button and arrow keys.
* Coordinates are mapped through `ViewportTransform`, which matches cnc-ddraw's `maintas=true` letterboxing.

## Adjustable settings

| Setting | Range | Default |
|---|---|---|
| Long press → right click | 200–1500 ms | 450 ms |
| Double-tap window | 100–800 ms | 300 ms |
| Tap tolerance | 4–120 px | 24 px |
| Pinch sensitivity | 5–100 % per notch | 18 % |
| Two-finger scroll threshold | 8–300 px | 40 px |
| One-finger drag scrolls the map | on/off | off |
| Invert scroll direction | on/off | off |

## Not yet implemented

* Injection into Wine (needs the runtime, BUILD 4).
* Configurable on-screen hotkey bar (game hotkeys beyond Esc/Space).
* Hardware mouse/keyboard pass-through.
