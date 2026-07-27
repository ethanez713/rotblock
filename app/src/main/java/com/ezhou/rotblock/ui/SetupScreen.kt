package com.ezhou.rotblock.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.block.Grayscale
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.service.RotblockAccessibilityService
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(viewModel: RotblockViewModel, settings: RotblockSettings) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Re-read the permission state every time this screen is shown, since granting happens in
    // Settings and we come back to a still-composed screen.
    var refreshKey by remember { mutableStateOf(0) }
    val accessibilityOn = remember(refreshKey) { isAccessibilityEnabled(context) }
    val overlayOn = remember(refreshKey) { Settings.canDrawOverlays(context) }
    val grayscaleOn = remember(refreshKey) { Grayscale(context).isAvailable }

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
        Spacer(Modifier.height(16.dp))

        PermissionRow(
            granted = accessibilityOn,
            title = "Accessibility service",
            body = "Lets rotblock see which app is in front. Without this nothing is enforced.",
            action = "Open settings",
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        PermissionRow(
            granted = overlayOn,
            title = "Display over other apps",
            body = "Draws the block screen. Without it, blocked apps just get closed instead.",
            action = "Open settings",
            onAction = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )

        Spacer(Modifier.height(10.dp))
        PermissionRow(
            granted = grayscaleOn,
            title = "Write secure settings (optional)",
            body = "Only needed for the greyscale option. Grant it over ADB — see below.",
            action = "",
            onAction = {},
            optional = true,
        )

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { refreshKey++ }) { Text("Re-check permissions") }

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

        Spacer(Modifier.height(28.dp))
        AdbCard(context)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun PermissionRow(
    granted: Boolean,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    /** Optional permissions show a muted marker rather than an alarming one when missing. */
    optional: Boolean = false,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = when {
                    granted -> MaterialTheme.colorScheme.primary
                    optional -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!granted && action.isNotBlank()) {
                    TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                        Text(action)
                    }
                }
            }
        }
    }
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

/**
 * Reads the enabled-services list rather than tracking our own flag, so the answer stays correct
 * when the service is turned off from outside the app.
 */
private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, RotblockAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
    for (entry in splitter) {
        if (entry.equals(expected, ignoreCase = true)) return true
    }
    return false
}
