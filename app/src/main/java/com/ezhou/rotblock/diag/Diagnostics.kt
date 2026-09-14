package com.ezhou.rotblock.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.policy.PolicyEngine
import com.ezhou.rotblock.service.EnforcementStatus
import com.ezhou.rotblock.service.KEY_DOWN_SINCE
import com.ezhou.rotblock.service.KEY_LAST_ALIVE
import com.ezhou.rotblock.service.KEY_VISIT_FRICTION_SERVED
import com.ezhou.rotblock.service.KEY_VISIT_PACKAGE
import com.ezhou.rotblock.service.KEY_VISIT_TARGET
import com.ezhou.rotblock.service.OUTAGE_PREFS
import com.ezhou.rotblock.setup.gatherSetupFacts
import com.ezhou.rotblock.setup.hasNotificationPermission
import com.ezhou.rotblock.setup.isAccessibilityEnabled
import com.ezhou.rotblock.setup.requirementsFor
import com.ezhou.rotblock.workout.WorkoutMode
import kotlinx.coroutines.launch

/**
 * Process-wide entry point for a diagnostic capture, shaped like
 * [com.ezhou.rotblock.block.BlockSelfTest]: plain state a Quick Settings tile writes to and the
 * accessibility service reads from, with no binder needed because both run in this process.
 *
 * The tile deliberately does not open rotblock to trigger this -- opening the app changes the
 * foreground window, which clears the exact state a capture exists to catch. This object is what
 * lets the tile ask for a capture without disturbing anything on screen.
 */
object Diagnostics {

    /** Set by the service while connected, cleared on teardown. Takes the request timestamp. */
    @Volatile var onCaptureRequested: ((Long) -> Unit)? = null

    @Volatile var lastCaptureAtMs: Long = 0L
        private set

    /**
     * Stamps now and asks for a capture. Returns the timestamp so a caller can show it at once,
     * without waiting on the write -- the write can take up to fifteen seconds, see
     * `RotblockAccessibilityService.attemptNodeDump`.
     *
     * When no service is bound there is nothing to ask -- which is itself the finding worth
     * capturing, since it means enforcement is not running. This writes what the app process can
     * still see (policies, settings, the audit log, requirements) rather than doing nothing.
     */
    fun request(context: Context): Long {
        val now = System.currentTimeMillis()
        lastCaptureAtMs = now
        val hook = onCaptureRequested
        if (hook != null) {
            hook(now)
        } else {
            val appContext = context.applicationContext
            Rotblock.scope.launch {
                runCatching { captureWithoutService(appContext, now) }
            }
        }
        return now
    }

    private suspend fun captureWithoutService(context: Context, now: Long) {
        val settings = Rotblock.settings(context).current()
        val policies = Rotblock.database(context).policyDao().getAll()
        val ledger = Rotblock.ledger(context)
        val pause = Rotblock.pause(context)
        pause.refresh()

        val outage = gatherOutageSnapshot(context)
        val targetId = outage.visitTarget
        val sessions = targetId?.let { ledger.sessionsFor(it, now) }.orEmpty()
        val decision = targetId?.let { id ->
            val policy = policies.firstOrNull { it.id == id }
            toDecisionSnapshot(
                PolicyEngine.evaluate(policy, sessions, pause.effectivePauseUntil(id), WorkoutMode.activeUntilMs(now), now),
            )
        }
        val budget = runCatching { pause.budgetStatus(now) }.getOrNull()?.let {
            PauseBudgetSnapshot(it.pausesUsed, it.minutesUsed, it.maxPauses, it.maxMinutes)
        }
        val requirements = requirementsFor(gatherSetupFacts(context, settings, policies, now)).map {
            RequirementSnapshot(it.id.name, it.status.name, it.need.name)
        }
        val events = runCatching { Rotblock.database(context).auditDao().recent(50) }
            .getOrDefault(emptyList())
            .map { toAuditEventSnapshot(it) }
        val appInfo = gatherAppInfo(context)

        val input = CaptureInput(
            capturedAtMs = now,
            processUptimeMs = now - Rotblock.processStartedAtMs,
            versionName = appInfo.first,
            versionCode = appInfo.second,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            osRelease = Build.VERSION.RELEASE,
            enforcement = EnforcementStatus.snapshot(),
            service = null,
            interstitialDepth = 0,
            interstitialShowing = false,
            openTargetId = ledger.openTargetId,
            currentDecision = decision,
            currentSessions = sessions.map { SessionRow(it.startMs, it.endMs) },
            pausedUntilMs = pause.pausedUntilMs,
            pausedTargets = pause.pausedTargets,
            pauseBudget = budget,
            workout = gatherWorkoutSnapshot(now),
            requirements = requirements,
            outage = outage,
            phone = gatherPhoneSnapshot(context),
            policies = policies.map { toPolicySnapshot(it) },
            auditEvents = events,
            nodeDump = NodeDumpSnapshot(
                status = "no service",
                packageName = null,
                nodeCount = 0,
                truncated = false,
                nodes = emptyList(),
                addressBar = null,
                linkUrls = emptyList(),
                linkDescriptions = emptyList(),
                linkConsensusHost = null,
                pageSignatureKey = null,
                classification = null,
            ),
        )
        CaptureStore(CaptureStore.directoryFor(context)).write(now, buildCapture(input))
    }
}

