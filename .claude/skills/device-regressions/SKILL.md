---
name: device-regressions
description: Manual on-device regression checks for rotblock, plus the one-time sideload device setup. Use when verifying enforcement, friction, pause, schedule, group, quota, workout or capture changes on the phone, or when setting up a fresh device.
---

# Device regressions

## One-time device setup

Only for sideloaded builds; a Play install needs none of it. Android 13+ hides accessibility for
sideloaded apps behind "Restricted settings":

```
adb shell appops set com.ezhou.rotblock ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set com.ezhou.rotblock SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.ezhou.rotblock android.permission.WRITE_SECURE_SETTINGS
adb shell cmd notification allow_listener com.ezhou.rotblock/.workout.WorkoutListener
adb shell appops set com.ezhou.rotblock GET_USAGE_STATS allow
```

The usage-stats line is optional too: without it Insights shows rotblocked time only.

That last line is workout mode's automatic half, and it is the only optional one here: without it
workout mode still works, but only when started by hand from Setup. It can also be granted in
Settings → Notifications → Notification access.

That last one is worth more than it looks. It is what greyscale needs, and it is also what lets
rotblock put itself back into Android's accessibility list after a force-stop drops it — which on
One UI is the difference between enforcement returning on its own and it staying off until someone
notices. See "Knowing when it is not working".

Then enable the service in Settings → Accessibility. On Samsung/One UI devices also set Battery →
**Unrestricted** and remove rotblock from "Deep sleeping apps", or the system kills it.

## Manual regressions

Manual regressions, since these need a device:

- Give a hard-blocked app a 10-minute daily pool and open it: it must not block, the ring must count
  down from what is left of the pool, and Setup → **Right now** must say "charging, on its daily
  pool". Sit in it until the pool runs out without touching anything — the block must arrive on its
  own. Give it a quota rule instead: after the pool, the quota's bar must show the pool time spent.

- Start a pause, sit inside a blocked app without touching anything, and confirm the block returns
  on its own when the pause lapses.
- With a friction rule on a website: open it in Samsung Internet, wait the countdown out, and
  confirm the page loads **once** rather than re-entering the countdown. Then scroll down until the
  address bar hides, leave to another app, come back — it must still be treated as that site.
- Burn a website quota to zero, take "Leave this site", then close and reopen the browser: it must
  come back on the loopback page, not on the blocked one — and no "Invalid address" toast.
- Then, still with the quota at zero, take "Leave this site" and immediately reopen the blocked
  site: it must block again. Also tap the address bar on a blocked site and type a few letters —
  the block must not lift just because the bar stopped reading as a URL.
- The other direction, which had no symptom until a capture caught it: with the quota at zero, take
  "Leave this site" and immediately pull the notification shade down and hold it open for ten
  seconds. The block must **not** come back. Every read in the verify window bounces off the shade,
  and treating that as "the browser refused" re-blocked a page already sitting on the loopback URL —
  four escapes in 24 seconds, five block screens, no way to get past it by doing anything right.
  Setup → **Right now** must show "nothing readable yet, still waiting". A browser that genuinely
  refuses is the case above and must still re-block after about three seconds, because a read that
  reached the browser's own window is evidence even when the bar in it was unreadable.
- Scroll a quota'd site until the address bar hides, switch to another app, come back, and keep
  scrolling: Setup → **Right now** must still say it is counting time against that site, and the
  block must arrive on schedule. This is the one that had no symptom.
- With a friction rule on a website, take "Not really — take me back": the browser must end up on
  the loopback page, so reopening it does not land back on the site with the countdown spent.
- Put a hard block on a schedule ending a couple of minutes out, sit inside the app without
  touching anything, and confirm the block lifts on its own at the boundary. Then set one starting
  a couple of minutes out, sit in the app the same way, and confirm the block *arrives* — this is
  the direction that fails open, and neither edge fires an event.
- With a quota'd app outside its scheduled hours, use it a while and check Setup → **Right now**
  says "outside its hours, not charging" and the quota bar has not moved.
- The acquisition case, which is the one with no symptom: open a quota'd site, scroll until the
  address bar is gone, then switch away and force-stop rotblock so nothing is remembered. Come back
  to the still-scrolled page. It must start counting within a few seconds — Setup → **Right now**
  should show a read ending `links=N -> web:…`. Before `LinkConsensus` this sat there forever.
- Then the false-block direction: on a page with a rule, follow an outbound link to a site with no
  rule and scroll it immediately. It must **not** be blocked, and the read must stay `Unreadable`
  rather than inventing the previous host.
- The search-results direction, which is the same failure from the other side: with a rule on
  reddit, search Google for "reddit" and scroll the results until the address bar goes away. Nothing
  may block — every link on screen agrees on reddit.com and the page is Google. Then tap a result:
  it must block on arrival.
- The same direction again, on the page that actually got through: ask Google something whose
  results are mostly reddit threads with a couple of YouTube videos mixed in — so the links agree on
  reddit but **not one of them is a google.com link** — and scroll until the address bar is gone.
  Nothing may block, and no friction countdown may appear. This is the case the per-link veto cannot
  see; only the page title stops it.
