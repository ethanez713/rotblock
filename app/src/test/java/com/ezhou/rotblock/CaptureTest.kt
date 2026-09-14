package com.ezhou.rotblock

import com.ezhou.rotblock.diag.AuditEventSnapshot
import com.ezhou.rotblock.diag.CaptureInput
import com.ezhou.rotblock.diag.DecisionSnapshot
import com.ezhou.rotblock.diag.NodeDumpSnapshot
import com.ezhou.rotblock.diag.NodeSnapshot
import com.ezhou.rotblock.diag.OutageSnapshot
import com.ezhou.rotblock.diag.PauseBudgetSnapshot
import com.ezhou.rotblock.diag.PhoneSnapshot
import com.ezhou.rotblock.diag.PolicySnapshot
import com.ezhou.rotblock.diag.RequirementSnapshot
import com.ezhou.rotblock.diag.ServiceInternals
import com.ezhou.rotblock.diag.SessionRow
import com.ezhou.rotblock.diag.WorkoutSnapshot
import com.ezhou.rotblock.diag.buildCapture
import com.ezhou.rotblock.service.EnforcementStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildCapture] is the one place a diagnostic capture can silently lose the field that would
 * have explained a bug -- everything upstream of it is a plain Android read with nothing to get
 * wrong. So this checks every section of a fully-populated [CaptureInput] round-trips by name,
 * not just that the JSON parses.
 */
class CaptureTest {

