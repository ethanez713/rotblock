# rotblock

This file is published with the repo. Personal or machine-specific notes go in the
gitignored `CLAUDE.local.md`, which Claude Code loads right after it.

A local app and website blocker for Android, built as a replacement for a paid blocker
subscription. Kotlin + Compose, single `:app` module, sideloaded. No network permission, no
accounts, no sync — usage history and the audit log never leave the device.

## The invariant everything hangs off

**Nothing fails open.** Every relaxation — a pause, a quota, a queued policy edit — is a *deadline
persisted to disk*. `PolicyEngine.evaluate` is a pure fold over those timestamps and stored rows.
It never consults a timer, a job, or an "is blocking on right now" boolean.

The consequence: a crash, a force-stop, a reboot, or a background job that never runs all resume
blocking on their own. This is the whole point of the app — the failure it exists to prevent is
"disabled a policy for one minute, forgot for three weeks."

If you are about to add state that says *whether* something is currently blocked, stop. Store the
deadline and compare it to the clock instead.

Corollaries worth keeping:
- **A pause has a scope, and the narrow scopes are per-target deadlines.** The widest is
  `Settings.pausedUntilMs`, the global deadline that unblocks the whole phone. Narrower ones live in
  `Settings.pausedTargets` (`targetId -> untilMs`): "this app" is one entry, "this group" is one per
  member target, so pausing a game never unblocks a social app. Enforcement folds them into one
  number per target — `PauseController.effectivePauseUntil(targetId)` is `max(global, scoped)` — and
  the engine is unchanged, because `evaluateAll` is already per-target and takes a single pause
  deadline. Every entry is a deadline compared to the clock like every other relaxation here, so an
  entry in the past is simply ignored and nothing prunes it for correctness. Budget does not vary by
  scope: a pause is a pause, counted from `PAUSE_STARTED` the same way. Time spent during a pause
  still lands in the ledger so the interstitial mirror can tell the truth, tagged so it cannot
  count toward quota. The tile stays *on* for a scoped pause (most of the phone is still blocking)
  and shows the count; only a global pause turns it off.
- `PendingChangeQueue.applyDue()` is opportunistic, never alarm-driven. A queued weakening landing
  late is harmless; landing early is not.
- Losing accrued usage on a crash is fine (you get time back). Over-charging is not.
- Emergency skip is **never denied**, only recorded loudly. Denying it drives the user back to
  disabling policies wholesale, which is the thing being prevented.
- **A deadline elapsing fires no event.** Nothing notifies you that a pause just ran out, that an
  allowance is spent, or that a scheduled window opened — no window change while you keep
  scrolling, no settings emission for time merely passing. So `PolicyEngine.evaluate` returns
  `recheckAtMs` alongside every decision, and the service arms the ticker for it in exactly one
  place. Any future "stop the timer, we're allowed" optimisation re-opens this hole. The same fact
  is why `PAUSE_EXPIRED` is stamped with the deadline rather than with the moment we noticed it.
- **A schedule is the one relaxation that is not a persisted deadline, and does not need to be.**
  The wall clock is already durable state no crash can lose, so "outside its hours" is re-derived
  on every call and there is nothing to restore and nothing to leak. What a schedule *does* owe is
  `Schedule.nextBoundaryAfter`: a window opening at 10am while you sit in the app is the same
  silent deadline as a pause elapsing. Two directions matter and they are not symmetric — a window
  *opening* late is a fail-open, and a window *closing* late over-charges the quota. Anything
  degenerate resolves toward more blocking: an unparseable schedule decodes to always-on, a block
  whose ends are equal covers a full day, and a schedule emptied of its last row collapses to
  always-on rather than never.
- **rotblock's own screens must not spend the target's quota.** The friction countdown and the
  pause flow cover the app you were using, and our package is excluded from foreground tracking,
  so without `Interstitial` the target keeps being charged while you look at us. That object is
  owned by the activities' start/stop callbacks, never by the launch site, so every failure
  (launch refused, activity killed, process death) resolves toward *resuming* accrual — a gate
  stuck open would silently stop enforcing quotas. It also expires on a deadline, because that one
  failure is undetectable from the inside: a gate stuck shut looks exactly like an app nobody uses,
  so quotas quietly stop filling and nothing is ever blocked again.

  It announces the gate going **up** as well as coming down, and that is not symmetry for its own
  sake. Nothing else tells the service one of our screens appeared, so the check that stops accrual
  was only reached on the next tick — up to `MAX_TICK_MS` later. A five-second friction wait was
  therefore charged in full, and the countdown ring sat on top of the friction screen visibly
  counting down, which is the one lie that readout may not tell. Both callbacks fire *after* the
  state they describe, because the listener re-enters this object by way of re-evaluating what
  should be on screen; announcing first made correctness depend on every listener happening to be
  asynchronous, which an object cannot enforce about its callers.
- **No block may be a dead end.** A block that covers more of the screen than it actually forbids
  has to offer a way back to the part you are still allowed to use — `escapeLabelFor` decides, and
  it is an exhaustive `when` so a new target kind forces the question. A website block covers the
  address bar you would need to leave the site, and a feed block covers the app around the feed;
  in both cases closing and reopening restores the same page and blocks again. The escape has to
  change what the app will *come back to*, not just what is on screen now — which is why leaving a
  site loads a URL rather than pressing Back, since a restored tab can have no history and Back
  would only close the browser. The test is always the same: **if the only way out is disabling the
  policy, it is a dead end**, because that is the exact behaviour this app exists to prevent. The
  escape is also the block screen's *only* button, and everything else on it — closing the app when
  there is something narrower to leave, and asking for a pause — is plain tappable text. Three equal
  buttons put "let me through" on the same footing as "fine, leave", and the way out of a rule
  should never be the easiest-looking thing on the screen.
