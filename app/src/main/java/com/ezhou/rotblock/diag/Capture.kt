package com.ezhou.rotblock.diag

import com.ezhou.rotblock.service.EnforcementStatus
import org.json.JSONArray
import org.json.JSONObject

/** What the service knows that [EnforcementStatus.snapshot] does not carry. Null when no service is bound. */
data class ServiceInternals(
    val tickerArmed: Boolean,
    val tickerDueAtMs: Long?,
    val frictionServedFor: String?,
    val targetConfidenceConfirmed: Boolean,
    val visitMemory: Map<String, String>,
    val searchPageMemory: Map<String, Long>,
    val linkCorroborationPending: Map<String, Pair<String, Long>>,
    val revealAttemptedThisVisit: Boolean,
    val hudDismissedThisSession: Boolean,
    val enabledTargets: List<String>,
    val websiteMatchers: List<String>,
)

/** [com.ezhou.rotblock.policy.Decision], flattened -- the fields present depend on the subtype. */
data class DecisionSnapshot(
    val subtype: String,
    val reason: String?,
    val remainingMs: Long?,
    val usedMs: Long?,
    val quotaMs: Long?,
    val liftsAtMs: Long?,
    val pausedUntilMs: Long?,
    val recheckAtMs: Long?,
)

data class SessionRow(val startMs: Long, val endMs: Long)

data class PauseBudgetSnapshot(
    val pausesUsed: Int,
    val minutesUsed: Int,
    val maxPauses: Int,
    val maxMinutes: Int,
)

/**
 * [com.ezhou.rotblock.workout.WorkoutMode], flattened for a capture. [source] is "notification",
 * "manual", or "none" -- never the notification itself, which never reaches a capture.
 */
data class WorkoutSnapshot(
    val enabled: Boolean,
    val appPackage: String,
    val source: String,
    val startedAtMs: Long,
    val activeUntilMs: Long?,
    val capMs: Long,
)

/** One row of [com.ezhou.rotblock.setup.Requirement], reduced to what a capture needs. */
data class RequirementSnapshot(val id: String, val status: String, val need: String)

/** The contents of `OUTAGE_PREFS`. */
data class OutageSnapshot(
    val heartbeatAtMs: Long,
    val downSinceMs: Long,
    val visitPackage: String?,
    val visitTarget: String?,
    val visitFrictionServed: String?,
)

data class PhoneSnapshot(
    val screenOn: Boolean,
    /** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, verbatim. */
    val enabledAccessibilityServicesRaw: String?,
    /** The master accessibility switch, `Settings.Secure.ACCESSIBILITY_ENABLED`. */
    val accessibilityMasterEnabled: Boolean,
    val rotblockInEnabledList: Boolean,
    val batteryUnrestricted: Boolean,
    val writeSecureSettingsGranted: Boolean,
    val systemAlertWindowGranted: Boolean,
    val notificationsGranted: Boolean,
)

data class PolicySnapshot(
    val id: String,
    val kind: String,
    val matcher: String,
    val label: String,
    val enabled: Boolean,
    val quotaSeconds: Int?,
    val windowSeconds: Int,
    val frictionSeconds: Int,
    val grayscale: Boolean,
    val schedule: String,
)

data class AuditEventSnapshot(
    val id: Long,
    val tsMs: Long,
    val type: String,
    val targetId: String?,
    val detail: String,
    val userReason: String?,
    val durationSeconds: Int?,
    val overBudget: Boolean,
)

/** One node from the dumped tree. [text] and [contentDescription] are already truncated. */
data class NodeSnapshot(
    val depth: Int,
    val className: String?,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: String,
    val visibleToUser: Boolean,
    val clickable: Boolean,
    val targetUrl: String?,
)

/**
 * The accessibility tree of the screen the capture was taken over, plus rotblock's own reading of
 * it -- see `RotblockAccessibilityService.buildNodeDump`. [status] is `"ok"`, `"unavailable"` (the
 * 15s poll never found a readable window), or `"no service"` (the capture was taken without a
 * bound service, so no node tree could ever be read).
 */
data class NodeDumpSnapshot(
    val status: String,
    val packageName: String?,
    val nodeCount: Int,
    val truncated: Boolean,
    val nodes: List<NodeSnapshot>,
    val addressBar: String?,
    val linkUrls: List<String>,
    val linkDescriptions: List<String>,
    val linkConsensusHost: String?,
    val pageSignatureKey: String?,
    val classification: String?,
)

