---
paths:
  - "**/com/ezhou/rotblock/service/**"
  - "**/com/ezhou/rotblock/block/TimerHud*"
  - "**/com/ezhou/rotblock/workout/**"
  - "**/res/xml/accessibility_service_config.xml"
---

## The second requirement: it has to be cheap to leave on

This is an always-on accessibility service, and the failure mode is not a crash — it is the user
spotting it in the battery screen and switching it off. A blocker that gets uninstalled blocks
nothing, so battery is a correctness requirement here, ranking directly below "never fails open"
and above everything else.

The two rank in one direction only: **when battery and enforcement conflict, enforcement wins, and
the cost gets written down rather than quietly paid.** `flagIncludeNotImportantViews` is the live
example — it inflates every tree read, and it stays, because feed containers are exactly what apps
mark not-important.

What actually costs power in an app like this, in order:

1. **Being asked about every event.** `res/xml/accessibility_service_config.xml` is most of the
   budget: event types, flags, and `notificationTimeout` (the framework's own coalescing window).
   Never add a flag without checking something calls the API it enables — `flagRetrieveInteractiveWindows`
   sat there making the system compute a window list on every event from every app, for a
   `getWindows()` call that did not exist.
2. **Reading the node tree.** Every `findAccessibilityNodeInfosByViewId` is an IPC plus a traversal
   *in the other app's process*. Scrolling emits content-change events without pause, so reads must
   be both debounced (`INSPECT_DEBOUNCE_MS`) and rate-limited (`MIN_INSPECT_INTERVAL_MS`), and
   skipped entirely when one of our own screens is on top.
3. **The ticker.** It is armed for the soonest deadline that matters — quota exhaustion, a pause
   elapsing, a scheduled window opening or closing — clamped to `MIN_TICK_MS..MAX_TICK_MS`, never a
   flat one-second heartbeat. The clamp is load-bearing in *both* directions: the ceiling keeps the
   ledger flushing and lets a stuck `Interstitial` heal, and never scheduling past the deadline is
   what stops a cheaper tick from becoming a fail-open. The ceiling lifts to `MAX_IDLE_TICK_MS` on
   a `Decision.Allow` only, where neither reason applies — no session is open and an interstitial
   clearing calls back directly — so sitting in an app outside its scheduled hours costs one
   wakeup rather than 240 an hour.
4. **The countdown ring**, on by default. `TimerHud` repaints once a second, which is the only
   repeating callback here that is not armed for a deadline. It is allowed because of what it is
   gated on: screen on, an app in front, and a session actually open — the same gate as the ledger,
   so it cannot outlive one. Anything that decouples it from the open session (a ring that lingers,
   a ring that ticks while blocked) makes it both a battery cost and a lie. Dragging it costs
   nothing beyond the touch events Android already dispatches for the gesture in progress, and the
   dismiss-target window that appears during a drag is created and torn down with the finger that
   is down — there is no new repeating callback and nothing that outlives one gesture. The
   notification form costs nothing at all: the countdown is the notification's own chronometer, so
   nothing of ours runs while it counts.

Ground rules, which are mostly about what is absent:

- **No alarms, no `WorkManager`, no wakelocks, no foreground service, no boot receiver.** Nothing
  in this app may wake a sleeping phone. It is a direct consequence of the deadline invariant: if
  state is only ever read and folded, there is nothing that *must* run at a particular moment, so
  every "I should schedule a job for when this expires" impulse is both a battery regression and an
  invariant regression. Opportunistic wins.

  `WorkoutListener` is a second always-bound service and does not breach this. It never wakes the
  phone: the framework delivers a callback when a notification is posted or removed, which is
  something already happening for its own reasons, exactly as accessibility events are. It polls
  nothing, arms nothing, and holds no state that has to be refreshed.

  The ticker may now run with **no target held**, which is new. A workout greys an app no rule
  covers, so the cap lapsing and a reminder falling due both change what is on screen while nothing
  at all is being enforced — and neither fires an event. It is armed for the soonest of those and
  clamped to `MAX_IDLE_TICK_MS`, so a workout costs one wakeup rather than 240 an hour, and
  screen-off still stops it. Anything that arms the no-target ticker at `MAX_TICK_MS` instead has
  turned a whole workout into a 15-second heartbeat.

  The one repeating callback that is not armed for a deadline is the diagnostic capture's poll for a
  readable window, and it earns the exception on every count that matters: a user just tapped a
  Quick Settings tile, so the screen is on by construction; it is bounded by a deadline like
  everything else here; it runs once per capture rather than on a schedule; and it exists to notice
  the shade closing, which emits no accessibility event of its own. `requestInspect` cannot stand in
  for it, because that channel only queues reads for browsers and feed apps and a capture has to
  work for a plain app target too.
- All work is gated on the screen being on and a policy target being in the foreground. Screen-off
  closes the session and stops the ticker.
- No network permission, so no radio wakeups at all.

Audit it on device with `adb shell dumpsys batterystats --charged com.ezhou.rotblock` and Settings →
Battery usage. The number to watch is background CPU time with the screen off; it should be zero.
