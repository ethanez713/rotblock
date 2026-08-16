package com.ezhou.rotblock.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How many captures are kept on disk. Older ones are dropped on every [CaptureStore.write]. */
private const val MAX_CAPTURES = 20

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

    /** Writes one capture, prunes to [MAX_CAPTURES], and returns the file written. */
    fun write(atMs: Long, json: String): File {
        dir.mkdirs()
        val file = File(dir, fileName(atMs))
        file.writeText(json)
        prune()
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

    private fun prune() {
        list().drop(MAX_CAPTURES).forEach { it.delete() }
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
    }
}
