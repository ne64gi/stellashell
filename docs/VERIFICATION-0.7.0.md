# StellaShell 0.7.0 — appearance settings

## Checkpoint and build

- Existing 0.6.1 work checkpointed separately as `d8a4f3e` before appearance implementation. 0.7 changes remain separate, uncommitted work; no push performed.
- versionName 0.7.0 / versionCode 16.
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passed (43 existing unit tests). Final footer-spacing-only adjustment passed `lintDebug assembleDebug` again.
- SOG06 upgraded successfully. Focused on-device instrumentation passed app organization, real RemoteViews launch routing, and appearance checks.

## Appearance checks

- Isolated preferences: defaults, draft isolation, round-trip color/opacity/font persistence, corrupt JSON fallback.
- Real Canvas render confirmed requested glass alpha; foreground choices verified for light/dark surfaces.
- Font tree traversal leaves text inside AppWidgetHostView unchanged.
- Temporary scrcpy display 16 (1280×900/160): Start → gear → Appearance opened correctly. Frost preset + Serif saved, preference readback matched, and Start/taskbar displayed the new translucent light surfaces and font. Light/dark Android themes follow the surface foreground choice.
- Reset-to-defaults UI used after verification; test-only virtual display closed without terminating user sessions.
- AppearanceActivity footer padding includes room for the overlaid taskbar.
- Existing app organization, widgets, wallpaper and task data are not cleared by theme save/reset. Saving recreates Shell activities and dock views, not other applications' windows.

## Limits

- Glass is alpha plus border, not a real backdrop blur. Extremely low opacity or user-selected accent colors can reduce readability; preview is provided.
- Preset font application is device-verified. Document-picker custom TTF/OTF import is implemented with a 128 MiB bounded private copy and Typeface validation, but a full picker/import/restart flow was not device-verified in this pass.
- Fonts affect Shell-generated UI; provider widget contents and system UI are excluded. Framework-owned controls can retain their theme defaults/accent.
- REDMAGIC unavailable for testing. Evidence screenshots remain outside the repository.

## Toolbar surface correction

- Removed independent opaque backgrounds from Start, Back, Show Desktop, screenshot, collapse, and app entries. Battery/clock/connection also share the same transparent idle treatment.
- Hover, press, keyboard focus and active-app selection use translucent theme-derived tints; active-app background no longer uses a fixed blue color. Ordinary settings buttons are unchanged.
- Existing user palette, opacity and font are retained during upgrade.
- `lintDebug assembleDebug` passed for the toolbar fix; installed on SOG06. Display 17 screenshot visually confirmed transparent idle controls with the user's existing 50% glass setting retained. User scrcpy session was preserved.

## TTC support

- Import accepts TTF/OTF/TTC data, up to 128 MiB. TTC directory versions 1/2, face count and offsets are validated before showing numbered face choices.
- Selected face index is persisted with the private font copy; Typeface.Builder.setTtcIndex is used both for preview and reload. Existing imports default to face 0.
- Four FontCollection unit tests passed (single font, valid collection, truncated directory, out-of-range offset); `testDebugUnitTest lintDebug assembleDebug` passed and main APK installed on SOG06.
- SOG06 instrumentation loaded the first and last face of the installed NotoSansCJK-Regular.ttc with Typeface.Builder and verified index serialization; passed. Full TTC document-picker selection remains unverified.

## Primary-panel-off experiment

- Baseline: SOG06 / Android 14, public scrcpy display 17, wirelessly charging. A separate bounded scrcpy control session with `--turn-screen-off --stay-awake` showed physical primary Off, virtual display On, Android Awake, and external Start interaction/rendering. Session exit restored primary On and original global stay-awake value 0.
- Added a quick-settings checkbox backed by Shizuku-only physical-display-token power control. Identifies display 0 by its physical address; never loops over or powers off USB displays. Holds a session wake lock without changing global sleep/charging settings.
- Binder-owner death, service destroy, desktop stop/reset and external-display disappearance release the lease and request primary On. Native helper availability is runtime-dependent; errors do not fall back to global sleep or black-overlay imitation.
- Initial in-app Off/Awake/virtual-On path verified; removed interaction with periodic task polling after an early unintended release. Stability/release checks recorded below after final verification.

