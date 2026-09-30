# Third-party notices and references

This file distinguishes incorporated code, dependencies, build tools and design
references. StellaShell's original code and bundled helper scripts are licensed
under GPL-3.0-or-later; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
Third-party notices and their original license terms are retained.

## Incorporated code: scrcpy (Apache-2.0)

`PrimaryScreenPower.sync()` contains a modified Android 14 DisplayControl
bootstrap from **scrcpy v3.3.1**, commit
`f01231dff8294fe2c99045a4f9a14b233a71bb86`:
[DisplayControl.java, static initializer](https://github.com/Genymobile/scrcpy/blob/f01231dff8294fe2c99045a4f9a14b233a71bb86/server/src/main/java/com/genymobile/scrcpy/wrappers/DisplayControl.java#L19).

- Copyright (C) 2018 Genymobile
- Copyright (C) 2018-2025 Romain Vimont
- [Full upstream license, included in the APK](app/src/main/assets/licenses/scrcpy-Apache-2.0.txt)
- [StellaShell attribution and modification notice, included in the APK](app/src/main/assets/licenses/scrcpy-NOTICE.txt)

Modified for StellaShell on 2026-09-29: `System.getenv`, lazy initialization,
primary-only display selection, session validation, Binder ownership, watchdog,
wake lock and restoration. The adapted block is identified in the source file.
The upstream release has no root NOTICE file; the notice above is our own
incorporation notice, not a claimed upstream document.

scrcpy is also used externally for virtual displays and device control during
development. Its complete client/server and native dependencies are not bundled
in the StellaShell APK; that does **not** mean no scrcpy-derived code is included.

## Runtime and development dependencies

- Shizuku API/provider 13.1.5, RikkaApps, MIT:
  [license and copyright included in the APK](app/src/main/assets/licenses/Shizuku-API-MIT.txt).
- AndroidX annotation 1.3.0, compile-only, Apache-2.0:
  [license text](app/src/main/assets/licenses/Apache-2.0.txt).
- JUnit 4.13.2, test-only, EPL-1.0; not included in the application APK.

This is not an exhaustive transitive-dependency SBOM.

## Build infrastructure: Gradle wrapper (Apache-2.0)

Before remediation, `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar`
matched the common Gradle wrapper assets in the pinned Taskbar tree below
(allowing line-ending normalization in the batch script, commit `dfdf2cb`).
The batch script now also carries an explicit StellaShell modification notice. These are build infrastructure,
not Taskbar application code, and are not bundled in the APK. Script copyright
and license headers are retained. See the [Apache-2.0 text](app/src/main/assets/licenses/Apache-2.0.txt).
Hash equality does not identify which distribution supplied the common assets.

## Design/API references and historical provenance

- [Taskbar](https://github.com/farmerbb/Taskbar/tree/e2a00b9a3f3adda069059abb9aebcb8b4e18954f),
  Braden Farmer / contributors, Apache-2.0. External-home, freeform launch and
  taskbar/widget-host concepts were reviewed. Its [NOTICE](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/NOTICE)
  also credits AOSP. The audit did not identify Taskbar-specific application
  code incorporation; the shared wrapper assets are recorded separately above.
- [Dextop](https://github.com/NarYuki/Dextop/tree/b3ecbbd04ba89ebf5774a609f299e16ca3486458),
  NarYuki / contributors, GPL-3.0-or-later. Architecture, capabilities and
  framework API use were reviewed. A historical task-reflection helper cluster
  was flagged for provenance review (F-02). The current implementation replaces
  it with an Android API-contract-based snapshot boundary; see
  [implementation provenance](docs/TASK-API-PROVENANCE.md).
  This replacement is not a declaration that historical versions were
  independently authored, nor a clean-room certification.
- Android Open Source Project, Apache-2.0: framework declarations inform the
  task snapshot implementation. Exact versioned references and the distinction
  between API use and code incorporation are recorded in the provenance note.

The current shell does not use Dextop's Flutter runtime or topology backend.
There is intentionally no blanket assertion that all source in all historical
versions was independently authored. See the [original audit](docs/LICENSE-AUDIT-2026-09-29.md)
and [remediation/re-audit](docs/LICENSE-REMEDIATION-2026-09-29.md).
Acknowledgement does not imply endorsement or affiliation.

## Optional Windows session launcher

`tools/windows/start-stellashell.ps1` and its JSON example are StellaShell helper
files under GPL-3.0-or-later. They invoke separately installed scrcpy and ADB.
No Windows executable, scrcpy server, DLL or Android platform-tools binary is
redistributed in this tools directory. Obtain these from their official
distributions and retain the licenses/notices supplied with those distributions.
