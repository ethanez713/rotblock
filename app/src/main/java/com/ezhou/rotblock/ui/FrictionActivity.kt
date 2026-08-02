package com.ezhou.rotblock.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ezhou.rotblock.block.Interstitial
import com.ezhou.rotblock.browser.escapeIntent
import kotlinx.coroutines.delay

/**
 * A few seconds of nothing before a rot app opens.
 *
 * Not a blocker -- it always lets you through. It exists to put a gap between the reflex and the
 * feed, which is usually enough to notice you did not actually mean to open it.
 */
class FrictionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_SECONDS = "seconds"

        /** Set only when the countdown covers a *website* block: the browser showing it. */
        const val EXTRA_BROWSER = "browser"
    }

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
        visit++
    }

    override fun onStop() {
        super.onStop()
        Interstitial.exited()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "this"
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 5).coerceIn(1, 120)
        val browser = intent.getStringExtra(EXTRA_BROWSER)

        setContent {
            RotblockTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    FrictionScreen(
                        label = label,
                        seconds = seconds,
                        visit = visit,
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
        finish()
    }
}

@Composable
private fun FrictionScreen(
    label: String,
    seconds: Int,
    /** Changes whenever the screen is re-entered, which starts the wait over. */
    visit: Int,
    onContinue: () -> Unit,
    onLeave: () -> Unit,
) {
    var left by remember(visit) { mutableIntStateOf(seconds) }

    LaunchedEffect(visit) {
        left = seconds
        while (left > 0) {
            delay(1_000)
            left -= 1
        }
    }

    val progress by animateFloatAsState(
        targetValue = 1f - left.toFloat() / seconds.toFloat(),
        label = "friction",
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(120.dp),
                strokeWidth = 5.dp,
            )
            Text(
                if (left > 0) "$left" else "→",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Light,
            )
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Opening $label",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Was there something you actually wanted here?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(36.dp))
        Button(
            onClick = onContinue,
            enabled = left <= 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (left > 0) "Wait…" else "Go ahead") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
            Text("Not really — take me back")
        }
    }
}
