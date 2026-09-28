# StellaShell 0.5.6 — opt-in device-screen desktop

Date: 2026-09-28. Version code: 11.

## Scope

- Explicit device-screen mode routes the session to Display 0. Default routing remains external-only, with no fallback to the phone after disconnection.
- Shizuku user service defaults to rejecting Display 0. The serialized client authorizes it from the active session before each operation, and stop revokes authorization.
- Default HOME is not changed. Stop removes overlays and opens the existing HOME in device-screen mode. Switching modes stops the old session without leaving settings.
- Start tools include Stop desktop. Narrow taskbars use a compact Start button. Main-display dock respects navigation-bar insets.
- Display-change callbacks rebuild overlays only when size, density or rotation changes. The initial implementation rebuilt overlays on REDMAGIC adaptive-refresh events, losing taps; the final build fixes this.

## Automated checks

Container: SDK 35 / JDK 17, 3 GiB memory, 2 CPUs.

`testDebugUnitTest lintDebug assembleDebug`: PASS. 43 unit tests, 0 failures. Lint: 0 errors, 21 warnings.

New policy checks cover explicit Display 0 selection, missing-display rejection, default external-only routing, command opt-in/revocation and negative-ID rejection. Existing external-routing tests still pass.

APK SHA-256:

`2466a1c9a82989834482fda381df548ec070ce535690689bf9082c23ecd1b9c3`

## REDMAGIC NX809J / Android 16

Verified with the user-unlocked primary display, via actual setup/menu UI and ADB dumpsys/screenshots:

1. Enable device-screen mode and start: desktop and dock on Display 0; Shizuku connected.
2. Start menu accepts taps and search after the adaptive-refresh fix.
3. Launch Google Calculator from Start: task 118, Display 0, freeform.
4. Title-bar drag: content and caption move together; resulting bounds `(308,703)-(1108,1418)`.
5. Bottom-right resize: content visibly reflows; resulting bounds `(308,703)-(1182,1842)`.
6. Close the test-created calculator using its caption. Existing user tasks are not closed.
7. Create a temporary scrcpy public virtual display while primary mode runs: dock stays on Display 0.
8. Start → settings → Stop desktop: dock removed; existing REDMAGIC Quickstep HOME visible. Default HOME unchanged.
9. Switch to external mode and start: dock, DesktopActivity and functional Start menu on scrcpy Display 12 with `--no-vd-system-decorations`.
10. Stop the test session and terminate only the test-owned scrcpy client. Restore external mode / stopped state, as found before testing.

Local raw evidence: `/home/fuyumori/android-dev/verification/stellashell-0.5.6/` (not published; screenshots and dumps may include personal data).

## Installation and limits

- Final APK installed successfully on both SOG06 (`.4`) and NX809J (`.6`); package reports 0.5.6 / code 11.
- SOG06 primary-mode UI/window operations are **not yet verified**; its stopped session was preserved.
- Primary/external simultaneous desktops are not supported. Per-OEM freeform limitations still apply.
- Rotation, all window operations, all apps and widget behavior on the primary display are not exhaustively tested.
- Standard HOME and global freeform/desktop settings were not changed during these checks.
