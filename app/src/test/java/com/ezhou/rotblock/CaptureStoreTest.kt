package com.ezhou.rotblock

import com.ezhou.rotblock.diag.CaptureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** File handling only -- [CaptureStore.directoryFor] needs a `Context` and is exercised on device. */
class CaptureStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L

    @Test
    fun `write creates a file that reads back the same content`() {
        val store = CaptureStore(tmp.root)
        val file = store.write(t0, """{"a":1}""")

        assertTrue(file.exists())
        assertEquals("""{"a":1}""", file.readText())
    }

    @Test
    fun `list is newest first`() {
        val store = CaptureStore(tmp.root)
        store.write(t0, "{}")
        store.write(t0 + 1_000, "{}")
        store.write(t0 + 2_000, "{}")

        val names = store.list().map { it.name }
        assertEquals(names, names.sortedDescending())
        assertEquals(3, names.size)
    }

    @Test
    fun `writing past 20 prunes the oldest`() {
        val store = CaptureStore(tmp.root)
        repeat(25) { i -> store.write(t0 + i * 1_000L, "{}") }

        val remaining = store.list()
        assertEquals(20, remaining.size)
        // Newest first, and the newest of the 25 survives.
        assertEquals(CaptureStore.fileName(t0 + 24_000L), remaining.first().name)
        // The oldest five were pruned.
        assertFalse(remaining.any { it.name == CaptureStore.fileName(t0) })
    }

    @Test
    fun `a capture past 30 days goes, however few there are`() {
        val store = CaptureStore(tmp.root)
        store.write(t0, "{}")
        store.write(t0 + 1_000, "{}")

        // Well inside MAX_CAPTURES, so only age can be what removes these.
        store.write(t0 + 31L * 24 * 60 * 60 * 1000, "{}")

        val remaining = store.list().map { it.name }
        assertEquals(1, remaining.size)
        assertEquals(CaptureStore.fileName(t0 + 31L * 24 * 60 * 60 * 1000), remaining.first())
    }

    @Test
    fun `a capture inside 30 days stays`() {
        val store = CaptureStore(tmp.root)
        store.write(t0, "{}")

        store.write(t0 + 29L * 24 * 60 * 60 * 1000, "{}")

        assertEquals(2, store.list().size)
    }

    @Test
    fun `a name that will not parse is kept rather than deleted`() {
        val store = CaptureStore(tmp.root)
        // Matches the capture- prefix so list() returns it, but carries no readable timestamp.
        val unparseable = tmp.newFile("capture-not-a-timestamp.json")

        store.write(t0 + 400L * 24 * 60 * 60 * 1000, "{}")

        assertTrue(unparseable.exists())
        assertEquals(null, CaptureStore.timeOf(unparseable))
    }

    @Test
    fun `deleteAll empties the directory`() {
        val store = CaptureStore(tmp.root)
        store.write(t0, "{}")
        store.write(t0 + 1_000, "{}")

        store.deleteAll()

        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `append adds to an existing file without disturbing its start`() {
        val store = CaptureStore(tmp.root)
        val file = store.write(t0, "{\"a\":1}")

        store.append(file, "\n// note")

        assertEquals("{\"a\":1}\n// note", file.readText())
    }
}
