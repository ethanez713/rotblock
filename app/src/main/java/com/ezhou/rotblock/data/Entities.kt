package com.ezhou.rotblock.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.policy.Schedule
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.quota.UsageSession

/** A closed interval of foreground time, persisted so quotas survive a restart. */
@Entity(tableName = "usage_sessions", indices = [Index("targetId"), Index("endMs")])
data class UsageSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetId: String,
    val startMs: Long,
    val endMs: Long,
) {
    fun toSession() = UsageSession(startMs, endMs)
}

class Converters {
    @TypeConverter fun targetKindToString(v: TargetKind): String = v.name
    @TypeConverter fun stringToTargetKind(v: String): TargetKind = TargetKind.valueOf(v)

    @TypeConverter fun auditTypeToString(v: AuditType): String = v.name

    @TypeConverter fun scheduleToString(v: Schedule): String = v.encode()

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
