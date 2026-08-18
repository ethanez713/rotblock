package com.ezhou.rotblock.browser

/**
 * The third and weakest way of answering "where am I", after the address bar and [LinkConsensus].
 *
 * Both of those give up on Samsung Internet's Shorts player: the toolbar is gone from the tree
 * entirely, so there is nothing for the bar to read, and the player is a bare ~37 nodes with only
 * two link nodes -- short of [LinkConsensus.MIN_SAMPLES] by a wide margin, so the link vote never
 * runs either. What is left is the page's own accessibility labelling: the next/previous buttons a
 * screen reader needs to use the player are still there, because hiding them would break the app
 * for someone who relies on TalkBack, not because YouTube left navigation in for us.
 *
 * This is also the only route of the three that can carry a *path*. Samsung Internet's address bar
 * reports host only -- measured, `en.m.wikipedia.org/wiki/X` reads back as `en.wikipedia.org` with
 * no path and no `m.` -- and [LinkConsensus] reports a host by design, never a path, for the reason
 * documented there. So `youtube.com/shorts` cannot be acquired through either signal, and this
 * exists specifically to close that gap.
 *
 * The strings below are YouTube's own web aria-labels, not stable identifiers: they are localized
 * and a reskin can change them overnight. A miss here has to degrade to "not blocked", exactly like
 * a view id in [com.ezhou.rotblock.block.FeedRegistry] going stale -- so matching is by strict
 * equality on trimmed, lowercased text, never a loose `contains`, and every one of a signature's
 * required descriptions has to be present before it says anything at all.
 *
 * Because those strings are somebody else's, a signature does not rest on them alone. It may also
 * require the page's own links to carry a path ([Signature.requiredLinkPath]), which comes off
 * Chromium's `targetUrl` extra and is therefore structural -- a real URL, unaffected by language or
 * a redesign. The two halves are deliberately of different kinds: the labels say *what kind of
 * screen* this is, the links say *what part of the site* it belongs to, and only a page that is
 * both gets a verdict. That pairing is what this class got wrong for its first life -- see the
 * Shorts signature's own comment for the failure it caused.
 *
 * The asymmetry that keeps this safe is the one that runs through this whole package: a signature
 * may only ever *acquire* a target, or *refine* an address bar it agrees with on host -- never
 * contradict one. See [com.ezhou.rotblock.service.classifyInspection] for where that is enforced.
 */
data class Signature(
    val key: String,
    val host: String,
    val path: String,
    /** Every one of these must be present, matched by equality on trimmed, lowercased text. */
    val requiredDescriptions: List<String>,
    /** At least one of these must be present -- as a description, or as the title's suffix. */
    val siteMarkers: List<String>,
    /**
     * A path fragment the page's *own* links must predominantly carry, or null to not consult them.
     *
     * This is the structural half of a signature: real URLs off Chromium's `targetUrl` extra rather
     * than someone's localized aria-label, so it survives a reskin and a translation.
     */
    val requiredLinkPath: String? = null,
)

object PageSignature {

    /**
     * How many of the page's links must be on the signature's own host before their paths may vote,
     * and what share of those must carry [Signature.requiredLinkPath].
     *
     * Measured on device. The Shorts player offers only three links and two of them are `/shorts`
     * (0.67); a normal watch page offers twenty-five and *none* is (0.00). A share rather than a
     * bare "at least one" because a watch page may carry a Shorts shelf, and one shelf link must
     * not be able to call the page a Short -- the same reasoning, and roughly the same threshold, as
     * [LinkConsensus.MIN_AGREEMENT]. A page with fewer than [MIN_LINK_SAMPLES] of its own links says
     * nothing at all rather than letting a single link decide.
     */
    const val MIN_LINK_SAMPLES = 2
    private const val MIN_LINK_SHARE = 0.5

