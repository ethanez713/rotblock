package com.ezhou.rotblock.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Export only, on purpose. Restoring a policy set would write every rule straight to the
 * database, applying whatever weakenings it contains instantly and bypassing
 * [PendingChangeQueue] entirely -- a fail-open, and the most direct way to violate this app's
 * core invariant that exists anywhere in the codebase. An importer has to route every restored
 * rule through the existing weaken-vs-strengthen classification against what is on the phone
 * already, which is a design problem, not a serialisation one. Do not add a quick `importFrom`
 * here -- it would quietly undo the reason this file stops at writing JSON out.
 */
suspend fun buildExport(db: RotblockDatabase, settings: RotblockSettings, nowMs: Long): String {
    val policies = db.policyDao().getAll()
    // endMs and tsMs are epoch milliseconds, always positive, so sinceMs = 0 is every row ever
    // written -- there is no getAll() on these DAOs because nothing but export needs one.
    val usageSessions = db.usageDao().since(0L)
    val auditEvents = db.auditDao().since(0L)

    val policiesJson = JSONArray(policies.map { p ->
        JSONObject()
            .put("id", p.id)
            .put("kind", p.kind.name)
            .put("matcher", p.matcher)
            .put("label", p.label)
            .put("enabled", p.enabled)
            .put("quotaSeconds", p.quotaSeconds ?: JSONObject.NULL)
            .put("windowSeconds", p.windowSeconds)
            .put("frictionSeconds", p.frictionSeconds)
            .put("grayscale", p.grayscale)
            .put("schedule", p.schedule.encode())
            .put("createdAtMs", p.createdAtMs)
            .put("updatedAtMs", p.updatedAtMs)
    })

    val usageSessionsJson = JSONArray(usageSessions.map { s ->
        JSONObject()
            .put("id", s.id)
            .put("targetId", s.targetId)
            .put("startMs", s.startMs)
            .put("endMs", s.endMs)
    })

    val auditEventsJson = JSONArray(auditEvents.map { e ->
        JSONObject()
            .put("id", e.id)
            .put("tsMs", e.tsMs)
            .put("type", e.type.name)
            .put("targetId", e.targetId ?: JSONObject.NULL)
            .put("detail", e.detail)
            .put("userReason", e.userReason ?: JSONObject.NULL)
            .put("durationSeconds", e.durationSeconds ?: JSONObject.NULL)
            .put("overBudget", e.overBudget)
    })

    val settingsJson = JSONObject()
        .put("pausedUntilMs", settings.pausedUntilMs)
        .put("cooldownSeconds", settings.cooldownSeconds)
        .put("maxPausesPerDay", settings.maxPausesPerDay)
        .put("maxPauseMinutesPerDay", settings.maxPauseMinutesPerDay)
        .put("dayStartHour", settings.dayStartHour)
        .put("weakeningDelayMinutes", settings.weakeningDelayMinutes)
        .put("blocksSort", settings.blocksSort.name)
        .put("hudEnabled", settings.hudEnabled)
        .put("hudOpacityPercent", settings.hudOpacityPercent)
        .put("hudNotification", settings.hudNotification)
        .put("hudPositionX", settings.hudPositionX)
        .put("hudPositionY", settings.hudPositionY)
        .put("onboardedAtMs", settings.onboardedAtMs)
        .put("accessibilityConsentAtMs", settings.accessibilityConsentAtMs)

    return JSONObject()
        .put("format", 1)
        .put("exportedAtMs", nowMs)
        .put("policies", policiesJson)
        .put("settings", settingsJson)
        .put("auditEvents", auditEventsJson)
        .put("usageSessions", usageSessionsJson)
        .toString(2)
}
