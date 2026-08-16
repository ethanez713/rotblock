package com.ezhou.rotblock

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.data.AuditDao
import com.ezhou.rotblock.data.PendingChange
import com.ezhou.rotblock.data.PendingChangeDao
import com.ezhou.rotblock.data.PolicyDao
import com.ezhou.rotblock.data.RotblockDatabase
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.data.UsageDao
import com.ezhou.rotblock.data.UsageSessionEntity
import com.ezhou.rotblock.data.buildExport
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * There is no `getAll()` on [UsageDao] or [AuditDao] beyond export -- `buildExport` reads
 * everything through `since(0L)`, since every stored timestamp is a positive epoch ms.
 *
 * A real in-memory Room database is not reachable from this plain-JVM test target (Room's
 * default SQLite driver needs a real Android runtime, which nothing here provides), so this
 * fakes the four DAOs directly and wraps them in a minimal [RotblockDatabase] that only
 * overrides the dao getters `buildExport` actually calls.
 */
class ExportTest {
    private fun fakeDb(
        policies: List<Policy> = emptyList(),
        sessions: List<UsageSessionEntity> = emptyList(),
        events: List<AuditEvent> = emptyList(),
    ): RotblockDatabase = object : RotblockDatabase() {
        override fun policyDao() = object : PolicyDao {
            override fun observeAll(): Flow<List<Policy>> = error("unused in export")
            override suspend fun getAll() = policies
            override suspend fun get(id: String): Policy? = error("unused in export")
            override suspend fun upsert(policy: Policy) = error("unused in export")
            override suspend fun delete(policy: Policy) = error("unused in export")
        }

        override fun usageDao() = object : UsageDao {
            override suspend fun since(sinceMs: Long) = sessions
            override suspend fun forTargetSince(targetId: String, sinceMs: Long) = error("unused in export")
            override suspend fun insert(session: UsageSessionEntity) = error("unused in export")
            override suspend fun updateEnd(id: Long, endMs: Long) = error("unused in export")
            override suspend fun prune(beforeMs: Long) = error("unused in export")
        }

        override fun auditDao() = object : AuditDao {
            override suspend fun insert(event: AuditEvent) = error("unused in export")
            override fun observeRecent(limit: Int): Flow<List<AuditEvent>> = error("unused in export")
            override suspend fun recent(limit: Int) = error("unused in export")
            override suspend fun since(sinceMs: Long) = events
            override suspend fun lastTimestampOf(type: String): Long? = error("unused in export")
        }

        override fun pendingChangeDao() = object : PendingChangeDao {
            override fun observeAll(): Flow<List<PendingChange>> = error("unused in export")
            override suspend fun due(nowMs: Long) = error("unused in export")
            override suspend fun forTarget(targetId: String) = error("unused in export")
            override suspend fun insert(change: PendingChange) = error("unused in export")
            override suspend fun delete(id: Long) = error("unused in export")
            override suspend fun deleteForTarget(targetId: String) = error("unused in export")
        }

        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper = error("unused in export")
        // RoomDatabase's own constructor calls this eagerly, so it must return something rather
        // than throw -- buildExport never touches invalidation, only the dao getters above.
        override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this)
        override fun clearAllTables() = error("unused in export")
    }

    @Test
    fun `round-trips policies, audit events and usage sessions with correct counts`() = runBlocking {
        val policies = listOf(
            Policy(id = "app:com.reddit.frontpage", kind = TargetKind.APP, matcher = "com.reddit.frontpage", label = "Reddit"),
            Policy(id = "web:reddit.com", kind = TargetKind.WEBSITE, matcher = "reddit.com", label = "Reddit web", quotaSeconds = 180),
        )
        val sessions = listOf(
            UsageSessionEntity(targetId = "app:com.reddit.frontpage", startMs = 1_000, endMs = 2_000),
            UsageSessionEntity(targetId = "web:reddit.com", startMs = 3_000, endMs = 4_000),
        )
        val events = listOf(
            AuditEvent(tsMs = 1_000, type = AuditType.POLICY_CREATED, targetId = "app:com.reddit.frontpage", detail = "created"),
            AuditEvent(tsMs = 2_000, type = AuditType.PAUSE_STARTED, durationSeconds = 60, detail = "paused"),
            AuditEvent(tsMs = 3_000, type = AuditType.EMERGENCY_SKIP, userReason = "urgent", overBudget = true),
        )

        val json = JSONObject(buildExport(fakeDb(policies, sessions, events), RotblockSettings(), nowMs = 9_999))

        assertEquals(1, json.getInt("format"))
        assertEquals(9_999L, json.getLong("exportedAtMs"))
        assertEquals(2, json.getJSONArray("policies").length())
        assertEquals(2, json.getJSONArray("usageSessions").length())
        assertEquals(3, json.getJSONArray("auditEvents").length())

        val webPolicy = json.getJSONArray("policies").getJSONObject(1)
        assertEquals("web:reddit.com", webPolicy.getString("id"))
        assertEquals(180, webPolicy.getInt("quotaSeconds"))

        val hardBlock = json.getJSONArray("policies").getJSONObject(0)
        assertTrue(hardBlock.isNull("quotaSeconds"))

        val skipEvent = json.getJSONArray("auditEvents").getJSONObject(2)
        assertEquals("urgent", skipEvent.getString("userReason"))
        assertTrue(skipEvent.isNull("targetId"))
        assertTrue(skipEvent.isNull("durationSeconds"))
        assertEquals(true, skipEvent.getBoolean("overBudget"))
    }

    @Test
    fun `empty database produces empty arrays rather than nulls`() = runBlocking {
        val json = JSONObject(buildExport(fakeDb(), RotblockSettings(), nowMs = 0))

        assertEquals(0, json.getJSONArray("policies").length())
        assertEquals(0, json.getJSONArray("usageSessions").length())
        assertEquals(0, json.getJSONArray("auditEvents").length())
        assertTrue(json.has("settings"))
    }

    @Test
    fun `settings are carried through by name and value`() = runBlocking {
        val settings = RotblockSettings(pausedUntilMs = 42, cooldownSeconds = 90, hudEnabled = false)
        val json = JSONObject(buildExport(fakeDb(), settings, nowMs = 0)).getJSONObject("settings")

        assertEquals(42L, json.getLong("pausedUntilMs"))
        assertEquals(90, json.getInt("cooldownSeconds"))
        assertEquals(false, json.getBoolean("hudEnabled"))
    }
}
