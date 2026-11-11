---
paths:
  - "**/com/ezhou/rotblock/browser/**"
  - "**/com/ezhou/rotblock/block/FeedRegistry*"
  - "**/com/ezhou/rotblock/service/Inspection*"
  - "**/com/ezhou/rotblock/service/VisitMemory*"
  - "**/com/ezhou/rotblock/service/TargetConfidence*"
---

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
- **Only a read that actually looked may vote, or clear a pending vote.** `PageInspector.harvestPageSignals`
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
`PageInspector.harvestPageSignals` already draws for the link vote, one layer up. Reads land about once a second
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
