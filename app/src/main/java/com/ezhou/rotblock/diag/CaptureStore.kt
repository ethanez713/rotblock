package com.ezhou.rotblock.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How many captures are kept on disk. Older ones are dropped on every [CaptureStore.write]. */
private const val MAX_CAPTURES = 20

/**
 * How long a capture is kept regardless of how few there are.
 *
 * [MAX_CAPTURES] alone bounds the disk but not the *age*: take twenty captures chasing one bug and
 * they sit there until twenty more replace them, which on a quiet month is never. That matters
 * here more than it would for an ordinary log, because a capture holds the page URLs and on-screen
 * text that led to a decision -- the most sensitive thing this app stores, per `CLAUDE.md`. A
 * capture is taken to debug something happening now; one nobody has looked at in a month has
 * outlived that, and keeping it is only exposure.
 */
private const val MAX_CAPTURE_AGE_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Where captures live, and pruning.
 *
 * Backed by a plain directory rather than the database: a capture is taken because something is
 * already wrong, and a Room write competing with whatever the accessibility service is doing at
 * that moment is exactly the extra risk this should not add. Kept apart from
 * [com.ezhou.rotblock.diag.Diagnostics] so the file-handling half of this can be exercised in a
 * plain JVM test, over a temp directory, with no `Context` at all.
 */
class CaptureStore(private val dir: File) {

    /**
     * Writes one capture, prunes to [MAX_CAPTURES] and [MAX_CAPTURE_AGE_MS], and returns the file
     * written.
     *
     * [atMs] doubles as the clock for the age prune. Pruning is opportunistic, on the one path
     * that already touches this directory -- nothing here may wake the phone, and a capture
     * outliving its window by however long it takes the next one to be taken costs nothing.
     */
    fun write(atMs: Long, json: String): File {
        dir.mkdirs()
        val file = File(dir, fileName(atMs))
        file.writeText(json)
        prune(atMs)
        return file
    }

    /** Appends raw text to an existing capture file. */
    fun append(file: File, text: String) {
        file.appendText(text)
    }

    /** Every capture on disk, newest first -- the names sort chronologically by construction. */
    fun list(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith("capture-") } ?: emptyArray())
            .sortedByDescending { it.name }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun prune(nowMs: Long) {
        val newestFirst = list()
        newestFirst.drop(MAX_CAPTURES).forEach { it.delete() }
        newestFirst.take(MAX_CAPTURES)
            .filter { file -> timeOf(file)?.let { nowMs - it > MAX_CAPTURE_AGE_MS } == true }
            .forEach { it.delete() }
    }

    companion object {
        /**
         * External-app-private, not internal: still unreadable by other apps, but reachable from a
         * computer with `adb pull /sdcard/Android/data/com.ezhou.rotblock/files/diagnostics/`,
         * which is how a capture actually gets off the phone and in front of anyone debugging it.
         * Falls back to internal storage on the rare device with no external storage mounted.
         */
        fun directoryFor(context: Context): File =
            context.getExternalFilesDir("diagnostics") ?: File(context.filesDir, "diagnostics")

        fun fileName(atMs: Long): String =
            "capture-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(atMs))}.json"

        /**
         * When a capture was taken, read back off its own name.
         *
         * The name rather than `lastModified()`, because the node dump is appended up to fifteen
         * seconds after the file is created and a restore or a file-manager copy rewrites the
         * mtime outright -- neither says anything about when the capture was taken.
         *
         * A name that will not parse answers null and is therefore *kept*. Degrading toward
         * keeping a file we cannot identify is the only safe direction: the cost is one stale
         * capture, against deleting something whose age was never established.
         */
        fun timeOf(file: File): Long? = runCatching {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .parse(file.name.removePrefix("capture-").removeSuffix(".json"))
                ?.time
        }.getOrNull()
    }
}
