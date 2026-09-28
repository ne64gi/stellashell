# SOG Desktop 0.5.2 / code 7

## Changes

- Per-task captions, muted inactive style, click-to-focus and continuous drag.
- `getAllRootTaskInfosOnDisplay` determines root z-order; root leaf IDs are reversed
  from their bottom-to-top array. `getTasks` recency order is not treated as z-order.
  Missing/incomplete stack information falls back to focused-only decoration.
- Rectangular subtraction removes occluded paint **and input**: each visible piece
  is its own clipped, small overlay window, not a full-screen transparent catcher.
  Application content, higher captions, fullscreen and shell tasks are blockers.
- Keep the input-owning caption fragment while it becomes a whole caption on focus.
  Dock-only refresh now preserves WindowChrome; the old full rebuild canceled an
  inactive-to-active drag after its first move.
- Existing Android 16 transition bounds backend retained. Shizuku service version 6.
- Skip unchanged fragment layouts/painting; poll at 300ms with multiple visible
  tasks, otherwise 1100ms. External focus/occlusion updates are not frame-synchronous.
- Widget edit toolbar sits above provider content instead of painting over it.
  Saved rectangle describes content only, and size options remain content-sized.
  At the top edge, content shifts temporarily to make room for the toolbar.
- Fixed-size widgets explicitly show `固定サイズ`; no false resize handle.

## Evidence

Directory: `(local evidence archive; not included)/sog-desktop-0.5.2/`.
Build: `testDebugUnitTest lintDebug assembleDebug` successful. 36 tests, zero
failures/errors. Lint zero errors / 15 warnings. Four new geometry tests cover
split captions, full coverage, edge contact, and exhaustive pixel ownership with
overlapping blockers (no duplicate/hidden clickable pixels).

Final APK SHA-256:
`8e8b90a8406e13af68c767aba224ac31f071c62a3bccd571fe05b53e38ec3504`.
Installed final APK on REDMAGIC NX809J / Android 16 and Sony SOG06 / Android 14.

### REDMAGIC / dedicated scrcpy display 7

Temporary fixture package `dev.fuyumori.windowprobe`, two independently tasked
plain Activities. No user app content was used as an input test target.

1. Background A: `(100,300)-(1100,800)`. Foreground B: `(400,200)-(800,700)`.
   A's caption appears in two pieces either side of B; B's active caption remains.
   `redmagic-split.png` and window dump.
2. Clicking `(650,285)`, where A's caption would have been under B, increments B's
   own application click counter. It does not focus/drag A.
3. Primary mouse drag from A's **second** visible caption fragment `(850,285)` to
   `(950,385)` focuses A and completes in one gesture to `(200,400)-(1200,900)`.
   `drag-second-fragment.txt` and corresponding SurfaceFlinger dump.
4. Clicking inactive B's caption focuses B. A is re-clipped into
   `(200,368)-(400,400)` and `(800,368)-(1200,400)`; B caption is
   `(400,168)-(800,200)`. `two-captions-surface.txt`.
5. Click in the newly covered A-caption area again reaches B's content.
   `click-through.xml` records B's counter. Mouse is injected SOURCE_MOUSE with
   BUTTON_PRIMARY, not a physical mouse test.

### Widgets on REDMAGIC

User's ChatGPT widget ID 12 is resizeMode=3; the vendor battery ID 13 is
resizeMode=0. The initial empty preference read preceded the user's additions;
later reads correctly contain both widgets. No widget allocation was deleted.

- `widget-edit.png`: editor toolbar no longer covers either provider. Battery
  shows fixed-size explanation. Existing content location remains the same at
  non-edge placements when entering edit mode.
- ChatGPT content size changed by mouse from 505×391 to 585×431, then restored to
  505×391. X/Y stayed 569/195. Battery stayed x48/y164, 240×120.
- `widgets-before-edit.xml`, `widgets-resized.xml`, `widgets-restored.xml`.
  Semantic equality of both entries was asserted after restoration.

### Sony / dedicated display 42

Stack-order capability probe succeeded and two caption fragments were created.
The phone was locked, so OS policy hid the overlays (`mForceHideNonSystemOverlayWindow`).
Did not bypass the lockscreen. Thus interactive multi-caption verification is
REDMAGIC-only. Sony's original clock ID 9 and layout were not changed.

## Cleanup / limits

Temporary fixture APK uninstalled from both devices. Only its per-component
launch-profile keys removed while SOG Desktop was stopped; unrelated profiles
preserved. Own scrcpy clients stopped and recordings finalized. User's replacement
display 8 retained; final REDMAGIC shell restarted there. User's widget IDs,
positions and sizes preserved. No global settings, HOME selection, commit or
publication changed. Arbitrary OEMs, physical USB, transient system popups and
frame-perfect external z-order changes remain outside this verification.
