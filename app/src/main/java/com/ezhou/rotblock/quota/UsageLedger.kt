package com.ezhou.rotblock.quota

import com.ezhou.rotblock.data.UsageDao
import com.ezhou.rotblock.data.UsageSessionEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** How often an in-progress session is written through to disk. */
private const val FLUSH_INTERVAL_MS = 15_000L

/** Sessions older than this are dropped; the tail feeds the Insights charts. */
const val USAGE_RETENTION_MS = 31L * 24 * 60 * 60 * 1000

/**
 * Foreground-time ledger: an in-memory cache the accessibility service can read synchronously,
 * written through to Room so quotas survive a restart.
 *
 * The in-progress session is inserted immediately and its end timestamp updated every
 * [FLUSH_INTERVAL_MS], so a crash loses at most 15 seconds of accrued usage. Losing usage always
 * errs toward giving time back rather than taking it away, which is the right way for this to
 * fail: the user should never be blocked for time they did not actually spend.
 */
class UsageLedger(
    private val dao: UsageDao,
    private val scope: CoroutineScope,
) {
    private val closed = ConcurrentHashMap<String, CopyOnWriteArrayList<UsageSession>>()

    @Volatile private var open: Open? = null

    private class Open(val targetId: String, val startMs: Long) {
        @Volatile var endMs: Long = startMs
        @Volatile var lastFlushMs: Long = startMs
        val rowId = CompletableDeferred<Long>()
    }

    /** The target currently accruing time, if any. */
    val openTargetId: String? get() = open?.targetId

    /** Loads the recent tail from disk. Call once when the service connects. */
    suspend fun load(nowMs: Long) {
        val rows = dao.since(nowMs - USAGE_RETENTION_MS)
        closed.clear()
        for (row in rows) {
            closed.getOrPut(row.targetId) { CopyOnWriteArrayList() }.add(row.toSession())
        }
    }

    /** Closed sessions for a target, plus the in-progress one clipped at [nowMs]. */
    fun sessionsFor(targetId: String, nowMs: Long): List<UsageSession> {
        val stored = closed[targetId].orEmpty()
        val o = open
        if (o == null || o.targetId != targetId) return stored
        // The live session runs from its start up to now, regardless of how recently `touch` ran.
        return stored + UsageSession(o.startMs, maxOf(o.startMs, nowMs))
    }

    /** Starts accruing time against [targetId]. Closes any previously open session first. */
    fun startSession(targetId: String, nowMs: Long) {
        if (open?.targetId == targetId) return
        closeSession(nowMs)
        val o = Open(targetId, nowMs)
        open = o
        scope.launch {
            val id = dao.insert(UsageSessionEntity(targetId = targetId, startMs = nowMs, endMs = nowMs))
            o.rowId.complete(id)
        }
    }

    /** Extends the in-progress session to [nowMs], flushing to disk periodically. */
    fun touch(nowMs: Long) {
        val o = open ?: return
        if (nowMs <= o.endMs) return
        o.endMs = nowMs
        if (nowMs - o.lastFlushMs >= FLUSH_INTERVAL_MS) {
            o.lastFlushMs = nowMs
            scope.launch { dao.updateEnd(o.rowId.await(), nowMs) }
        }
    }

    /** Ends the in-progress session at [nowMs] and commits it. */
    fun closeSession(nowMs: Long) {
        val o = open ?: return
        open = null
        val endMs = maxOf(o.startMs, nowMs)
        closed.getOrPut(o.targetId) { CopyOnWriteArrayList() }.add(UsageSession(o.startMs, endMs))
        scope.launch { dao.updateEnd(o.rowId.await(), endMs) }
    }

    /** Drops sessions past the retention horizon, in memory and on disk. */
    fun prune(nowMs: Long) {
        val cutoff = nowMs - USAGE_RETENTION_MS
        for ((_, list) in closed) {
            list.removeAll { it.endMs < cutoff }
        }
        scope.launch { dao.prune(cutoff) }
    }
}
