# Idle work budget

StellaShell may keep its process, foreground service and edge overlay resident.
Their presence must not cause recurring task queries, geometry sampling or healthy
lease checks. **When nothing changes, wait for an event.** Memory residency and
foreground-service duration are not CPU-time measurements.

## Owners and wake-up reasons

| Owner | Work allowed |
| --- | --- |
| `ShellTaskEvents` | A demand-scoped Shizuku task-stack listener; one 100 ms debounce after events, never a self-rescheduling timer. The last subscriber removes the remote listener and screen/display observers. |
| `PhoneRunningTasks` | One read when opened, then connection/task/wake events or explicit commands. Hidden/closed/sleeping feeds do not query; late sleeping/closed results do not publish. In-flight invalidations coalesce into one follow-up. |
| `TaskSession` | Selected-display events and explicit commands; off/dozing/disconnected displays do not query. Resuming reads fresh state. Unchanged task/stack/capability/role state does not redraw captions or bars. |
| `WorkAreaObserver` | Insets, display-configuration events and explicit setting changes. No 750 ms metrics sampler. Insets remain display-scoped; IME and rotation still invalidate layout. |
| `Bridge` | Read-model/event calls do not run mouse/IME/pin maintenance. Connection, enabled/primary-mode/IME-setting changes, display selection and explicit commands maintain those leases. Read policy still uses the current primary-mode owner. |
| `MouseRouting` | Display/input events and client death. Healthy ownership has no watchdog, including a session with zero mice. Failed native release retains owned descriptor debt and one backoff retry; event bursts cannot bypass that deadline. |
| `PrimaryScreenPower` | Display/screen/keyguard events and client death. No healthy watchdog. The user's explicit external-desktop/main-panel-off option intentionally holds a bright wake lock while active; terminal events release it. Failed restoration retains the exact token and one retry. |
| HOME / Hub / notifications | HOME closes its menu on stop; stopped desktop visual updates are deferred. Hub listens to widgets only on its visible, awake widget page. No notification-observer dispatch is queued when nobody subscribes. |

Minute-only visible clocks, the visible Quick Settings refresh, finite launch/mode
confirmation waits, connection timeouts and retries of **known failed cleanup**
are not unattended idle task monitoring. Do not remove recovery ownership or
replace failed cleanup with a false success just to achieve zero scheduled work.

## Framework boundary

Shizuku user-service version **33** adds task-change invalidation callbacks while
preserving existing Binder descriptor and transaction IDs (new IDs 24/25).
`TaskChangeMonitor` resolves the installed framework's transaction names; it
does not hard-code Android-version-dependent callback numbers or decode thumbnail
and task payloads. Mutations still validate fresh task ID + component + display.
No task organizer or transition player is registered.

API references: [AOSP task-stack events](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/core/java/android/app/ITaskStackListener.aidl)
and [registration contract](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/core/java/android/app/IActivityTaskManager.aidl).
Unsupported registration does not enable an infinite fallback poll; UI open,
connection and screen events retain finite refresh opportunities.

## Verification

- JVM tests and ownership/module fitness guards remain required.
- Android instrumentation `-e idle_event_checks true` checks idle/sleep no-read,
  event refresh, in-flight coalescing, late callbacks and semantic-state comparison;
  it also checks installed framework invalidation and removal using an exact,
  disposable test task. No personal task is moved or killed.
- Measure before/after CPU with two PID + process-start-epoch endpoints over a
  stated interval; count main and privileged processes separately. Do not poll
  agent status or save personal screen/notification/task dumps.
- Inspect app-UID battery CPU and active app/tag wake locks, alarms and registered
  jobs separately. UID-wide/global data must not be attributed to Stella; the
  shell-UID Shizuku process is not exclusively represented by the app UID.
- Keep selection, persistent preferences, global desktop/freeform settings and
  phone geometry unchanged, and remove only owned fixtures.

A short CPU check cannot prove a six-hour battery improvement or explain a
reported percentage. Long sleep/wake, physical input and main-panel restoration
remain distinct verification scopes.
