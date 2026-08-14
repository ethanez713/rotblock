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
data class BrowserSpec(
    val packageName: String,
    val urlBarIds: List<String>,
    /**
     * A view id that, when tapped, brings the address bar back onto screen -- Samsung Internet's
     * toolbar-reveal control, not the bar itself. Null everywhere: acting on a scrolled-away page
     * by synthesising a click is opted into per [RotblockSettings.revealAddressBar] and gated hard
     * by [shouldAttemptReveal], so a browser with no id here just never gets offered the reveal.
     */
    val revealBarId: String? = null,
)

object BrowserRegistry {

    /**
     * Where "Leave this site" sends the browser.
     *
     * Not `about:blank`, which was the obvious choice and was wrong twice over. Samsung Internet
     * validates the URL in an incoming `ACTION_VIEW` and rejects anything that is not http(s):
     * the escape produced an "Invalid address" toast, the tab stayed on the blocked site, and
     * `startActivity` still returned normally, so nothing noticed. And the text `about:blank`
     * sitting in an address bar does not parse as a URL, so a browser that *did* accept it read
     * back as unreadable -- which now means "hold the target", i.e. re-block the page you just
     * left.
     *
     * Loopback satisfies both: every browser accepts it as a URL, it parses back as a host that
     * no sane rule matches, and it never leaves the device -- the connection is refused by this
     * phone, immediately, with no network permission on our side and no request on the wire.
     */
    const val ESCAPE_URL = "http://127.0.0.1"

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
            // TODO: null until the real id is found on device --
            //   adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml
            // on a scrolled-down page, looking for whatever node the toolbar-reveal gesture
            // corresponds to. Samsung Internet is the browser this exists for: it is the one whose
            // toolbar leaves the tree entirely on scroll, per CLAUDE.md's "Fragile by nature".
            revealBarId = null,
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

    /** Floor between two reveal-tap attempts on the same package. See [shouldAttemptReveal]. */
    const val REVEAL_COOLDOWN_MS = 60_000L
}

/**
 * Whether it is worth tapping the browser's toolbar-reveal control to try to get an address bar
 * back onto screen.
 *
 * A synthesised click is a user-visible side effect on a page that may carry no rule at all, so
 * every condition here narrows toward "there is nothing left to lose" rather than "this would be
 * convenient":
 *
 * - [enabled] and [revealId] must both hold -- the setting is opt-in, and a browser with no known
 *   reveal control has nothing to click.
 * - [url] must be null and [chooser] false: a bar that read *something*, even a chooser, is
 *   already the ground truth and a click can only disturb it.
 * - [heldTarget] must be null. This acquires; it never contradicts a target already held.
 * - [matcherCount] must be positive -- no website rule exists to acquire toward.
 * - [attemptedThisVisit] must be false: at most one guess per visit, because the visit is the unit
 *   the countdown and everything else in this app already thinks in, and a click retried on every
 *   scroll event would be a much louder side effect than one per visit.
 * - at least [BrowserRegistry.REVEAL_COOLDOWN_MS] must have passed since [lastAttemptMs] for this
 *   package, so a browser with a reveal control that never actually works cannot be clicked once a
 *   minute forever.
 */
fun shouldAttemptReveal(
    enabled: Boolean,
    revealId: String?,
    url: String?,
    chooser: Boolean,
    heldTarget: String?,
    matcherCount: Int,
    lastAttemptMs: Long,
    attemptedThisVisit: Boolean,
    nowMs: Long,
): Boolean =
    enabled &&
        revealId != null &&
        url == null &&
        !chooser &&
        heldTarget == null &&
        matcherCount > 0 &&
        !attemptedThisVisit &&
        nowMs - lastAttemptMs >= BrowserRegistry.REVEAL_COOLDOWN_MS
