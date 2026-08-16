package com.ezhou.rotblock.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import com.ezhou.rotblock.block.BlockSelfTest
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.data.buildExport
import com.ezhou.rotblock.diag.Diagnostics
import com.ezhou.rotblock.service.EnforcementStatus
import com.ezhou.rotblock.setup.RequirementId
import com.ezhou.rotblock.setup.blocking
import com.ezhou.rotblock.setup.isInstalledFromStore
import com.ezhou.rotblock.setup.hasNotificationPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SetupScreen(viewModel: RotblockViewModel, settings: RotblockSettings) {
    val context = LocalContext.current
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

        Spacer(Modifier.height(20.dp))

        // Everything below is collapsed, and each header carries the setting's current value, so
        // the screen reads as a short list of answers rather than eight essays. The requirement
        // cards above are the exception and stay open: they are the only part of this screen that
        // is ever urgent, and one that has to be opened to be seen is one nobody sees.
        Section("Letting something through", pauseSummary(settings)) {
            LettingThroughControls(settings)
        }
        Section("The countdown readout", countdownSummary(settings)) {
            CountdownControls(settings)
        }
        Section(
            "Finding a scrolled-away page",
            if (settings.revealAddressBar) "On" else "Off",
        ) { RevealControls(settings) }
        Section("Testing the block screen", blockTestSummary(settings)) {
            BlockScreenTestControls(context)
        }
        Section("Keeping a copy", "Write every rule and all history to a file") {
            ExportControls()
        }
        Section("Diagnostic captures", diagnosticsSummary()) {
            DiagnosticsControls()
        }

        Spacer(Modifier.height(28.dp))
        EnforcementCard()

        // Only for a sideloaded build, and only while enforcement is not running. "Restricted
        // settings" is a story about a switch that would not turn on, so once it is on this card
        // is a paragraph about a problem that has already been solved, sitting under the one
        // screen where every extra word costs.
        val enforcementReady = requirements.firstOrNull {
            it.id == RequirementId.ENFORCEMENT
        }?.isReady ?: false
        if (sideloaded && !enforcementReady) {
            Spacer(Modifier.height(28.dp))
            AdbCard(context)
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * One collapsed setting group: what it is, what it is currently set to, and the controls behind a
 * tap.
 *
 * The summary is the point rather than decoration. This screen is read to answer "what is it doing
 * right now", and a header that says "60s wait · 3 a day" answers that without opening anything --
 * which is what lets the explanations stay long enough to be worth reading when they are opened.
 */
@Composable
private fun Section(title: String, summary: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide" else "Show",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(Modifier.padding(bottom = 12.dp)) { content() }
        }
    }
}

private fun pauseSummary(settings: RotblockSettings): String {
    val cooldown = if (settings.cooldownSeconds == 0) "no wait" else "${settings.cooldownSeconds}s wait"
    val perDay = if (settings.maxPausesPerDay == 0) "unlimited" else "${settings.maxPausesPerDay} a day"
    val loosening = when (val m = settings.weakeningDelayMinutes) {
        0 -> "loosening applies at once"
        in 1..59 -> "loosening waits ${m}m"
        else -> "loosening waits ${"${m / 60}h ${m % 60}m".removeSuffix(" 0m")}"
    }
    return "$cooldown · $perDay · $loosening"
}

private fun countdownSummary(settings: RotblockSettings): String = when {
    settings.hudEnabled && settings.hudNotification -> "Ring and notification"
    settings.hudEnabled -> "Ring, ${settings.hudOpacityPercent}% solid"
    settings.hudNotification -> "Notification only"
    // Named rather than left as "Off", because an absent readout is supposed to mean "nothing is
    // being counted" -- and that reading is only honest if it is obvious you switched it off.
    else -> "Off — nothing will show while time is spent"
}

private fun blockTestSummary(settings: RotblockSettings): String = when {
    settings.blockTestAtMs == 0L -> "Never tested"
    settings.blockTestSeen -> "Last test: the block screen appeared"
    else -> "Last test: nothing appeared"
}

private fun diagnosticsSummary(): String {
    val at = Diagnostics.lastCaptureAtMs
    return if (at == 0L) "Never captured" else "Last capture ${clockTime(at)}"
}

