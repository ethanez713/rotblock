package com.ezhou.rotblock.block

/**
 * Short-form feeds living inside apps that are otherwise fine to use.
 *
 * This is the feature that makes the whole app workable: blocking YouTube outright is not an
 * option when you actually use it, but the Shorts shelf is pure rot. Detection is by view id,
 * which is the most brittle surface in the codebase -- these apps redesign constantly, so a miss
 * has to degrade to "not blocked", never to a crash or a false block on the rest of the app.
 *
 * The IDs below are starting points and MUST be checked against the installed version:
 *   adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml
 * with the feed open, then look for the container node's resource-id.
 */
data class FeedSpec(
    val key: String,
    val label: String,
    val packageName: String,
    /** Any one of these being present means the feed is on screen. */
    val viewIdHints: List<String>,
)

object FeedRegistry {

    val specs = listOf(
        FeedSpec(
            key = "youtube_shorts",
            label = "YouTube Shorts",
            packageName = "com.google.android.youtube",
            viewIdHints = listOf(
                "com.google.android.youtube:id/reel_recycler",
                "com.google.android.youtube:id/reel_player_page_container",
                "com.google.android.youtube:id/shorts_video_container",
            ),
        ),
        FeedSpec(
            key = "instagram_reels",
            label = "Instagram Reels",
            packageName = "com.instagram.android",
            viewIdHints = listOf(
                "com.instagram.android:id/clips_viewer_view_pager",
                "com.instagram.android:id/clips_video_container",
            ),
        ),
        FeedSpec(
            key = "facebook_reels",
            label = "Facebook Reels",
            packageName = "com.facebook.katana",
            viewIdHints = listOf(
                "com.facebook.katana:id/reels_viewer_root",
            ),
        ),
    )

    private val byPackage = specs.groupBy { it.packageName }

    val packages: Set<String> = byPackage.keys

    fun specsFor(packageName: String): List<FeedSpec> = byPackage[packageName].orEmpty()
}
