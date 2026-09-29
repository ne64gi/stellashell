# StellaShell 0.6.1 — bulk app organization

- Version code 15; reusable searchable icon/checkbox list for Start visibility and group membership.
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passed; 43 unit tests, no failures.
- SOG06 / Android 14: upgrade installed on the existing scrcpy display without resetting user preferences.
- Focused `organization_only` instrumentation passed: visibility deltas preserve unknown hidden entries and memberships; group changes preserve visibility and untouched memberships; checked apps move groups; unchecked own-group apps are removed; stale unchecks preserve other-group membership; missing groups are rejected.
- UI verified: filtering retains staged selection; Cancel left organization preferences byte-for-byte unchanged; new-group creation opens the checklist; selecting and saving Shizuku added it to a temporary group; reopening via the group control and clearing/saving removed its membership.
- Readback after user edits confirmed the temporary `StellaBulkCheck` group is gone. User-created groups and visibility changes were preserved.
- Follow-up UI change moves panel widget Add/Edit actions into a header gear menu and removes the permanent note/action rows. Final APK upgrade installation succeeded. Gear UI subsequently verified on temporary display 13.
- REDMAGIC not available for this verification. Existing 0.6.0 notification/widget and IME limitations remain unchanged.
- Device screenshots retained outside the public repository.

## Widget app destination

- Added `WidgetLaunchContext`, a public ContextWrapper path used when inflating widget views. It overrides the IntentSender dispatch used by RemoteViews, merging only the requested display option into the original options. Sender, fill-in intent, flags and existing BAL options are retained; broadcasts/services remain their original operation types.
- Header gear toggle defaults to primary display; disabling requests the widget host display. Shared by desktop and clock-panel widgets. Configuration/binding flows are unchanged.
- Final `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passed.
- Installed main/test APKs on SOG06. Instrumentation inflated a real RemoteViews with a PendingIntent click and confirmed dispatch reached the wrapper with primary display 0; current-display mode also passed on the display-0 test context. This is dispatch verification, not an end-to-end cross-display app launch.
- After installation the device reported only physical display 0; the former scrcpy display 11 no longer existed. Requested user reconnect for NERV end-to-end verification. Do not claim NERV routing or secondary-display fallback device-verified yet.
- No hidden-API exemption, provider click replacement, notification routing change, or display recreation was used.

## Screenshot and quick settings follow-up

- Added bounded Shizuku capture IPC with a file descriptor (no image bytes in Binder), explicit logical display ID, existing-display/primary-mode/lock checks, and no fallback to display 0. Framework capture defaults do not include secure layers. A missing capture API returns an error.
- App-owned MediaStore PNG output uses `Pictures/StellaShell` and pending publication; failure deletes its incomplete row. Captures are user-triggered, not automatic.
- SOG06: temporary scrcpy display 13, 1280×720/160, created solely for verification. Toolbar screenshot button produced a PNG of that desktop, visually inspected; phone display was 1096×2560. Main APK upgrade succeeded.
- Battery tap opened quick settings. Wi-Fi connected status rendered. SeekBar changed media volume 8 → 7; restored to 8 immediately after verification. No network state was changed. Wi-Fi settings entry exists but was not device-tested.
- Compact widget header gear visually verified. Google Calendar widget header opened Calendar on display 0 while HubActivity remained resumed on display 13. NERV-specific routing and the external-destination toggle still need follow-up.
- Test-only scrcpy session is closed after verification; user display sessions are not terminated. Screenshot evidence stays outside the repository. The test screenshot remains in the requested gallery folder as a usable example.
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passed. REDMAGIC/older frameworks not tested; screenshot capture currently requires the Android framework ScreenCapture/captureDisplay path.