- **The friction countdown is per visit, and per wait *served* — not per screen shown.** It is once
  per visit rather than once per event (`FrictionGate`, re-armed on any genuine foreground change),
  and the countdown itself restarts whenever `FrictionActivity` comes back to the front. Both halves
  matter and the second one is the hole: a coroutine in a stopped activity keeps counting, so
  starting the five seconds, switching away and coming back a minute later used to hand you a
  countdown already at zero. The gap between the reflex and the feed is the whole product; waiting
  it out elsewhere is not waiting. Re-arming may only happen on a *foreground change*, never on any
  window event — a toast or a heads-up notification re-arming it would pop the countdown on top of
  an app you are already using.

  What spends the wait is the countdown reaching zero, reported by the countdown itself. Marking the
  visit at the moment the screen was *launched* is the same mistake as hiding a block because
  `startActivity` returned: back out of the countdown and the app underneath resumes, and because
  our own package is excluded from foreground tracking, nothing fires to re-arm it — no foreground
  change, no target change, no event at all. One press of back and you were in. So the gate now
  re-launches whenever the wait is owed and the screen is gone, without limit: each re-show costs a
  user action, so it cannot loop. The loop the old code feared is a *launch that produces nothing*,
  which is a different failure and gets its own answer — the countdown reports when it appears, and
  a launch that never does is abandoned for the visit rather than retried on every redraw. Back is
  also intercepted and routed to "take me back", so declining by back declines properly rather than
  flashing the live page until the service notices.

  **A restored target may block, but it may not nag.** A website target reaches `applyTarget` from
  four places and only some of them are facts: a read is evidence, while `VisitMemory.resume` and
  the visit persisted across a rebind are guesses about what is still on screen. The countdown used
  to fire off a guess within milliseconds of a foreground change, before any read could confirm it —
  so reopening a browser after an escape landed on the loopback page and immediately demanded the
  wait for the site just left, and coming back into a tab switcher did the same. `TargetConfidence`
  splits the two, and the asymmetry runs the opposite way to everything else here: a guess may still
  be **enforced**, because a block it is wrong about is lifted by the very next read, but it may not
  drive the countdown, because a false countdown costs you the wait *before* you can prove it wrong
  and teaches you to distrust the whole mechanism. Confirmation only ever goes up on a read and is
  reset only by a target change, so a later re-evaluation cannot un-confirm what a read established.
  The accepted cost: returning to an already-scrolled blocked page never re-shows the countdown,
  because the bar is hidden and no read can confirm it. That page already cost you the wait once.
- **An escape is a request, and it gets checked.** It hides the block and then *asks* an app to go
  somewhere else, and apps refuse: Samsung Internet answered `about:blank` with an "Invalid
  address" toast, stayed on the blocked page, and `startActivity` still returned normally — one tap
  bought unlimited browsing on a site that had just run out of time. So the destination is
  `BrowserRegistry.ESCAPE_URL` (loopback: every browser accepts it as a URL, it reads back as a
  host no rule matches, and it never leaves the device), and `verifyEscaped` re-blocks if the
  target is still current a few seconds later. Anything that hides a block on the strength of an
  action succeeding has to carry its own proof, because nothing else will notice.

  An escape also *forgets*. `VisitMemory` holds the page a read last found in that browser, and an
  escape is positive evidence that page is stale — without clearing it, the next re-entry resumes
  the exact site the user just paid to leave. Declining the friction countdown counts as an escape
  for the same reason and reports back through `FrictionGate.onDeclined`, since it moves the browser
  too. What neither of them touches is `currentTargetId`: `verifyEscaped` reads it to decide whether
  the escape actually took, and that proof must not be disturbed by the thing that triggers it.

- **The daily pool is a relaxation folded from the ledger, not a deadline.** `Policy.poolSeconds`
  on a target's own rule means the first N minutes of that target's countable usage since the day
  rolled over (`dayStartHour`, the same 4am as the pause budget) cannot be blocked by anything but a
  workout — not a spent quota, not a group, not a hard block. Every visit drains it, not only time
  the rules would have refused, and pause time does not. Unlike a pause, pool time is charged to
  every covering quota (`Decision.Allowed(fromPool = true)` opens an ordinary counting session), so
  the rules resume afterwards knowing what was spent. It is stored usage folded against the clock,
  so a crash can only hand pool time back the way it hands quota back. The pool running out is a
  silent deadline like every other and is in `recheckAtMs`. Groups never carry one, and a bigger pool
  is a weakening that waits.
- **Workout mode is the one *strengthening*, so its dangerous direction runs the opposite way.**
  Everything else here relaxes enforcement, and the failure to fear is a relaxation that never ends.
  This overrides enforcement, so the failure to fear is that it never *stops*: a phone left grey
  with every rule blocking and a nudge every minute, and no obvious way to work out why. That is
  why it stores nothing at all. Strong keeps one ongoing notification for the life of a workout and
  stamps it with the real start time, which is durable state outside this process that no crash of
  ours can lose — the same argument a `Schedule` makes about the wall clock. `WorkoutMode` is a fold
  over that timestamp and a cap, and the listener re-derives the whole answer from
  `getActiveNotifications()` when it reconnects. Losing the signal reads as "not working out", which
  resolves toward *normal rules*, not toward a locked phone.

  The cap is a backstop against a workout that never ends, not a promise about this one. It arms the
  ticker and is deliberately **not** offered to the block screen as a lift time — a three-hour
  countdown there would say the phone is locked until then, which is false, and this app's readouts
  may not lie about time.

  It overrides a pause already in effect and a rule's scheduled hours, and that ordering is the
  point rather than an accident of where the branch sits. But an **emergency skip ends it**, in
  `PauseController.startPause` where every skip passes through, because "never denied, only recorded
  loudly" outranks it. An ordinary pause does not. Ending it this way also holds for the rest of
  that workout: `endManually` remembers which workout it dismissed, so Strong's next re-post seconds
  later cannot undo it. An escape hatch that gets quietly reversed is not one.

  **The allowlist is the one relaxation of a strengthening, and it is safe because of what it
  subtracts.** Music, podcasts, a timer — apps that help rather than distract. `overrideUntilMs` is
  the whole of it: it answers `activeUntilMs` for an ordinary package and null for an allowed one,
  and being allowed removes *the override* and nothing else. The caller then evaluates that
  package's own rules with no workout in the fold, so a rule on Spotify still blocks Spotify, and
  the allowlist can never leave the phone weaker than workout mode being switched off. That is the
  only direction a relaxation of a strengthening may go; anything that let a listed app skip its own
  rules would be a fail-open wearing a whitelist's clothes.

  It is one function rather than three because a workout does three things — blocks every rule,
  drains the colour, nudges you back — and all three ask the same question about the app in front.
  Greyscale and the reminder each carried their own copy of `activeUntilMs() != null && currentPackage
  != appPackage`, which is the same shape as the eight-places greyscale bug one section up: the
  workout app was exempt from the colour but not from the block, and a fourth reason to exempt
  something would have had to be written into each of them separately. The workout app is now exempt
  by being in the same answer, not by being special-cased beside it. A null package is never exempt —
  not knowing what is in front is exactly when the override should still apply.

  The reminder is the half that matters most in practice. Choosing the next podcast is not drifting,
  and a nudge every minute out of the app you deliberately allowed is how the whole feature gets
  switched off.

