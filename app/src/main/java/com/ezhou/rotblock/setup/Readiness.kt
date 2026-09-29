package com.ezhou.rotblock.setup

/**
 * What rotblock needs from the phone, what it has, and what to say when those disagree.
 *
 * This exists because of the failure this app is built around: everything it does is invisible when
 * it works, so everything it does is *also* invisible when it does not. "Nothing is blocked" looks
 * exactly the same whether the rules are fine and the service was killed, the accessibility toggle
 * was switched off in a settings sweep, or a rule asks for greyscale it was never granted. On one
 * phone with a cable attached that is an afternoon of guessing; for anyone else it is an app that
 * silently does nothing and gets uninstalled.
 *
 * So: one model, three surfaces. Onboarding walks the same requirements a new user has to satisfy,
 * the Setup tab shows their live state, and the Blocks screen banners the ones that mean nothing is
 * being enforced. Adding a requirement means adding it here and nowhere else.
 *
 * Deliberately pure and Android-free so the interesting part — which combination of facts means
 * *broken* rather than merely *missing* — is testable without a device. [SetupFacts] is gathered
 * separately in [gatherSetupFacts].
 */

/** How much a requirement matters, which decides where it is allowed to interrupt you. */
enum class Need {
    /** Without this, rules do not work. Worth a banner. */
    REQUIRED,

    /** Without this, they work until Android decides otherwise. Worth a nudge, not a banner. */
    RECOMMENDED,

    /** Only relevant because of something the user switched on. Never nags on its own. */
    OPTIONAL,
}

enum class Status {
    READY,

    /** Never granted. The ordinary case, and the one onboarding is for. */
    MISSING,

    /**
     * Granted, and still not working.
     *
     * The whole reason this file exists. An accessibility service that Android lists as enabled but
     * has not bound is the single most confusing state this app can be in, and it is common: One UI
     * kills sideloaded services, and a force-stop silently drops rotblock from the enabled list
     * while leaving the toggle looking on. "Turn it on" is useless advice here; "turn it off and on
     * again, then set battery to unrestricted" is the actual fix.
     */
    BROKEN,
}

/** What the user has to do about it, kept abstract so this file stays free of Android imports. */
sealed interface Fix {
    /** Somewhere in system Settings. The screen is resolved by the UI layer. */
    data class OpenSettings(val screen: SettingsScreen) : Fix

    /** A runtime permission rotblock can ask for itself. */
    data object AskForNotifications : Fix

    /** Kick the accessibility service's component so Android re-binds it. See [EnforcementRevival]. */
    data object AttemptRebind : Fix

    /**
     * Write rotblock back into Android's enabled-accessibility-services list, which it can do for
     * itself where `WRITE_SECURE_SETTINGS` was granted. See [AccessibilityEnrolment].
     *
     * Offered whether or not [shouldRestoreEnrolment] would have acted unprompted: that guard exists
     * to keep the app from undoing a decision nobody asked it to undo, and a finger on this button
     * *is* the asking.
     */
    data object SelfEnable : Fix

    /** Needs a computer. Shown as copyable text, never as a button that cannot work. */
    data class RunOverAdb(val command: String) : Fix
}

enum class SettingsScreen {
    ACCESSIBILITY, OVERLAY, BATTERY, APP_NOTIFICATIONS, NOTIFICATION_LISTENER, USAGE_ACCESS,
}

enum class RequirementId {
    ENFORCEMENT, BLOCK_SCREEN, BATTERY, NOTIFICATIONS, GREYSCALE, DETECTION, WORKOUT_DETECTION,
    USAGE_ACCESS,
}

data class Requirement(
    val id: RequirementId,
    val title: String,
    val status: Status,
    val need: Need,
    /**
     * What is not happening while this is unmet, in the present tense.
     *
     * Consequence rather than instruction, and never "grant permission X": nobody grants a
     * permission because an app asked, they grant it because they understand what it buys.
     */
    val consequence: String,
    /** What to do about it, in the order to do it. */
    val fix: String,
    val action: Fix?,
) {
    val isReady: Boolean get() = status == Status.READY
}

