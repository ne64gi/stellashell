# REDMAGIC Android 16: caption and app surface divergence

## Reproduction (2026-09-28)

NX809J / Android 16 / API 36, installed SOG Desktop 0.5.0 (original code 5).
User dragged Calculator on their scrcpy display 4, then left the screen untouched.
Read-only evidence: `(local evidence archive; not included)/redmagic-follow/`.

- Calculator task 73 is freeform, root task (`parentTaskId=-1`), on display 4.
- Task/configuration bounds: `(404,164)-(1204,771)`, 800×607.
- TaskInfo positionInParent: `(404,164)`.
- SOG caption SurfaceFlinger transform: `(404,132)` (32px above task).
- Calculator SurfaceFlinger transform: `(695,171)`; its input sink has the same stale transform.
- SystemUI ShellTaskOrganizer holds Task 73; Shell transitions are present.
- Bounds-operation logs report requested == actual **configuration bounds**. This is NOT proof of rendered position matching.

The mismatch is therefore confirmed below SOG's caption layout: logical task bounds
and rendered app/input surface position diverge. The precise OEM/framework path
responsible has not yet been established. This is not evidence that the app is
unresizeable, and not merely an optimistic drag-preview offset.

## Candidate APIs and limits

Runtime reflection confirms this framework has `startNewTransition(int, WCT)` and
`applySyncTransaction(WCT, callback)`. It does **not** have the older
`WCT.setBoundsChangeTransaction(WindowContainerToken, Rect)` overload.
The overload accepting `SurfaceControl.Transaction` exists, but requires an
appropriate surface handle; do not take over the global TaskOrganizer to get it.

Prepared bounded probe: display 4 / Calculator task 73 only, freeform guard,
`TRANSIT_CHANGE=6`, bounds offset +1,+1 via `startNewTransition`.
Executed once after explicit user authorization. The API returned without error
and task bounds became `(405,165)-(1205,772)`. However, the pre-operation TaskInfo
reported `isSleeping=true`, and subsequent power dump showed `mWakefulness=Dozing`.
The app surface was not visible; its hidden input sink still reported `(695,171)`.
This is inconclusive for visible rendering, not a successful fix. Awaiting user
wake/unlock; no second mutation authorized or performed. Post-operation evidence:
`activities-transition.txt`, `surfaces-transition.txt`, `power-transition.txt`.
No app patch or deployment has been made for this issue yet.

Reference: AOSP WindowOrganizerController's `startNewTransition` / transition
collection path:
https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/WindowOrganizerController.java

## Follow-up with user-authorized autonomous testing

User subsequently authorized unrestricted test operation while doing other work.
Their original display 4 remained OFF after the phone woke; the user reported a
frozen last frame rather than having intentionally switched it off. This streaming
issue is separate from the bounds/surface mismatch; its cause is not established.
Created our own scrcpy display 5 (1920×1080/160); an external-display-specific
`IPowerManager.wakeUpWithDisplayId` request cleared the display sleep tokens.
No global sleep/timeout settings were changed. Calculator task 73 was brought to
display 5. The user's original display 4 was later no longer present; we did not
kill their scrcpy server.

Controlled comparison on awake display 5:

- Legacy `applyTransaction(setBounds)` requested `(550,230)-(1350,837)`, but the
  rendered surface remained at `(406,166)`.
- `startNewTransition(TRANSIT_CHANGE, setBounds)` requested
  `(350,210)-(1250,910)`; both rendering and input sink matched these bounds.

This isolates the failing path to legacy configuration-only bounds application
with the existing Shell surface owner, and verifies the transition-based remedy
on this framework. It does not establish the exact vendor-internal line of code.

## 0.5.1 patch and verification

- Android API 36+ probes `startNewTransition(int, WCT)` and uses it for bounds
  operations and new freeform profile-window configuration. Older Android keeps
  the existing legacy WCT path. No replacement TaskOrganizer or transition player.
- Missing API falls back to legacy WCT; diagnostics expose capability and selected
  dispatch. A null transition token explicitly reports `legacy-no-player`.
  Invocation failures are surfaced, not blindly replayed as another mutation.
- Shizuku service version incremented to 5 so stale backend code is not reused.
- Diagnostic app-version label now reads installed package metadata.
- Installed on REDMAGIC: versionName 0.5.1 / versionCode 6.
- APK SHA-256: `218d61d657feb61f1b20ea88fd6dcaf917437f6f669622d41b87fc3e32b0d180`.
- `testDebugUnitTest lintDebug assembleDebug`: success; 32 tests, zero failures
  or errors; lint zero errors / 15 warnings.

Actual installed app UI tests, task configuration **and SurfaceFlinger bounds**:

| Operation | Resulting app bounds |
|---|---|
| Title drag (+150,+100) | (500,310)-(1400,1010) |
| Mouse primary-button bottom-right resize | (500,310)-(1500,910) |
| Maximize | (0,32)-(1920,1020) |
| Restore | (500,310)-(1500,910) |
| Left snap | (0,32)-(960,1020) |
| Right snap | (960,32)-(1920,1020) |
| Maximize then restore after snapping | original (500,310)-(1500,910) |

All listed surface bounds matched configuration bounds. Input sink also tracked
the app. Evidence includes `check-actions.py`, per-action dumps,
`drag-prefs.txt`, `mouse-resize.txt`, and `fixed-0.5.1.png`.
During a separate 1.8-second title drag, seven surface samples progressed from
`(500,310)` through `(516,302)`, `(544,288)`, `(585,267)`, `(613,254)`,
`(654,233)`, `(682,219)` while the gesture was still active (`live-drag.json`).
Thus rendering follows during movement, not only after release.
Mouse input was injected with SOURCE_MOUSE/BUTTON_PRIMARY, not a physical mouse.
Sony installation and its original 0.5.0 evidence remain unchanged. REDMAGIC HOME
still resolves to the vendor launcher. Other Android 16 devices remain unverified.

Cleanup: stopped only our exact recording scrcpy client; recording finalized.
Removed our temporary ADB helper jars/screenshots. No external display remained
at cleanup (display 0 only); user should reconnect scrcpy for the installed fix.
Global freeform=1 / force-desktop=0 retained; no commit or publication.
