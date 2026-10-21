package com.ezhou.rotblock.setup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.ezhou.rotblock.service.RotblockAccessibilityService

/**
 * The one thing rotblock can do about its own service being listed as on and not running.
 *
 * Disabling and re-enabling the service *component* makes Android tear down and re-create the
 * binding, which is the in-app equivalent of the advice [requirementsFor] otherwise has to give:
 * go to Settings → Accessibility and toggle the switch. Worth having because that state is common
 * on One UI and the advice is a trip out of the app for something the app could try itself.
 *
 * Not an Android `Service` and not a receiver. A blocker that fires this from a boot receiver has to
 * wrap it in a `Service`; this app has none and must not gain one —
 * see `CLAUDE.md`, "No alarms, no `WorkManager`, no wakelocks, no foreground service, no boot
 * receiver."
 */
object EnforcementRevival {

    private fun component(context: Context) =
        ComponentName(context, RotblockAccessibilityService::class.java)

    /**
     * Off and on again, at the component level. User-initiated only.
     *
     * Deliberately not fired automatically when [Readiness] computes BROKEN, even though that is
     * exactly when it would help. Toggling the component may cause Android to drop rotblock from
     * its enabled-accessibility-services list, which is a worse state than the one being repaired
     * and would happen with nobody watching. That risk is **unverified on device**; promoting this
     * to automatic is contingent on someone checking, on a real phone, that a BROKEN service
     * survives the round trip still listed as enabled.
     */
    fun attemptRebind(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val component = component(context)
        pm.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        pm.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }.isSuccess

    /**
     * Re-enable the component unconditionally. The safety net, and the reason [attemptRebind] is
     * acceptable at all.
     *
     * A process kill landing between [attemptRebind]'s two calls leaves the service component
     * **disabled**, which is a permanent, silent fail-open — the exact failure this app exists to
     * prevent, and one with no symptom anywhere. Nothing in the app would ever set it back.
     * Idempotent, so calling it at every start costs a `PackageManager` write on the one boot in a
     * thousand where it matters and nothing on the rest.
     */
    fun ensureEnabled(context: Context) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
