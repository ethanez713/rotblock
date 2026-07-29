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
 */
enum class TargetKind { APP, WEBSITE, FEED }

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
        }
    }
}
