package com.ezhou.rotblock.insights

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Broad kind of app, for grouping usage in the Insights screen. */
enum class Category {
    SOCIAL, VIDEO, MESSAGING, GAMES, SHOPPING, NEWS, MUSIC, TOOLS, PRODUCTIVITY, BROWSER, UNKNOWN,
}

/** How an app's own time tends to read, independent of any rule the user set on it. */
enum class Productivity {
    VERY_DISTRACTING, DISTRACTING, NEUTRAL, PRODUCTIVE, UNCLASSIFIED,
}

/** Defaults to unclassified rather than null so a lookup miss never needs its own branch. */
data class Classification(
    val category: Category = Category.UNKNOWN,
    val productivity: Productivity = Productivity.UNCLASSIFIED,
)

/**
 * Bundled package -> category/productivity map, read from `assets/app_catalog.json`.
 *
 * The app has no network permission and must not acquire one for this: every answer ships in the
 * APK. A package the catalog has no opinion on -- or a catalog that fails to parse at all -- comes
 * back [Classification]'s defaults rather than throwing, so a stale or malformed asset degrades to
 * "everything unclassified" instead of crashing the Insights screen.
 *
 * Reporting only. Nothing in the enforcement path may touch this: opening an asset must never end
 * up on the inspection hot path.
 */
object AppCatalog {

    private const val ASSET_NAME = "app_catalog.json"

    @Volatile
    private var entries: Map<String, Classification>? = null

    fun classify(pkg: String): Classification = entries?.get(pkg) ?: Classification()

    /**
     * Reads and parses the asset once; every call after the first returns immediately.
     *
     * Suspending because it opens a file, and the caller is a composable — the answer is worth
     * waiting a frame for and not worth a jank on the main thread.
     */
    suspend fun load(context: Context) {
        if (entries != null) return
        val parsed = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() } }
                .map(::parse)
                .getOrDefault(emptyMap())
        }
        synchronized(this) { if (entries == null) entries = parsed }
    }

    /** Split out from [load] so the parsing is exercisable on a plain JVM, with no assets involved. */
    internal fun parse(json: String): Map<String, Classification> = runCatching {
        val array = JSONArray(json)
        buildMap {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val pkg = entry.optString("pkg").takeIf { it.isNotBlank() } ?: continue
                put(
                    pkg,
                    Classification(
                        category = decode(entry.optString("category"), Category.UNKNOWN),
                        productivity = decode(
                            entry.optString("productivity"),
                            Productivity.UNCLASSIFIED,
                        ),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    /** A name this build does not know decodes to the default, the same as one that is absent. */
    private inline fun <reified T : Enum<T>> decode(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}
