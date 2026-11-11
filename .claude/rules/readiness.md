---
paths:
  - "**/com/ezhou/rotblock/setup/**"
---

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
`OutageJournal.reportPreviousOutage` uses. Heard from, never told to stop, no longer listed: that is a drop, not a
decision. A phone where enforcement has never run is left alone too, so a fresh install cannot
switch on its own accessibility service before the disclosure has been shown — it only ever puts
back what was already running. And the merge may only ever *add* rotblock: a list already containing
us is returned byte for byte rather than rebuilt, because clobbering somebody's screen reader would
be far worse than the fault being repaired, and this is the only code in the app that could.

This is not the same fault as listed-but-not-bound, which `EnforcementRevival` owns; writing the
list again for a component that is already enrolled changes nothing.
