package com.ezhou.rotblock.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.ezhou.rotblock.audit.AuditType
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

    /**
     * Unknown names decode to [AuditType.INTERNAL_ERROR] rather than throwing. A row written by a
     * newer build must never be able to crash the log reader -- see the never-crash invariant.
     */
    @TypeConverter fun stringToAuditType(v: String): AuditType =
        runCatching { AuditType.valueOf(v) }.getOrDefault(AuditType.INTERNAL_ERROR)
}
