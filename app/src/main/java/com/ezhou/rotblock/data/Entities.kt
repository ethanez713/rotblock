package com.ezhou.rotblock.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.Schedule
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.UsageSession

/**
 * One target's foreground time on one budget-day, after site and feed time has been taken out of
 * the browser or host app it was measured inside.
 *
 * Derived, and rebuilt by [com.ezhou.rotblock.insights.DailyRollup]. Export and diagnostic
 * captures leave the table out: a frozen copy of something that can be re-folded is not a source
 * of truth, and a capture has nothing to ask it.
 */
@Entity(tableName = "daily_usage", primaryKeys = ["dayStartMs", "key"])
data class DailyUsage(
    val dayStartMs: Long,
    val key: String,
    val ms: Long,
)

/** A closed interval of foreground time, persisted so quotas survive a restart. */
@Entity(tableName = "usage_sessions", indices = [Index("targetId"), Index("endMs")])
data class UsageSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetId: String,
    val startMs: Long,
    val endMs: Long,
    @ColumnInfo(defaultValue = "1")
    val countsTowardQuota: Boolean = true,
) {
    fun toSession() = UsageSession(startMs, endMs, countsTowardQuota)
}

class Converters {
    @TypeConverter fun targetKindToString(v: TargetKind): String = v.name
    @TypeConverter fun stringToTargetKind(v: String): TargetKind = TargetKind.valueOf(v)

    @TypeConverter fun auditTypeToString(v: AuditType): String = v.name

    @TypeConverter fun scheduleToString(v: Schedule): String = v.encode()

    @TypeConverter fun membersToString(v: List<String>): String = Policy.encodeMembers(v)

    /** Drops only the entries it cannot read, never the row. See [Policy.decodeMembers]. */
    @TypeConverter fun stringToMembers(v: String?): List<String> = Policy.decodeMembers(v)

    /**
     * A row this cannot parse decodes to "always on" rather than throwing, for the same reason
     * unknown audit types do not throw -- and, unlike them, in the only direction that is safe.
     * See [Schedule.decode].
     */
    @TypeConverter fun stringToSchedule(v: String?): Schedule = Schedule.decode(v)

    /**
     * Unknown names decode to [AuditType.INTERNAL_ERROR] rather than throwing. A row written by a
     * newer build must never be able to crash the log reader -- see the never-crash invariant.
     */
    @TypeConverter fun stringToAuditType(v: String): AuditType =
        runCatching { AuditType.valueOf(v) }.getOrDefault(AuditType.INTERNAL_ERROR)
}
