package com.ezhou.rotblock.service

/**
 * What was on screen last time you were inside an app that has to be read to be understood.
 *
 * Switching apps clears the target, because the app you switched *to* is a different question.
 * Coming back then asks the question again -- and inside a browser the answer usually cannot be
 * read: you left the page scrolled, so the address bar is hidden, so the read is `Unreadable`, so
 * the target stays whatever the app switch left it as. Which is nothing.
 *
 * That is a fail-open with no visible symptom at all. Reddit is on screen, the quota is not
 * filling, no block is coming, and nothing anywhere says so -- the app simply stops noticing the
 * site for as long as you keep scrolling. Every trip out of the browser and back re-opened it:
 * the pause screen, the home screen, a notification, a glance at another app.
 *
 * So the last target established *by a read* is remembered per package and restored on re-entry.
 * Restoring can only be wrong for about a second: the app resumes, emits content changes, and the
 * next successful read either confirms it or replaces it. Wrong for a second in the direction of
 * blocking is the trade this app makes everywhere else, and it is the same reasoning as
 * `seedForeground` restoring a visit across a service rebind.
 *
 * The memory is only ever written by a read, never by a restore, so it cannot bootstrap itself
 * into holding a target no read ever established.
 */
class VisitMemory {

    private val byPackage = HashMap<String, String>()

    /** A read established [targetId] inside [pkg]. */
    fun remember(pkg: String, targetId: String) {
        // Bounded, though in practice this only ever holds browsers and apps with feeds.
        if (byPackage.size > 32 && !byPackage.containsKey(pkg)) byPackage.clear()
        byPackage[pkg] = targetId
    }

    /**
     * A read proved [pkg] is showing nothing a policy covers.
     *
     * Only a *successful* read may call this. Forgetting on an unreadable one would throw away
     * the memory in exactly the case it exists for.
     */
    fun forget(pkg: String) {
        byPackage.remove(pkg)
    }

    /** What to enforce on re-entry to [pkg], until a read says otherwise. */
    fun resume(pkg: String): String? = byPackage[pkg]
}