data class CaptureInput(
    val capturedAtMs: Long,
    val processUptimeMs: Long,
    val versionName: String,
    val versionCode: Long,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val osRelease: String,
    val enforcement: EnforcementStatus.Snapshot,
    val service: ServiceInternals?,
    val interstitialDepth: Int,
    val interstitialShowing: Boolean,
    val openTargetId: String?,
    val currentDecision: DecisionSnapshot?,
    val currentSessions: List<SessionRow>,
    val pausedUntilMs: Long,
    val pauseBudget: PauseBudgetSnapshot?,
    val workout: WorkoutSnapshot,
    val requirements: List<RequirementSnapshot>,
    val outage: OutageSnapshot,
    val phone: PhoneSnapshot,
    val policies: List<PolicySnapshot>,
    val auditEvents: List<AuditEventSnapshot>,
    val nodeDump: NodeDumpSnapshot?,
)

private fun Any?.orNull(): Any = this ?: JSONObject.NULL

/**
 * Serialises a [CaptureInput] to JSON. Pure -- every Android read that fills [CaptureInput]
 * happens at the call site, in `RotblockAccessibilityService`, so this stays testable on a plain
 * JVM the same way [com.ezhou.rotblock.data.buildExport] does.
 */
fun buildCapture(input: CaptureInput): String {
    val json = JSONObject()
        .put("capturedAtMs", input.capturedAtMs)
        .put("processUptimeMs", input.processUptimeMs)
        .put("versionName", input.versionName)
        .put("versionCode", input.versionCode)
        .put("manufacturer", input.manufacturer)
        .put("model", input.model)
        .put("sdkInt", input.sdkInt)
        .put("osRelease", input.osRelease)
        .put("enforcement", enforcementJson(input.enforcement))
        .put("service", input.service?.let(::serviceInternalsJson).orNull())
        .put("interstitialDepth", input.interstitialDepth)
        .put("interstitialShowing", input.interstitialShowing)
        .put("openTargetId", input.openTargetId.orNull())
        .put("currentDecision", input.currentDecision?.let(::decisionJson).orNull())
        .put("currentSessions", JSONArray(input.currentSessions.map(::sessionJson)))
        .put("pausedUntilMs", input.pausedUntilMs)
        .put("pauseBudget", input.pauseBudget?.let(::pauseBudgetJson).orNull())
        .put("workout", workoutJson(input.workout))
        .put("requirements", JSONArray(input.requirements.map(::requirementJson)))
        .put("outage", outageJson(input.outage))
        .put("phone", phoneJson(input.phone))
        .put("policies", JSONArray(input.policies.map(::policyJson)))
        .put("auditEvents", JSONArray(input.auditEvents.map(::auditEventJson)))
        .put("nodeDump", input.nodeDump?.let(::nodeDumpJson).orNull())
    return json.toString(2)
}

private fun enforcementJson(s: EnforcementStatus.Snapshot): JSONObject = JSONObject()
    .put("connected", s.connected)
    .put("currentPackage", s.currentPackage.orNull())
    .put("currentTargetId", s.currentTargetId.orNull())
    .put("accruingTargetId", s.accruingTargetId.orNull())
    .put("interstitialShowing", s.interstitialShowing)
    .put("lastReadAtMs", s.lastReadAtMs)
    .put("lastRead", s.lastRead.orNull())
    .put(
        "trail",
        JSONArray(
            s.trail.map { entry ->
                JSONObject()
                    .put("atMs", entry.atMs)
                    .put("text", entry.text)
                    .put("repeats", entry.repeats)
            },
        ),
    )

private fun serviceInternalsJson(s: ServiceInternals): JSONObject = JSONObject()
    .put("tickerArmed", s.tickerArmed)
    .put("tickerDueAtMs", s.tickerDueAtMs.orNull())
    .put("frictionServedFor", s.frictionServedFor.orNull())
    .put("targetConfidenceConfirmed", s.targetConfidenceConfirmed)
    .put("visitMemory", JSONObject(s.visitMemory))
    .put("searchPageMemory", JSONObject(s.searchPageMemory))
    .put(
        "linkCorroborationPending",
        JSONObject(
            s.linkCorroborationPending.mapValues { (_, hostAndTime) ->
                JSONObject().put("host", hostAndTime.first).put("atMs", hostAndTime.second)
            },
        ),
    )
    .put("revealAttemptedThisVisit", s.revealAttemptedThisVisit)
    .put("hudDismissedThisSession", s.hudDismissedThisSession)
    .put("enabledTargets", JSONArray(s.enabledTargets))
    .put("websiteMatchers", JSONArray(s.websiteMatchers))

