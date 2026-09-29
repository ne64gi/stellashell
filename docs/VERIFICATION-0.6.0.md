# StellaShell 0.6.0 — verification (2026-09-29)

## Checkpoint

- Previous prototype committed separately as `dabc038` (0.5.8).
- 0.6.0 versionCode 14 adds a clock widget panel and optional notification listener.
- `testDebugUnitTest lintDebug assembleDebug`: passed, 43 unit tests, no failures; lint has warnings, no errors.
- Debug APK SHA-256: `e16149bc06e97c248ef1916eb26083a322db568e09c2b61ada4a881a3c6853cc`.

## SOG06 / Android 14 / existing scrcpy virtual display

- Upgrade install succeeded; existing wallpaper, shortcuts and Start/taskbar preferences retained.
- Clock opens the right-side panel. Widget and notification tabs render in both the original landscape display and the subsequently rotated portrait display.
- User added/configured a NERV widget and its RemoteViews rendered in the panel. Reopening the panel retained the widget.
- Readback confirms panel entries use `panel_widgets`; `desktop_widgets` remained unchanged (empty on this test device). Host IDs are distinct; no migration/deletion of desktop widgets was performed.
- Existing widget move/resize/scaling implementation reused. Full matrix of provider resizing, process-death during setup, and panel rotation during a pending configuration is not separately verified here.
- Notification access was not granted during this verification. The ungranted state and settings-entry UI were visually checked. Live notification updates, opening/dismissal, revocation and lock transitions require a follow-up with user-granted access. Do not describe these as device-verified.
- No notification content is persisted/logged/transmitted by the implementation. Screenshot artifacts stay outside the public repository.

## Known limitations

- Launching NERV itself from its widget created a fullscreen task; its portrait orientation request rotated the virtual display. Widget layout persisted. PendingIntent app launches do not pass through all Start launch-profile handling; this also limits notification destination/window-mode guarantees.
- Notifications require Android's notification-listener access, separately from Shizuku. No permission was granted via ADB. The panel shows only current OS notifications, excludes its own service notification, and hides content while the device is locked.
- No notification reply/actions or persisted notification history in this version.
- REDMAGIC mouse routing remains unverified: see MOUSE-ROUTING-WIP.md.
- PC physical-keyboard language switching remains environment-dependent. ADB-injected Ctrl+Space switched Gboard subtypes, but the user's PC shortcut path was unresolved. This version does not claim to fix it.
- Primary-display hub behavior and additional OEMs require follow-up; the existing primary/USB/scrcpy entry paths were not removed.
