# Compact Workspace — experimental prototype

Opt in while the desktop is stopped: **Compact workspace**, then start on the
phone. In Compact presentation ordinary launches / taskbar selection promote a
single Primary (work-area-maximized freeform task). Context menus provide explicit
Primary / Floating actions. Previous Primary is minimized, not terminated.
Desktop presentation retains ordinary launch profiles.

The setup screen has a manual external / phone transfer button. A separate,
default-off checkbox transfers on a newly connected public external display.
Both physical and scrcpy virtual displays use the same display listener. On a
successful external transfer the phone opens SetupActivity.

## Scope and limitations

- Only tasks launched through Stella in the current process/session are tracked.
  Task IDs are paired with component names; unrelated phone apps are not adopted.
- Root-task transfer uses the framework `moveRootTaskToDisplay` API. Grouped roots
  are rejected rather than moving unrelated children.
- Transfer does not restart an app. Density/size changes can recreate Activities;
  application state retention remains the application's responsibility.
- Phone bounds are retained for the return trip; destination bounds are clamped
  to its Work Area. The remembered Primary is promoted again on return.
- For scrcpy, use `--no-vd-destroy-content` (now included in the Windows launcher)
  so Android moves tasks to the phone instead of destroying them.
- Unplug recovery only handles tasks Android has retained/moved to the phone.
  Some virtual-display removal policies destroy tasks; destroyed tasks cannot be
  recovered by this implementation. Explicitly return before disconnecting when
  preserving state is important.
- Session state is not a persistent workspace: process death, service restart,
  rapid repeated connection changes, OEM transition failures, and more than one
  external display still need hardening. Automatic mode is experimental/off.
- Layout migration of desktop shortcuts and widgets is not implemented. Existing
  desktop preferences are reused; this is task migration, not pixel mirroring.
- No guarantee of uninterrupted media/game rendering or unsaved editor state.

## Initial device evidence

SOG06 / Android 14: calculator task 656 moved from display 0 to a temporary scrcpy
1920x1080/160 display 7, then back to display 0 with the same task ID. Entered `314`
on the external screen and confirmed it remained on return. This establishes the
framework route, not full product/automatic/physical-monitor validation. Private
screenshots and dumps are kept outside the repository.

## Integrated prototype verification (2026-09-30)

- Built and installed on SOG06 only. 53 JVM tests pass; lint 0 errors / 52 warnings.
- Selected existing calculator task 656 from the Compact dock: Primary bounds
  `(0,147)-(1096,2434)`, excluding status/navigation and the caption.
- Auto connection to scrcpy display 9 transferred the same task to
  `(0,32)-(1096,1020)`. Screenshot confirms caption/content and `314` intact.
  Phone switched to Setup. Fixed initial ordering that left the app behind Home;
  refocus now runs after shell creation.
- Closed that scrcpy process with `--no-vd-destroy-content`: Android moved task
  656 to display 0; Stella restored Primary/work-area bounds. Screenshot again
  confirms `314`, same task and aligned caption.
- Long-press dock → Floating changed the same task to
  `(182,528)-(914,2053)` without relaunch.
- Restored Sony's previous stopped/external-mode selection, pins and presentation
  preference after testing. Temporary capture files and test connection cleaned up.
- Final APK SHA256:
  `86ac02da19b40c98c21efa4c5e4b066fe34cd5e7c37d22402b7debe2b88c1f04`.
- REDMAGIC not updated/tested for this feature. Physical USB connection, multiple
  apps/Primary replacement, failure rollback, process death, rapid reconnect and
  Windows PowerShell execution remain unverified. This is an opt-in prototype,
  not a general guarantee of seamless state preservation.