    private fun fullInput(): CaptureInput = CaptureInput(
        capturedAtMs = 1_700_000_000_000L,
        processUptimeMs = 60_000L,
        versionName = "0.1",
        versionCode = 1L,
        manufacturer = "Samsung",
        model = "SM-S911U",
        sdkInt = 34,
        osRelease = "14",
        enforcement = EnforcementStatus.Snapshot(
            connected = true,
            currentPackage = "com.sec.android.app.sbrowser",
            currentTargetId = "web:reddit.com",
            accruingTargetId = "web:reddit.com",
            interstitialShowing = false,
            lastReadAtMs = 1_700_000_000_000L,
            lastRead = "sbrowser bar=\"reddit.com\" -> web:reddit.com",
            trail = listOf(
                EnforcementStatus.Entry(1_699_999_999_000L, "web:reddit.com: charging", 3),
            ),
            workoutUntilMs = null,
            workoutSource = null,
        ),
        service = ServiceInternals(
            tickerArmed = true,
            tickerDueAtMs = 1_700_000_010_000L,
            frictionServedFor = "web:reddit.com",
            targetConfidenceConfirmed = true,
            visitMemory = mapOf("com.sec.android.app.sbrowser" to "web:reddit.com"),
            searchPageMemory = mapOf("com.android.chrome" to 1_699_999_000_000L),
            linkCorroborationPending = mapOf("com.android.chrome" to ("reddit.com" to 1_699_999_500_000L)),
            revealAttemptedThisVisit = false,
            hudDismissedThisSession = false,
            enabledTargets = listOf("web:reddit.com", "app:com.reddit.frontpage"),
            websiteMatchers = listOf("reddit.com"),
            coveringPolicyIds = listOf("web:reddit.com", "group:brainrot"),
            governingPolicyId = "group:brainrot",
        ),
        interstitialDepth = 0,
        interstitialShowing = false,
        openTargetId = "web:reddit.com",
        currentDecision = DecisionSnapshot(
            subtype = "Allowed",
            reason = null,
            remainingMs = 45_000L,
            usedMs = 135_000L,
            quotaMs = 180_000L,
            liftsAtMs = null,
            pausedUntilMs = null,
            recheckAtMs = 1_700_000_045_000L,
        ),
        currentSessions = listOf(SessionRow(1_699_999_900_000L, 1_700_000_000_000L)),
        pausedUntilMs = 0L,
        pausedTargets = mapOf("app:com.grumpyrhinogames.necromerger" to 1_700_000_900_000L),
        pauseBudget = PauseBudgetSnapshot(pausesUsed = 1, minutesUsed = 20, maxPauses = 3, maxMinutes = 60),
        workout = WorkoutSnapshot(
            enabled = true,
            appPackage = "io.strongapp.strong",
            source = "notification",
            startedAtMs = 1_699_990_000_000L,
            activeUntilMs = 1_700_001_000_000L,
            capMs = 3 * 60 * 60 * 1000L,
        ),
        requirements = listOf(RequirementSnapshot(id = "ENFORCEMENT", status = "READY", need = "REQUIRED")),
        outage = OutageSnapshot(
            heartbeatAtMs = 1_700_000_000_000L,
            downSinceMs = 0L,
            visitPackage = "com.sec.android.app.sbrowser",
            visitTarget = "web:reddit.com",
            visitFrictionServed = null,
        ),
        phone = PhoneSnapshot(
            screenOn = true,
            enabledAccessibilityServicesRaw = "com.ezhou.rotblock/.service.RotblockAccessibilityService",
            accessibilityMasterEnabled = true,
            rotblockInEnabledList = true,
            batteryUnrestricted = true,
            writeSecureSettingsGranted = false,
            systemAlertWindowGranted = true,
            notificationsGranted = true,
        ),
        policies = listOf(
            PolicySnapshot(
                id = "web:reddit.com",
                kind = "WEBSITE",
                matcher = "reddit.com",
                label = "Reddit",
                enabled = true,
                quotaSeconds = 180,
                windowSeconds = 3600,
                frictionSeconds = 5,
                grayscale = false,
                schedule = "",
            ),
        ),
        auditEvents = listOf(
            AuditEventSnapshot(
                id = 7,
                tsMs = 1_699_999_999_000L,
                type = "QUOTA_EXHAUSTED",
                targetId = "web:reddit.com",
                detail = "Quota exhausted",
                userReason = null,
                durationSeconds = null,
                overBudget = false,
            ),
        ),
        nodeDump = NodeDumpSnapshot(
            status = "ok",
            packageName = "com.sec.android.app.sbrowser",
            nodeCount = 2,
            truncated = false,
            nodes = listOf(
                NodeSnapshot(
                    depth = 1,
                    className = "android.widget.FrameLayout",
                    viewId = "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                    text = "reddit.com",
                    contentDescription = null,
                    bounds = "Rect(0, 0 - 100, 50)",
                    visibleToUser = true,
                    clickable = true,
                    targetUrl = null,
                ),
                NodeSnapshot(
                    depth = 2,
                    className = "android.view.View",
                    viewId = null,
                    text = null,
                    contentDescription = "Previous video",
                    bounds = "Rect(0, 50 - 100, 100)",
                    visibleToUser = true,
                    clickable = false,
                    targetUrl = "https://reddit.com/r/test",
                ),
            ),
            addressBar = "Text(\"reddit.com\")",
            linkUrls = listOf("https://reddit.com/r/test"),
            linkDescriptions = listOf("Previous video"),
            linkConsensusHost = "reddit.com",
            pageSignatureKey = null,
            classification = "Target(web:reddit.com)",
        ),
    )