- Final implementation: on-device test kept primary Off and virtual display On for at least 35 seconds while Android remained Awake and physical-input-injected Start actions rendered. Manual checkbox off restored On and released the wake lock; re-enabling succeeded (native helper reuse checked).
- Force-stopping only StellaShell restored primary On via lease teardown and removed its wake lock. Restarted Shell on existing display 17; left checkbox off after testing. User scrcpy was not stopped. USB-disconnect, battery-only and REDMAGIC paths still need verification; abrupt Shizuku process death cannot execute an in-process cleanup callback.
- Final `testDebugUnitTest lintDebug assembleDebug` passed; installed on SOG06.

## Pensum existing-task window mode

- User profile was WINDOWED/SMALL but existing Pensum task 453 on display 17 was fullscreen and resizeable.
- Found that launchProfile reused existing tasks by reordering only, and post-launch configuration applied only to newly created tasks. Added mode correction for existing/reused tasks when requested mode differs; preserves bounds when the existing mode already matches. Taskbar focus repairs fullscreen tasks with explicit WINDOWED/MAXIMIZED profiles.
- After upgrade, clicking Pensum in the taskbar changed the same task 453 to freeform without closing the app. Initial requested bounds were 800×600; the app/framework subsequently constrained its size to approximately 1050×788. It remained freeform during subsequent screen-power testing.
- This is not a perpetual override of app fullscreen requests; apps that actively change modes after launch can still require further handling.

## 2026-09-29: license remediation deployment and star identity

- Installed the license-remediated debug APK on SOG06 with `adb install -r`,
  then resumed the existing external desktop (display 17). No app-data clear.
- Confirmed new task snapshots and hierarchy capabilities through the installed
  Shizuku service. Pensum remained freeform; title-bar drag and right-edge resize
  changed both requested and actual bounds. Reversed the test adjustments.
- Existing wallpaper/font files and desktop/panel widget preferences were compared
  with a pre-upgrade backup and retained. Appearance, shortcuts and pins remained.
- Replaced the SLLSLL lettering with an original vector star mark: launcher and
  Start share the adaptive icon, monochrome has a dedicated silhouette, and the
  notification uses a transparent-background white star. README SVG matches.
  No external icon pack or new runtime dependency was introduced.
- `lintDebug` and `assembleDebug` passed for the final star-icon build. Reused the
  47 passing unit tests and device backend checks from the remediation build;
  the subsequent change is icon resources only.
- Installed the final star-icon APK on SOG06 and verified its on-device SHA-256
  equals the local artifact:
  `026a5200d689360748b444062a3a43ecf74ded15d4dd145f6439c326a2d7e5ee`.
- Visually confirmed the new splash and Start star, existing wallpaper, taskbar
  and freeform chrome. Left the active external desktop running.
- REDMAGIC: ADB/TCP attempts timed out. It was **not updated**, woken or tested.
  Resume after device/VPN/self-ADB connectivity returns; no sleep cause inferred.
- Local private evidence: `verification/license-device-upgrade-2026-09-29/`.
  Screenshots and app settings backups are not committed or published. Temporary
  on-device capture helper and captures were removed after checks.
- This is a working-tree development build, not a fixed-commit v1 release.

## 2026-09-29: notification settings gear / documentation closeout

- After the no-code Limited Mode investigation, the user explicitly authorized
  only the notification settings UI change and SOG06 replacement.
- HubActivity now retains a shared header gear on both tabs. Notifications gear
  opens a menu containing notification access settings; the full-width access
  button was removed. Widget menu behavior remains unchanged. Accessibility
  description and tooltip follow the active tab; edit marker is widget-only.
- `lintDebug assembleDebug` passed. Existing backend/unit evidence was reused;
  no new implementation-mirroring tests were added for this small UI change.
- APK license assets (GPL notice/text plus scrcpy Apache notice/text) verified
  byte-for-byte against sources. Version remains 0.7.0 / 16.
- SOG06 `adb install -r`: Success. APK SHA-256:
  `b6b4ff19a42720a32c1f3ff651ab0ee7041a6504d8d08fb173b804b23eae8c5c`.
