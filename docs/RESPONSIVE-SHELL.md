# Responsive shell / Work Area

Implementation design, 2026-09-30. No new upstream source incorporated.

## Geometry before presentation

`WorkArea` uses display coordinates, not Activity-local coordinates:
physical bounds → union of visible system-bar/cutout/mandatory-gesture/IME insets
→ usable bounds → presentation reservation → application bounds → caption
reservation → task content bounds. Insets are combined by maximum, not added
(IME and navigation commonly overlap).

`WorkAreaObserver` is a transparent, non-focusable/non-touchable 1px overlay scoped to
the selected display. Insets callbacks trigger display-scoped WindowMetrics reads;
a 750ms sample covers OEMs that do not dispatch changed inset geometry to a tiny
window. It never consumes insets or becomes the IME layering target. No status
bar/navigation size constants, SystemUI disabling, gesture exclusion or OEM offsets.
Geometry and density changes recreate the display-scoped observer. Session stop
removes the observer and cached area.

Profiles, maximize/snap/restore, drag clamps, initial normalization, and caption
clipping use this model. The app sends validated content bounds to the existing
Shizuku backend immediately before task operations (bridge protocol version 17).
The backend refuses bounds operations without geometry rather than assuming a
primary-display status-bar height. Fullscreen remains Android fullscreen.

IME adjustments are debounced. Focused freeform windows are fitted to the work
area; background windows are corrected when focused, avoiding OEM transition
focus theft. Start input suspends automatic task normalization. A temporary pre-IME rectangle is restored only if the user/application has
not changed the adjusted rectangle. Temporary IME geometry is not saved as an
application's preferred bounds. Android/app minimum sizes may further constrain
requested rectangles; this must be checked on devices rather than assumed away.

## Presentation is not a backend

`ShellPresentation` chooses from Auto / Desktop Taskbar / Compact Edge Dock.
No device names, display IDs or Local/Physical/Virtual backend tests enter this
policy. Auto uses density-normalized usable size excluding transient IME changes:
short side >=600dp, or width >=960dp and height >=480dp selects Desktop. A wide
but very short phone stays Compact. User override is global and persists.

Desktop keeps its existing controls and 60dp shell reservation. Compact reserves
no permanent application area. Both use the same task session, launch profiles,
app library, widgets, notification center, quick settings, appearance and fonts.

Compact has two lower edge handles, inset past reported system back-gesture
regions. Swipe inward (or tap for accessibility) on either side opens a vertical
Dock on that side, per the user's follow-up; no fixed left/right preference.
Clock opens the hub; battery opens quick settings; pins/tasks reuse existing
launch/focus/context-menu behavior; the star opens Start. Outside touch/close
hides the dock. Compact hides the screenshot control only, not its backend.

## Verification

See the dated responsive-shell section in VERIFICATION-0.7.0.md for checks and
unverified scenarios. In particular, WindowInsets propagation to overlays is an
OEM-sensitive runtime condition; building successfully is not proof of correct
IME behavior.

API references (interfaces only, no implementation copied):
- https://developer.android.com/reference/android/view/WindowInsets
- https://developer.android.com/reference/android/view/WindowMetrics
