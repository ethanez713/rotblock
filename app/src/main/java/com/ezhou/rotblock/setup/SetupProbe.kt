package com.ezhou.rotblock.setup

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind
import com.ezhou.rotblock.service.EnforcementStatus
import com.ezhou.rotblock.service.KEY_LAST_ALIVE
import com.ezhou.rotblock.service.OUTAGE_PREFS
import com.ezhou.rotblock.service.RotblockAccessibilityService
import com.ezhou.rotblock.workout.WorkoutMode

/**
 * Asks the phone every question [requirementsFor] needs answering, in one pass.
 *
 * Everything here is read from the system rather than from anything rotblock stores about itself.
 * That is the whole point: a flag saying "the user granted this" goes stale the moment they revoke
 * it in Settings, and the states worth catching are exactly the ones where what we were told and
 * what is true have come apart.
 */
fun gatherSetupFacts(
    context: Context,
    settings: RotblockSettings,
    policies: List<Policy>,
    nowMs: Long = System.currentTimeMillis(),
): SetupFacts = SetupFacts(
    accessibilityEnabled = isAccessibilityEnabled(context),
    serviceConnected = EnforcementStatus.snapshot().connected,
    lastAliveMs = lastAliveMs(context),
    overlayGranted = Settings.canDrawOverlays(context),
    notificationsGranted = hasNotificationPermission(context),
    batteryUnrestricted = isBatteryUnrestricted(context),
    secureSettingsGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_SECURE_SETTINGS,
    ) == PackageManager.PERMISSION_GRANTED,
    installedFromStore = isInstalledFromStore(context),
    processUptimeMs = nowMs - Rotblock.processStartedAtMs,
    notificationReadoutWanted = settings.hudNotification,
    greyscaleRuleCount = policies.count { it.enabled && it.grayscale },
    packageName = context.packageName,
    nowMs = nowMs,
    rottedFeeds = rottedFeedsFrom(settings, policies, nowMs),
    blockTestAtMs = settings.blockTestAtMs,
    blockTestSeen = settings.blockTestSeen,
    workoutModeWanted = settings.workoutModeEnabled,
    notificationListenerGranted = hasNotificationListenerAccess(context),
    notificationListenerConnected = WorkoutMode.listenerConnected,
    usageAccessGranted = hasUsageAccess(context),
)

/**
 * Only feeds someone actually wrote a rule for, and only while that rule is on.
 *
 * The counters do not know about policies -- the service increments them wherever it looks, which
 * is what keeps them off the enforcement path -- so the join happens here. A feed nobody asked to
 * be blocked has never been failing to block.
 */
private fun rottedFeedsFrom(
    settings: RotblockSettings,
    policies: List<Policy>,
    nowMs: Long,
): List<FeedProbe> {
    val counters = DetectionCounters.decode(settings.detectionCounters)
    if (counters.windowStartMs == 0L) return emptyList()
    val ruled = FeedRegistry.specs.filter { spec ->
        policies.any { it.enabled && it.kind == TargetKind.FEED && it.matcher == spec.key }
    }
    return rottedFeeds(counters.probes(ruled), nowMs - counters.windowStartMs)
}

/**
 * Reads Android's own enabled-services list rather than tracking a flag of our own, so the answer
 * stays right when the service is switched off from outside the app -- which is how it is usually
 * switched off.
 */
fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, RotblockAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
    for (entry in splitter) {
        if (entry.equals(expected, ignoreCase = true)) return true
    }
    return false
}

/**
 * Usage access, the app-op behind [android.app.usage.UsageStatsManager].
 *
 * Not a runtime permission: [Context.checkSelfPermission] stays denied after the user has allowed
 * it, so the app-op is the check. The API 36 rename of this call is not on minSdk 29. A failure
 * reads as not granted, which is the ledger-only path.
 */
@Suppress("DEPRECATION")
fun hasUsageAccess(context: Context): Boolean = runCatching {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName,
    ) == AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

/** Notification-listener access, which is a separate grant from ordinary notification posting. */
private fun hasNotificationListenerAccess(context: Context): Boolean = runCatching {
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}.getOrDefault(false)

fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun isBatteryUnrestricted(context: Context): Boolean = runCatching {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    power.isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(true) // Unknown reads as fine: this is advice, and a false alarm here is noise.

private fun lastAliveMs(context: Context): Long = runCatching {
    context.getSharedPreferences(OUTAGE_PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_ALIVE, 0L)
}.getOrDefault(0L)

/**
 * Whether an app store installed this, which decides whether ADB advice is worth showing at all.
 *
 * Android's "Restricted settings" only hides the accessibility switch for sideloaded apps, so on a
 * Play install the whole appops dance is noise -- and noise on a setup screen is what stops people
 * reading the part that matters.
 */
fun isInstalledFromStore(context: Context): Boolean = runCatching {
    val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
    installer != null && installer != "com.android.shell" && installer != "adb"
}.getOrDefault(false)

/** Where a [Fix.OpenSettings] actually goes. Null when this phone has no such screen. */
fun settingsIntentFor(context: Context, screen: SettingsScreen): Intent? = when (screen) {
    SettingsScreen.ACCESSIBILITY ->
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    SettingsScreen.OVERLAY ->
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    // The list of apps, not the "let this app ignore battery optimisation" prompt. The prompt
    // needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Play restricts to a short list of app
    // types, and this reaches the same setting without asking for anything.
    SettingsScreen.BATTERY ->
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    SettingsScreen.APP_NOTIFICATIONS ->
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    SettingsScreen.NOTIFICATION_LISTENER ->
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    SettingsScreen.USAGE_ACCESS ->
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