- External display 17 was no longer available when reopening; the display-targeted
  start was rejected. No new virtual display was created and no primary-screen
  session was forced during the user's travel. New gear's device UI verification
  is therefore pending. REDMAGIC remains on hold.
- Three setup guides, font links, Limited Mode matrix and Windows helper added.
  No commit, push, visibility change or v1 release performed.

## 2026-09-29: REDMAGIC upgrade resumed

- User resumed REDMAGIC work. NX809J / Android 16 reached over authorized TCP ADB.
- Backed up own-app settings/files privately; upgraded installed 0.5.7 to 0.7.0
  with `adb install -r` (Success). Installed APK SHA-256 matches SOG06 above.
- Shizuku and the new Stella bridge were running. No physical external display
  detected, so a bounded 180-second scrcpy virtual display 19 was used.
- Desktop, existing NERV widget, new star Start icon, battery/clock bar rendered.
- Full notification gear interaction could not be verified: keyguard remained
  showing on the primary display; tapping the clock led to a black content area
  with the taskbar, no current focus, and no crash-buffer entries. Treat this as
  a locked-device observation, not a proven gear regression or a passing UI test.
- Asked user to unlock when available. No unlock bypass attempted. Temporary
  scrcpy session expired normally; test display removed and capture helper cleaned.
- Evidence: local private `verification/redmagic-notification-gear-2026-09-29/`.
  Notification gear/tab interaction and physical-monitor tests remain pending.

### REDMAGIC unlocked follow-up

- After user unlocked the phone, keyguard showing=false was confirmed.
- Temporary displays 20/21: notification gear opens its access-settings menu;
  tapping the item opens Android notification-listener settings successfully.
- Full-width notification settings button is absent. Widget gear retains Add,
  Edit and launch-on-primary options. Switching back to notifications restores
  the notification-specific gear menu. No crash-buffer entries observed.
- Notification access is not granted to StellaShell on this device; the expected
  permission guidance is shown. Did not grant access or claim live-notification
  delivery was tested.
- The phone was in primary-mode selection with desktop stopped before this
  follow-up. External mode was used temporarily, then the prior primary-mode
  selection/stopped state was restored. Temporary scrcpy sessions expired;
  helper and temporary capture removed. No new APK/code changes in this pass.

## Responsive shell work-in-progress — 2026-09-30

Design: [RESPONSIVE-SHELL.md](RESPONSIVE-SHELL.md). No new upstream code copied.

- Added display-scoped usable/application/content bounds; actual system, cutout,
  gesture and IME insets feed launch, maximize/snap/restore, drag/resize and caption
  clipping. Bridge protocol bumped to 17; app version remains 0.7.0.
- Added independent Auto/Desktop/Compact policy and bilingual settings. Compact
  shares tasks/apps/profiles and opens from either lower edge, as requested.
- 53 JVM unit tests passed (including six size/override policy cases). Device
  instrumentation passed work-area offset/clamp checks plus existing organization,
  widget routing and appearance checks on REDMAGIC.
- Initial device build: local portrait Compact rendered; left/right tap invocation
  worked, star/pins/tasks/clock/battery rendered. Insets reported actual status
  top 107px and navigation bottom 130px (1216x2688 display, 520dpi).
- IME observation: usable bottom changed 2558→1599 (Termux) / 1697 (another IME
  layout); Compact panel and handles moved above the keyboard. Fullscreen Termux
  was not a proof of freeform task resize.
- Found multi-task auto-reflow could oscillate focus/IME through Android 16 Shell
  transitions. Restricted automatic correction to the focused task; background
  tasks correct on focus. Added suspension while Start owns keyboard input.
- UIAutomator returned an empty hierarchy with the initial full-display observer
  (cause not proven). Replaced the observer with a
  1px, non-interactive observer reading display WindowMetrics and periodic fallback;
  this also avoids becoming the IME layering target. Device validation pending.
- ADB became offline before the final observer/input changes could be installed.
  No claim yet of final-build device success, swipe success, IME restore success,
  landscape/rotation/transient-navigation success, or external/virtual regression
  pass. These require follow-up after connection recovery.
- Private evidence: local `verification/responsive-shell-2026-09-30/`; includes
  pre-test preferences backup, intermediate screenshots, window dumps and builds.
  No personal screenshots are checked into the repository.

