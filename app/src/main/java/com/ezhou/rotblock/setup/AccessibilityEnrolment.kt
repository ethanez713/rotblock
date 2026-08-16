package com.ezhou.rotblock.setup

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.service.KEY_DOWN_SINCE
import com.ezhou.rotblock.service.KEY_LAST_ALIVE
import com.ezhou.rotblock.service.OUTAGE_PREFS
import com.ezhou.rotblock.service.RotblockAccessibilityService

/**
 * Putting rotblock back into Android's enabled-accessibility-services list after the phone drops it.
 *
 * This is the answer to the one failure mode nothing else in the app can repair. Force-stopping
 * rotblock removes it from that list — verified on device, and the reason [com.ezhou.rotblock.RotblockApp]
 * restores greyscale at startup — while the toggle in Settings goes on looking switched on. From
 * inside the app there is nothing to do about it but tell the user to walk to Settings, and until
 * they do, nothing is blocked at all.
 *
 * The list is a secure setting, and rotblock already holds `WRITE_SECURE_SETTINGS` because greyscale
 * needs it. So on a phone where that grant exists, this is repairable from here.
 *
 * [EnforcementRevival] is the neighbouring case and stays separate: that one is for a service that
 * *is* listed and has not bound, which is a different fault with a different repair.
 */
object AccessibilityEnrolment {

    fun canSelfEnrol(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Writes rotblock into the list. Returns whether anything actually changed.
     *
     * User-initiated only, or via [restoreIfDropped], which is far more careful about when. Without
     * the grant this throws a `SecurityException`, which is the ordinary case on any phone that was
     * not set up over ADB and is not an error worth reporting.
     */
    fun enrol(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val merged = withServiceEnrolled(current, componentName(context))
        if (merged == current) {
            false
        } else {
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merged,
            )
            // The list alone is not enough: Android reads this as the master switch, and a phone
            // whose last accessibility service was rotblock has it sitting at 0.
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }
    }.getOrDefault(false)

    /**
     * Repairs the list, but only where the evidence says the phone dropped us rather than the user
     * switching us off. Cheap enough to call from anywhere that happens to be running anyway.
     */
    fun restoreIfDropped(context: Context) {
        val prefs = runCatching {
            context.getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        val decided = shouldRestoreEnrolment(
            listedByAndroid = isAccessibilityEnabled(context),
            canWriteSecureSettings = canSelfEnrol(context),
            lastAliveMs = prefs.getLong(KEY_LAST_ALIVE, 0L),
            cleanlyStoppedAtMs = prefs.getLong(KEY_DOWN_SINCE, 0L),
        )
        if (!decided || !enrol(context)) return
        Rotblock.audit(context).record(
            AuditType.ENFORCEMENT_RE_ENROLLED,
            detail = "Android had dropped rotblock from its accessibility list; put back",
        )
    }

    private fun componentName(context: Context): String =
        ComponentName(context, RotblockAccessibilityService::class.java).flattenToString()
}

/**
 * Whether rotblock should put itself back in the list without being asked.
 *
 * Two of these four conditions are about *permission* and two are about *evidence*, and the
 * evidence half is the whole design. Being absent from the list has two causes that look identical
 * from here — the phone force-stopped us, or the user went to Settings and switched us off — and
 * silently undoing the second would be the app fighting its owner. rotblock's whole posture is that
 * a way out always exists and is merely made visible and time-bounded; a switch that flicks itself
 * back on is not that.
 *
 * The two are told apart by something already on disk. A deliberate switch-off unbinds the service,
 * which runs `teardown` and stamps [KEY_DOWN_SINCE]. A force-stop, a crash, or a reboot kills the
 * process with no callback at all, so that stamp is absent while [KEY_LAST_ALIVE] still holds a
 * heartbeat. The same distinction `reportPreviousOutage` already relies on to decide what to write
 * in the log. So: heard from, never told to stop, and not in the list — that is a drop, not a
 * decision.
 *
 * [lastAliveMs] carries a second job. Zero means enforcement has never once run on this phone, so
 * this is a fresh install rather than a repair — and rotblock must never switch its own
 * accessibility service on for the first time, because that would step around the disclosure the
 * user is supposed to accept first. It only ever puts back what was already there.
 */
fun shouldRestoreEnrolment(
    listedByAndroid: Boolean,
    canWriteSecureSettings: Boolean,
    lastAliveMs: Long,
    cleanlyStoppedAtMs: Long,
): Boolean =
    !listedByAndroid &&
        canWriteSecureSettings &&
        lastAliveMs > 0L &&
        cleanlyStoppedAtMs <= 0L

/**
 * [current] with [component] present, as a colon-separated list.
 *
 * It may only ever add rotblock. Removing or reordering somebody's screen reader would be a far
 * worse thing to do than the fault being repaired, and this is the only code in the app that could
 * — hence the untouched passthrough when we are already listed, rather than a rebuilt string that
 * happens to be equivalent. Case-insensitive to match how [isAccessibilityEnabled] reads the same
 * list back.
 */
fun withServiceEnrolled(current: String?, component: String): String {
    val existing = current.orEmpty()
    val entries = existing.split(':').filter { it.isNotBlank() }
    if (entries.any { it.equals(component, ignoreCase = true) }) return existing
    return (entries + component).joinToString(":")
}
