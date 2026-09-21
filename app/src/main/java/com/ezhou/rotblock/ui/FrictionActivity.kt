package com.ezhou.rotblock.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ezhou.rotblock.block.FrictionGate
import com.ezhou.rotblock.block.Interstitial
import com.ezhou.rotblock.browser.escapeIntent
import com.ezhou.rotblock.insights.DiscouragingStatsResult
import kotlinx.coroutines.delay

/**
 * A few seconds of nothing before a rot app opens.
 *
 * Not a blocker -- it always lets you through. It exists to put a gap between the reflex and the
 * feed, which is usually enough to notice you did not actually mean to open it.
 */
class FrictionActivity : ComponentActivity() {

    private val viewModel: RotblockViewModel by viewModels()

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_SECONDS = "seconds"

        /**
         * The longest countdown this screen will serve. The rule editor offers the same ceiling,
         * because a rule allowed to ask for more would be silently clamped here and the block
         * screen would then promise a wait it never makes you serve.
         */
        const val MAX_SECONDS = 120

        /** The target this countdown covers, reported back to the service. See [FrictionGate]. */
        const val EXTRA_TARGET = "target"

        /** Set only when the countdown covers a *website* block: the browser showing it. */
        const val EXTRA_BROWSER = "browser"
    }

    /**
     * What this countdown is covering, kept as state rather than read once in `onCreate`.
     *
     * The activity is `singleTask`, so a second launch reaches a live instance through
     * `onNewIntent` and `getIntent()` goes on returning the *first* one's extras. Cover a different
     * target that way and the screen would name the app you were in ten minutes ago, and -- since
     * serving the wait now names its target -- would credit the wait to it too, leaving the target
     * actually on screen to charge you the countdown a second time.
     */
    private var covering by mutableStateOf(Covering())

    private data class Covering(
        val targetId: String? = null,
        val label: String = "this",
        val seconds: Int = 5,
        val browser: String? = null,
    )

    private fun covering(intent: Intent) = Covering(
        targetId = intent.getStringExtra(EXTRA_TARGET),
        label = intent.getStringExtra(EXTRA_LABEL) ?: "this",
        seconds = intent.getIntExtra(EXTRA_SECONDS, 5).coerceIn(1, MAX_SECONDS),
        browser = intent.getStringExtra(EXTRA_BROWSER),
    )

    /**
     * Bumped every time this screen comes back to the front. The countdown restarts on it.
     *
     * The wait has to be *spent on the wait*, and nothing about a coroutine in a stopped activity
     * says so: start the five seconds, switch away, answer a message, come back, and the countdown
     * ran the whole time in the background and is already at zero. The gap between the reflex and
     * the feed is the entire product here, and that path bought it for free.
     *
     * Restarting rather than pausing, and on any absence at all rather than a long one: coming back
     * to a countdown frozen at 2 is a worse thing to have built than coming back to a fresh 5, and
     * the whole cost of being wrong is a few more seconds of not opening something you did not mean
     * to open.
     */
    private var visit by mutableIntStateOf(0)

    /**
     * Waiting out the friction must not spend the quota of the app underneath -- friction costs
     * time, not allowance. See [Interstitial].
     */
    override fun onStart() {
        super.onStart()
        Interstitial.entered()
        // The service cannot see whether its launch produced anything, and it has to know: a
        // launch that goes nowhere must not be retried on every redraw. See [FrictionGate].
        covering.targetId?.let { FrictionGate.onShown?.invoke(it) }
        visit++
    }

    override fun onStop() {
        super.onStop()
        Interstitial.exited()
        covering.targetId?.let { FrictionGate.onHidden?.invoke(it) }
    }

    /** A relaunch onto a live instance. Re-entry restarts the wait; [onStart] follows. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent) {
        covering = covering(intent)
        viewModel.loadDiscouragingStatsFor(covering.targetId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyIntent(intent)

        // Back is the bypass this screen was built without: it finishes the activity, the app
        // underneath resumes, and no foreground change fires to say so because our own package is
        // excluded from tracking. The service now re-shows the countdown when the wait was not
        // served, so pressing back would only flash the app for as long as it takes to notice --
        // and on a website that flash is the live page. So back means what the text button means:
        // you did not want this, and the browser is moved off the page rather than left on it.
        onBackPressedDispatcher.addCallback(this) { leave(covering.browser) }

        setContent {
            InterstitialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val (target, label, seconds, browser) = covering
                    val stats by viewModel.discouragingStats.collectAsStateWithLifecycle()
                    FrictionScreen(
                        label = label,
                        seconds = seconds,
                        visit = visit,
                        stats = stats,
                        onServed = { target?.let { FrictionGate.onServed?.invoke(it) } },
                        onContinue = { finish() },
                        onLeave = { leave(browser) },
                    )
                }
            }
        }
    }

    /**
     * Backs out of the countdown without opening the target.
     *
     * Going home is the whole answer for an app: it is behind you. For a website it is not --
     * the browser stays on the page, so reopening it lands on the site with the countdown already
     * spent for this visit. So the browser is moved off the page first, and that becomes where it
     * comes back to. Declining the countdown has to actually decline it.
     */
    private fun leave(browser: String?) {
        val moved = browser != null && runCatching { startActivity(escapeIntent(browser)) }.isSuccess
        if (!moved) {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        // Reached from both the text button and the back-press callback, so both count as declining
        // rather than waiting it out. Told which target this covered, the service can stop treating
        // the page it just moved away from as merely unconfirmed and treat it as stale instead --
        // otherwise the very next re-entry would resume the site this decline just left.
        covering.targetId?.let { FrictionGate.onDeclined?.invoke(it) }
        finish()
    }
}

@Composable
private fun FrictionScreen(
    label: String,
    seconds: Int,
    /** Changes whenever the screen is re-entered, which starts the wait over. */
    visit: Int,
    stats: DiscouragingStatsResult?,
    /** The wait was actually sat through. The only thing that spends it; see [FrictionGate]. */
    onServed: () -> Unit,
    onContinue: () -> Unit,
    onLeave: () -> Unit,
) {
    var left by remember(visit, seconds) { mutableIntStateOf(seconds) }

    LaunchedEffect(visit, seconds) {
        left = seconds
        while (left > 0) {
            delay(1_000)
            left -= 1
        }
        // Zero, not the tap: waiting it out and then backing out is still waiting it out. What must
        // not count is a countdown that was merely put on screen.
        onServed()
    }

    val progress by animateFloatAsState(
        targetValue = 1f - left.toFloat() / seconds.toFloat(),
        label = "friction",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(128.dp),
                strokeWidth = 5.5.dp,
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                if (left > 0) "$left" else "→",
                style = MaterialTheme.typography.displaySmall,
                fontFamily = InterstitialMono,
                fontWeight = FontWeight.Normal,
            )
        }

        Spacer(Modifier.height(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        val hist = stats?.takeIf { it.hasHistory }
        if (hist != null) {
            Spacer(Modifier.height(6.dp))
            UsageGlance(
                stats = hist,
                targetLabel = label,
                dense = false,
                onDark = isSystemInDarkTheme(),
            )
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onContinue,
            enabled = left <= 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (left > 0) "Wait…" else "Go ahead") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
            Text("Take me back")
        }
    }
}