/**
 * Every knob that decides how hard it is to let something through, in one place.
 *
 * The pause budget and the weakening delay used to be two sections, and they are the same
 * question asked twice: how much friction sits between wanting past a rule and being past it. A
 * pause buys minutes now, a weakening delay taxes the edit that would buy them permanently, and
 * neither can switch blocking off for good.
 */
@Composable
private fun LettingThroughControls(settings: RotblockSettings) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Text(
        "None of these can switch blocking off permanently — every pause still ends by itself.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

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

    Spacer(Modifier.height(12.dp))
    Text(
        "How long an edit waits before it takes effect, when that edit makes a rule weaker — " +
            "raising a limit, turning a block off, deleting it. Tightening a rule is never " +
            "delayed. Set it to 0 to turn the wait off entirely.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SliderRow(
        label = "Wait before a loosening applies",
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
private fun CountdownControls(settings: RotblockSettings) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Asked for here because this is where the user expresses the intent; if they refuse, or
    // revoke it later, the requirement list above says so and offers the prompt again. This
    // screen does not need to track the answer.
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

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

/**
 * Off by default, unlike everything else this app infers a page from. [LinkConsensus] and
 * [PageSignature] only ever read what a page already shows; this instead taps the browser's own
 * toolbar-reveal control to try to bring a scrolled-away address bar back -- a real, user-visible
 * action on a page that might carry no rule at all, so it is opted into rather than assumed.
 */
@Composable
private fun RevealControls(settings: RotblockSettings) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Text(
        "Some browsers hide the address bar once you scroll, which can leave a site untracked " +
            "until you scroll back up. When this is on, rotblock will occasionally tap your " +
            "browser's toolbar to bring the bar back and check where you are.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        label = "Tap to find the page",
        checked = settings.revealAddressBar,
        onChange = { scope.launch { Rotblock.settings(context).setRevealAddressBar(it) } },
    )
}

private sealed interface BlockTestState {
    data class Idle(val message: String? = null) : BlockTestState
    data object Running : BlockTestState
    data object AwaitingReport : BlockTestState
}

/**
 * Fires the real block screen with a synthetic label and asks whether it actually appeared.
 *
 * `overlayGranted` above only proves rotblock *asked* to draw over other apps -- some OEMs grant
 * that and silently drop the window anyway, which nothing on the phone can query for. This runs
 * the exact path a real block takes (`BlockOverlay`, via [BlockSelfTest]) rather than a mock, and
 * records what the human looking at the screen reports back.
 */
@Composable
private fun BlockScreenTestControls(context: Context) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var state by remember { mutableStateOf<BlockTestState>(BlockTestState.Idle()) }

    // No event marks the overlay coming down -- a tap and the ten-second timeout both just stop
    // it -- so this polls the same way EnforcementCard does, and only while this state is active.
    LaunchedEffect(state) {
        if (state !is BlockTestState.Running) return@LaunchedEffect
        while (BlockSelfTest.isRunning) delay(250)
        state = BlockTestState.AwaitingReport
    }

    Text(
        "Puts the real block screen up for a few seconds, right here on top of this one. That " +
            "checks the service is running and that the window actually paints — some phones " +
            "grant the permission and drop the window anyway. It does not test foreground " +
            "detection or whether a rule would fire.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    when (val current = state) {
        is BlockTestState.Idle -> {
            TextButton(
                onClick = {
                    state = when (BlockSelfTest.request()) {
                        true -> BlockTestState.Running
                        false -> BlockTestState.Idle(
                            "A block is already on screen. Try again once it clears.",
                        )
                        null -> BlockTestState.Idle(
                            "Enforcement is not running, so there is nothing to test.",
                        )
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Test the block screen") }
            current.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        BlockTestState.Running -> Text(
            "Showing it now. Tap “I saw it” on the block screen, or wait for it to go.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BlockTestState.AwaitingReport -> {
            Text("Did it appear?", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    onClick = {
                        scope.launch {
                            Rotblock.settings(context)
                                .setBlockTestResult(System.currentTimeMillis(), seen = true)
                        }
                        state = BlockTestState.Idle()
                    },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("I saw it") }
                TextButton(
                    onClick = {
                        scope.launch {
                            Rotblock.settings(context)
                                .setBlockTestResult(System.currentTimeMillis(), seen = false)
                        }
                        state = BlockTestState.Idle()
                    },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Nothing appeared") }
            }
        }
    }
}

/** A copy of every rule, setting and history row, written wherever the user chooses. */
@Composable
private fun ExportControls() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val json = buildExport(
                    db = Rotblock.database(context),
                    settings = Rotblock.settings(context).current(),
                    nowMs = System.currentTimeMillis(),
                )
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }
        }
    }

    Text(
        "A reinstall or a lost phone loses every rule and all history, and this is the only way " +
            "to keep a copy. There is deliberately no way to read one back in: restoring rules " +
            "would apply any loosening in them instantly, which is the one thing this app exists " +
            "to stop.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = {
            launcher.launch("rotblock-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json")
        },
        contentPadding = PaddingValues(0.dp),
    ) { Text("Export a copy") }
}

