package com.ezhou.rotblock.service

import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.block.Interstitial
import com.ezhou.rotblock.setup.DetectionCounters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How often the feed-detection counters reach disk.
 *
 * Long, because nothing depends on them being current: the alarm they feed needs three days of
 * counting before it says anything. Short enough that a process kill costs one window's worth of
 * evidence rather than the window itself.
 */
private const val DETECTION_FLUSH_MS = 5 * 60 * 1000L

/**
 * How much of the trail an [EvaluationRate] storm carries into the audit log with it.
 *
 * Enough to say what was happening before the rate went up, and bounded because this lands in a
 * single `detail` column that a person reads. The rest of the trail is still in the snapshot a
 * diagnostic capture takes -- if one is taken before the storm overwrites it, which is the whole
 * reason these lines are copied somewhere that outlives the process.
 */
private const val STORM_TRAIL_LINES = 12

class DetectionJournal(
    private val scope: CoroutineScope,
    private val saveCounters: suspend (String) -> Unit,
    private val audit: () -> AuditLog,
    private val frictionServedFor: () -> String?,
    private val currentTargetId: () -> String?,
) {

    /** See [noteDetectionLook]. Diagnostic only; never read by anything that decides a block. */
    @Volatile private var detection = DetectionCounters()
    private var detectionFlushedAtMs = 0L

    /** Main thread only, like [friction]. See [EvaluationRate]. */
    private val evaluationRate = EvaluationRate()

    fun load(encoded: String) {
        detection = DetectionCounters.decode(encoded)
    }

    /**
     * Tally one inspection of a package a feed rule cares about, and whether its ids were found.
     *
     * Two map increments and a clock comparison, deliberately: this runs on the inspector coroutine
     * for every read of YouTube or Instagram, and anything heavier here would be paid for on the
     * busiest path in the app. The disk write happens at most every [DETECTION_FLUSH_MS] and is
     * fire-and-forget.
     *
     * Purely diagnostic. Nothing in [PolicyEngine], [classifyInspection] or [applyTarget] reads
     * these, and losing a window to a crash costs a warning arriving later than it might have.
     */
    fun noteDetectionLook(pkg: String, foundFeedKey: String?) {
        val nowMs = System.currentTimeMillis()
        val counted = detection.rolled(nowMs).withLook(pkg)
        detection = if (foundFeedKey == null) counted else counted.withHit(foundFeedKey)
        if (nowMs - detectionFlushedAtMs >= DETECTION_FLUSH_MS) flushDetection(nowMs)
    }

    /**
     * Piggybacks on the inspection that just happened rather than arming anything of its own —
     * `CLAUDE.md` forbids a new repeating callback, and a counter is not worth a wakeup.
     */
    fun flushDetection(nowMs: Long) {
        detectionFlushedAtMs = nowMs
        val encoded = detection.encode()
        scope.launch {
            runCatching { saveCounters(encoded) }
        }
    }

    /**
     * One audit event when the evaluation rate goes pathological and one when it recovers.
     *
     * In the healthy case this is two field updates and a comparison on a path that runs a few
     * times a second -- the cost only appears once the fault has, which is the same bargain
     * [shouldRecheckForeground]'s throttle makes.
     */
    fun noteEvaluationRate(targetId: String, nowMs: Long) {
        when (val verdict = evaluationRate.evaluated(nowMs)) {
            EvaluationRate.Verdict.Quiet -> Unit
            is EvaluationRate.Verdict.Began -> recordStormBegan(targetId, verdict.perSecond)
            is EvaluationRate.Verdict.Ended -> recordStormEnded(targetId, verdict)
        }
    }

    /**
     * The wide event: everything that could name the cause, in one row.
     *
     * Every field here is one that was wanted and missing while reading back the storms this was
     * written for -- the gate depth and the friction gate's state because the alternation was
     * between "one of our screens is up" and "charging", the last read because a target flapping
     * would show there, and the trail because whatever *started* it happened before the first line
     * the storm itself wrote.
     */
    private fun recordStormBegan(targetId: String, perSecond: Int) {
        val snapshot = EnforcementStatus.snapshot()
        val preceding = snapshot.trail
            .take(STORM_TRAIL_LINES)
            .joinToString(" | ") { if (it.repeats > 1) "${it.text} x${it.repeats}" else it.text }
        EnforcementStatus.note("evaluation storm: ${perSecond}/s")
        audit().record(
            AuditType.EVALUATION_STORM,
            targetId = targetId,
            detail = "storm began at $perSecond/s" +
                " pkg=${snapshot.currentPackage}" +
                " gate=${Interstitial.currentDepth}" +
                " friction=${frictionServedFor()}" +
                " lastRead=${snapshot.lastRead}" +
                " before: $preceding",
        )
    }

    private fun recordStormEnded(targetId: String, ended: EvaluationRate.Verdict.Ended) {
        EnforcementStatus.note("evaluation storm ended after ${ended.durationMs}ms")
        audit().record(
            AuditType.EVALUATION_STORM,
            targetId = targetId,
            detail = "storm ended after ${ended.durationMs}ms, ${ended.evaluations} evaluations",
            durationSeconds = (ended.durationMs / 1000).toInt(),
        )
    }

    /**
     * Brackets a storm that stopped because evaluation stopped -- the target was dropped, the
     * screen went off, the service went away -- rather than because the rate came down.
     */
    fun settleEvaluationRate(nowMs: Long) {
        val ended = evaluationRate.settle(nowMs)
        if (ended is EvaluationRate.Verdict.Ended) {
            recordStormEnded(currentTargetId() ?: "none", ended)
        }
    }
}
