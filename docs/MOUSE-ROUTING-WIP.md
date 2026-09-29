# Mouse routing — paused at user request (2026-09-28)

User is starting work; resume actual device checks only when they ask. No commit/push of this WIP yet.

## Confirmed

- REDMAGIC NX809J `.6:5555`, USB external Display 13, ERGO M575 Bluetooth mouse.
- InputReader sees the mouse, but unassigned pointer controller chooses Display 0. This is not a StellaShell click-handler failure.
- Shell UID has ASSOCIATE_INPUT_DEVICE_TO_DISPLAY permission and descriptor association APIs.
- Probe `addUniqueIdAssociationByDescriptor(mouse descriptor, display unique ID)` routes to Display 13. User explicitly confirmed both pointer and Start click work.
- Do not use port association here: the Bluetooth keyboard and mouse share the same `Location`, so it would affect the keyboard too.
- Remove-by-descriptor returns successfully but leaves InputReader's cached descriptor display association on this framework. Probe verified fallback: add empty unique ID, wait for native reconfigure, remove. This returns associatedDisplayId to -1, pointer controller to 0, ranges to 1216x2688. Touchscreen association remains Display 13.
- Related AOSP source exhibits the missing descriptor-cache reset: https://android.git.googlesource.com/platform/frameworks/native/+/fcbbe1795996853fb857be9e7527340623c6464b/services/inputflinger/reader/InputDevice.cpp

## Implementation in working tree

0.5.7 / code 12, UserService version 10. New MouseRouting backend probes API support, routes only unassigned external nonvirtual mouse-only devices, skips keyboards/touchscreens/touchpads and existing associations, and tracks only its own mappings. App Binder death recipient releases mappings; stop/display changes trigger cleanup. Bridge syncs alongside existing serialized calls, so hotplug is picked up by task polling. Mouse diagnostics are exposed in setup diagnostics. Unsupported routing does not block other desktop operations.

Latest uninstalled revision adds verified-read cleanup with a bounded empty-ID fallback. It reads InputDevice directly from IInputManager to avoid client-cache lag. This integration is NOT yet device-validated.

## Device state at pause

- REDMAGIC has the FIRST provisional 0.5.7 build installed (without the final cleanup fallback). It was force-stopped for isolated probe testing and has NOT been restarted afterward. Prefs may still have enabled=true, primary_mode=false, preferred_display=13.
- Probe-owned mapping was cleared with the fallback. Last dump confirms pointer on Display 0 / associatedDisplayId=-1. No global desktop/home setting was changed.
- Install the final rebuilt APK before restarting/testing StellaShell: the provisional installed build lacks the cleanup fix.
- Sony `.4` still has prior 0.5.6; not updated in this mouse-routing task.
- Temporary `/data/local/tmp/stella-input-probe.jar` remains on REDMAGIC; remove after resumed tests. No persistent probe process.
- Physical external screen belongs to the user; do not disconnect it. User permitted testing previously, but has now paused actual checks.

## Resume

1. Confirm user is ready and current mouse/display IDs (previous mouse id 21; IDs change on reconnect).
2. Review MouseRouting cleanup/lease behavior, confirm latest build result, install rebuilt APK on REDMAGIC.
3. Start external desktop via normal UI or explicit MAIN/LAUNCHER SetupActivity on the actual external display. Verify mouse_diagnostics reports mice=1 and actual pointer display matches.
4. Verify stop restores default mouse routing, then start reroutes it. Check actual device data, not just Binder success.
5. Verify app-process death cleanup and external unplug cleanup without disrupting unrelated tasks; reconnect/hotplug as user permits.
6. Verify keyboard and touchscreen associations unchanged. Probe/fail gracefully on SOG06 before broadening compatibility claims.
7. Finish focused checks, update both devices as authorized, document evidence, commit/push only completed work. Keep unverified limitations explicit.

Local evidence/probe source: `/home/fuyumori/android-dev/verification/mouse-routing/`. Do not publish raw dumps containing device identifiers.

Build at pause: `testDebugUnitTest lintDebug assembleDebug` PASSED (40s). Final cleanup-fallback APK is built but NOT installed. SHA-256: `baf068697d02896370983d60686c79fece5685cecb31ccccffee1e47c2177763`.

## Resumed 2026-09-28 evening

User authorized continuation on `.6`, and requested a display/input reset. Current physical display is 16, not the previous 13. `dumpsys display` shows only the built-in and HDMI device; increasing IDs are not accumulated active displays.

New physical mouse identifies as Logitech M336/M337/M535 with InputDevice sources KEYBOARD | MOUSE, keyboardType=1 (non-alphabetic). Prior filter excluded any KEYBOARD source. Changed to exclude only alphabetic keyboards, retaining touchscreen/touchpad/virtual exclusions.

Added explicit Reset display and mouse connection in Setup and Start tools. It closes the shell overlays/task poller, releases its owned mouse mappings, clears the stale preferred display ID, then re-detects and starts the current target. It does not renumber Android IDs, delete user tasks/data/widgets, or release other apps' virtual displays. UserService version now 11 to replace provisional version 10 backend.

User was asked to unlock; last check still showed secure keyguard / Dozing. Do not bypass. Latest device UI verification still pending at the time of this note.

Latest resumed build: `testDebugUnitTest lintDebug assembleDebug` passed; final UserService-version change also passed `lintDebug assembleDebug`. Updated 0.5.7 / code 12 installed on REDMAGIC successfully. SHA-256: `7fd405283a8309779807616aa26be8c3bc5d1629f1d9b6434e5cfd5116e13fd4`. After install DockService is stopped (no service); secure keyguard still showing. App start/stop/reset, mouse lease cleanup and actual clicks await unlock. Sony is absent from adb devices. Do not claim resumed integration verified or commit/publish it as complete yet.

## 2026-09-29 checkpoint

User explicitly requested committing the current 0.5.8 prototype before starting 0.6.
The historical no-commit notes above are superseded by that request. REDMAGIC
integration verification remains pending; do not describe it as verified.
Sony now runs 0.5.8 (UserService version 12); unsupported descriptor association
is diagnosed and does not prevent desktop operation. See VERIFICATION-0.5.8.md.