### Connection recovery and continued device checks

- Sony .4 reachable while .6 was offline. Temporary scrcpy display 3,
  1920x1080/160: Auto selected Desktop; usable (0,0)-(1920,1080), task content
  (0,32)-(1920,1020), matching the previous undecorated external geometry.
  Wallpaper/shortcuts/taskbar/Start rendered; Termux launched at the requested
  (560,226)-(1360,826). Search typing retained the Start window's focus.
- Same virtual backend switched Auto→Compact→Auto through the settings UI.
  Compact removed the 60px reservation. Left swipe opened dock at x=0; right
  swipe then opened it at x=1844. No backend/session duplication was involved.
- Temporary Sony display 4, 1080x1920/320, with system decorations and local IME:
  Auto selected Compact; navigation reserved 96px. IME changed content bottom
  1824→1096. Termux freeform was fitted above the keyboard; maximize requested
  and achieved (0,64)-(1080,1096). Virtual rotation to 1920x1080 selected Desktop
  using dp geometry, confirming policy is not tied to a display/backend type.
- Stopped only the test scrcpy, restored Sony's previous mode/stopped state,
  preferred-display setting and launch profiles; removed temporary capture files.
- REDMAGIC reconnected. Updated 1px observer works: local status top 107px,
  navigation bottom 130px; IME content bottom 1599. Termux task 275 was freeform,
  fitted to bottom1599. Maximized caption occupied y107–211 (below status bar).
  After Back hid IME, maximized content returned to (0,211)-(1216,2558).
- REDMAGIC rotation: 2688x1216 physical bounds; landscape IME work area updated
  to (0,107)-(2558,461). Caption remained above IME. Returned to portrait and
  original rotation lock. No new crash-buffer entries observed.
- Actual USB-monitor reconnection remains untested in this pass. REDMAGIC tap
  invocation works; ADB swipe injection did not deliver handle touch events, so
  physical-finger swipe confirmation was requested rather than claimed as passed.
- `sony-06-maximized.png` is stale: capture was killed as timed display3 expired.
  It is NOT evidence of maximize; display4's successful capture/logs are used.

### Final artifact for this pass

- Final build: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  :app:assembleDebugAndroidTest` passed. 53 JVM tests; lint 0 errors / 52 warnings.
- Added scrolling access for Compact controls when landscape IME leaves less than
  the controls' minimum height. Normal-height final Dock rendering checked on .6;
  tiny-height scrolling remains a follow-up interaction check.
- Replaced bright debug handles with short translucent strokes, retaining their
  touch targets. Final normal-height panel/handle screenshot inspected.
- APK SHA256: `ba942598eec8cef2feae9764318f7cef0876f5500d8be22615349b5a57743e0f`.
  Both .4 and .6 installed base APK hashes match. GPL/NOTICE assets match root files.
- Sony remains stopped in its prior external-mode selection. REDMAGIC remains
  running in local Auto/Compact for the requested physical-finger swipe check;
  original portrait rotation lock restored. Temporary debug log property restored.
  Test launches may have updated REDMAGIC's remembered task bounds; pre-test
  preferences are backed up privately, not silently overwritten during user testing.
- No commit, push, release or v1 version bump. Primary/Floating task roles remain
  a proposal, documented separately in COMPACT-WORKSPACE-NOTES.md.

### Setup navigation cleanup

- Replaced the single long setup page with Start / Settings / Diagnostics tabs,
  a star header and a centered 720dp maximum-width content area.
- Start shows connection status and relevant session actions. Permission setup,
  appearance, input and experimental controls moved to Settings. Maintenance and
  selectable/copyable logs remain available in Diagnostics, not on the start page.
- Removed promotional intro from the screen; shortened Japanese/English labels.
  Updated setup instructions. Existing action callbacks and setting storage retained.
- `:app:lintDebug :app:assembleDebug` passed. Installed on SOG06 (.4), inspected
  the start screenshot and exercised all three tabs with UIAutomator. Start page
  left open, session still stopped. REDMAGIC not updated in this pass.
- No commit/push. Wide-screen visual review and English device rendering not done.
