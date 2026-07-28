package com.ezhou.rotblock.browser

import android.content.Intent
import android.net.Uri

/**
 * Sends [browserPackage] to [BrowserRegistry.ESCAPE_URL] -- the one way this app moves a browser
 * off a page.
 *
 * One function rather than an intent built at each call site, because the destination is the part
 * that is easy to get wrong: `about:blank` looks obviously right, and Samsung Internet answers it
 * with an "Invalid address" toast and stays exactly where it was.
 */
fun escapeIntent(browserPackage: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(BrowserRegistry.ESCAPE_URL))
        .setPackage(browserPackage)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