/**
 * Everything [requirementsFor] needs to know, gathered in one pass.
 *
 * One snapshot rather than a set of live queries, so a list of requirements can never be internally
 * inconsistent — half of it describing the phone before a permission was granted and half after.
 */
data class SetupFacts(
    /** Android lists rotblock in its enabled accessibility services. */
    val accessibilityEnabled: Boolean,
    /** The service is bound and running in this process right now. */
    val serviceConnected: Boolean,
    /** Epoch ms the service last recorded that it was alive. 0 means never. */
    val lastAliveMs: Long,
    val overlayGranted: Boolean,
    val notificationsGranted: Boolean,
    /** Exempt from battery optimisation, i.e. Android is not free to kill the service. */
    val batteryUnrestricted: Boolean,
    /** `WRITE_SECURE_SETTINGS`, which only ADB can grant. */
    val secureSettingsGranted: Boolean,
    /** Installed by an app store rather than sideloaded. Decides whether ADB advice is relevant. */
    val installedFromStore: Boolean,
    /** The user asked for the countdown notification. */
    val notificationReadoutWanted: Boolean,
    /** How long this process has been alive. See [BIND_GRACE_MS]. */
    val processUptimeMs: Long,
    /** How many enabled rules ask for greyscale. */
    val greyscaleRuleCount: Int,
    /** Ours, so the ADB advice is copyable rather than approximately right. */
    val packageName: String,
    val nowMs: Long,
    /** Feeds whose view ids have stopped matching, per [rottedFeeds]. Usually empty. */
    val rottedFeeds: List<FeedProbe> = emptyList(),
    /** When Setup last ran the block-screen self test. 0 means never. See [blockScreen]. */
    val blockTestAtMs: Long = 0L,
    /** Whether the user reported seeing the test overlay. Meaningless while [blockTestAtMs] is 0. */
    val blockTestSeen: Boolean = false,
    /** The user asked for workout mode. Nothing below matters when this is off. */
    val workoutModeWanted: Boolean = false,
    /** Notification-listener access is granted to rotblock. */
    val notificationListenerGranted: Boolean = false,
    /** The listener is bound right now. Meaningless while [notificationListenerGranted] is false. */
    val notificationListenerConnected: Boolean = false,
    /** Usage access, so Insights can read every app's foreground time. Rules do not need it. */
    val usageAccessGranted: Boolean = false,
)

/**
 * How long after the last heartbeat a connected-looking service is called stale.
 *
 * The service writes one at most once a minute, and only while accessibility events are arriving —
 * so a phone sitting idle legitimately has an old one. This is not "is it alive", which
 * [SetupFacts.serviceConnected] answers directly; it is only used to say *how long* enforcement has
 * been down once something else has established that it is.
 */
private const val STALE_HEARTBEAT_MS = 5 * 60 * 1000L

/**
 * How long after this process started an unbound service is given the benefit of the doubt.
 *
 * The accessibility service lives in this process, and when the process is fresh — the app was
 * opened after being killed — Android has a moment's work to do before it binds. Reporting BROKEN
 * in that window means opening rotblock can greet you with a red banner saying nothing is being
 * blocked, which then clears itself two seconds later.
 *
 * Optimistic on purpose, and it costs nothing: the list is re-read every couple of seconds, so a
 * service that really is dead is reported the moment the grace runs out. A screen that cries wolf
 * is a screen people stop reading, and this one has exactly one job — being believed when it says
 * something is wrong.
 */
private const val BIND_GRACE_MS = 6_000L

/**
 * The full list, in the order they matter. Requirements that do not apply are simply absent —
 * greyscale is not a missing permission if no rule ever asked for it.
 */
fun requirementsFor(facts: SetupFacts): List<Requirement> = buildList {
    add(enforcement(facts))
    add(blockScreen(facts))
    add(battery(facts))
    if (facts.notificationReadoutWanted) add(notifications(facts))
    if (facts.greyscaleRuleCount > 0) add(greyscale(facts))
    if (facts.rottedFeeds.isNotEmpty()) add(detection(facts))
    if (facts.workoutModeWanted) add(workoutDetection(facts))
    add(usageAccess(facts))
}

