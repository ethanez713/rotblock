---
paths:
  - "**/com/ezhou/rotblock/policy/**"
  - "**/com/ezhou/rotblock/pause/**"
  - "**/com/ezhou/rotblock/quota/**"
  - "**/com/ezhou/rotblock/block/**"
  - "**/com/ezhou/rotblock/workout/**"
  - "**/com/ezhou/rotblock/service/**"
  - "**/com/ezhou/rotblock/browser/BrowserEscape*"
  - "**/com/ezhou/rotblock/ui/FrictionActivity*"
  - "**/com/ezhou/rotblock/ui/PauseRequestActivity*"
---

# Relaxations and enforcement: corollaries of the deadline invariant

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