- With a rule on reddit and a reddit tab open *in the background*, open Samsung Internet's tab
  switcher from some other tab. Nothing may block, and Setup → **Right now** should show the read
  saying several urls were on offer. Then check the same from a reddit tab in front: the block
  stays, because the target was already held.
- Start a friction countdown, switch away before it finishes, do something else for longer than the
  countdown, and come back. It must start the wait again from the top rather than letting you
  straight through.
- With a friction rule on a quota'd site, open it and let the countdown appear: the countdown ring
  must **not** be on screen over it, and the quota must not move while you sit there. Do the same
  with the pause cooldown. Before the interstitial announced itself this cost up to fifteen seconds
  of quota per visit, with the ring visibly counting it down.
- Take "Not really — take me back" on a friction countdown for a website, then reopen the browser:
  it must come up on the loopback page and stay there. No countdown for the site you just left.
  Same check after "Leave this site" on a real block screen.
- With a rule on reddit, leave the browser sitting on a scrolled reddit page, switch away and come
  back: it must still block on schedule (a restored target still enforces), and it must **not** put
  up a friction countdown (a restored target may not nag). Then scroll up so the address bar
  reappears — once a read confirms the target, the countdown is allowed again on the next visit.
- Arrive on a scrolled page with a rule, with no address bar readable at all, and confirm it still
  gets acquired — it now takes **two** agreeing scans, so allow a few seconds longer than before.
  Setup → **Right now** should show "links agree on … , awaiting a second scan" and then the
  acquisition. If it shows the first line forever, corroboration is broken.
- Pull down Quick Settings and tap the rotblock capture tile: the shade must stay open, nothing must
  launch, and the app underneath must be exactly where it was. Close the shade, wait a few seconds,
  then Setup → **Diagnostic captures** must list it. Open the file and confirm the node dump is
  there rather than "no readable window within 15s". Do this once from a browser and once from a
  plain app, since only the browser path goes through `requestInspect`.
- The fail-open with no symptom at all: get the service into a stale foreground and confirm it comes
  back. Open a quota'd site in Samsung Internet, leave to the home screen, come back, and keep
  scrolling. Setup → **Right now** must say it is counting against that site within a few seconds. If
  it recovered by the backstop rather than a normal app switch, the trail says so — look for
  "foreground recheck: … was stale, corrected to …". Before this, the service could sit for twenty
  minutes believing the launcher was in front while reddit was on screen, alive and enforcing nothing.
- The capture that used to vanish: tap the capture tile and then force-stop rotblock from Settings →
  Apps within the next fifteen seconds, before the dump has had a chance to land. A file must still
  appear, with its node dump saying the service was torn down while waiting. Before this, the tile
  tap wrote nothing at all — no file, no error, no trace — and it did so in the one situation a
  capture is worth most.
- Put two quota'd sites in a group with an allowance smaller than the two put together. Spend most
  of the group's time on the first, then open the second: it must block on arrival even though its
  own rule has time left, and the block screen must name the **group**. Setup → **Right now** and
  the group's card must both show the shared bar filling from either site.
- With a group over a website, burn it to zero and confirm the block screen still offers "Leave this
  site" — not "Close this app". The escape comes from the page, not from the rule that blocked it.
- Give a group a friction countdown and its member site none: opening the site must show the
  countdown once, named after the group, and waiting it out must not produce a second one from the
  site's own rule.
- Edit a group and remove a member: the change must go to the pending queue with a countdown, like
  any other loosening. Add one instead and it must land immediately. Rename the group and confirm
  its recorded blocks stay attached to it (Blocks → Most blocked keeps its count).
- Give a group hours it is not currently inside, and its member site an always-on rule. Use the site
  a while: the member's bar moves and the group's must not, and the group must not start counting
  that time when its window opens later.
- The one with no symptom at all, and the one this pair of rules exists for: set Settings → Lock
  screen → **Lock automatically** to a delay of a minute or so, open a quota'd app, press power to
  blank the screen, then press power again **within that delay** so no lock screen appears. Setup →
  **Right now** must be counting against that app again within a few seconds — before this, the
  ticker stayed stopped and the app was never enforced or charged again for as long as you stayed
  in it. Worth doing with a game, which emits no useful events of its own.
- Lock the phone inside a blocked app, unlock straight back into it, and confirm the block is there
  when the screen comes back. Then the direction that had no symptom: lock the phone inside a
  quota'd app, unlock, and go straight to the home screen instead — Setup → **Right now** must stop
  naming that app within a few seconds, and its quota bar must not move while you sit on the
  launcher. Worth doing once with a game that has no accessibility chatter at all, which is the
  case with no other route back.
- Spend a game's quota, go home, and reopen it by every route there is — its icon, the recents
  screen, a navigation swipe from another app. Each must block within about a second, and Setup →
  **Right now** may show "settle: … was stale, corrected to …" when the switch's own event was lost.
