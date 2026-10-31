---
paths:
  - "**/com/ezhou/rotblock/quota/**"
  - "**/com/ezhou/rotblock/service/**"
---

## Why the timer under-counted

Worth knowing before "the quota feels wrong" gets debugged from scratch again. The arithmetic in
`RollingQuota` was never the problem — checked against real device rows, the block landed at
180.002s of a 180s quota. What went missing was time that never reached the ledger:

- **A session is inserted with `endMs == startMs`** and only becomes non-zero at the first flush.
  Kill the process before that and the visit is stored as **0.0 seconds** — observed twice in real
  device data. That is what "the timer isn't tracking properly" looks like, and One UI
  kills sideloaded apps freely, so the flush interval is the entire exposure. It is 5s.
- **The flush must not ride solely on the tick.** `evaluateAndEnforce` touches the ledger itself,
  because a page emitting events several times a second re-arms the tick faster than it can come
  due, and a tick that never fires is a session that never flushes.
- **`ACTION_SCREEN_ON` must not resume accrual** — only `ACTION_USER_PRESENT`. The lock screen is
  system UI, which is ignored, so on SCREEN_ON `currentPackage` is still whatever sits underneath
  it and the target gets charged for every glance at the clock.
- **Re-entering a browser must restore the target, not re-derive it.** An app switch clears the
  target, and coming back asks the question again — but the page you left is scrolled, so the
  address bar is hidden, so the read is `Unreadable` and the target stays at what the switch left
  it: nothing. Reddit then sits on screen filling no quota, heading for no block, with no symptom
  whatsoever. `VisitMemory` holds the last target *a read established* per package and
  `onForegroundChanged` restores it; the next successful read confirms or replaces it within about
  a second. Only a read may write that memory, so it can never invent a target. Every trip out of
  the browser and back used to reset the count: the pause screen, the home screen, a notification.

Direction still matters more than precision: losing accrued time gives quota back, over-charging
takes it away. Where the two conflict, lose the time. That trade is about *rounding*, though — it
never justifies dropping a target, which loses all of the time rather than some of it.

When usage looks wrong, read Setup → **Right now** before theorising. `EnforcementStatus` publishes
what the service currently believes — is it bound, what package is in front, which target is being
enforced, what is being charged, what the last screen read actually returned — plus a collapsing
trail of the last few decisions. Every failure this app has had presents as "the app is open and
nothing happens", and that panel is the difference between reading it off and guessing.

## When the enforcement loop runs away

`service/EvaluationRate.kt` watches how fast `evaluateAndEnforce` is being called, because on
device it was once called sixty-two times a second for 102 seconds at a stretch.

Found by reading `usage_sessions` off a phone: **88% of every row in the database came from 5.3
minutes** spread over three days. `Interstitial.isShowing` was flipping at about 30Hz, so the
`Decision.Allowed` branch alternated between closing and opening a ledger session — an UPDATE and
an INSERT per cycle, sessions 26ms long, and `sessionsFor` rebuilding a list that grew with every
one of them. No crash, no block, no symptom; `BLOCK_SHOWN` never fired once during any of it.

The trigger is still unknown, and it is state-dependent rather than a regression: it started and
stopped with no commit at either boundary. What stopped it being diagnosable is the part worth
remembering — **a storm destroys the evidence for itself.** The `EnforcementStatus` trail holds 300
entries, a storm writes two per cycle, and because the two texts *alternate* they never collapse
into a repeat count. Six seconds of storming and the buffer holds nothing else, so the one capture
taken during one contained only the storm and none of the minutes before it.

So the detector writes an `EVALUATION_STORM` audit event at onset carrying the trail as it stood
*before* the rate climbed, plus the gate depth, the friction gate, the package and the last read —
and a second event when it stops, which is the only thing that records how long it ran. The audit
log is the right home because it outlives the process, which the trail does not. It reports once
per storm at each end, never per evaluation: an instrument that logged a runaway loop in a loop
would be the same bug one layer down.

The threshold is 20/s, and it is chosen against what the healthy paths can produce rather than
against the fault: the ticker floors at one a second, `notificationTimeout` coalesces the two
subscribed event types to about ten between them, and interstitial callbacks each cost a user
action. In the healthy case this is two field updates and a comparison — the cost appears only
once the fault does, the same bargain `shouldRecheckForeground`'s throttle makes.

A clock that jumps backwards resets the window *and* its count. Resetting only the start left the
tally intact while the window could never close, so an NTP correction would accumulate its way to
the threshold — an instrument inventing its own finding, which is worse than no instrument.

## What a screen read costs the app in front

`EnforcementStatus` counts node reads and their wall time, and this is the one number no `dumpsys`
can produce. A tree read is an IPC plus a traversal **in the foreground app's process**, so the CPU
goes on *that* app's battery budget, not rotblock's. Android's own attribution therefore cannot see
it at all: measured over 6h34m, rotblock's whole UID came to 23.3 mAh of 1950 (1.2%, of which 12.9
was screen time in its own UI), with 34ms of wakelock and no alarms or jobs — and none of that says
anything about whether Samsung Internet was held up while being read.

Wall time rather than CPU time, deliberately: the question is how long the other app was blocked,
not how much of this process's budget went on it. `readCount`, `readTotalMs` and `readMaxMs` ride
in every diagnostic capture, and a read slow enough to matter says `took=NNms` in the Right now
panel. Tier 0 — it is there to be read when the inspection path is being changed, and there is no
threshold attached to it.