/** The ones that mean nothing is being enforced right now. Drives the banner. */
val List<Requirement>.blocking: List<Requirement>
    get() = filter { it.need == Need.REQUIRED && !it.isReady }

private fun enforcement(facts: SetupFacts): Requirement {
    val status = when {
        !facts.accessibilityEnabled -> Status.MISSING
        facts.serviceConnected -> Status.READY
        // Switched on, not bound yet, and the process is young enough that this is probably just
        // Android getting round to it. See [BIND_GRACE_MS].
        facts.processUptimeMs < BIND_GRACE_MS -> Status.READY
        else -> Status.BROKEN
    }
    return Requirement(
        id = RequirementId.ENFORCEMENT,
        title = "Enforcement",
        status = status,
        need = Need.REQUIRED,
        consequence = when (status) {
            Status.READY -> "Watching what is in front of you, on this device only."
            Status.MISSING -> "Nothing is blocked. Rules are saved, and none of them are running."
            Status.BROKEN ->
                "Android lists rotblock as switched on, but the service is not running, so " +
                    "nothing is being blocked." + downFor(facts)
        },
        fix = when (status) {
            Status.READY -> ""
            // With the secure-settings grant rotblock can write itself back into Android's list, so
            // the honest instruction is the button rather than a walk to Settings. The walk is still
            // spelled out underneath it, because the grant can be there and the write still fail.
            Status.MISSING -> if (facts.secureSettingsGranted) {
                "The button below puts rotblock back in Android's accessibility list without " +
                    "leaving the app. If it does not take, Settings → Accessibility → Installed " +
                    "apps → rotblock enforcement, and turn it on."
            } else {
                "Settings → Accessibility → Installed apps → rotblock enforcement, and turn it on." +
                    if (facts.installedFromStore) {
                        ""
                    } else {
                        " Android hides that switch for apps installed outside the Play Store; if " +
                            "it is greyed out, the ADB command below unhides it."
                    }
            }
            // Deliberately not "turn it on" -- it is already on, which is exactly why this state
            // is so confusing. Off and on again is the only thing that rebinds it.
            Status.BROKEN ->
                "Try the button below first — it does the same thing from in here. Otherwise turn " +
                    "the switch off and back on in Settings → Accessibility. If it keeps " +
                    "happening, the phone is killing rotblock in the background — set its battery " +
                    "usage to unrestricted."
        },
        action = when {
            status == Status.BROKEN -> Fix.AttemptRebind
            // Listed-but-not-bound is a different fault and is not this one's to repair: the
            // component is enrolled already, so writing the list again changes nothing.
            status == Status.MISSING && facts.secureSettingsGranted -> Fix.SelfEnable
            else -> Fix.OpenSettings(SettingsScreen.ACCESSIBILITY)
        },
    )
}

private fun downFor(facts: SetupFacts): String {
    if (facts.lastAliveMs <= 0L) return ""
    val downMs = facts.nowMs - facts.lastAliveMs
    if (downMs < STALE_HEARTBEAT_MS) return ""
    val hours = downMs / (60 * 60 * 1000L)
    val minutes = downMs / (60 * 1000L)
    return if (hours > 0) {
        " Last seen about ${hours}h ago."
    } else {
        " Last seen about ${minutes}m ago."
    }
}

/**
 * Whether the last block-screen self test said the overlay never actually appeared.
 *
 * The permission check alone cannot tell this apart from a phone that is genuinely fine -- some
 * OEMs grant `SYSTEM_ALERT_WINDOW` and still suppress the window, which is a state
 * [SetupFacts.overlayGranted] reads as ready. Only a human looking at the screen can catch it, so
 * this is the one requirement in the file driven by a self-report rather than a system query.
 */
private fun blockScreenTestFailed(facts: SetupFacts): Boolean =
    facts.blockTestAtMs > 0L && !facts.blockTestSeen