private fun decisionJson(d: DecisionSnapshot): JSONObject = JSONObject()
    .put("subtype", d.subtype)
    .put("reason", d.reason.orNull())
    .put("remainingMs", d.remainingMs.orNull())
    .put("usedMs", d.usedMs.orNull())
    .put("quotaMs", d.quotaMs.orNull())
    .put("liftsAtMs", d.liftsAtMs.orNull())
    .put("pausedUntilMs", d.pausedUntilMs.orNull())
    .put("recheckAtMs", d.recheckAtMs.orNull())

private fun sessionJson(s: SessionRow): JSONObject = JSONObject()
    .put("startMs", s.startMs)
    .put("endMs", s.endMs)

private fun pauseBudgetJson(b: PauseBudgetSnapshot): JSONObject = JSONObject()
    .put("pausesUsed", b.pausesUsed)
    .put("minutesUsed", b.minutesUsed)
    .put("maxPauses", b.maxPauses)
    .put("maxMinutes", b.maxMinutes)

private fun workoutJson(w: WorkoutSnapshot): JSONObject = JSONObject()
    .put("enabled", w.enabled)
    .put("appPackage", w.appPackage)
    .put("source", w.source)
    .put("startedAtMs", w.startedAtMs)
    .put("activeUntilMs", w.activeUntilMs.orNull())
    .put("capMs", w.capMs)

private fun requirementJson(r: RequirementSnapshot): JSONObject = JSONObject()
    .put("id", r.id)
    .put("status", r.status)
    .put("need", r.need)

private fun outageJson(o: OutageSnapshot): JSONObject = JSONObject()
    .put("heartbeatAtMs", o.heartbeatAtMs)
    .put("downSinceMs", o.downSinceMs)
    .put("visitPackage", o.visitPackage.orNull())
    .put("visitTarget", o.visitTarget.orNull())
    .put("visitFrictionServed", o.visitFrictionServed.orNull())

private fun phoneJson(p: PhoneSnapshot): JSONObject = JSONObject()
    .put("screenOn", p.screenOn)
    .put("enabledAccessibilityServicesRaw", p.enabledAccessibilityServicesRaw.orNull())
    .put("accessibilityMasterEnabled", p.accessibilityMasterEnabled)
    .put("rotblockInEnabledList", p.rotblockInEnabledList)
    .put("batteryUnrestricted", p.batteryUnrestricted)
    .put("writeSecureSettingsGranted", p.writeSecureSettingsGranted)
    .put("systemAlertWindowGranted", p.systemAlertWindowGranted)
    .put("notificationsGranted", p.notificationsGranted)

private fun policyJson(p: PolicySnapshot): JSONObject = JSONObject()
    .put("id", p.id)
    .put("kind", p.kind)
    .put("matcher", p.matcher)
    .put("label", p.label)
    .put("enabled", p.enabled)
    .put("quotaSeconds", p.quotaSeconds.orNull())
    .put("windowSeconds", p.windowSeconds)
    .put("frictionSeconds", p.frictionSeconds)
    .put("grayscale", p.grayscale)
    .put("schedule", p.schedule)

private fun auditEventJson(e: AuditEventSnapshot): JSONObject = JSONObject()
    .put("id", e.id)
    .put("tsMs", e.tsMs)
    .put("type", e.type)
    .put("targetId", e.targetId.orNull())
    .put("detail", e.detail)
    .put("userReason", e.userReason.orNull())
    .put("durationSeconds", e.durationSeconds.orNull())
    .put("overBudget", e.overBudget)

private fun nodeJson(n: NodeSnapshot): JSONObject = JSONObject()
    .put("depth", n.depth)
    .put("className", n.className.orNull())
    .put("viewId", n.viewId.orNull())
    .put("text", n.text.orNull())
    .put("contentDescription", n.contentDescription.orNull())
    .put("bounds", n.bounds)
    .put("visibleToUser", n.visibleToUser)
    .put("clickable", n.clickable)
    .put("targetUrl", n.targetUrl.orNull())

private fun nodeDumpJson(d: NodeDumpSnapshot): JSONObject = JSONObject()
    .put("status", d.status)
    .put("packageName", d.packageName.orNull())
    .put("nodeCount", d.nodeCount)
    .put("truncated", d.truncated)
    .put("nodes", JSONArray(d.nodes.map(::nodeJson)))
    .put("addressBar", d.addressBar.orNull())
    .put("linkUrls", JSONArray(d.linkUrls))
    .put("linkDescriptions", JSONArray(d.linkDescriptions))
    .put("linkConsensusHost", d.linkConsensusHost.orNull())
    .put("pageSignatureKey", d.pageSignatureKey.orNull())
    .put("classification", d.classification.orNull())
