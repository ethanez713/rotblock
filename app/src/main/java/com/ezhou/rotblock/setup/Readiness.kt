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

    /** Needs a computer. Shown as copyable text, never as a button that cannot work. */
    data class RunOverAdb(val command: String) : Fix
}

enum class SettingsScreen { ACCESSIBILITY, OVERLAY, BATTERY, APP_NOTIFICATIONS }

enum class RequirementId { ENFORCEMENT, BLOCK_SCREEN, BATTERY, NOTIFICATIONS, GREYSCALE }

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
            Status.MISSING ->
                "Settings → Accessibility → Installed apps → rotblock enforcement, and turn it on." +
                    if (facts.installedFromStore) {
                        ""
                    } else {
                        " Android hides that switch for apps installed outside the Play Store; if " +
                            "it is greyed out, the ADB command below unhides it."
                    }
            // Deliberately not "turn it on" -- it is already on, which is exactly why this state
            // is so confusing. Off and on again is the only thing that rebinds it.
            Status.BROKEN ->
                "Try the button below first — it does the same thing from in here. Otherwise turn " +
                    "the switch off and back on in Settings → Accessibility. If it keeps " +
                    "happening, the phone is killing rotblock in the background — set its battery " +
                    "usage to unrestricted."
        },
        action = when (status) {
            Status.BROKEN -> Fix.AttemptRebind
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

private fun blockScreen(facts: SetupFacts): Requirement = Requirement(
    id = RequirementId.BLOCK_SCREEN,
    title = "The block screen",
    status = if (facts.overlayGranted) Status.READY else Status.MISSING,
    need = Need.REQUIRED,
    consequence = if (facts.overlayGranted) {
        "Blocks can explain themselves, and offer a way back out."
    } else {
        // Not a fail-open, which is why the wording is about being stranded rather than about
        // being unblocked: showBlock() still ejects you. It just cannot say why, and a website
        // block loses the "Leave this site" button that stops the browser being a dead end.
        "Blocked apps get closed with no explanation, and a blocked website has no way out " +
            "except closing the browser, which reopens on the same page."
    },
    fix = "Settings → Apps → rotblock → Display over other apps.",
    action = Fix.OpenSettings(SettingsScreen.OVERLAY),
)

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
