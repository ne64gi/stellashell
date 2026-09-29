# 0.5.8 verification — 2026-09-29

- `testDebugUnitTest lintDebug assembleDebug`: passed; 43 unit tests, 0 failures.
- Installed debug APK (versionCode 13) on Sony SOG06 / Android 14.
- Used existing scrcpy virtual display 3 (1920×1080 / 160 dpi); did not recreate the user's display or change HOME.
- Start icon and six-column pinned/all-app grid visually checked.
- Created temporary `StellaTest` group using UI, confirmed child panel and group tools, then deleted only that test group. Existing hidden apps and taskbar pins preserved.
- Virtual keyboard checkbox runtime checks via `IWindowManager.getDisplayImePolicy`:
  - unchecked: main display 0 = 0, virtual display 3 = 0;
  - checked: main = 0, virtual = 2 (HIDE);
  - unchecked again: main = 0, virtual = 0;
  - checked then desktop stopped: main = 0, virtual = 0;
  - desktop restarted: main = 0, virtual = 2.
- With hiding enabled, the group-name text field accepted injected ASCII without showing the screen keyboard. `dumpsys input_method` also reported `mInputShown=false` on display 3.
- Left desktop running with keyboard hiding enabled, as requested. Fresh-install default remains OFF.

## Limits / remaining checks

### Follow-up: SOG06 widget configuration launch

- Reproduced selecting NERV weather (`app.nerv.widget.weather.WeatherWidget`): binding succeeded, then Android 14 rejected its system-owned configure PendingIntent with `BAL_BLOCK`. No widget entry was saved.
- `DesktopWidgets.configure()` now supplies ActivityOptions with the originating display and the Android 14+ sender background-activity-start opt-in, only for the user-initiated configuration request. No global background-launch policy was changed.
- `assembleDebug lintDebug` passed; installed on SOG06. Selecting the same provider now logs `BAL_ALLOW_PENDING_INTENT`, result code 0, with pending widget ID 13 and the provider configuration activity launched. User must finish provider-specific choices; final rendering confirmation pending.
- During debugging the phone also entered Dozing, making touch probes unresponsive despite a visible virtual display; woke it without bypassing the lock.
- UHID: observed both Shift key down/up events and Space while Shift was held. Missing Shift delivery is ruled out for that sample. Enabled Gboard subtypes were Japanese QWERTY and Japanese 12-key only; user asked to add English (US) and verify switching. No language preferences changed via ADB.

### Follow-up: search focus and IME correction

- Fixed search TextWatcher's unconditional `closeFolder()` focus reset: with no folder open, it now leaves the editor and composition alone.
- Rebuilt (`assembleDebug lintDebug`) and installed on SOG06. A single `input text termux` delivered all six characters, filtered the grid, and retained the search caret. Further input without refocusing was sent. PC IME composition is not verified by this ASCII check.
- Found HIDE policy also returns `InputBindResult.NO_IME` in Android 14 InputMethodManagerService. It is **not** a composition-preserving keyboard visibility control. Corrected English/Japanese explanatory text and README; turned the test device checkbox OFF again. Earlier policy readback tests only proved policy changes, not Japanese conversion.
- Gboard is installed on this device. Physical-keyboard Japanese conversion via the user's Windows scrcpy/UHID path remains to be verified. No PC composer tool was created; user prefers Android IME.

- REDMAGIC unavailable today; mouse routing and Android 16 IME behavior remain unverified there. See MOUSE-ROUTING-WIP.md.
- Physical-display exclusion and Binder-death cleanup implemented but not exercised in this session.
- Populated-group layout, every launch-profile action, and Start pin independence have not all been re-exercised on device.
- PC clipboard / Japanese input behavior is separate from hiding the Android screen keyboard; it is not proven by these checks.
- Screenshots retained outside the repository; no user screenshots published.