    private val signatures = listOf(
        Signature(
            key = "youtube_shorts_web",
            host = "youtube.com",
            path = "/shorts",
            // Both conditions below are needed, and neither is sufficient, which is the whole
            // repair. This once required "previous video" AND "next video" and got it exactly
            // backwards: a device capture of the real Shorts player has "Next video" and **no**
            // "Previous video", while a watch page grows both the moment you tap it and the
            // controls appear. So it fired on the one page it must not and never matched the one
            // it exists for -- the Shorts rule had, on the evidence, never once blocked a Short.
            //
            // "Next video" alone would still catch a tapped watch page; the link share alone would
            // still catch a home feed scrolled to a Shorts shelf. A player showing a Short is the
            // only screen that is both.
            requiredDescriptions = listOf("next video"),
            // "Search YouTube" is on every YouTube web screen observed; the title suffix is the
            // fallback for whichever build or layout hides it.
            siteMarkers = listOf("search youtube", "- youtube"),
            requiredLinkPath = "/shorts",
        ),
    )

    /** The distinct hosts a signature can imply, so the service knows which bar reads are worth refining. */
    val hosts: Set<String> = signatures.map { it.host }.toSet()

    /**
     * The URL implied by the first signature that matches, or null.
     *
     * [compatibleWith] is the host an address bar already read, or null when the bar read nothing
     * at all. Passing it restricts the search to signatures that only ever *refine* that host --
     * the same host, or a subdomain of it -- so a Shorts player embedded in some unrelated site can
     * never borrow youtube.com's identity from a bar that is not on youtube.com. Leave it null only
     * when there is no bar to agree with; that is the one case a signature is allowed to acquire a
     * target outright rather than merely refine one.
     */
    fun of(
        descriptions: List<String>,
        title: String?,
        linkUrls: List<String> = emptyList(),
        compatibleWith: String? = null,
    ): WebMatcher.ParsedUrl? = matching(descriptions, title, linkUrls, compatibleWith)
        ?.let { WebMatcher.ParsedUrl(host = it.host, path = it.path) }

    /**
     * The key of whichever signature matched, regardless of whether a website rule covers it.
     *
     * Diagnostic only, for [com.ezhou.rotblock.service.EnforcementStatus]: "the Shorts signature
     * matched but nothing blocked" is a different, more useful, answer than seeing nothing at all
     * when a rule appears not to be firing.
     */
    fun keyOf(
        descriptions: List<String>,
        title: String?,
        linkUrls: List<String> = emptyList(),
        compatibleWith: String? = null,
    ): String? = matching(descriptions, title, linkUrls, compatibleWith)?.key

    /**
     * Whether some registered signature's host is [host] itself or a subdomain of it.
     *
     * Used to gate the service's node walk: a signature can only ever refine a bar it agrees with,
     * so there is no point scanning for one on a page whose bar already said something incompatible
     * with every signature this registry knows about.
     */
    fun couldRefine(host: String): Boolean = signatures.any { hostCompatible(host, it.host) }

    private fun matching(
        descriptions: List<String>,
        title: String?,
        linkUrls: List<String>,
        compatibleWith: String?,
    ): Signature? {
        val normalizedDescriptions = descriptions.mapTo(HashSet()) { it.trim().lowercase() }
        val normalizedTitle = title?.trim()?.lowercase()
        return signatures.firstOrNull { sig ->
            (compatibleWith == null || hostCompatible(compatibleWith, sig.host)) &&
                sig.requiredDescriptions.all { it in normalizedDescriptions } &&
                sig.siteMarkers.any { marker ->
                    marker in normalizedDescriptions || normalizedTitle?.endsWith(marker) == true
                } &&
                linksCarryPath(sig, linkUrls)
        }
    }

    /**
     * Whether the page's own links predominantly point at [Signature.requiredLinkPath].
     *
     * Only links on the signature's own host are counted, in either direction: an outbound link
     * cannot dilute the share, and a `/shorts` path on some unrelated site cannot inflate it.
     * Parsing goes through [WebMatcher.parse] so hosts and paths are normalised exactly the way an
     * address bar's text is.
     */
    private fun linksCarryPath(sig: Signature, linkUrls: List<String>): Boolean {
        val required = sig.requiredLinkPath ?: return true
        val own = linkUrls.mapNotNull { WebMatcher.parse(it) }
            .filter { hostCompatible(it.host, sig.host) }
        if (own.size < MIN_LINK_SAMPLES) return false
        return own.count { it.path.contains(required) }.toDouble() / own.size >= MIN_LINK_SHARE
    }

    private fun hostCompatible(barHost: String, sigHost: String): Boolean =
        barHost == sigHost || barHost.endsWith(".$sigHost")
}
