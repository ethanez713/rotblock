package com.ezhou.rotblock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.pause.BudgetStatus
import com.ezhou.rotblock.pause.PauseResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the emergency button must be held before it fires. */
private const val HOLD_MS = 3_000L

private val DURATIONS = listOf(5, 15, 30, 60)

/**
 * The "I actually need this" flow.
 *
 * Deliberately shaped so that the easy path is the slow one and the fast path is the loud one.
 * The cooldown exists to outlast an impulse; the emergency skip exists so that a genuine need is
 * never a dead end. Neither can leave blocking off indefinitely -- both end in a deadline.
 */
class PauseRequestActivity : ComponentActivity() {

    private val viewModel: RotblockViewModel by viewModels()

    companion object {
        const val EXTRA_TARGET_LABEL = "target_label"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra(EXTRA_TARGET_LABEL)
        viewModel.refresh()
        setContent {
            RotblockTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PauseRequestScreen(
                        viewModel = viewModel,
                        targetLabel = label,
                        onDone = { finish() },
                    )
                }
            }
        }
    }
}

private enum class Step { Choose, Cooling, Emergency }

@Composable
private fun PauseRequestScreen(
    viewModel: RotblockViewModel,
    targetLabel: String?,
    onDone: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(Step.Choose) }
    var minutes by remember { mutableIntStateOf(15) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf<BudgetStatus?>(null) }

    fun commit(emergency: Boolean, reason: String?) {
        scope.launch {
            when (val result = viewModel.startPause(minutes, emergency, reason)) {
                is PauseResult.Started -> onDone()
                is PauseResult.Denied -> {
                    denied = result.status
                    step = Step.Emergency
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "rotblock",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        when (step) {
            Step.Choose -> ChooseStep(
                targetLabel = targetLabel,
                budget = budget,
                minutes = minutes,
                onMinutes = { minutes = it },
                cooldownSeconds = settings.cooldownSeconds,
                onStart = {
                    if (settings.cooldownSeconds <= 0) {
                        commit(emergency = false, reason = null)
                    } else {
                        secondsLeft = settings.cooldownSeconds
                        Rotblock.audit(viewModel.getApplication()).record(
                            AuditType.COOLDOWN_STARTED,
                            detail = "Waiting ${settings.cooldownSeconds}s before a $minutes min pause",
                            durationSeconds = settings.cooldownSeconds,
                        )
                        step = Step.Cooling
                    }
                },
                onCancel = onDone,
            )

            Step.Cooling -> CoolingStep(
                minutes = minutes,
                totalSeconds = settings.cooldownSeconds,
                secondsLeft = secondsLeft,
                onTick = { secondsLeft = it },
                onFinished = { commit(emergency = false, reason = null) },
                onEmergency = { step = Step.Emergency },
                onAbandon = {
                    Rotblock.audit(viewModel.getApplication()).record(
                        AuditType.COOLDOWN_ABANDONED,
                        detail = "Backed out during the cooldown",
                    )
                    onDone()
                },
            )

            Step.Emergency -> EmergencyStep(
                minutes = minutes,
                deniedStatus = denied,
                onConfirm = { reason -> commit(emergency = true, reason = reason) },
                onCancel = onDone,
            )
        }
    }
}

@Composable
private fun ChooseStep(
    targetLabel: String?,
    budget: BudgetStatus?,
    minutes: Int,
    onMinutes: (Int) -> Unit,
    cooldownSeconds: Int,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        if (targetLabel != null) "Pause blocking for $targetLabel?" else "Pause blocking?",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Everything unblocks for a set time, then blocking comes back on its own. You do not " +
            "have to remember to turn it back on.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DURATIONS.forEach { d ->
            FilterChip(
                selected = minutes == d,
                onClick = { onMinutes(d) },
                label = { Text("$d min") },
            )
        }
    }

    if (budget != null) {
        Spacer(Modifier.height(16.dp))
        Text(
            budget.summary(),
            style = MaterialTheme.typography.bodySmall,
            color = if (budget.exhausted) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }

    Spacer(Modifier.height(28.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text(if (cooldownSeconds > 0) "Wait ${cooldownSeconds}s, then pause" else "Pause now")
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Never mind") }
}

@Composable
private fun CoolingStep(
    minutes: Int,
    totalSeconds: Int,
    secondsLeft: Int,
    onTick: (Int) -> Unit,
    onFinished: () -> Unit,
    onEmergency: () -> Unit,
    onAbandon: () -> Unit,
) {
    LaunchedEffect(Unit) {
        var left = secondsLeft
        while (left > 0) {
            delay(1_000)
            left -= 1
            onTick(left)
        }
        onFinished()
    }

    val progress by animateFloatAsState(
        targetValue = if (totalSeconds > 0) 1f - secondsLeft.toFloat() / totalSeconds else 1f,
        label = "cooldown",
    )

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(140.dp),
                    strokeWidth = 6.dp,
                )
                Text(
                    "$secondsLeft",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Light,
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Pausing for $minutes min once this finishes.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "If the urge passes before then, just leave.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    Spacer(Modifier.height(36.dp))
    TextButton(onClick = onAbandon, modifier = Modifier.fillMaxWidth()) {
        Text("I changed my mind")
    }
    TextButton(onClick = onEmergency, modifier = Modifier.fillMaxWidth()) {
        Text("Skip the wait — I need this now", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun EmergencyStep(
    minutes: Int,
    deniedStatus: BudgetStatus?,
    onConfirm: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    var holding by remember { mutableStateOf(false) }
    var heldFraction by remember { mutableStateOf(0f) }

    LaunchedEffect(holding) {
        if (!holding) {
            heldFraction = 0f
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (holding) {
            val elapsed = System.currentTimeMillis() - start
            heldFraction = (elapsed.toFloat() / HOLD_MS).coerceIn(0f, 1f)
            if (heldFraction >= 1f) {
                holding = false
                onConfirm(reason.ifBlank { null })
                return@LaunchedEffect
            }
            delay(16)
        }
    }

    Text(
        "Emergency skip",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (deniedStatus?.exhausted == true) {
            "You are out of pause budget for today. This will still go through — it is just " +
                "recorded as over budget."
        } else {
            "This skips the wait. It goes in the log with its own marker so you can see later " +
                "how often you reached for it."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = reason,
        onValueChange = { reason = it.take(120) },
        label = { Text("What do you need it for?") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(24.dp))
    HoldButton(
        label = "Hold to pause $minutes min",
        fraction = heldFraction,
        onPressChanged = { holding = it },
    )
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Never mind") }
}

/**
 * A press-and-hold confirm. Three seconds of deliberate contact is short enough to never be a
 * real obstacle and long enough that it cannot happen by reflex.
 */
@Composable
private fun HoldButton(label: String, fraction: Float, onPressChanged: (Boolean) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressChanged(true)
                        tryAwaitRelease()
                        onPressChanged(false)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(56.dp)
                .background(MaterialTheme.colorScheme.error),
        )
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private fun BudgetStatus.summary(): String {
    val bits = buildList {
        if (maxPauses > 0) add("$pausesUsed of $maxPauses pauses used today")
        if (maxMinutes > 0) add("$minutesUsed of $maxMinutes min used")
    }
    if (bits.isEmpty()) return "No daily limit on pauses."
    return bits.joinToString(" · ") + if (exhausted) " — budget spent" else ""
}