On-device regression checks for the rules above, and the one-time sideload setup, live in the
`device-regressions` skill.

## Groups, and why they cost the ledger nothing

A per-target rule can answer "how long on this site" and cannot answer "how long scrolling". Three
sites at two minutes an hour each is six minutes an hour, and every one of those rules is satisfied
the whole time. `TargetKind.GROUP` is the answer: one allowance shared across several targets.

**A group is a `Policy` with `members`, not a second kind of entity, and that falls out of the
ledger already being keyed by target.** Time is recorded against the page in front whoever is asking
about it, so a group's quota is the union of its members' sessions — `Policy.chargedTargets` is the
whole of it, and nothing about accrual, flushing or retention changed. The corollary is the feature:
a target can be in several groups *and* have a rule of its own, and every one of them is charged for
the same minute.

**Nothing on screen is ever a group.** The target is still an app, a website or a feed;
`coverageByTarget` indexes a group under each of its members, so a group's own id is a value in
that map and never a key. Two things had been keying off "the policy that blocked you" and had to
move to the target now that those differ:

- The block screen's escape. `escapeLabelFor` takes the kind of the thing *on screen*; taking it
  from the blocking rule would offer "close this app" over a browser sitting on a page it cannot
  leave, which is exactly the dead end that function exists to refuse.
- `FrictionGate`. Keyed on the rule, several policies covering one page would each owe you a
  countdown, and `onDeclined` hands its key back to be compared against `currentTargetId` — which
  only a target id can ever match. The wait is the target's own rule's when it has one, otherwise the
  longest any live group asks — and it is *named* after the rule that asked.

Every component of a `Policy` combines across the covering policies by one of three strategies, named
in `PolicyEngine.MergeStrategy`, and `evaluateAll` is split so each lives in one place:

- **`SHARED_STRICTEST` — the budget** (`foldBudget`): block, countdown, ticker. Strictest-wins across
  every covering policy, because a shared cap a per-target rule could undercut would not be a cap — so
  an app in a group is blocked as soon as *either* its own quota or the group's is spent. Among
  several blocks the screen names the one that holds you **longest** (a null lift time outranks every
  dated one, since null means "nothing but a pause or an edit lifts this"); the countdown shows the
  least time left; the ticker arms for the **soonest** deadline any of them named, or a rule never
  comes back on.
- **`OWN_WINS` — behaviour** (`foldBehaviour`, via `ownElseGroups`): the friction wait and greyscale.
  When a target has a live individual rule these are that rule's alone — a group can neither add a
  wait nor drain the colour on a member that has its own rule, so a no-wait app inside a group that
  demands a wait keeps its no wait. With no live own rule they fall back to the live groups: greyscale
  unions, the wait is the longest asked.
- **`COUNT_ALL` — accrual**: a minute is charged to every covering policy at once. Not folded in the
  engine; it falls out of the ledger being keyed by target plus `Policy.chargedTargets` / `chargeable`.

A new component of a `Policy` must be placed in one of the three — there is no default. That is the
whole of the "app rule for behaviour, every rule for the budget" split.

**Dropping a member is a weakening and waits**, for the same reason removing a rule does. A group
whose members merely *moved* is therefore a weakening too, which is the fail-closed reading a
schedule that moved already gets. `PendingChange` carries `members` for that reason: without it a
queued edit would land its quota an hour late and silently put the dropped member back.

**A group renamed keeps its id.** The slug is derived from the name once, at creation, so the audit
log reads `group:brainrot` — but every block already recorded points at that id, so editing only
ever changes the label.

### The one thing groups broke, which had no symptom

Charging used to be exactly right without anyone thinking about it: the service closes the ledger
session whenever a rule goes out of hours, and while a target had one rule that *was* the rule's
hours. It stops being true here. A target is now charged whenever **any** policy covering it is
live, so a rule sitting outside its own hours can find usage in the ledger that it must not count —
which takes quota away for time the rule never governed, and over-charging is the one direction this
app may not fail in.

So `Schedule.activeIntervalsWithin` clips a policy's view of the sessions to the hours it was live
for, and `PolicyEngine.chargeable` is the single call every adder-up goes through — the engine, the
quota bar on the Blocks screen, and the per-rule effect line — so the bar cannot say something the
block screen disagrees with. An always-on schedule answers the whole range without touching a
calendar, so every rule written before this, and every group without hours, pays nothing for it.

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

## Rules for the accessibility service

`service/RotblockAccessibilityService.kt` is the only enforcement path.

1. **It must never crash.** A dead accessibility service is a silently unblocked phone — worse
   than not having the app. Every entry point goes through `guard()`, which swallows into the
   audit log.
2. **Never walk the node tree in `onAccessibilityEvent`.** Content-change events fire constantly
   while scrolling. The callback does a set lookup and a conflated-channel send; all node reading
   happens on the inspector coroutine, debounced and rate-limited.
3. **A tree read must confirm whose window it read.** `rootInActiveWindow` frequently belongs to
   something other than the package the event came from — usually one of our own screens sitting on
   top of an app that is still loading. Acting on that read drops a live target.
4. **Close the usage session on screen-off.** It is the one way a target leaves the foreground
   without an accessibility event.
