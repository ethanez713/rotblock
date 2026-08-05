package com.ezhou.rotblock.browser

/**
 * Decides which of the nodes carrying an address-bar view id is actually the address bar.
 *
 * The question looks silly until you meet a screen with several of them. `findAccessibilityNodeInfosByViewId`
 * searches the whole tree, and browsers reuse their URL view ids: a tab switcher labels every card
 * with the URL of that tab, and the suggestion drop-down labels every row with the URL it would
 * take you to. Taking the first node with text -- which is what this used to do -- reads one of
 * those as "the page you are on", and blocks the tab list of a browser because one of the *other*
 * tabs is on a site with a rule. Observed on Samsung Internet.
 *
 * The distinction is the same one [SearchEngines] exists for, arriving through a different door: a
 * screen offering you a choice of URLs is not a page, and nothing on it says where you are. So the
 * rule is that an address bar is *one* bar. Several URLs on offer and none standing out is a
 * [Chooser], which is not a read at all -- it holds whatever was being enforced rather than
 * acquiring or clearing anything, exactly like a bar that was not in the tree.
 *
 * Visibility only breaks ties, and deliberately does not filter on its own: a browser that keeps
 * the toolbar in the tree while it is scrolled off screen still has the current page's URL sitting
 * in it, and that is a good read. Dropping it would lose a target rather than gain one, which is
 * the direction nothing here is allowed to fail in.
 */
object AddressBar {

    /** One node found under an address-bar view id. */
    data class Candidate(val text: String?, val visible: Boolean)

    /** What the nodes under one view id amount to. */
    sealed interface Read {
        /** Exactly one of them offered text. */
        data class Text(val value: String) : Read

        /** None of them did. The bar is not in the tree, or is empty. */
        data object Absent : Read

        /** Several did, and none stood out: a tab list or a suggestion drop-down. */
        data class Chooser(val offered: Int) : Read
    }

    /**
     * Reads [candidates], all found under a single view id.
     *
     * `contentDescription` stands in for missing `text` because some browsers label the bar rather
     * than filling it, which is how it has always worked here.
     */
    fun read(candidates: List<Candidate>): Read {
        val withText = candidates.filter { !it.text.isNullOrBlank() }
        return when {
            withText.isEmpty() -> Read.Absent
            withText.size == 1 -> Read.Text(withText.first().text!!)
            // Several. The real bar is on screen and the rest of a chooser's rows may be too, so
            // this rescues only the case where exactly one of them is visible -- a toolbar showing
            // through above a list of something else.
            else -> withText.filter { it.visible }
                .singleOrNull()
                ?.let { Read.Text(it.text!!) }
                ?: Read.Chooser(withText.size)
        }
    }
}
