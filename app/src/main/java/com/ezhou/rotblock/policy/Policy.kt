package com.ezhou.rotblock.policy

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What a policy applies to.
 *
 * [APP] matches a package name, [WEBSITE] matches a URL read from a browser's address bar,
 * and [FEED] matches a short-form surface inside an otherwise-allowed app (YouTube Shorts,
 * Instagram Reels). All three share one policy shape so the quota engine, the audit log and
 * the UI never have to branch on kind.
 *
 * [GROUP] is the odd one and is deliberately not a fourth thing to be in front of. Nothing on
 * screen ever *is* a group: the target is still an app, a website or a feed, and a group is a
 * second policy that also covers it. See [Policy.members].
 */
enum class TargetKind { APP, WEBSITE, FEED, GROUP }

/**
 * Separator for [Policy.members]. Not a character any target id can contain -- package names,
 * hosts and paths all exclude it -- so a member can never be split in half by its own name.
 */
private const val MEMBER_SEPARATOR = "|"

/**
 * A single rule.
 *
 * A policy with a null [quotaSeconds] is a hard block. A policy with a quota allows
 * [quotaSeconds] of use out of any trailing [windowSeconds], and blocks once that is spent
 * until enough old usage ages out of the window.
 *
 * [schedule] gates all of that: outside its hours the rule does nothing at all, and nothing spent
 * there counts against the quota.
 */
@Entity(tableName = "policies")
data class Policy(
    /** Stable target id, e.g. `app:com.reddit.frontpage`. See [targetId]. */
    @PrimaryKey val id: String,
    val kind: TargetKind,
    /** Package name, URL pattern, or feed key, depending on [kind]. */
    val matcher: String,
    /** Human-readable name, used in the UI and in audit entries. */
    val label: String,
    val enabled: Boolean = true,
    /** null = hard block, no allowance at all. */
    val quotaSeconds: Int? = null,
    /** Rolling window the quota is measured over. Ignored when [quotaSeconds] is null. */
    val windowSeconds: Int = 3600,
    /** Seconds of breathing screen before the target opens. 0 = none. (Phase 7) */
    val frictionSeconds: Int = 0,
    /** Force the display to greyscale while this target is in the foreground. (Phase 7) */
    val grayscale: Boolean = false,
    /**
     * The hours this rule is live for. Empty means always, which is what every rule written before
     * schedules existed decodes to. (Phase 8)
     */
    @ColumnInfo(defaultValue = "")
    val schedule: Schedule = Schedule.ALWAYS,
    /**
     * For a [TargetKind.GROUP], the target ids this rule also covers. Empty for every other kind.
     *
     * A group is a shared allowance across several targets, written this way because usage is
     * already keyed by target: the ledger records time against `web:reddit.com` whoever is asking,
     * so a group's quota is simply the union of its members' sessions and nothing about accrual
     * has to change. It also falls out of that choice that a target can be in several groups and
     * have a rule of its own, and every one of them is charged for the same minute -- which is the
     * point. Three two-minute allowances that were never meant to add up to six.
     */
    @ColumnInfo(defaultValue = "")
    val members: List<String> = emptyList(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    val quotaMs: Long? get() = quotaSeconds?.let { it * 1000L }
    val windowMs: Long get() = windowSeconds * 1000L

    companion object {
        fun targetId(kind: TargetKind, matcher: String): String = when (kind) {
            TargetKind.APP -> "app:$matcher"
            TargetKind.WEBSITE -> "web:$matcher"
            TargetKind.FEED -> "feed:$matcher"
            TargetKind.GROUP -> "group:$matcher"
        }

        /**
         * The kind a target id names, or null if it names nothing recognisable.
         *
         * Exists so the parts of the app that answer "what is on screen" can stop borrowing the
         * kind off whichever policy happens to be blocking. Since groups, those are not the same
         * question: a group covers a website but is not one, and a block screen that took its
         * escape from the blocking rule's kind would offer "close this app" over a browser --
         * which is the dead end [com.ezhou.rotblock.block.escapeLabelFor] exists to refuse.
         */
        fun kindOf(targetId: String): TargetKind? = when {
            targetId.startsWith("app:") -> TargetKind.APP
            targetId.startsWith("web:") -> TargetKind.WEBSITE
            targetId.startsWith("feed:") -> TargetKind.FEED
            targetId.startsWith("group:") -> TargetKind.GROUP
            else -> null
        }

        fun encodeMembers(members: List<String>): String =
            members.filter { it.isNotBlank() }.joinToString(MEMBER_SEPARATOR)

        /**
         * Keeps every member it can still read rather than rejecting the row.
         *
         * Unlike [Schedule.decode] there is no degenerate value here that resolves toward more
         * blocking -- a group with no members covers nothing, so "reject the lot" would be the
         * fail-open answer. Dropping only the entries that are unreadable preserves as much
         * coverage as survives, which is the nearest thing to a safe direction this has.
         */
        fun decodeMembers(text: String?): List<String> =
            text?.split(MEMBER_SEPARATOR)?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
    }
}

/**
 * A rolling window as it reads in a sentence: "3 min per **hour**", "3 min in any **90 min**".
 *
 * Windows are arbitrary now that the editor takes a typed number, and the units they are written
 * in are not the units they read best in -- a day spelled out in minutes is 1440 of them.
 */
fun formatWindowSeconds(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        minutes == 60 -> "hour"
        minutes == 24 * 60 -> "day"
        minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} days"
        minutes % 60 == 0 -> "${minutes / 60} hr"
        else -> "$minutes min"
    }
}