5. **Greyscale writes a global secure setting, and exactly one function decides it.**
   `refreshGrayscale` folds every reason at once — the rule in front of you, and workout mode — and
   takes what the rule wants as an *argument* rather than reading a flag, because a flag set in one
   branch and cleared in six others is the bug this replaced rather than one worth moving. It used
   to be decided in eight places, each about a single rule going out of scope; a workout greys the
   whole phone for a reason none of them knew about, so every one of them would have put the colour
   back mid-workout.

   Two callers deliberately do not ask it: screen-off and teardown restore unconditionally. A phone
   must never be left monochrome behind a lock screen or after enforcement goes away, whatever else
   is true, and that outranks any feature. Unlock re-evaluates, which is what puts the grey back.
   Leaving the phone monochrome is still the worst bug this app can have.
6. **A stale `currentPackage` is indistinguishable from a correct one, so it needs its own way
   back.** Observed on device, twice in one morning: the service alive and receiving events —
   `beat()` piggy-backs on them and its heartbeat was two seconds old — with the last screen read 22
   minutes stale, the trail silent for 19, `currentPackage` stuck on the launcher, and the capture's
   node dump showing Samsung Internet in front on a reddit page. Nothing enforced, nothing charged,
   no symptom anywhere. A total fail-open.

   The trap is that every route back was gated on `currentPackage` being **null**. An app you are
   already inside emits only content-change events, those were discarded for not matching the stale
   value, and `reEvaluateCurrent` re-seeds only when nothing is established — so a single missed
   window-state change was permanent. `shouldRecheckForeground` is the way out, and what it does
   *not* do is the design: it never adopts the event's package. A content-change tells you a package
   is *rendering*, which a background browser tab does too; adopting on that would charge that tab's
   quota while you sit in another app, and over-charging is the direction this app may not fail in.
   So it only decides whether a check is **owed**, and `requestForegroundRecheck` settles it against
   `rootInActiveWindow` — off the callback thread, per rule 2 — correcting the foreground only once
   the active window agrees. A read that disagrees does nothing at all, which is exactly the
   background-tab case being rejected rather than believed.

   It is throttled, and the throttle is honest rather than free: in the healthy case
   `currentPackage` is right, every content-change matches it, and this path never runs. The cost
   appears only once the fault has, and is then one `rootInActiveWindow` read every two seconds
   until the disagreement resolves. Recovery is also scoped the same way `isForegroundChange` is —
   only a package with a policy, a browser, or a feed app can trigger it — so landing on an app
   rotblock has no interest in still leaves the belief stale. That direction costs quota rather than
   letting a rule through, and the ordinary window-state path already handles it.

7. **Unlocking is a foreground change with no event behind it, so it has to ask the screen.**
   `ACTION_SCREEN_OFF` deliberately leaves `currentPackage` standing — the common case is coming
   back to the app you left, and re-enforcing it the instant the screen returns is the whole point
   of `ACTION_USER_PRESENT` calling `reEvaluateCurrent`. But the phone can equally come back
   somewhere else: Android restores the top task, a notification was taken off the lock screen, the
   launcher is what appears. No accessibility event says so.

   Rule 6's recovery cannot reach this. `shouldRecheckForeground` is driven by
   `TYPE_WINDOW_CONTENT_CHANGED`, and `requestInspect` returns immediately for a package that is not
   a browser or feed app — so an app that emits no content changes of its own, which is every game
   rendering into a surface, has **no** route back at all. Reported from device: unlocked straight
   into a blocked game, nothing enforced, nothing charged, and nothing that would ever have noticed.

   It fails both ways at once, which is why it is worth its own path rather than a wider event
   filter. Unlocking *into* a blocked app enforces nothing; unlocking *out* of one goes on charging
   its quota from the launcher, which is the over-charging direction this app may not fail in.

   So `onUnlocked` does two things and they answer different questions: `reEvaluateCurrent` puts
   the block back straight away on what was believed before the lock, and `confirmForegroundFromScreen`
   then questions that belief against `rootInActiveWindow`. `shouldAdoptActiveWindow` holds
   the guards — an unreadable window concludes nothing, an event that landed while the read was in
   flight outranks it, and agreeing with what is already believed is not a correction. The null
   case is left to `reEvaluateCurrent`'s `reseed` alone, because both would poll the same window.

8. **The ticker is the only thing that re-evaluates a plain app target, so nothing may stop it
   without something starting it again.** A browser or feed app has the inspection loop, which ends
   in `applyTarget` on every read. `requestInspect` declines everything else, so once a game is the
   established foreground, every event it emits reaches `pkg == currentPackage` and stops there —
   no other line in the event path calls `evaluateAndEnforce` at all.

   `ACTION_SCREEN_OFF` stops the ticker, which is right, and `ACTION_USER_PRESENT` starts it again.
   The hole is a screen that comes back on with **no keyguard to pass**: inside Samsung's lock-delay
   grace period the display locks without the lock screen engaging, so no `USER_PRESENT` is
   broadcast, and an app that was already in front fires no window-state change either.

   Measured on device, and it is the most complete fail-open this app has had: two captures a
   minute apart, the service connected with a 45-second-old heartbeat (so events were arriving the
   whole time), `currentPackage` and `currentTargetId` both correctly naming a blocked game, the
   node dump showing that game on screen — and `tickerArmed=false`, `accruingTargetId=null`, the
   trail frozen 23 minutes earlier on the last block. Everything the service believed was right.
   Nothing ran.

   Two answers, because the second has to hold when the first is missed:

   - `ACTION_SCREEN_ON` is subscribed and calls `onUnlocked` **only when the keyguard is not
     locked**. That gate is what keeps "never resume on SCREEN_ON" intact — with a lock screen up
     this does nothing and `USER_PRESENT` still does the work, so a glance at the clock cannot
     charge the target underneath. `isKeyguardLocked` assumes locked when it cannot tell.
   - `shouldRearmFromEvent` lets an event restart a stopped ticker on a held target, throttled, and
     never with the screen off. Same bargain as `shouldRecheckForeground`: in the healthy case a
     held target has an armed ticker and this never fires. The one healthy state that pays is a
     hard block with no deadline, where the ticker is legitimately stopped — it costs one cheap
     fold every five seconds and buys the block screen coming back that fast if it is ever lost.