    @Test
    fun `every section round-trips by name`() {
        val json = JSONObject(buildCapture(fullInput()))

        assertEquals(1_700_000_000_000L, json.getLong("capturedAtMs"))
        assertEquals("Samsung", json.getString("manufacturer"))
        assertEquals(34, json.getInt("sdkInt"))

        val enforcement = json.getJSONObject("enforcement")
        assertEquals("web:reddit.com", enforcement.getString("currentTargetId"))
        val trail = enforcement.getJSONArray("trail")
        assertEquals(1, trail.length())
        assertEquals(3, trail.getJSONObject(0).getInt("repeats"))

        val service = json.getJSONObject("service")
        assertTrue(service.getBoolean("tickerArmed"))
        assertEquals("web:reddit.com", service.getJSONObject("visitMemory").getString("com.sec.android.app.sbrowser"))
        val pending = service.getJSONObject("linkCorroborationPending").getJSONObject("com.android.chrome")
        assertEquals("reddit.com", pending.getString("host"))

        // The single most valuable field in the file -- see CLAUDE.md.
        val decision = json.getJSONObject("currentDecision")
        assertEquals("Allowed", decision.getString("subtype"))
        assertEquals(45_000L, decision.getLong("remainingMs"))
        assertEquals(1, json.getJSONArray("currentSessions").length())

        assertEquals(1, json.getJSONObject("pauseBudget").getInt("pausesUsed"))
        val workout = json.getJSONObject("workout")
        assertEquals("notification", workout.getString("source"))
        assertEquals(1_700_001_000_000L, workout.getLong("activeUntilMs"))
        assertEquals("ENFORCEMENT", json.getJSONArray("requirements").getJSONObject(0).getString("id"))
        assertEquals("web:reddit.com", json.getJSONObject("outage").getString("visitTarget"))
        assertTrue(json.getJSONObject("phone").getBoolean("rotblockInEnabledList"))
        assertEquals("Reddit", json.getJSONArray("policies").getJSONObject(0).getString("label"))
        assertEquals("QUOTA_EXHAUSTED", json.getJSONArray("auditEvents").getJSONObject(0).getString("type"))

        val dump = json.getJSONObject("nodeDump")
        assertEquals("ok", dump.getString("status"))
        assertEquals(2, dump.getJSONArray("nodes").length())
        assertEquals("Previous video", dump.getJSONArray("nodes").getJSONObject(1).getString("contentDescription"))
        assertEquals("Target(web:reddit.com)", dump.getString("classification"))
    }

    @Test
    fun `null fields become JSON null rather than being dropped`() {
        val minimal = fullInput().copy(
            service = null,
            currentDecision = null,
            pauseBudget = null,
            nodeDump = null,
        )
        val json = JSONObject(buildCapture(minimal))

        assertTrue(json.has("service"))
        assertTrue(json.isNull("service"))
        assertTrue(json.isNull("currentDecision"))
        assertTrue(json.isNull("pauseBudget"))
        assertTrue(json.isNull("nodeDump"))
    }

    @Test
    fun `a decision with no reason omits it as null rather than the string 'null'`() {
        val input = fullInput().copy(
            currentDecision = DecisionSnapshot(
                subtype = "Allow",
                reason = null,
                remainingMs = null,
                usedMs = null,
                quotaMs = null,
                liftsAtMs = null,
                pausedUntilMs = null,
                recheckAtMs = null,
            ),
        )
        val decision = JSONObject(buildCapture(input)).getJSONObject("currentDecision")

        assertEquals("Allow", decision.getString("subtype"))
        assertTrue(decision.isNull("reason"))
        assertTrue(decision.isNull("recheckAtMs"))
        assertFalse(decision.has("subtypeXyz"))
    }

    /**
     * Live, enabled-but-idle, and switched-off all leave [WorkoutSnapshot.enabled] and
     * [WorkoutSnapshot.activeUntilMs] in different combinations -- telling the three apart is the
     * entire reason the field exists, so this checks the rendered JSON distinguishes all three
     * rather than just that it parses.
     */
    @Test
    fun `workout mode renders differently live, idle, and switched off`() {
        val live = fullInput().workout
        val idle = live.copy(activeUntilMs = null, startedAtMs = 0L, source = "none")
        val off = idle.copy(enabled = false)

        val liveJson = JSONObject(buildCapture(fullInput().copy(workout = live))).getJSONObject("workout")
        val idleJson = JSONObject(buildCapture(fullInput().copy(workout = idle))).getJSONObject("workout")
        val offJson = JSONObject(buildCapture(fullInput().copy(workout = off))).getJSONObject("workout")

        assertTrue(liveJson.getBoolean("enabled"))
        assertFalse(liveJson.isNull("activeUntilMs"))
        assertEquals("notification", liveJson.getString("source"))

        assertTrue(idleJson.getBoolean("enabled"))
        assertTrue(idleJson.isNull("activeUntilMs"))
        assertEquals("none", idleJson.getString("source"))

        assertFalse(offJson.getBoolean("enabled"))
        assertTrue(offJson.isNull("activeUntilMs"))

        // Live and idle must not render identically -- the whole point of carrying activeUntilMs
        // separately from enabled.
        assertNotEquals(liveJson.toString(), idleJson.toString())
        assertNotEquals(idleJson.toString(), offJson.toString())
    }
}
