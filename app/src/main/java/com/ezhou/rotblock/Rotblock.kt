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

        // Open the database now so the first accessibility event, which must stay fast, is not
        // the thing that pays for it.
        Rotblock.scope.launch {
            runCatching { Rotblock.database(this@RotblockApp).policyDao().getAll() }
        }
    }
}