private fun blockScreen(facts: SetupFacts): Requirement {
    val status = when {
        !facts.overlayGranted -> Status.MISSING
        blockScreenTestFailed(facts) -> Status.BROKEN
        else -> Status.READY
    }
    return Requirement(
        id = RequirementId.BLOCK_SCREEN,
        title = "The block screen",
        status = status,
        need = Need.REQUIRED,
        consequence = when (status) {
            Status.READY -> "Blocks can explain themselves, and offer a way back out."
            // Not a fail-open, which is why the wording is about being stranded rather than about
            // being unblocked: showBlock() still ejects you. It just cannot say why, and a
            // website block loses the "Leave this site" button that stops the browser being a
            // dead end.
            Status.MISSING ->
                "Blocked apps get closed with no explanation, and a blocked website has no way " +
                    "out except closing the browser, which reopens on the same page."
            Status.BROKEN ->
                "Android says rotblock can draw over other apps, but the test found nothing on " +
                    "screen -- this phone is granting the permission and then silently dropping " +
                    "the window, so a real block would do the same."
        },
        fix = when (status) {
            // Says how to clear it, because nothing else will. This is the only requirement here
            // resting on something the user typed rather than something the phone reports, so it
            // cannot correct itself the way every other one does -- and a red banner with no way
            // down is how a screen stops being read.
            Status.BROKEN ->
                "Run the test again from Setup if that was a mistake. Otherwise check Settings → " +
                    "Apps → rotblock → Display over other apps is actually on; if it is, this " +
                    "phone's manufacturer is dropping the window some other way, and there is no " +
                    "setting here that fixes it."
            else -> "Settings → Apps → rotblock → Display over other apps."
        },
        action = Fix.OpenSettings(SettingsScreen.OVERLAY),
    )
}

private fun battery(facts: SetupFacts): Requirement = Requirement(
    id = RequirementId.BATTERY,
    title = "Battery",
    status = if (facts.batteryUnrestricted) Status.READY else Status.MISSING,
    need = Need.RECOMMENDED,
    consequence = if (facts.batteryUnrestricted) {
        "Android will leave the service running."
    } else {
        "Android is free to kill rotblock in the background. When it does, blocking stops until " +
            "something wakes it up again, and nothing tells you."
    },
    fix = "Settings → Apps → rotblock → Battery → Unrestricted. On a Galaxy, also take rotblock " +
        "out of Settings → Battery → Background usage limits → Deep sleeping apps.",
    action = Fix.OpenSettings(SettingsScreen.BATTERY),
)

private fun notifications(facts: SetupFacts): Requirement = Requirement(
    id = RequirementId.NOTIFICATIONS,
    title = "Countdown notification",
    // Not MISSING: the user switched the readout on and it is not appearing, which is a broken
    // promise rather than an ungranted permission.
    status = if (facts.notificationsGranted) Status.READY else Status.BROKEN,
    need = Need.OPTIONAL,
    consequence = if (facts.notificationsGranted) {
        "Shows what is left of an allowance while you spend it."
    } else {
        "You asked for the countdown as a notification, and Android is not letting rotblock post " +
            "it, so it never appears."
    },
    fix = "Allow notifications for rotblock.",
    action = Fix.AskForNotifications,
)

private fun greyscale(facts: SetupFacts): Requirement = Requirement(
    id = RequirementId.GREYSCALE,
    title = "Greyscale",
    // Same reasoning as the notification: these rules say they drain the colour, and they do not.
    status = if (facts.secureSettingsGranted) Status.READY else Status.BROKEN,
    need = Need.OPTIONAL,
    consequence = if (facts.secureSettingsGranted) {
        "Rules that drain the colour can do it."
    } else {
        val n = facts.greyscaleRuleCount
        "${if (n == 1) "One rule asks" else "$n rules ask"} for greyscale, and it is silently " +
            "doing nothing: Android only grants that permission over a USB cable."
    },
    fix = "Connect the phone to a computer with USB debugging on and run this once. It survives " +
        "reboots, but not reinstalling rotblock.",
    action = Fix.RunOverAdb(
        "adb shell pm grant ${facts.packageName} android.permission.WRITE_SECURE_SETTINGS",
    ),
)

/**
 * A feed rule that is switched on and has never once recognised its feed.
 *
 * RECOMMENDED rather than REQUIRED on purpose, and it is the only requirement here built on a guess
 * rather than a fact about the phone. The banner on the Blocks screen means "nothing is being
 * enforced"; a heuristic that can be wrong must never be allowed to raise it, or the one signal
 * this app has stops being believed.
 */
