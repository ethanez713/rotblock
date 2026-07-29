package com.ezhou.rotblock.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ezhou.rotblock.block.FeedRegistry
import com.ezhou.rotblock.browser.WebMatcher
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.policy.TargetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The logo shown beside a rule.
 *
 * Everything here is answered from the device. The app holds no `INTERNET` permission and is not
 * getting one for decoration -- fetching a favicon would tell a third party which sites you have
 * asked to be kept off, which is the single most sensitive thing this app knows. So:
 *
 *  - an app or feed rule uses the installed app's own launcher icon;
 *  - a website rule looks for an installed app that *declares* that host (`ACTION_VIEW` on
 *    `https://host/`), which covers the cases that matter -- reddit.com finds the Reddit app,
 *    youtube.com/shorts finds YouTube -- and costs one PackageManager query;
 *  - anything left over falls back to a monogram tile, which is not a placeholder for a logo we
 *    failed to fetch. It is the answer.
 *
 * Browsers are filtered out of that second lookup: they answer for every host, so letting one
 * through would put Chrome's logo on every website rule.
 */
object TargetIcons {

    private const val ICON_PX = 128

    /**
     * A host in a TLD reserved by RFC 2606 to never exist. Whatever answers for it answers for
     * anything, which is exactly the definition of "tells you nothing about this rule". Nothing is
     * ever fetched -- this is a query against locally installed intent filters.
     */
    private const val PROBE_HOST = "rotblock-no-such-host.invalid"

    /** Keyed by target id. Icons do not change while the process lives, and the list re-reads them
     *  once a second. */
    private val cache = ConcurrentHashMap<String, Cached>()

    /** ConcurrentHashMap cannot store a null, and "no icon exists" is a result worth remembering. */
    private class Cached(val icon: ImageBitmap?)

    @Volatile
    private var genericHandlers: Set<String>? = null

    suspend fun load(context: Context, kind: TargetKind, matcher: String): ImageBitmap? {
        val key = Policy.targetId(kind, matcher)
        cache[key]?.let { return it.icon }
        val loaded = withContext(Dispatchers.IO) {
            Cached(runCatching { resolve(context, kind, matcher) }.getOrNull())
        }
        cache[key] = loaded
        return loaded.icon
    }

    private fun resolve(context: Context, kind: TargetKind, matcher: String): ImageBitmap? {
        val pm = context.packageManager
        val packageName = when (kind) {
            TargetKind.APP -> matcher
            TargetKind.FEED -> FeedRegistry.specs.firstOrNull { it.key == matcher }?.packageName
            TargetKind.WEBSITE -> hostOf(matcher)?.let { appDeclaring(pm, it, context.packageName) }
        } ?: return null
        return runCatching {
            pm.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }.getOrNull()
    }

    /** The host part of a website rule, or null for a `kw:` rule, which is not about one site. */
    fun hostOf(matcher: String): String? {
        val rule = WebMatcher.canonicalRule(matcher)
        if (rule.startsWith("kw:")) return null
        val host = rule.substringBefore('/')
        return host.takeIf { it.contains('.') && !it.contains(' ') }
    }

    private fun appDeclaring(pm: PackageManager, host: String, self: String): String? {
        val generic = genericHandlers ?: handlersFor(pm, PROBE_HOST).toSet().also {
            genericHandlers = it
        }
        return handlersFor(pm, host).firstOrNull { it != self && it !in generic }
    }

    private fun handlersFor(pm: PackageManager, host: String): List<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://$host/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
    }
}

/**
 * The rule's logo, or a monogram when the device has nothing to show.
 *
 * [dimmed] is for a rule that is switched off: the card greys its text the same way, and an icon
 * left at full strength is the one thing on a disabled card still shouting.
 */
@Composable
fun TargetAvatar(
    kind: TargetKind,
    matcher: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    dimmed: Boolean = false,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, kind, matcher) {
        value = TargetIcons.load(context, kind, matcher)
    }
    val shape = RoundedCornerShape(size * 0.26f)
    val box = modifier.size(size).alpha(if (dimmed) 0.4f else 1f)

    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = box.clip(shape))
        return
    }

    // One tint for every monogram rather than a colour per host: the chart palette's slot order is
    // its colourblind-safety mechanism and must not be dealt out by hash, and a letter is already
    // the thing telling these apart.
    Box(
        modifier = box.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialFor(kind, matcher, label),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
fun TargetAvatar(policy: Policy, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    TargetAvatar(
        kind = policy.kind,
        matcher = policy.matcher,
        label = policy.label,
        modifier = modifier,
        size = size,
        dimmed = !policy.enabled,
    )
}

/** The site's own initial, not the label's: a rule labelled "Doomscrolling" is still reddit.com. */
private fun initialFor(kind: TargetKind, matcher: String, label: String): String {
    val source = when (kind) {
        TargetKind.WEBSITE -> TargetIcons.hostOf(matcher) ?: matcher.removePrefix("kw:")
        else -> label
    }
    return source.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}
