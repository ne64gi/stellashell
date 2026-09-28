# StellaShell 0.5.3 verification

- Display name: StellaShell; versionCode 8 / versionName 0.5.3.
- Application ID, Java/AIDL namespace, task affinity and internal intent actions: `net.fuyumori.stellashell`.
- This is a separate installation from SOG Desktop. Existing settings and widgets are not automatically migrated; grant overlay and Shizuku access to the new app.
- Container build: JDK 17 / Android SDK 35 / Gradle 8.11.1.
- `testDebugUnitTest lintDebug assembleDebug`: successful.
- Unit tests: 36; failures/errors: 0. Lint: 0 errors, 15 warnings.
- Debug APK SHA-256: `ecc940d1e427c107a1ea89dd8013430e844f50418e32d45a8b4da756ae3d4cf8`.
- Gradle Wrapper added with pinned distribution checksum. APKs, signing keys and local build outputs are excluded from Git.
- Earlier verification documents refer to the old application identity; their device behavior evidence is historical, not a claim of full UI revalidation after renaming.
- Installed successfully on Sony SOG06 and REDMAGIC NX809J; both package-manager reports confirm versionCode 8 / versionName 0.5.3 under the new ID. Old installations/data retained.
- REDMAGIC physical monitor detected as external display 9 (JAPANNEXT, 1920×1080, approximately 60 Hz, state ON). Detection alone does not verify desktop window operation on that monitor.