/** Shared with the accessibility service, so the two capture paths agree on what "live" means. */
fun gatherWorkoutSnapshot(now: Long): WorkoutSnapshot = WorkoutSnapshot(
    enabled = WorkoutMode.enabled,
    appPackage = WorkoutMode.appPackage,
    source = WorkoutMode.source.name.lowercase(),
    startedAtMs = WorkoutMode.startedAtMs,
    activeUntilMs = WorkoutMode.activeUntilMs(now),
    capMs = WorkoutMode.capMs,
)

/** Shared with the accessibility service, so the two capture paths ask the phone the same way. */
fun gatherPhoneSnapshot(context: Context): PhoneSnapshot {
    val interactive = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(false)
    val batteryUnrestricted = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)
    return PhoneSnapshot(
        screenOn = interactive,
        enabledAccessibilityServicesRaw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ),
        accessibilityMasterEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) != 0,
        rotblockInEnabledList = isAccessibilityEnabled(context),
        batteryUnrestricted = batteryUnrestricted,
        writeSecureSettingsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED,
        systemAlertWindowGranted = Settings.canDrawOverlays(context),
        notificationsGranted = hasNotificationPermission(context),
    )
}

/** Shared with the accessibility service. */
fun gatherOutageSnapshot(context: Context): OutageSnapshot {
    val prefs = context.getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE)
    return OutageSnapshot(
        heartbeatAtMs = prefs.getLong(KEY_LAST_ALIVE, 0L),
        downSinceMs = prefs.getLong(KEY_DOWN_SINCE, 0L),
        visitPackage = prefs.getString(KEY_VISIT_PACKAGE, null),
        visitTarget = prefs.getString(KEY_VISIT_TARGET, null),
        visitFrictionServed = prefs.getString(KEY_VISIT_FRICTION_SERVED, null),
    )
}

/** Shared with the accessibility service. versionName to versionCode. */
fun gatherAppInfo(context: Context): Pair<String, Long> {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        ?: return "unknown" to -1L
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    return (info.versionName ?: "unknown") to versionCode
}

fun toDecisionSnapshot(d: com.ezhou.rotblock.policy.Decision): DecisionSnapshot = when (d) {
    is com.ezhou.rotblock.policy.Decision.Allow -> DecisionSnapshot(
        subtype = "Allow",
        reason = null,
        remainingMs = null,
        usedMs = null,
        quotaMs = null,
        liftsAtMs = null,
        pausedUntilMs = d.pausedUntilMs,
        recheckAtMs = d.recheckAtMs,
    )
    is com.ezhou.rotblock.policy.Decision.Allowed -> DecisionSnapshot(
        subtype = "Allowed",
        reason = null,
        remainingMs = d.remainingMs,
        usedMs = d.usedMs,
        quotaMs = d.quotaMs,
        liftsAtMs = null,
        pausedUntilMs = null,
        recheckAtMs = d.recheckAtMs,
    )
    is com.ezhou.rotblock.policy.Decision.Blocked -> DecisionSnapshot(
        subtype = "Blocked",
        reason = d.reason.name,
        remainingMs = null,
        usedMs = null,
        quotaMs = null,
        liftsAtMs = d.liftsAtMs,
        pausedUntilMs = null,
        recheckAtMs = d.recheckAtMs,
    )
}

fun toPolicySnapshot(p: com.ezhou.rotblock.policy.Policy): PolicySnapshot = PolicySnapshot(
    id = p.id,
    kind = p.kind.name,
    matcher = p.matcher,
    label = p.label,
    enabled = p.enabled,
    quotaSeconds = p.quotaSeconds,
    windowSeconds = p.windowSeconds,
    frictionSeconds = p.frictionSeconds,
    grayscale = p.grayscale,
    schedule = p.schedule.encode(),
)

fun toAuditEventSnapshot(e: com.ezhou.rotblock.audit.AuditEvent): AuditEventSnapshot = AuditEventSnapshot(
    id = e.id,
    tsMs = e.tsMs,
    type = e.type.name,
    targetId = e.targetId,
    detail = e.detail,
    userReason = e.userReason,
    durationSeconds = e.durationSeconds,
    overBudget = e.overBudget,
)
