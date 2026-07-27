package com.ezhou.rotblock.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

/** Insert-and-read only, on purpose. See [AuditEvent]. */
@Dao
interface AuditDao {
    @Insert
    suspend fun insert(event: AuditEvent): Long

    @Query("SELECT * FROM audit_events ORDER BY tsMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEvent>>

    @Query("SELECT * FROM audit_events WHERE tsMs >= :sinceMs ORDER BY tsMs")
    suspend fun since(sinceMs: Long): List<AuditEvent>
}
