---
paths:
  - "**/com/ezhou/rotblock/block/TimerHud*"
  - "**/com/ezhou/rotblock/block/TrackingNotification*"
  - "**/com/ezhou/rotblock/block/HudGeometry*"
---

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
session in progress ends (`hudDismissedThisSession` in `EnforcementUi`, cleared everywhere a session
closes), and the very next target to start accruing gets the ring back. If a drag could turn the
ring off for good, its absence would stop meaning "nothing is being counted" and start meaning "or
you swiped it away once, weeks ago, and forgot" — the exact ambiguity this whole section exists to
remove. That is also why the ring now defaults on rather than off: the signal is only honest from
the first run if there was never a decision to make. The Setup toggle is still the one way to turn
it off for good; that is a decision, made once, and it says so.
