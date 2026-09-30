# Task snapshot API provenance — 2026-09-29

## Why this boundary exists

F-02 of the historical license audit flagged a group of reflection helpers in
TaskBackend. The implementation record confirms that Dextop helper bodies were
read before those helpers were written. The current owner requested their
replacement, separately from choosing StellaShell's own license.

The replacement is **not clean-room development**: the same development context
has seen both implementations. It replaces the identified helper cluster; it
does not establish a new provenance for old commits or clear legal questions
about historical distributions. No git history was erased.

## Contract chosen before implementing the replacement

- Return an explicit task snapshot, not arbitrary reflected objects to UI/policy.
- Include task ID, display ID, user, component, activity type, windowing mode,
  visibility/focus, bounds, and the opaque transaction token.
- Copy mutable geometry/child arrays when reading; never modify framework data.
- Resolve a fixed schema at the framework boundary. Do not provide generic
  `field(object,name)`/`number`/`configuration` helpers.
- Use TaskInfo's own getters where available; older-framework fallback is limited
  to the Android WindowConfiguration API, with no OEM-specific field guessing.
- Enumeration must remain display-scoped. Policy still validates the display,
  user and task before each mutation. Recency order is not treated as z-order.
- Root hierarchy lookup is optional: failure must retain the task list and mark
  stacking as unreliable. It does not register a new organizer or transition player.

## Primary sources used

The API declarations were checked against these fixed AOSP releases:

- [Android 14 TaskInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/core/java/android/app/TaskInfo.java):
  public task identity/component fields, hidden display/user/visibility fields,
  getConfiguration/getToken/getWindowingMode/getActivityType signatures.
- [Android 14 IActivityTaskManager](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/core/java/android/app/IActivityTaskManager.aidl):
  getTasks and root hierarchy query contracts.
- [Android 14 Configuration](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/core/java/android/content/res/Configuration.java)
  and [WindowConfiguration](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/core/java/android/app/WindowConfiguration.java):
  window configuration type and bounds/mode/activity getters.
- [Android 14 ActivityTaskManager.RootTaskInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/core/java/android/app/ActivityTaskManager.java):
  RootTaskInfo inheritance and childTaskIds.
- [Android 11 TaskInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/app/TaskInfo.java)
  and [Android 16 TaskInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/app/TaskInfo.java):
  Android 11 lacks the TaskInfo window-mode/activity-type convenience getters.

AOSP is Apache-2.0 licensed. These are API-contract references; the new adapter
is not a copied AOSP class. Identical API names, signatures and required
configuration access are intentional interoperability requirements.

## Implementation map

`FrameworkTaskAccess` owns cached reflection, framework list decoding and optional
root decoding. Its `Entry` and `Root` are detached snapshots. `TaskBackend`
consumes these records. The former field/number/configuration/mode/bounds/type/
component helper group is removed, and enumeration is implemented at this new
boundary. No Dextop helper is retained as a fallback.

The existing WCT operations, launch-profile logic (including fullscreen-to-window
recovery), display/user filtering and root/leaf ordering are retained. They were
not classified as suspect helper code. API-contract overlap with Dextop will
remain: the same Android task API necessarily has the same method names.

## Claims intentionally not made

- Not proof that a previous version was independently authored.
- Not a claim that every similar expression in all projects is copyright-free.
- Not a full legal clearance or a reason to choose GPL for StellaShell.
- Not a claim of verified behavior on every supported SDK/OEM. See the remediation
  report for actual build/device evidence and remaining limits.
