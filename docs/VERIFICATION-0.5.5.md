# StellaShell 0.5.5 verification

## Changes

- Searchable widget picker: provider label, application label and package; Unicode NFKC + case normalization, whitespace-separated AND terms, clear and empty states. Selection maps directly to the filtered provider object.
- Battery percentage and external-power marker, low-battery tint; battery broadcasts update the existing text view without rebuilding the taskbar. Collapsed taskbar retains battery status.
- Display-scoped Back via Shizuku. The backend validates an available, public external display and executes a fixed `input -d DISPLAY keyevent 4` command. Display 0, missing/negative targets and private displays are excluded; there is no phone-display fallback.
- Desktop tools and settings live inside Start; desktop corner menu removed, blank-area context menu retained.
- Custom groups with assignment/rename/delete, launcher-only hiding and restoration. Optional bulk hiding of home launchers. No package disabling/uninstalling or removal of existing pins, shortcuts or running tasks.
- Windows/Super key mappings deferred at the user's request.

## Build and automated checks

- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`: successful.
- 41 unit tests, 0 failures/errors; includes explicit external-display Back routing and rejection tests.
- Lint: 0 errors, 19 warnings.
- English/Japanese resource key and placeholder parity passed.
- Both Sony SOG06 / Android 14 and REDMAGIC NX809J / Android 16 passed Android instrumentation: rendering/input scaling, bounded wallpaper decoding, locales, and isolated app-organization preferences (assignment, rename, delete, hide/unhide and visibility preservation).
- Instrumentation uses a separate temporary preference file for organization and removes it; user grouping preferences are not overwritten.
- APK SHA-256: `7e3bbef7a57ec8802523718039208929ad816038f84d630d1055eebd232f95fb`.

## REDMAGIC display 10 checks

- Actual toolbar displayed charging/external-power marker and 94%; updated to 95% during testing.
- Start opened with 161 apps, group selector and settings inside the launcher.
- Start → settings → Desktop tools → Add widget opened the searchable picker.
- Query `NERV` produced four matching providers (two home variants, radar and weather), via the provider application's name/package. No widget was allocated during this check.
- Clicking the toolbar Back button closed that external-display dialog. Window dumps before/after showed display 10 focus change from the dialog to the desktop; display 0 remained unfocused. No default-display Back command was sent.
- UI screenshots retained in the local verification archive only, not published with potentially private app contents.

## Limits

- Group storage operations were device-tested in isolation; every drag/input/OEM popup interaction is not exhaustively covered.
- Low-battery color behavior follows the level/plugged state logic; live battery level was not spoofed for this release.
- Protected Android screens may suppress application overlays, including the taskbar; no bypass was introduced.

Final installation: both phones report versionCode 10 / versionName 0.5.5. Temporary test APKs and capture helpers/cache were removed. Sony's disabled desktop state was retained; REDMAGIC was left on its desktop with existing app tasks preserved.
