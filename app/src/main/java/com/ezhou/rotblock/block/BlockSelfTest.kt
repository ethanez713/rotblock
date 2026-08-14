package com.ezhou.rotblock.block

/**
 * The request/result channel between Setup's "Test the block screen" button and the service that
 * actually owns the `WindowManager` -- Setup cannot draw over other apps itself, and a mock of the
 * overlay would only prove the mock works. Modelled on
 * [com.ezhou.rotblock.service.EnforcementStatus]: plain state either side reads or writes
 * directly, not a binder or a broadcast.
 *
 * Exists because a granted `SYSTEM_ALERT_WINDOW` is not proof the window actually paints -- some
 * OEMs grant the permission and silently drop the window anyway, which the permission check alone
 * can never see. Only a human looking at the screen can tell those apart, so the result this
 * records is what the user reports, not what the API claims.
 */
object BlockSelfTest {

    /** How long the test overlay stays up if nothing is tapped. */
    const val TIMEOUT_MS = 10_000L

    /**
     * Set by the service while connected, cleared on teardown. Invoked on whatever thread calls
     * [request] -- a Setup button tap is already on the main thread, the same thread the service
     * does everything on, so there is nothing to post across. Returns whether the overlay actually
     * went up.
     */
    @Volatile var onRequested: (() -> Boolean)? = null

    @Volatile private var running = false

    val isRunning: Boolean get() = running

    /**
     * True once the overlay is up. False means it was refused -- a real block is already showing,
     * or the window could not be created -- and null means there is no service to ask at all.
     */
    fun request(): Boolean? {
        val callback = onRequested ?: return null
        if (running) return false
        val started = callback()
        running = started
        return started
    }

    /** Called by the service once the overlay comes down, tapped or timed out. */
    fun ended() {
        running = false
    }
}
