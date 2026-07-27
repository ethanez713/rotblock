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
    }

    /**
     * Waiting out the friction must not spend the quota of the app underneath -- friction costs
     * time, not allowance. See [Interstitial].
     */
    override fun onStart() {
        super.onStart()
        Interstitial.entered()
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

        setContent {
            RotblockTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    FrictionScreen(
                        label = label,
                        seconds = seconds,
                        onContinue = { finish() },
                        onLeave = {
                            startActivity(
                                Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FrictionScreen(
    label: String,
    seconds: Int,
    onContinue: () -> Unit,
    onLeave: () -> Unit,
) {
    var left by remember { mutableIntStateOf(seconds) }

    LaunchedEffect(Unit) {
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