/** One row of the diagnostics list: the file plus a summary read out of its own JSON. */
private data class CaptureRow(val file: java.io.File, val atMs: Long, val summary: String)

private fun captureTimeLabel(atMs: Long): String {
    val local = java.time.Instant.ofEpochMilli(atMs).atZone(java.time.ZoneId.systemDefault())
    return "%04d-%02d-%02d %02d:%02d:%02d".format(
        local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute, local.second,
    )
}

/**
 * A capture is read back just far enough to show one honest line per row -- what was in front of
 * you and whether the service was even bound to say -- without parsing the whole file for a list
 * nobody is reading closely yet.
 */
private fun summarize(json: org.json.JSONObject): String {
    val enforcement = json.optJSONObject("enforcement")
    if (enforcement == null || !enforcement.optBoolean("connected", false)) {
        return "service not bound"
    }
    val targetId = enforcement.optString("currentTargetId", "").takeIf { it.isNotBlank() }
    val pkg = enforcement.optString("currentPackage", "").takeIf { it.isNotBlank() }
    return when {
        targetId != null -> "$pkg → $targetId"
        pkg != null -> pkg
        else -> "nothing in front"
    }
}

private fun loadCaptureRows(dir: java.io.File): List<CaptureRow> =
    com.ezhou.rotblock.diag.CaptureStore(dir).list().map { file ->
        val parsed = runCatching { org.json.JSONObject(file.readText()) }
        CaptureRow(
            file = file,
            atMs = parsed.getOrNull()?.optLong("capturedAtMs") ?: 0L,
            summary = parsed.map { summarize(it) }.getOrDefault("unreadable capture"),
        )
    }

/**
 * One capture per tap of the diagnostics Quick Settings tile, kept as plain files rather than
 * anything in the database -- see [com.ezhou.rotblock.diag.CaptureStore]. Export is SAF-only, the
 * same as [ExportControls]: no `ACTION_SEND`, because a capture holds page URLs and on-screen
 * text and this app does not get to decide that leaving the device is fine.
 */
@Composable
private fun DiagnosticsControls() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dir = remember { com.ezhou.rotblock.diag.CaptureStore.directoryFor(context) }
    var rows by remember { mutableStateOf<List<CaptureRow>>(emptyList()) }
    var refreshToken by remember { mutableStateOf(0) }
    var pendingSave by remember { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(refreshToken) {
        rows = withContext(Dispatchers.IO) { loadCaptureRows(dir) }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(file.readBytes()) }
                }
            }
        }
    }

    Text(
        "A capture writes down everything rotblock currently believes about your phone to one " +
            "file: which app or site is in front and why it would or would not be blocked, plus " +
            "the on-screen text and page URLs that led to that answer. That makes a capture the " +
            "most sensitive thing this app holds -- treat one the way you would a screenshot, and " +
            "only save or share it when you mean to.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    SelectionContainer {
        Text(
            "Files stay on this phone, never uploaded, at " +
                "/sdcard/Android/data/com.ezhou.rotblock/files/diagnostics/ -- pull one with a " +
                "computer attached:\nadb pull /sdcard/Android/data/com.ezhou.rotblock/files/diagnostics/",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(10.dp))

    if (rows.isEmpty()) {
        Text(
            "No captures yet. Open Quick Settings and tap the rotblock diagnostics tile.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    rows.forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(captureTimeLabel(row.atMs), style = MaterialTheme.typography.bodyMedium)
                Text(
                    row.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    pendingSave = row.file
                    saveLauncher.launch(row.file.name)
                },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Save a copy") }
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { com.ezhou.rotblock.diag.CaptureStore(dir).deleteAll() }
                refreshToken++
            }
        },
        contentPadding = PaddingValues(0.dp),
    ) { Text("Delete all") }
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
