package com.ezhou.rotblock.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.policy.Policy
import kotlinx.coroutines.flow.Flow

@Dao
interface PolicyDao {
    @Query("SELECT * FROM policies ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<Policy>>

    @Query("SELECT * FROM policies")
    suspend fun getAll(): List<Policy>

    @Query("SELECT * FROM policies WHERE id = :id")
    suspend fun get(id: String): Policy?

    @Upsert
    suspend fun upsert(policy: Policy)

    @Delete
    suspend fun delete(policy: Policy)
}

@Dao
interface UsageDao {
    /** Sessions that could still be inside any rolling window, cheapest thing to load at start. */
    @Query("SELECT * FROM usage_sessions WHERE endMs >= :sinceMs ORDER BY startMs")
    suspend fun since(sinceMs: Long): List<UsageSessionEntity>

    @Query("SELECT * FROM usage_sessions WHERE targetId = :targetId AND endMs >= :sinceMs ORDER BY startMs")
    suspend fun forTargetSince(targetId: String, sinceMs: Long): List<UsageSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: UsageSessionEntity): Long

    @Query("UPDATE usage_sessions SET endMs = :endMs WHERE id = :id")
    suspend fun updateEnd(id: Long, endMs: Long)

    @Query("DELETE FROM usage_sessions WHERE endMs < :beforeMs")
    suspend fun prune(beforeMs: Long)
}

/** Rolled-up whole-phone days. Written by [com.ezhou.rotblock.insights.DailyRollup], never by hand. */
@Dao
interface DailyUsageDao {
    @Query("SELECT * FROM daily_usage WHERE dayStartMs IN (:dayStarts)")
    suspend fun forDays(dayStarts: List<Long>): List<DailyUsage>

    @Query("DELETE FROM daily_usage WHERE dayStartMs IN (:dayStarts)")
    suspend fun deleteDays(dayStarts: List<Long>)

    @Upsert
    suspend fun upsertAll(rows: List<DailyUsage>)

    /**
     * One day is replaced whole. A crash between the delete and the insert would otherwise leave
     * yesterday's keys mixed with today's, and the marker would not have moved to stop a retry.
     */
    @Transaction
    suspend fun replaceDays(dayStarts: List<Long>, rows: List<DailyUsage>) {
        deleteDays(dayStarts)
        if (rows.isNotEmpty()) upsertAll(rows)
    }

    @Query("DELETE FROM daily_usage WHERE dayStartMs < :beforeMs")
    suspend fun prune(beforeMs: Long)
}

/** Insert, read, and a blanket retention delete. Nothing else, on purpose. See [AuditEvent]. */
@Dao
interface AuditDao {
    @Insert
    suspend fun insert(event: AuditEvent): Long

    /** A single read of the newest [limit] events, for a diagnostic capture. */
    @Query("SELECT * FROM audit_events ORDER BY tsMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditEvent>

    /**
     * One page of the log, newest first. The Insights card holds [limit] rows and asks again
     * for the next page, so the screen never keeps the whole log in memory. A new event
     * re-emits the page that contains it.
     */
    @Query("SELECT * FROM audit_events ORDER BY tsMs DESC LIMIT :limit OFFSET :offset")
    fun observePage(limit: Int, offset: Int): Flow<List<AuditEvent>>

    @Query("SELECT COUNT(*) FROM audit_events")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM audit_events WHERE tsMs >= :sinceMs ORDER BY tsMs")
    suspend fun since(sinceMs: Long): List<AuditEvent>

    /** Dedupe anchor for events that are detected late; see `PauseController.noteExpiryIfNeeded`. */
    @Query("SELECT MAX(tsMs) FROM audit_events WHERE type = :type")
    suspend fun lastTimestampOf(type: String): Long?

    /**
     * Retention only. The one delete on this table, and it takes a cutoff rather than an id or a
     * type on purpose -- see [com.ezhou.rotblock.audit.AUDIT_RETENTION_MS].
     */
    @Query("DELETE FROM audit_events WHERE tsMs < :beforeMs")
    suspend fun prune(beforeMs: Long)
}
