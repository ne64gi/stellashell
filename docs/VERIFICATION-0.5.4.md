# StellaShell 0.5.4 verification

## Changes

- Per-widget normal, forced-resize and proportional-scaling modes, persisted with logical render size.
- Initial/recommended widget dimensions include framework host padding. Scale mode reports the logical size to providers, not the scaled viewport size. Android 12+ receives the modern size-list option.
- User-selected wallpaper through Android document selection, normalized off the UI thread to at most 2048 pixels on its longest edge, atomically saved in app-private storage. Fill/fit and built-in gradients remain available.
- Original sllsll/star adaptive icon, including monochrome.
- English default and Japanese UI resources, Android 13+ app-language declaration, localized known backend errors at the UI boundary. Provider names and raw framework diagnostics are not translated.
- Requirements and Shizuku setup documented independently of optional Termux self-ADB scripts. Shell backend has no new ADB-management responsibility.

## Checks

- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` built in the resource-limited Android container.
- 40 unit tests, no failures/errors.
- Resource keys and format placeholders match between English/Japanese.
- Device instrumentation (`WidgetRenderInstrumentation`) checks real Android uniform rendering and inverse input coordinates at enlargement, reduction and 1:1; wallpaper decode bounds/aspect and invalid-image rejection; English/Japanese/unsupported-language fallback, plurals, formatted labels and known backend error translation.
- The test runner does not change system language or widget allocations. Its temporary APK and preview cache are removed afterward.
- `bash -n` passed for both optional shell tools. Daily helper mocked checks passed for existing 5555, recovery, false-success/offline rejection and invalid-port rejection. No script was run against live adbd in this release.

## Limits

- NERV's own rendered content and data updates have not been revalidated in this release. Forced resizing requests reflow but cannot make an unresponsive provider reflow; proportional mode offers a separate logical render size as a workaround.
- Instrumentation verifies the wallpaper decoder, not the full system document-picker/cloud-provider interaction or every image format.
- Window-manager behavior is unchanged from the prior verified backend, apart from localization of known errors and a Shizuku user-service version refresh.

## API references

- [Android AppWidgetHostView](https://developer.android.com/reference/android/appwidget/AppWidgetHostView): size guidance and framework padding.
- [Android framework host implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/appwidget/AppWidgetHostView.java): provider size updates subtract host padding; proportional rendering therefore keeps the logical host dimensions stable.

## Final build

- Lint: 0 errors, 17 warnings (including the intentionally API-33-only locale declaration).
- APK SHA-256: `b7b23df68de2c81642b2354662b47997e832f2601b2b6fd8ad7aff1136149817`.
- Final APK installed on both Sony SOG06 / Android 14 and REDMAGIC NX809J / Android 16. Both report versionCode 9 / versionName 0.5.4.
- All listed device instrumentation checks passed on both phones, including locale checks. Test APKs and icon-preview cache removed; Sony external desktop reopened and REDMAGIC setup screen restored. Existing user preferences were not reset.
