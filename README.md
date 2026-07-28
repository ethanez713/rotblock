# rotblock

A local app and website blocker for Android, built to replace a paid blocker subscription.

Kotlin + Compose, single module, sideloaded. **No network permission**, no accounts, no sync — the
usage history and audit log physically cannot leave the device, because the app has no way to send
them anywhere.

## What it does

- **Blocks apps**, outright or on a rolling time quota ("3 minutes out of any 60").
- **Blocks websites** by domain, path prefix (`youtube.com/shorts`), or keyword, read from the
  browser's address bar — so it works regardless of DNS or VPN settings.
- **Blocks short-form feeds inside apps** you otherwise want to keep, like Reels, by recognising the
  feed's own view hierarchy.
- **Friction**: a few seconds of blank countdown before a chosen app opens. It always lets you
  through; it just puts a gap between the reflex and the feed.
- **Greyscale** while a target is open, if you grant the ADB permission for it.
- **Pauses** with a cooldown and a daily budget, and an audit log of every one you took.
- **Insights**: where the time actually went.

## The design constraint everything follows from

**Nothing fails open.** Every relaxation — a pause, a quota, a queued policy edit — is a *deadline
persisted to disk*, and the decision function is a pure fold over those timestamps. There is no
"blocking is currently off" flag and no timer that turns blocking back on.

So a crash, a force-stop, a reboot, or a background job that never runs all resume blocking on
their own. That is the entire point: the failure this exists to prevent is *"disabled a policy for
one minute, forgot about it for three weeks."*

The corollary is that it has to be cheap enough to leave enabled — an always-on accessibility
service that shows up in the battery screen gets switched off, and a blocker that gets switched off
blocks nothing. Battery is treated as a correctness requirement, one rank below never failing open.

[`CLAUDE.md`](CLAUDE.md) is the engineering documentation: the invariants, what breaks them, and
the bugs that were found the hard way on a real device.

## Build

```
./gradlew assembleDebug
./gradlew testDebugUnitTest      # 98 tests, all pure JVM — no device needed
```

JDK 17 and an Android SDK with platform 35. AGP 8.6.0 / Gradle 8.7 / Kotlin 2.0.21,
compileSdk+targetSdk 35, minSdk 29. No Android Studio required.

Tests cover the logic that has to be right and needs no device: rolling-window arithmetic at the
window edges, quota boundaries to the millisecond, weaken-vs-strengthen classification of policy
edits, URL rule matching, pause budgets, the 4am day rollover, and which way a screen inspection
fails when it cannot read anything.

## Install

Android 13+ hides accessibility for sideloaded apps behind "Restricted settings", so a few grants
have to come over ADB:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.ezhou.rotblock ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set com.ezhou.rotblock SYSTEM_ALERT_WINDOW allow
adb shell appops set com.ezhou.rotblock android:get_usage_stats allow
adb shell pm grant com.ezhou.rotblock android.permission.WRITE_SECURE_SETTINGS   # greyscale only
```

Then enable **rotblock enforcement** in Settings → Accessibility. On Samsung/One UI devices also set
Battery → **Unrestricted** and remove rotblock from "Deep sleeping apps", or the system kills it.

## What it needs, and why

| Permission | Why |
|---|---|
| Accessibility service | The only enforcement path: which app is in the foreground, and the address bar text in browsers. |
| `SYSTEM_ALERT_WINDOW` | Draws the block screen. Without it, blocking degrades to ejecting you to the home screen. |
| `QUERY_ALL_PACKAGES` | Lists installed apps so you can pick what to block. |
| `PACKAGE_USAGE_STATS` | Backfills usage history. |
| `WRITE_SECURE_SETTINGS` | Greyscale only. Grantable solely over ADB; the app works fine without it. |

There is deliberately **no `INTERNET` permission**, and no networking library in the dependency
tree. Cloud backup and device-to-device transfer are both excluded in
`res/xml/data_extraction_rules.xml`.

Note that debug builds log the URLs they inspect to logcat, behind `BuildConfig.DEBUG`. Release
builds log nothing.

## Fragile by nature

`browser/BrowserRegistry.kt` and `block/FeedRegistry.kt` hold view-ids that app updates break. They
are kept as data rather than scattered constants, and a miss degrades to "not blocked" rather than a
crash or a false block. To find current ids for a browser or feed that has drifted, open it and run:

```
adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml
```

## Status

Personal project, built for one phone and used daily. Published as a reference — it is not on any
app store, and there is no release signing config in the repo.
