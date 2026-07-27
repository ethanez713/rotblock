package com.ezhou.rotblock.block

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Forces the whole display to greyscale while a chosen app is open.
 *
 * Colour is a large part of what makes a feed compelling; draining it makes scrolling markedly
 * less rewarding without blocking anything.
 *
 * This writes a **global** secure setting, so the dangerous failure is leaving the phone
 * monochrome. Three defences: the original values are saved to disk *before* the first write, an
 * `active` flag persists across process death, and [restoreOnStart] runs unconditionally when the
 * service connects so a crash mid-greyscale is undone the moment enforcement comes back.
 *
 * Requires WRITE_SECURE_SETTINGS, which only ADB can grant:
 *   adb shell pm grant com.ezhou.rotblock android.permission.WRITE_SECURE_SETTINGS
 * Without it every method here is a no-op and the rest of the policy still works.
 */
class Grayscale(private val context: Context) {

    private companion object {
        // @hide constants, so the raw keys are used deliberately rather than via Settings.Secure.
        const val KEY_ENABLED = "accessibility_display_daltonizer_enabled"
        const val KEY_MODE = "accessibility_display_daltonizer"

        /** Daltonizer mode 0 is monochromacy. */
        const val MODE_MONOCHROME = 0

        const val PREFS = "rotblock_grayscale"
        const val PREF_ACTIVE = "active"
        const val PREF_SAVED_ENABLED = "saved_enabled"
        const val PREF_SAVED_MODE = "saved_mode"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Cheap in-memory guard so the hot path does not hit SharedPreferences every tick. */
    @Volatile private var active: Boolean = prefs.getBoolean(PREF_ACTIVE, false)

    val isAvailable: Boolean
        get() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Undoes a greyscale left on by a crash or a force-stop. Called unconditionally at startup:
     * being wrong in the harmless direction (restoring colour that was already there) beats any
     * chance of leaving the screen grey.
     */
    fun restoreOnStart() {
        if (prefs.getBoolean(PREF_ACTIVE, false)) restore()
    }

    fun enable() {
        if (active || !isAvailable) return
        try {
            val resolver = context.contentResolver
            val previousEnabled = Settings.Secure.getInt(resolver, KEY_ENABLED, 0)
            val previousMode = Settings.Secure.getInt(resolver, KEY_MODE, MODE_MONOCHROME)

            // Persist BEFORE mutating, so a crash between the two writes still leaves a record of
            // what to put back.
            prefs.edit()
                .putInt(PREF_SAVED_ENABLED, previousEnabled)
                .putInt(PREF_SAVED_MODE, previousMode)
                .putBoolean(PREF_ACTIVE, true)
                .commit()
            active = true

            Settings.Secure.putInt(resolver, KEY_MODE, MODE_MONOCHROME)
            Settings.Secure.putInt(resolver, KEY_ENABLED, 1)
        } catch (t: Throwable) {
            Log.e("rotblock", "greyscale enable failed", t)
            restore()
        }
    }

    fun restore() {
        if (!prefs.getBoolean(PREF_ACTIVE, false)) {
            active = false
            return
        }
        try {
            val resolver = context.contentResolver
            val savedEnabled = prefs.getInt(PREF_SAVED_ENABLED, 0)
            val savedMode = prefs.getInt(PREF_SAVED_MODE, MODE_MONOCHROME)
            Settings.Secure.putInt(resolver, KEY_ENABLED, savedEnabled)
            Settings.Secure.putInt(resolver, KEY_MODE, savedMode)
        } catch (t: Throwable) {
            Log.e("rotblock", "greyscale restore failed", t)
        } finally {
            // Cleared even if the write threw: a stuck flag would stop future restores from
            // running at all, which is the worse failure.
            prefs.edit().putBoolean(PREF_ACTIVE, false).commit()
            active = false
        }
    }
}
