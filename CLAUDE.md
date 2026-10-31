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

## Rules that hold everywhere

1. **The accessibility service must never crash.** A dead service is a silently unblocked phone,
   which is worse than not having the app. Every entry point goes through `guard()`.
2. **Never walk the node tree in `onAccessibilityEvent`.** Node reading happens on the inspector
   coroutine, debounced and rate-limited.
3. **Anything degenerate resolves toward more blocking**, and accounting errs the other way: losing
   accrued time gives quota back, over-charging takes it away, so where they conflict lose the time.
4. **Emergency skip is never denied, only recorded loudly.** No block may be a dead end: if the only
   way out is disabling the policy, the app has caused what it exists to prevent.
5. **Never leave the phone monochrome.** Screen-off and teardown restore colour unconditionally.
6. **No alarms, no `WorkManager`, no wakelocks, no foreground service, no boot receiver.** Nothing may
   wake a sleeping phone, and no repeating callback may run that is not armed for a deadline. When
   battery and enforcement conflict, enforcement wins and the cost gets written down.
7. **No `INTERNET` permission, ever**, and no host without a rule is ever recorded.
8. **Fixtures for screen reads come off a device capture, not out of a guess.** When usage looks
   wrong, read Setup → **Right now** before theorising.

## Design record

The full reasoning lives in `.claude/rules/`, which Claude Code loads when a matching file is read.
Read the file directly when the work crosses into its area from elsewhere.

| Topic | File |
|---|---|
| Pauses, schedules, the friction countdown, escapes, the daily pool, workout mode | `relaxations.md` |
| Groups and how policies combine (`MergeStrategy`) | `groups.md` |
| Why the timer under-counted, evaluation storms, what a screen read costs | `usage-accounting.md` |
| Rules for the accessibility service | `accessibility-service.md` |
| Battery: the second requirement | `battery.md` |
| Fragile by nature: address bars, link consensus, page signatures, view-ids | `page-reading.md` |
| Readiness: knowing when it is not working | `readiness.md` |
| The countdown readout and why absence is the point | `readouts.md` |
| The mark, logos, sorting, screen time, charts | `ui-notes.md` |

On-device regression checks for the rules above, and the one-time sideload setup, live in the
`device-regressions` skill.

## Build & test

```
./gradlew assembleDebug
./gradlew testDebugUnitTest      # all pure JVM — no device needed
```

Needs JDK 17 and an Android SDK with platform 36; no Android Studio required.

targetSdk tracks whatever Play requires of new submissions; 36 is the bar from 31 Aug 2026.
A *clean* build APK is ~24MB; an incremental one balloons to ~55MB from zipflinger padding —
that is packaging slack, not content.
