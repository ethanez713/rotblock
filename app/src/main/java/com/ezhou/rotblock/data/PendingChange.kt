package com.ezhou.rotblock.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.Schedule
import kotlinx.coroutines.flow.Flow

/**
 * A policy edit that loosens the rules, parked until [applyAtMs].
 *
 * Fields are stored explicitly rather than as a serialised blob so the queue stays readable in a
 * database browser and needs no JSON dependency.
 */
@Entity(tableName = "pending_changes", indices = [Index("targetId"), Index("applyAtMs")])
data class PendingChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetId: String,
    val createdAtMs: Long,
    val applyAtMs: Long,
    val description: String,
    /** True when the queued edit removes the policy entirely. */
    val isDeletion: Boolean,
    // Desired end state, ignored when isDeletion is true.
    val enabled: Boolean = true,
    val quotaSeconds: Int? = null,
    val windowSeconds: Int = 3600,
    val frictionSeconds: Int = 0,
    val grayscale: Boolean = false,
    @ColumnInfo(defaultValue = "")
    val schedule: Schedule = Schedule.ALWAYS,
) {
    /** Applies this change's fields onto the policy as it stands now. */
    fun applyTo(current: Policy, nowMs: Long): Policy = current.copy(
        enabled = enabled,
        quotaSeconds = quotaSeconds,
        windowSeconds = windowSeconds,
        frictionSeconds = frictionSeconds,
        grayscale = grayscale,
        schedule = schedule,
        updatedAtMs = nowMs,
    )

    companion object {
        fun from(target: Policy, applyAtMs: Long, description: String, nowMs: Long) = PendingChange(
            targetId = target.id,
            createdAtMs = nowMs,
            applyAtMs = applyAtMs,
            description = description,
            isDeletion = false,
            enabled = target.enabled,
            quotaSeconds = target.quotaSeconds,
            windowSeconds = target.windowSeconds,
            frictionSeconds = target.frictionSeconds,
            grayscale = target.grayscale,
            schedule = target.schedule,
        )

        fun deletion(targetId: String, applyAtMs: Long, description: String, nowMs: Long) =
            PendingChange(
                targetId = targetId,
                createdAtMs = nowMs,
                applyAtMs = applyAtMs,
                description = description,
                isDeletion = true,
            )
    }
}

@Dao
interface PendingChangeDao {
    @Query("SELECT * FROM pending_changes ORDER BY applyAtMs")
    fun observeAll(): Flow<List<PendingChange>>

    @Query("SELECT * FROM pending_changes WHERE applyAtMs <= :nowMs ORDER BY applyAtMs")
    suspend fun due(nowMs: Long): List<PendingChange>

    @Query("SELECT * FROM pending_changes WHERE targetId = :targetId")
    suspend fun forTarget(targetId: String): List<PendingChange>

    @Insert
    suspend fun insert(change: PendingChange): Long

    @Query("DELETE FROM pending_changes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_changes WHERE targetId = :targetId")
    suspend fun deleteForTarget(targetId: String)
}
