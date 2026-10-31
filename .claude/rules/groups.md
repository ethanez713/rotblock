---
paths:
  - "**/com/ezhou/rotblock/policy/**"
  - "**/com/ezhou/rotblock/quota/**"
  - "**/com/ezhou/rotblock/block/FrictionGate*"
  - "**/com/ezhou/rotblock/ui/BlocksScreen*"
---

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