- Force-stop rotblock from Settings → Apps, then pull down the shade *without* opening rotblock:
  Android's accessibility list must have rotblock back in it, and the audit log must show it. Then
  the case that must NOT self-repair: turn the switch off deliberately in Settings → Accessibility,
  pull the shade down, and confirm it stays off.
- Lock the phone inside an app with a friction rule, force-stop rotblock while the screen is off,
  then unlock straight back into it: the countdown must be owed again. The visit ended when the
  screen did, and a restored "already served" mark skips the wait with nothing left to re-arm it.
- Press **back** on a friction countdown: it must behave like "Not really — take me back" (home for
  an app, the loopback page for a website), never drop you into the app. Waiting the countdown out
  and *then* pressing back is the one case that must let you in — the wait was served. To exercise
  the backstop under back, turn on developer options → **Don't keep activities**, start a countdown,
  press home and return: the countdown must come back from the top, however many times you do it.
- With the countdown ring switched on in Setup, open a quota'd app: the ring appears and counts
  down. Leave the app, lock the phone, let a pause start, or let the rule fall outside its hours —
  in every one of those the ring must go away, because a ring on screen means time is being charged.
  Drag the ring to a new spot, leave the app and come back: it must still be where you put it, and
  the same after a force-stop and reopen. Drag it onto the X that appears at the bottom of the
  screen and let go: it must disappear. Then leave the app and come back — the ring must be showing
  again, because the drop only lasted the session it happened in.
- Pause a blocked app and sit in it: a **squircle** (not a circle) countdown must appear, its border
  draining toward empty, and the block must return on its own when it reaches zero. Sit through the
  pause into accrual: the squircle must give way to the round ring, never both at once. Dismiss the
  squircle onto the X, then switch to another paused app — the squircle must return there, because a
  dismissal lasts only the session it happened in. Extend a running pause: the border must grow back
  out rather than restart, and it must count down the whole extended span.
- Start a workout in Strong and watch a phone with rules on it: within a second or two every
  enabled rule must block regardless of quota or schedule, and the screen must go greyscale
  everywhere *except* inside Strong. Setup → **Right now** must show a "Workout mode" row. End the
  workout: colour and normal rules must come back on their own.
- The same, but end the workout with the phone **locked in a pocket**, then unlock. Nothing may have
  been charged against a quota while the screen was off — check Setup → **Right now** and the
  quota bars. This is the one with no symptom: the workout ending is an event that arrives with the
  screen off, and the app you locked the phone inside is still the held target.
- With workout mode on, leave Strong and sit on another app: a reminder must appear after the
  configured wait and again every wait after that. Go back into Strong for a few seconds and leave
  again — the wait must start from the second departure, not carry over. Lock the phone for a while
  and unlock: the wait must start again rather than firing immediately.
- Take a reminder's "I'm doing something else" and stay put: the next one must be five minutes out,
  not one. Then open an app that is *blocked* while a reminder is on screen — the reminder must go
  away rather than sitting on top of the block screen.
- With Spotify (or anything) on the workout allowlist, start a workout and open it: it must not
  block, must keep its colour, and must never put up a "still mid-workout" nudge however long you
  sit there. Setup → **Right now** must say the workout is "allowing" it. Then leave for a
  non-allowed app: it blocks, goes grey, and the nudge clock starts from *that* departure. Give the
  allowed app a quota rule of its own and burn it down mid-workout — it must still block on its own
  rule, because the allowlist subtracts the override and nothing else.
- Mid-workout, take an emergency skip from a block screen. The pause screen must say it also ends
  workout mode before you confirm; afterwards the phone must come out of greyscale and stay out for
  the rest of that workout, even though Strong is still posting its notification.
- Force-stop rotblock mid-workout and reopen it: workout mode must still be live, re-derived from
  Strong's notification rather than restored from anything on disk.
- Turn workout mode on with notification access **not** granted: Setup must list "Workout detection"
  as missing, the Blocks banner must **not** go red, and "Start a workout now" must still work.
- Burn a quota to zero on an app rule with no schedule, then sit on the block screen without
  touching anything until its countdown reaches zero: the block must lift on its own. Nothing else
  re-evaluates an app target, so before the quota's own refill was armed for, it sat there.
- With a `youtube.com/shorts` rule, open a Short in Samsung Internet and leave it on screen for a
  minute without touching anything: the block must stay up continuously. It must not disappear and
  come back every few seconds. Then tap through to an ordinary watch page — the block must lift.
- With a `youtube.com/shorts` rule, open a Short in Samsung Internet and confirm it blocks even
  though the address bar never shows a path there. Then the page it used to fire on instead: open a
  normal watch page and **tap the video so the player controls appear**. It must not block — that
  tap is what used to trigger it, because the controls carry Previous/Next-video and the old
  signature keyed on exactly those. Check the YouTube home feed too, including scrolled down to a
  Shorts shelf, where the links are Shorts but the screen is not a player.