private fun detection(facts: SetupFacts): Requirement {
    val names = facts.rottedFeeds.joinToString(" and ") { it.label }
    return Requirement(
        id = RequirementId.DETECTION,
        title = "Feed detection",
        // Not MISSING: the rule exists, it is switched on, and it is quietly doing nothing --
        // the same shape as a greyscale rule on a phone that never granted the permission.
        status = Status.BROKEN,
        need = Need.RECOMMENDED,
        consequence = "You have opened the app behind $names many times with the rule on and the " +
            "feed itself was never recognised, so it has not been blocked. That usually means an " +
            "app update renamed the thing rotblock looks for.",
        fix = "Open the feed on this phone with a computer attached and run this, then look in " +
            "w.xml for the container the feed sits in. The ids live in FeedRegistry.kt.",
        action = Fix.RunOverAdb("adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml"),
    )
}

/**
 * Workout mode's automatic half depends on Android's notification-listener grant, which is a
 * different permission from everything else here and one nobody has a reason to grant until they
 * switch workout mode on -- so, like [greyscale], it is only listed at all once they have.
 *
 * Same shape as [enforcement]: MISSING is the ordinary "never granted" case, and BROKEN is granted
 * with the listener not bound, which reads identically from a permission check alone and needs its
 * own advice. The same [BIND_GRACE_MS] applies for the same reason -- opening the app can catch the
 * listener a moment before Android has rebound it.
 */
private fun workoutDetection(facts: SetupFacts): Requirement {
    val status = when {
        !facts.notificationListenerGranted -> Status.MISSING
        facts.notificationListenerConnected -> Status.READY
        facts.processUptimeMs < BIND_GRACE_MS -> Status.READY
        else -> Status.BROKEN
    }
    return Requirement(
        id = RequirementId.WORKOUT_DETECTION,
        title = "Workout detection",
        status = status,
        // Manual start/stop still works either way -- this only decides whether workout mode can
        // switch itself on, not whether it works at all.
        need = Need.OPTIONAL,
        consequence = when (status) {
            Status.READY -> "Workout mode starts and ends on its own when you start a workout."
            Status.MISSING ->
                "rotblock cannot tell when a workout starts, so workout mode only runs when you " +
                    "start it here by hand."
            Status.BROKEN ->
                "Notification access is granted, but the listener is not running -- so a workout " +
                    "will not switch this on by itself."
        },
        fix = when (status) {
            Status.READY -> ""
            Status.MISSING ->
                "Settings → Notifications → Notification access, and turn rotblock on. Over ADB, " +
                    "this does the same thing: adb shell cmd notification allow_listener " +
                    "${facts.packageName}/.workout.WorkoutListener"
            Status.BROKEN ->
                "Turn it off and back on in Settings → Notifications → Notification access. If it " +
                    "keeps happening, the phone is killing rotblock in the background -- set its " +
                    "battery usage to unrestricted."
        },
        action = Fix.OpenSettings(SettingsScreen.NOTIFICATION_LISTENER),
    )
}

/**
 * Whole-phone screen time. Rules keep working either way — this only decides whether Insights can
 * see apps that have no rule — so, like [workoutDetection], it is optional and never raises the
 * Blocks banner.
 */
private fun usageAccess(facts: SetupFacts): Requirement {
    val granted = facts.usageAccessGranted
    return Requirement(
        id = RequirementId.USAGE_ACCESS,
        title = "Usage access",
        status = if (granted) Status.READY else Status.MISSING,
        need = Need.OPTIONAL,
        consequence = if (granted) {
            "Insights can show time spent in every app, alongside rotblocked sites and feeds."
        } else {
            "Insights can only show rotblocked time."
        },
        fix = if (granted) {
            ""
        } else {
            "Settings → Apps → Special app access → Usage access, and turn rotblock on. Over " +
                "ADB, this does the same thing: adb shell appops set ${facts.packageName} " +
                "GET_USAGE_STATS allow"
        },
        action = Fix.OpenSettings(SettingsScreen.USAGE_ACCESS),
    )
}
