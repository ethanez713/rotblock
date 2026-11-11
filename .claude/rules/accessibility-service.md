---
paths:
  - "**/com/ezhou/rotblock/service/**"
  - "**/com/ezhou/rotblock/block/Grayscale*"
---

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
   `OutageJournal.beat()` piggy-backs on them and its heartbeat was two seconds old — with the last screen read 22
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
   `TYPE_WINDOW_CONTENT_CHANGED`, and `PageInspector.requestInspect` returns immediately for a package that is not
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
   in `applyTarget` on every read. `PageInspector.requestInspect` declines everything else, so once a game is the
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
