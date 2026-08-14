package com.ezhou.rotblock.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.service.EnforcementStatus
import com.ezhou.rotblock.setup.blocking
import com.ezhou.rotblock.setup.isInstalledFromStore
import com.ezhou.rotblock.setup.hasNotificationPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(viewModel: RotblockViewModel, settings: RotblockSettings) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val policies by viewModel.policies.collectAsStateWithLifecycle()

    // One list, shared with onboarding and the Blocks banner, re-read while you look at it.
    val requirements = rememberRequirements(settings, policies)
    val consent = rememberConsentGate(settings)
    val sideloaded = remember { !isInstalledFromStore(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "Setup",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                requirements.blocking.isNotEmpty() ->
                    "Something below is stopping rules from being enforced."
                // Everything required is fine and something optional is not, which is its own
                // sentence: a screen that says "all good" above a red card is not to be believed.
                requirements.any { !it.isReady } ->
                    "Rules are being enforced. Something optional below is not working."
                else -> "Everything rotblock needs is in place."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            requirements.forEach { requirement ->
                RequirementCard(requirement, onNeedsConsent = consent.onNeedsConsent)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Pause rules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "These shape how hard it is to let something through. None of them can switch " +
                "blocking off permanently — every pause still ends by itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        SliderRow(
            label = "Cooldown before a pause starts",
            value = settings.cooldownSeconds.toFloat(),
            range = 0f..120f,
            steps = 11,
            format = { if (it.toInt() == 0) "off" else "${it.toInt()}s" },
            onChange = { scope.launch { Rotblock.settings(context).setCooldownSeconds(it.toInt()) } },
        )
        SliderRow(
            label = "Pauses allowed per day",
            value = settings.maxPausesPerDay.toFloat(),
            range = 0f..10f,
            steps = 9,
            format = { if (it.toInt() == 0) "unlimited" else "${it.toInt()}" },
            onChange = { scope.launch { Rotblock.settings(context).setMaxPausesPerDay(it.toInt()) } },
        )
        SliderRow(
            label = "Pause minutes per day",
            value = settings.maxPauseMinutesPerDay.toFloat(),
            range = 0f..240f,
            steps = 15,
            format = { if (it.toInt() == 0) "unlimited" else "${it.toInt()} min" },
            onChange = {
                scope.launch { Rotblock.settings(context).setMaxPauseMinutesPerDay(it.toInt()) }
            },
        )
        SliderRow(
            label = "Day resets at",
            value = settings.dayStartHour.toFloat(),
            range = 0f..12f,
            steps = 11,
            format = { "${it.toInt()}:00" },
            onChange = { scope.launch { Rotblock.settings(context).setDayStartHour(it.toInt()) } },
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "Loosening a rule",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "How long an edit waits before it takes effect, when that edit makes a rule weaker — " +
                "raising a limit, turning a block off, deleting it. Tightening a rule is never " +
                "delayed. Set it to 0 to turn the wait off entirely.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        SliderRow(
            label = "Wait before it applies",
            value = settings.weakeningDelayMinutes.toFloat(),
            range = 0f..240f,
            steps = 15,
            format = {
                when (val m = it.toInt()) {
                    0 -> "off"
                    in 1..59 -> "$m min"
                    else -> "${m / 60}h ${m % 60}m".removeSuffix(" 0m")
                }
            },
            onChange = {
                scope.launch { Rotblock.settings(context).setWeakeningDelayMinutes(it.toInt()) }
            },
        )

        Spacer(Modifier.height(24.dp))
        CountdownSection(settings)

        Spacer(Modifier.height(24.dp))
        ExportRow()

        Spacer(Modifier.height(28.dp))
        EnforcementCard()

        // Only for sideloaded builds. "Restricted settings" does not apply to a store install, so
        // on a normal phone this card is a paragraph about a problem that cannot happen, sitting
        // under the one screen where every extra word costs.
        if (sideloaded) {
            Spacer(Modifier.height(28.dp))
            AdbCard(context)
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * The countdown readout: a ring over the app, a notification, or neither.
 *
 * On by default now that the ring can be dragged out of the way instead of only ever accepted or
 * hidden for good. What earns it the space either way is the second thing it does: every failure
 * this app has had presents as "the app is open and nothing happens", and a readout that is
 * *missing* while you sit in a quota'd app says the service is not counting, without opening
 * anything to find out. That is why the transparency slider stops at 10% rather than at nothing --
 * and why a drag-dismiss only lasts the one session rather than turning the ring off the way this
 * toggle does. This switch is the only way to make that permanent.
 */
@Composable
private fun CountdownSection(settings: RotblockSettings) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Asked for here because this is where the user expresses the intent; if they refuse, or
    // revoke it later, the requirement list above says so and offers the prompt again. This
    // screen does not need to track the answer.
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Text(
        "While you are using something",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "A countdown of what is left of an allowance, shown only while that allowance is actually " +
            "being spent — if it is not there, nothing is being counted. Drag the ring anywhere, " +
            "or flick it onto the X at the bottom to get it out from under whatever you are " +
            "reading; that only puts it away for now, and it comes back the next time something " +
            "is being counted. Turning it off below is the one way to make that stick.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    ToggleRow(
        label = "Countdown ring on screen",
        checked = settings.hudEnabled,
        onChange = { scope.launch { Rotblock.settings(context).setHudEnabled(it) } },
    )
    if (settings.hudEnabled) {
        SliderRow(
            label = "How solid it looks",
            value = settings.hudOpacityPercent.toFloat(),
            range = 10f..100f,
            steps = 8,
            format = { "${it.toInt()}%" },
            onChange = {
                scope.launch { Rotblock.settings(context).setHudOpacityPercent(it.toInt()) }
            },
        )
    }

    ToggleRow(
        label = "Countdown as a notification",
        checked = settings.hudNotification,
        onChange = { on ->
            if (on && !hasNotificationPermission(context)) {
                requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            scope.launch { Rotblock.settings(context).setHudNotification(on) }
        },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * What enforcement believes right now, and the last few things it decided.
 *
 * Here because every failure this app has had presents identically: the app is on screen and
 * nothing happens. Whether that is a dropped target, an unreadable address bar, a pause still
 * running, or a service that is not bound at all is invisible from the outside, and guessing at it
 * from the block screen's absence is how an afternoon disappears.
 */
@Composable
private fun EnforcementCard() {
    var snapshot by remember { mutableStateOf(EnforcementStatus.snapshot()) }
    var expanded by remember { mutableStateOf(false) }

    // Only while this screen is composed, so nothing polls in the background.
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = EnforcementStatus.snapshot()
            delay(1_000)
        }
    }

    Text("Right now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "What the service currently thinks is on screen. If something is not being counted, this " +
            "is where it shows.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            StatusRow("Service", if (snapshot.connected) "running" else "not connected")
            StatusRow("Foreground app", snapshot.currentPackage ?: "—")
            StatusRow("Enforcing", snapshot.currentTargetId ?: "nothing")
            StatusRow(
                "Counting time against",
                snapshot.accruingTargetId
                    ?: if (snapshot.interstitialShowing) "nothing (our screen is up)" else "nothing",
            )
            StatusRow(
                "Last screen read",
                snapshot.lastRead?.let { read ->
                    val age = (System.currentTimeMillis() - snapshot.lastReadAtMs) / 1000
                    "$read (${age}s ago)"
                } ?: "none yet",
            )

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide what happened" else "Show what happened")
            }

            if (expanded) {
                SelectionContainer {
                    Column {
                        if (snapshot.trail.isEmpty()) {
                            Text(
                                "Nothing yet. Open a blocked app and come back.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        snapshot.trail.forEach { entry ->
                            Text(
                                buildString {
                                    append(clockTime(entry.atMs))
                                    append("  ")
                                    append(entry.text)
                                    if (entry.repeats > 1) append("  ×${entry.repeats}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun clockTime(atMs: Long): String {
    val local = java.time.Instant.ofEpochMilli(atMs).atZone(java.time.ZoneId.systemDefault())
    return "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                format(local),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun AdbCard(context: Context) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("If Android hides the accessibility toggle", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Android 13+ puts accessibility behind “Restricted settings” for sideloaded apps. " +
                    "Run this over ADB once and the toggle becomes available:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    "adb shell appops set ${context.packageName} " +
                        "ACCESS_RESTRICTED_SETTINGS allow\n\n" +
                        "# optional, enables the greyscale option:\n" +
                        "adb shell pm grant ${context.packageName} " +
                        "android.permission.WRITE_SECURE_SETTINGS",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "On a Galaxy, also set Battery to Unrestricted and take rotblock out of " +
                    "“Deep sleeping apps”, or One UI will kill the service.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
