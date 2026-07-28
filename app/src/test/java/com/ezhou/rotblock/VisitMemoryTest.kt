package com.ezhou.rotblock

import com.ezhou.rotblock.service.VisitMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Re-entering an app that has to be read to be understood.
 *
 * The bug this exists for had no symptom: leave a browser sitting on a blocked site, go anywhere
 * else, come back, and the target was gone. The app switch cleared it, and the read that should
 * have re-established it could not see a thing, because the page was scrolled and the address bar
 * was hidden. Reddit then sat on screen filling no quota and heading for no block, silently.
 */
class VisitMemoryTest {

    private val sbrowser = "com.sec.android.app.sbrowser"

    @Test
    fun `a target established by a read comes back on re-entry`() {
        val memory = VisitMemory()
        memory.remember(sbrowser, "web:reddit.com")
        assertEquals("web:reddit.com", memory.resume(sbrowser))
    }

    @Test
    fun `an app never read holds nothing`() {
        // The memory may only ever repeat a read, never invent one -- otherwise a browser you have
        // never opened a blocked site in could arrive already blocked.
        assertNull(VisitMemory().resume(sbrowser))
    }

    @Test
    fun `a read that finds nothing covered clears it`() {
        val memory = VisitMemory()
        memory.remember(sbrowser, "web:reddit.com")
        memory.forget(sbrowser)
        assertNull(memory.resume(sbrowser))
    }

    @Test
    fun `the newest read wins`() {
        val memory = VisitMemory()
        memory.remember(sbrowser, "web:reddit.com")
        memory.remember(sbrowser, "web:youtube.com")
        assertEquals("web:youtube.com", memory.resume(sbrowser))
    }

    @Test
    fun `apps are remembered separately`() {
        val memory = VisitMemory()
        memory.remember(sbrowser, "web:reddit.com")
        memory.remember("com.google.android.youtube", "feed:youtube_shorts")
        assertEquals("web:reddit.com", memory.resume(sbrowser))
        assertEquals("feed:youtube_shorts", memory.resume("com.google.android.youtube"))
    }

    @Test
    fun `it stays bounded without losing the app being used`() {
        val memory = VisitMemory()
        repeat(200) { memory.remember("pkg$it", "web:site$it.com") }
        memory.remember(sbrowser, "web:reddit.com")
        assertEquals("web:reddit.com", memory.resume(sbrowser))
    }
}