9. **The window-state event that arrives is not necessarily the app switch.** `notificationTimeout`
   delivers only the last window-state change of each burst, and One UI posts its own alongside
   every switch — the launcher's, `sidegesturepad` on each navigation swipe, Game Booster's overlay
   as a game starts. Whichever lands last wins, and the app's is dropped. For a browser rule 6
   repairs it; a game emits nothing afterwards, so it stayed invisible — seen in five captures of
   one week, a quota'd game on screen for minutes with `currentPackage` on the launcher.

   So every window-state change, including ones judged not to be a foreground change, restarts a
   one-second settle that reads `rootInActiveWindow` once and corrects the foreground through
   `shouldAdoptActiveWindow`, the same guards unlock uses. An inspector read that finds the active
   window owned by some other package arms it too, but never adopts that package itself: mid-
   transition it is still looking at the app being left. The cost is one root read per burst of
   window-state changes — no traversal, no polling — and a read that lands on one of our own
   screens concludes nothing.

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

## Fragile by nature

`browser/BrowserRegistry.kt` and `block/FeedRegistry.kt` hold view-ids that app updates break.
They are data, not scattered constants, and a miss must degrade to "not blocked" — never a crash
or a false block.

That rule is about a view-id we never find. It is **not** licence to treat a *failed read* as
"nothing on screen": browsers hide the address bar as you scroll, and rotblock's own screens cover
the app while it goes on emitting events. `classifyInspection` therefore answers `Unreadable`
rather than `AppOnly` in those cases, and the service leaves the current target alone. Collapsing
the two is what looped the friction countdown on Samsung Internet and let a scrolled-into page back
in unblocked. A bar we *did* read but that does not parse as a URL — a half-typed search, a
placeholder, the `about:blank` a rejected escape left behind — is the same failure wearing a
costume, and answers `Unreadable` too: only a real URL is evidence about the page you are on.
Note this can only ever hold a match already read, never invent one, so registry rot
still degrades to "not blocked".

**An address bar is *one* bar.** `findAccessibilityNodeInfosByViewId` searches the whole tree, and
browsers reuse their URL view ids: Samsung Internet's tab switcher labels every card with that tab's
URL, and the suggestion drop-down labels every row with where it would take you. Reading the first
node with text blocked the *tab list* because one of the other tabs was on reddit — a screen with no
page on it at all. So `AddressBar.read` answers `Chooser` when several nodes offer text and none
stands out, and a chooser is not a read: it holds the current target like an absent bar, ends the
whole bar read rather than falling through to the next id, and suppresses the link harvest too, since
a screen offering a choice of URLs is not a page and nothing on it says where you are. It is the
`SearchEngines` problem arriving through a different door. Visibility only breaks ties and never
filters on its own — a toolbar scrolled off screen still holds the current page's URL, and dropping
that read would lose a target rather than gain one.

**Holding is only enough when there is something to hold.** Samsung Internet does not merely hide
the address bar on scroll — it removes the toolbar from the accessibility tree entirely and gives
the WebView the whole screen, so there is no bar to read at any depth. Arrive on a page that is
already scrolled and the target is never *acquired*, and holding nothing enforces nothing. Observed
on device: a browser sat on a quota'd site for eleven hours, `url=null` on every read once a second,
nothing blocked, nothing charged, no symptom anywhere. `VisitMemory` cannot help — the persisted
visit correctly said "package sbrowser, target none", which is exactly what it had been told.

`LinkConsensus` is the second opinion. Chromium annotates every link node with an
`AccessibilityNodeInfo.targetUrl` extra, and a page's links overwhelmingly point at itself —
measured at 164 of 164 on a Reddit post — so the host they agree on is the host you are on, and
unlike the toolbar it is part of the content and never scrolls away. It is deliberately weaker
evidence than the bar and is fenced in accordingly:

- It never overrides a bar that was read. The bar is a fact; this is an inference.
- It runs **only to acquire**, never while a target is held, so it cannot contradict a live one —
  and the common case, scrolling a page already recognised, never pays for the scan.
- It may say "you are on reddit". It may never say "you left it": consensus on a host with no rule
  still answers `Unreadable`. Letting a weaker signal clear a target re-opens the original hole.
- It reports a **host and no path**. Chromium resolves a same-page `href="#x"` into the full current
  URL, so a path is tantalisingly close — but a fragment link to a *different* page on the same host
  is indistinguishable from one to this page, and guessing wrong puts a block on a page no rule
  covers. So `youtube.com/shorts` does not match through this route and degrades to "not blocked".
- Anything short of near-unanimity (`MIN_AGREEMENT`, over at least `MIN_SAMPLES` parsed links)
  answers nothing at all. A page linking heavily outbound must not be able to carry the vote.
- **The vote sees every link the walk found, not the first handful.** The sample used to stop at
  twelve while the walk went on visiting all `MAX_LINK_SCAN_NODES` nodes anyway — the links-only walk
  it was saving does not exist, because wanting links means the bar was unreadable, which also wants
  a signature. So a results page could spend its whole sample on results before its own navigation
  links appeared, and the search-engine veto never got to look at them. Widening it to
  `MAX_LINK_URLS_RETAINED` costs no extra traversal; it stops discarding evidence already paid for.
- **One scan is not enough.** `LinkCorroboration` requires the same host from two *consecutive*
  scans of the same package before the vote may acquire anything — a tree caught mid-load can skew
  one sample, and this is the acquisition route with the weakest evidence behind it. A scan naming a
  different host, or naming none, replaces what was pending rather than being ignored, so agreement
  always comes from the two most recent scans.
- **Only a read that actually looked may vote, or clear a pending vote.** `harvestPageSignals`
  answers null rather than an empty result when `MIN_PAGE_SCAN_INTERVAL_MS` has not elapsed, and the
  distinction is load-bearing: reads land once a second and scans once every three, so two of every
  three reads on a scrolled page did not look at all. Letting those offer "no consensus" erased the
  pending verdict between every pair of real scans, and no two consecutive votes could ever agree —
  which would have killed this entire fallback silently. It is `Unreadable` against `AppOnly` one
  layer down: not looking is not the same as finding nothing.
