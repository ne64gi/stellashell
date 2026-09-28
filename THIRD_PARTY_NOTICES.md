# Third-party notices and references

Runtime dependencies: Shizuku API/provider 13.1.5 (RikkaApps, MIT); see `app/src/main/assets/licenses/Shizuku-API-MIT.txt`. AndroidX annotation 1.3.0 is compile-only (Apache-2.0). JUnit 4.13.2 is test-only and is not shipped in the APK.

Design references (source not incorporated):
- Taskbar, Braden Farmer, Apache-2.0: https://github.com/farmerbb/Taskbar/tree/e2a00b9a3f3adda069059abb9aebcb8b4e18954f
- Dextop, NarYuki, GPL-3.0-or-later: https://github.com/NarYuki/Dextop/tree/b3ecbbd04ba89ebf5774a609f299e16ca3486458

The external-home/overlay architecture and capability/restore concerns were reviewed. No Taskbar/Dextop source, resources, Flutter runtime, virtual-display backend, or OEM-specific code is included in this implementation.
