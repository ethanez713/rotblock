package com.ezhou.rotblock.browser

/**
 * Where each browser keeps its address bar in the accessibility tree.
 *
 * These IDs are data, not scattered constants, because they are the most fragile thing in the
 * app -- a browser redesign silently breaks web blocking. Several IDs per browser because they
 * differ between the focused (editing) and unfocused (displaying) states, and across versions.
 *
 * To find the ID for a browser that is not here: open it, then
 *   adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml
 * and look for the resource-id on the node holding the URL text.
 */
data class BrowserSpec(val packageName: String, val urlBarIds: List<String>)

object BrowserRegistry {

    private val specs = listOf(
        BrowserSpec(
            "com.android.chrome",
            listOf("com.android.chrome:id/url_bar", "com.android.chrome:id/search_box_text"),
        ),
        BrowserSpec(
            "com.sec.android.app.sbrowser",
            listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/sbrowser_url_bar",
                "com.sec.android.app.sbrowser:id/url_bar",
            ),
        ),
        BrowserSpec(
            "org.mozilla.firefox",
            listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title",
            ),
        ),
        BrowserSpec("com.microsoft.emmx", listOf("com.microsoft.emmx:id/url_bar")),
        BrowserSpec("com.brave.browser", listOf("com.brave.browser:id/url_bar")),
        BrowserSpec("com.duckduckgo.mobile.android", listOf("com.duckduckgo.mobile.android:id/omnibarTextInput")),
        BrowserSpec("com.opera.browser", listOf("com.opera.browser:id/url_field")),
        BrowserSpec("com.opera.mini.native", listOf("com.opera.mini.native:id/url_field")),
        BrowserSpec("com.vivaldi.browser", listOf("com.vivaldi.browser:id/url_bar")),
        BrowserSpec("com.kiwibrowser.browser", listOf("com.kiwibrowser.browser:id/url_bar")),
        BrowserSpec("com.chrome.beta", listOf("com.chrome.beta:id/url_bar")),
        BrowserSpec("com.chrome.dev", listOf("com.chrome.dev:id/url_bar")),
        BrowserSpec("com.chrome.canary", listOf("com.chrome.canary:id/url_bar")),
    )

    private val byPackage = specs.associateBy { it.packageName }

    val packages: Set<String> = byPackage.keys

    fun specFor(packageName: String): BrowserSpec? = byPackage[packageName]
}