- **A search results page is exempt, by name.** It is the one page on the web built to break the
  assumption underneath all of this: search for "reddit" and every link on screen agrees on
  reddit.com, unanimously, while the page is Google. No amount of evidence in the links can say
  otherwise, because the only thing that distinguishes the page is *which site it is* — the thing
  the missing address bar was going to tell us. So `SearchEngines` is a hardcoded list of brands,
  matched per host label so every ccTLD is covered, and it fires two ways: one search-engine link
  anywhere in the sample discards the whole vote, and a bar read that positively identified a search
  engine switches the fallback off for that browser entirely (`SearchPageMemory`, two minutes, or
  until a read says you are somewhere else). Suppression has to expire, or one visit to Google would
  blind the browser for good, which is a fail-open. The cost is that a rule *on* a search engine
  cannot be acquired from links and has to wait for a readable bar.
- **Both of those routes can be absent at once, so the page's own title is the third.** Observed on
  device, and it produced the false block this fence exists to stop: a Google AI Mode results page,
  scrolled so Samsung Internet had dropped the toolbar from the tree entirely, whose retained links
  were exclusively reddit.com, youtube.com and a fandom wiki — **no `google.*` host anywhere in the
  sample**, because modern results link straight to the destination rather than through a redirect.
  The per-link veto had nothing to fire on. `SearchPageMemory` had never been armed either, since
  arming it takes a bar read and the bar was unreadable for the whole visit. Both routes to the
  exemption need evidence that is missing by construction in exactly the situation the fallback runs
  in, so the vote went to reddit and blocked a search page.

  What survives a toolbar leaving the tree is the document title, which `PageSignature` already
  reads off the `WebView` node — measured here as a `"<query> - Google Search"` title. So
  `SearchEngines.isSearchPageTitle` discards the vote when a brand appears in the title's *branding
  slot*: the tail after the last separator, never the query that makes up the rest of it. That
  distinction is the whole safety of it. A reddit post titled "Why is Google bad?" is entirely tail,
  so a title with no separator gets no verdict at all rather than vetoing a real reddit page.

  It is checked before the sample is even tallied, and it may only ever *discard* a vote — never
  clear a target, never contradict a bar, never touch `SearchPageMemory` or `VisitMemory`. Titles
  are other people's localized strings (`"- Google Suche"` in German) and a redesign can change the
  format overnight, so this over-matches on purpose the same way the host list does: a thread in
  `r/google` reads back as the tail `r/Google` and loses its vote. Every consequence is "conclude
  nothing from the links", which degrades to *not blocked*.

Cost is bounded and is *less* than what it runs after: a page's own navigation clusters at the top
of its DOM — ten links inside thirty nodes, measured — so a depth-first walk stops at
`MAX_LINK_SCAN_NODES`, against three `findAccessibilityNodeInfosByViewId`
calls that each traverse the whole tree in the other app's process and, on a scrolled page, all
find nothing.

Read the extra with `getCharSequence`, not `getString`. `Bundle.getString` returns null when the
value was stored as a `CharSequence`, which Chromium does in some versions — the harvest silently
returned zero links until that was changed.

`PageSignature` is the third opinion, weaker still than link consensus, for the pages where even
that has nothing to work with. Samsung Internet's Shorts player is exactly that page: the toolbar
is gone from the tree the same as any scrolled page, and the player itself is barely thirty-odd
nodes with only a couple of link nodes on it — short of `LinkConsensus.MIN_SAMPLES` by a wide
margin, so the vote never runs. What survives is the page's own accessibility labelling, plus the
destinations its handful of links point at. It is also the only
one of the three that can carry a *path* — measured, Samsung's address bar reports host only, no
`/wiki/...`, no `m.` subdomain, so `youtube.com/shorts` can never match through the bar there, and
link consensus reports a host and nothing else by design. A signature is what closes that gap. The
same asymmetry governs it as everything else in this section: it may only ever *acquire* a target
outright, when the bar has nothing at all, or *refine* one the bar already agrees with on host —
`m.youtube.com` sharpened into `youtube.com/shorts` — and it may never contradict a bar that read
something else. A miss degrades to "not blocked", the same as a stale view id in `FeedRegistry`,
because the strings it matches on are YouTube's own web aria-labels: localized, and one reskin away
from changing without notice. That is also why matching is by strict equality and never `contains`
— a near-miss wording change is exactly the kind of false positive this whole file exists to avoid.

**Unlike the link vote, a signature runs while a target is held, and it has to.** The vote is
acquire-only because a weak host inference must never contradict a live target. A signature refining
a readable bar is the opposite case: the bar names `m.youtube.com`, that matches no path rule, and
`classifyInspection` is stateless — so on every read after the one that acquired the target, a bar
that is structurally incapable of seeing a path was saying "you left `youtube.com/shorts`". Gated to
acquisition, the signature could name a target but never hold one, and the block flapped on and off
at the scan interval — measured on device at three seconds, with `lastRead` saying `-> app only`
while the capture's own dump of the same tree said `Target(web:youtube.com/shorts)`. Its safety
never came from the target being absent; it comes from `compatibleWith` refusing a bar that
disagrees on host, which holds equally either way. The acquire-only gate stays for the case where it
*is* about acquisition: with nothing readable in the bar, a held target is already held, so the
common case — scrolling a page recognised long ago — still never pays for a scan.

The other half of that flap is the same "not looking is not the same as finding nothing" that
`harvestPageSignals` already draws for the link vote, one layer up. Reads land about once a second
and the walk runs once every three, so two reads in three carry no signature evidence at all;
answering `AppOnly` on those cleared the target between every pair of real scans. So
`signatureScanned` distinguishes them, and only on a host `PageSignature.couldRefine` recognises: a
read that *did* look and found no signature still answers `AppOnly`, because that is what lifts the
block when you tap out of a Short onto an ordinary watch page.

**A signature needs two kinds of evidence, and the first attempt used one kind, guessed.** It
required "Previous video" *and* "Next video", written from an assumption about what a Shorts player
looks like rather than from a capture of one — and it had the discriminator backwards in both
directions at once. A device capture of the real player has "Next video" and **no** "Previous
video", so the rule never once matched a Short; a normal watch page grows *both* labels the moment
you tap the video and the controls appear, so the only thing it ever fired on was the page it must
not. The reported symptom was exactly that: the block arriving on tap.

