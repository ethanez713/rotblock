---
paths:
  - "**/com/ezhou/rotblock/ui/**"
  - "**/com/ezhou/rotblock/insights/**"
  - "**/com/ezhou/rotblock/policy/PolicyOrder*"
  - "**/res/drawable*/ic_*"
---

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
