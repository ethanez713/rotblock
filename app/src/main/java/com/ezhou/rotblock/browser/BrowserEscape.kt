package com.ezhou.rotblock.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

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

/**
 * Types [url] into [bar] and submits it, the way a person would -- rather than asking the browser
 * to open a URL by intent, which is what [escapeIntent] does and what Samsung Internet rejected
 * outright (an "Invalid address" toast, the page unchanged, `startActivity` still returning
 * normally). Driving the field the browser already trusts sidesteps whatever validation rejected
 * the intent.
 *
 * `ACTION_IME_ENTER` is API 30+; callers gate on that before calling this.
 *
 * Stops at the first action that fails. In particular, a `false` from `ACTION_SET_TEXT` means the
 * field never received the URL, so pressing enter next would submit whatever was in it before.
 */
fun typeEscape(bar: AccessibilityNodeInfo, url: String): Boolean {
    bar.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    bar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    val setText = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url)
    }
    if (!bar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) return false
    val selectAll = Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, url.length)
    }
    bar.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)
    bar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    return true
}