So a signature now also requires the page's own links to predominantly carry a path
(`Signature.requiredLinkPath`), read off Chromium's `targetUrl` extra. That half is *structural* — a
real URL, indifferent to language and to a reskin — where the labels are somebody else's localized
strings. Measured on device: the Shorts player offers three links of which two are `/shorts`; a
watch page offers twenty-five of which **none** is. The two halves answer different questions on
purpose — the labels say what kind of screen this is, the links say which part of the site it
belongs to — and each alone still false-positives: "Next video" alone catches a tapped watch page,
and a link share alone catches a feed scrolled to a Shorts shelf. Only a screen that is both gets a
verdict.

It is a *share* rather than "at least one link", for the same reason `LinkConsensus` needs
near-unanimity: a watch page may legitimately carry a Shorts shelf, and one shelf link must not be
able to rename the page. Only links on the signature's own host are counted, so an outbound link
cannot dilute the share and a `/shorts` path on an unrelated site cannot inflate it. Too few of the
page's own links is no verdict at all rather than a verdict from one.

The lesson is the one this whole file keeps relearning: **fixtures for these come off a capture, not
out of a guess.** `PageSignatureTest` now holds the real Shorts player and the real watch page side
by side, because a discriminator is only meaningful against the case it has to reject.

To find current ids, with the app open:

```
adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml
```

## Build & test

```
./gradlew assembleDebug
./gradlew testDebugUnitTest      # all pure JVM — no device needed
```

Needs JDK 17 and an Android SDK with platform 36; no Android Studio required.

targetSdk tracks whatever Play requires of new submissions; 36 is the bar from 31 Aug 2026.
A *clean* build APK is ~24MB; an incremental one balloons to ~55MB from zipflinger padding —
that is packaging slack, not content.

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

## Knowing when it is not working

`setup/Readiness.kt` is the answer to the problem the whole app has: everything it does is
invisible when it works, and therefore equally invisible when it does not. It folds a snapshot of
the phone into a list of requirements, each with the *consequence* of it being unmet and the fix,
and three surfaces render that one list — the first-run walkthrough, the Setup tab, and a banner on
the Blocks screen. Adding a requirement means adding it there and nowhere else.

The distinction it exists for is **missing versus broken**. Not granted is the ordinary case and
the walkthrough handles it. Granted and still not working is the one that wastes afternoons:

- Accessibility listed as enabled while the service is not bound — One UI kills sideloaded
  services, and a force-stop drops rotblock from Android's enabled list while leaving the toggle
  looking on. "Turn it on" is useless advice for a switch that is already on, so this state gets its
  own text, and the heartbeat says how long it has been down.
- A rule that asks for greyscale on a phone that never granted `WRITE_SECURE_SETTINGS`: the rule
  says it drains the colour and it silently does not.
- The countdown notification switched on with notifications denied.

Two things keep it believable, and both are load-bearing. It never reports BROKEN inside
`BIND_GRACE_MS` of process start, because opening the app after a kill genuinely has a moment where
the service is not bound yet — a red banner that clears itself teaches people to ignore banners.
And it re-reads every couple of seconds while on screen, because granting happens in another
process and there is no event to observe when you come back.

**Being dropped from Android's list is the one of these rotblock can repair itself.** A force-stop
removes it from `ENABLED_ACCESSIBILITY_SERVICES` while the toggle goes on looking switched on, and
that list is a secure setting the app can already write, because greyscale needs the same grant. So
`setup/AccessibilityEnrolment.kt` puts it back — at app start and whenever the Quick Settings shade
opens, which costs no wakeup of ours because a tile is already bound at that moment.

The care goes into *when*, not the write. Being absent has two causes that look identical from here
— the phone force-stopped us, or the user went and switched us off — and silently undoing the second
would be the app fighting its owner, which is not what this app is. They are told apart by state
already on disk: a deliberate switch-off unbinds the service, which stamps `down_since`, while a
force-stop kills the process with no callback and leaves only a heartbeat. The same signal
`reportPreviousOutage` uses. Heard from, never told to stop, no longer listed: that is a drop, not a
decision. A phone where enforcement has never run is left alone too, so a fresh install cannot
switch on its own accessibility service before the disclosure has been shown — it only ever puts
back what was already running. And the merge may only ever *add* rotblock: a list already containing
us is returned byte for byte rather than rebuilt, because clobbering somebody's screen reader would
be far worse than the fault being repaired, and this is the only code in the app that could.

This is not the same fault as listed-but-not-bound, which `EnforcementRevival` owns; writing the
list again for a component that is already enrolled changes nothing.

## The countdown readout, and why absence is the point

`block/TimerHud.kt` (a ring on screen) and `block/TrackingNotification.kt` (the same countdown as a
notification) both show only while a target is *actually accruing* — not while blocked, not while
paused, not outside a rule's hours, not while one of our own screens is up. They hang off the same
branch that opens a ledger session, and every path that closes one takes them down with it. The
notification defaults off; the ring defaults **on**.

A pause is the one relaxation that gets its own readout: `TimerHud` in `HudShape.SQUIRCLE` form,
shown over a paused app counting down to when blocking resumes. It is the same widget in every
respect but the outline — same drag, same dismiss, same shared position — so the shape alone tells a
pause apart from a spent allowance at a glance. It hangs off the `Decision.Allow` branch for a
paused target the way the ring hangs off `Decision.Allowed`, and the two are mutually exclusive on
screen. Its border drains against the pause's whole span, `until - since`: `PauseController` tracks
each pause's grant time (`pausedSinceMs`, `pausedTargetsSince`) as a *display anchor only* —
enforcement never reads it, a lost or absent anchor degrades to a full non-draining border
(`pauseArcTotalMs`), and extending a running pause keeps the original grant so the total grows rather
than snapping back to full. Its dismissal is tracked apart from the ring's
(`pauseHudDismissedThisSession`): a pause and an accrual are different sessions, and dismissing the
squircle while paused must not suppress the ring once time starts being spent again.

