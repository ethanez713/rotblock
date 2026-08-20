package com.ezhou.rotblock

import android.app.Application
import android.content.Context
import com.ezhou.rotblock.audit.AuditLog
import com.ezhou.rotblock.block.Grayscale
import com.ezhou.rotblock.data.PendingChangeQueue
import com.ezhou.rotblock.data.RotblockDatabase
import com.ezhou.rotblock.data.SettingsRepository
import com.ezhou.rotblock.pause.PauseController
import com.ezhou.rotblock.quota.UsageLedger
import com.ezhou.rotblock.setup.AccessibilityEnrolment
import com.ezhou.rotblock.setup.EnforcementRevival
import com.ezhou.rotblock.workout.WorkoutMode
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide singletons.
 *
 * Deliberately a hand-rolled locator rather than a DI framework: this app is one module with a
 * handful of objects in it, and the accessibility service needs them from a plain
 * `AccessibilityService` that has no injection point.
 */
object Rotblock {
    /** Survives individual failures; used for all background writes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("rotblock"))

    /**
     * When this process started, near enough.
     *
     * Read by the setup screen, which has to tell "the accessibility service is dead" from "the
     * accessibility service has not been bound *yet*" — the same condition a second apart, and only
     * one of them is worth alarming anyone about. Set on first touch of this object, which
     * [RotblockApp] does before anything else.
     */
    val processStartedAtMs: Long = System.currentTimeMillis()

    @Volatile private var db: RotblockDatabase? = null
    @Volatile private var settingsRepo: SettingsRepository? = null
    @Volatile private var ledgerRef: UsageLedger? = null
    @Volatile private var auditRef: AuditLog? = null
    @Volatile private var pauseRef: PauseController? = null
    @Volatile private var queueRef: PendingChangeQueue? = null

    fun database(context: Context): RotblockDatabase =
        db ?: synchronized(this) { db ?: RotblockDatabase.get(context).also { db = it } }

    fun settings(context: Context): SettingsRepository =
        settingsRepo ?: synchronized(this) {
            settingsRepo ?: SettingsRepository(context).also { settingsRepo = it }
        }

    fun ledger(context: Context): UsageLedger =
        ledgerRef ?: synchronized(this) {
            ledgerRef ?: UsageLedger(database(context).usageDao(), scope).also { ledgerRef = it }
        }

    fun audit(context: Context): AuditLog =
        auditRef ?: synchronized(this) {
            auditRef ?: AuditLog(database(context).auditDao(), scope).also { auditRef = it }
        }

    fun pause(context: Context): PauseController =
        pauseRef ?: synchronized(this) {
            pauseRef ?: PauseController(
                settings = settings(context),
                auditDao = database(context).auditDao(),
                audit = audit(context),
            ).also { pauseRef = it }
        }

    fun changeQueue(context: Context): PendingChangeQueue =
        queueRef ?: synchronized(this) {
            queueRef ?: PendingChangeQueue(
                policyDao = database(context).policyDao(),
                pendingDao = database(context).pendingChangeDao(),
                settings = settings(context),
                audit = audit(context),
            ).also { queueRef = it }
        }
}

class RotblockApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Undo a greyscale that outlived the thing that set it.
        //
        // The accessibility service does this too, but force-stopping rotblock while greyscale is
        // active *also* removes it from Android's enabled-services list -- verified on device --
        // so the service alone can never recover: the phone stays monochrome until the user goes
        // and re-enables accessibility, with no hint that is what is wrong. Doing it here means
        // simply opening rotblock puts the colour back, which is what anyone would try first.
        runCatching { Grayscale(this).restoreOnStart() }

        // Undo a disabled service component that outlived the thing that set it. Same shape as the
        // greyscale restore above and there for the same reason: EnforcementRevival.attemptRebind
        // disables the component and re-enables it, and a process kill between those two calls
        // would leave enforcement switched off at a level nothing else in the app ever reads.
        runCatching { EnforcementRevival.ensureEnabled(this) }

        // And undo Android having dropped rotblock from its accessibility list, which a force-stop
        // does on its own. Same shape again, and the last of the three states where simply opening
        // the app is what puts enforcement back. Heavily fenced -- see [shouldRestoreEnrolment] --
        // so it can only ever restore something that was already running, never switch it on.
        runCatching { AccessibilityEnrolment.restoreIfDropped(this) }

        // Open the database now so the first accessibility event, which must stay fast, is not
        // the thing that pays for it.
        Rotblock.scope.launch {
            runCatching { Rotblock.database(this@RotblockApp).policyDao().getAll() }
        }

        // The one writer of WorkoutMode's config. Both the accessibility service and the
        // notification listener run in this process, either one can be the only one currently
        // alive, and letting each push its own copy of these fields would be two places that have
        // to be kept in step -- so neither does, and both just read WorkoutMode directly.
        Rotblock.scope.launch {
            runCatching {
                Rotblock.settings(this@RotblockApp).settings.collect {
                    WorkoutMode.enabled = it.workoutModeEnabled
                    WorkoutMode.appPackage = it.workoutAppPackage
                    WorkoutMode.capMs = it.workoutCapMinutes * 60_000L
                    WorkoutMode.reminderMs = it.workoutReminderSeconds * 1000L
                    WorkoutMode.allowedPackages = it.workoutAllowedPackages
                }
            }
        }
    }
}