That coupling is the whole feature, not tidiness. Every failure this app has had presents as "the
app is open and nothing happens", and from inside the app there is no way to tell a target being
charged from one that was silently dropped. The Right now panel answers that, but only once you
already suspect something. A readout that is simply **not there** while you sit in a quota'd app
says the service is not counting, at a glance. Which is why the transparency slider stops at 10%
rather than at nothing, and why a readout that lingered past its session would be worse than none.

The ring is also draggable, and dismissible with the standard chat-head gesture: drag it onto the
X that appears at the bottom of the screen and let go. It remembers where it was put — across the
show/hide cycle of a visit and across a process restart — because a resting spot is a preference,
not enforcement state, and losing it is merely annoying. A dismissal is a different kind of fact
and is handled differently on purpose: it is **never persisted**. It lasts only until the accrual
session in progress ends (`hudDismissedThisSession` in the service, cleared everywhere a session
closes), and the very next target to start accruing gets the ring back. If a drag could turn the
ring off for good, its absence would stop meaning "nothing is being counted" and start meaning "or
you swiped it away once, weeks ago, and forgot" — the exact ambiguity this whole section exists to
remove. That is also why the ring now defaults on rather than off: the signal is only honest from
the first run if there was never a decision to make. The Setup toggle is still the one way to turn
it off for good; that is a decision, made once, and it says so.

## The mark

`ic_launcher_foreground.xml` is a seedling on off-white: what grows once the rot stops taking the
hours. The mark it replaced — two chevrons stopped by a bar — said the opposite, and mostly read as
a failed download. Geometry lives in three places that must move together: the adaptive foreground,
the themed `ic_launcher_monochrome` (where the stem stops short of the soil, because a launcher
tinting everything one colour turns a touching stem and bar into one blob), and `ic_tile_rotblock`
redrawn tight to a 24dp box because the quick-settings tile scales whatever it is handed.

Everything stays inside the 66dp safe circle, stroke included, so a circular, squircle or teardrop
mask all crop nothing. The backdrop does not follow the system theme: the mark is drawn dark for it.

## Logos, without a network

`ui/TargetIcon.kt` puts the target's own logo on each rule. Every answer comes off the device,
because the app has no `INTERNET` permission and must not acquire one: a favicon fetch would tell
whoever serves it which sites you have asked to be kept away from, which is the most sensitive
thing this app knows. An app or feed rule uses the installed package's launcher icon; a website
rule looks for an installed app that *declares* that host, which is how `reddit.com` finds Reddit
and `youtube.com/shorts` finds YouTube. Browsers are excluded from that lookup by probing a
reserved `.invalid` host first — whatever answers for a host that cannot exist answers for
everything, and would otherwise stamp Chrome on every website rule.

Anything unresolved falls back to a monogram tile. That is the answer, not a placeholder for a
fetch that failed, so nothing should ever be added to "fix" it. The tile is one themed colour for
every host rather than a colour per site: `ChartPalette`'s slot order is its colourblind-safety
mechanism and must not be dealt out by hash.

## Sorting the Blocks screen

`policy/PolicyOrder.kt` ranks rules by one number — the fraction of the week the rule actually
keeps the target away, which is schedule coverage times the share of that time it refuses. It
exists so rules written in different units can be compared at all: an hour-a-day hard block against
a three-minutes-an-hour allowance that never lapses. It ranks, it does not predict; the tiebreakers
are there because the score ties constantly (every always-on hard block scores 1.0) and a list that
reshuffles on a refresh is worse than one in an arguable order. Disabled rules sort last on their
own key, not by scoring zero — an allowance wider than its own window also scores zero, and that
rule is still switched on.

**Most blocked** answers the other question, and answers it from history rather than from the rule:
how often this rule has actually stopped you, counted as `BLOCK_SHOWN` events over the ledger's
retention window. It reuses `RuleEffect.blocksShown`, which the Blocks screen already loads for the
per-rule effect line — no new metric, no new query, and nothing extra recorded. Strictness is what a
rule says it will do; this is what it has done, and the two disagree constantly: a hard block you
never go near outranks everything by score and has never fired once.

Unlike strictness it does not sort disabled rules last. A rule that blocked you forty times is worth
seeing near the top whether or not it is switched on right now — that history is the reason you would
be looking at this order at all. Ties fall through to name, same as everywhere else, so the list is
stable across refreshes. The counts arrive a moment after the screen does, because they are read off
a month of audit history rather than the one-second heartbeat; the list settles once and then holds.

## Screen time, and why rotblock does not track it

Insights shows the whole phone, but rotblock records none of it. Per-app time comes from
`UsageStatsManager`, which Android keeps whatever this app does, so reading it costs one query
while Insights is on screen and nothing otherwise. Counting it ourselves would put ledger writes
and ticker wakeups behind every app on the phone — and give worse numbers, since foreground
recovery is only built for packages a rule cares about. Do not move whole-phone tracking into the
accessibility service.

Android prunes usage events after about ten days, so `DailyRollup` folds completed days into
`daily_usage` and keeps a year. It runs from moments that are already awake — unlock, process
start, a Quick Settings tile binding, Insights loading — and never from a schedule. Unlock is the
one that makes the year dependable: it happens daily, and the others can all go quiet for longer
than Android keeps its events. Everything after that check is one prefs read.

Rotblocked sites and feeds are the one thing the OS cannot see, so they come from the ledger and
are subtracted from the browser or host app they happened inside: a minute is never counted twice.
Browsing on a site with no rule stays browser time. No unruled host is ever recorded — a history of
every site visited would be the most sensitive thing on the device, and nothing here needs it.

"Rotblocked" means covered by any current rule, enabled or not, group members included, and
today's rules colour every day shown. The chart diverges from one baseline — unblocked above,
rotblocked below — so the split reads from position before colour, on a single shared scale. The
slot order in `SegmentSlot` is the adjacency `ChartPalette`'s split palette was validated for, so it
is not reordered by height.

## Charts

`ui/ChartPalette.kt` was validated with a contrast validator against this app's own card
surfaces, not eyeballed. Slot **order** is the colourblind-safety mechanism — assign slots in order,
never cycle, never sort by value. Light mode carries a sub-3:1 contrast warning on four slots, so
the per-target breakdown table under the usage chart is required relief, not decoration.
Re-run the validator if the palette changes.
