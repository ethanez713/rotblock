package com.ezhou.rotblock.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.audit.AuditType
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.setup.AccessibilityEnrolment
import com.ezhou.rotblock.setup.EnforcementRevival
import com.ezhou.rotblock.setup.Fix
import com.ezhou.rotblock.setup.Requirement
import com.ezhou.rotblock.setup.RequirementId
import com.ezhou.rotblock.setup.SettingsScreen
import com.ezhou.rotblock.setup.gatherSetupFacts
import com.ezhou.rotblock.setup.requirementsFor
import com.ezhou.rotblock.setup.settingsIntentFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the requirements are re-checked while they are on screen. */
private const val RECHECK_MS = 2_000L

/**
 * The live requirement list, re-read while it is being looked at.
 *
 * Polled rather than event-driven because there is no event: granting a permission happens in
 * Android's settings, in another process, and coming back to a still-composed screen produces
 * nothing to observe. The alternative is a "Re-check" button, which is a way of making the user do
 * the app's job. Two seconds, only while this is composed, and every one of the checks is a
 * property read or a `SharedPreferences` lookup.
 */
@Composable
fun rememberRequirements(
    settings: RotblockSettings,
    policies: List<Policy>,
): List<Requirement> {
    val context = LocalContext.current
    var facts by remember(settings, policies) {
        mutableStateOf(gatherSetupFacts(context, settings, policies))
    }
    LaunchedEffect(settings, policies) {
        while (true) {
            delay(RECHECK_MS)
            facts = gatherSetupFacts(context, settings, policies)
        }
    }
    return remember(facts) { requirementsFor(facts) }
}

/**
 * One requirement, with its consequence and its fix.
 *
 * A satisfied one still shows, quietly. Setup screens that hide what is working turn into a list of
 * complaints, and the thing people most want to know here -- "is it actually watching?" -- is
 * answered by a row that says yes.
 */
@Composable
fun RequirementCard(
    requirement: Requirement,
    /** Called instead of the action when the accessibility disclosure has not been accepted. */
    onNeedsConsent: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (requirement.isReady) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (requirement.isReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (requirement.isReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    requirement.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    requirement.consequence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!requirement.isReady && requirement.fix.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        requirement.fix,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!requirement.isReady) {
                    when (val action = requirement.action) {
                        is Fix.OpenSettings -> {
                            val consentNeeded =
                                requirement.id == RequirementId.ENFORCEMENT && onNeedsConsent != null
                            TextButton(
                                onClick = {
                                    if (consentNeeded) {
                                        onNeedsConsent()
                                    } else {
                                        openSettings(context, action.screen)
                                    }
                                },
                                contentPadding = PaddingValues(0.dp),
                            ) { Text(actionLabel(action.screen)) }
                        }

                        Fix.AskForNotifications -> {
                            TextButton(
                                onClick = {
                                    askNotifications.launch(
                                        android.Manifest.permission.POST_NOTIFICATIONS,
                                    )
                                },
                                contentPadding = PaddingValues(0.dp),
                            ) { Text("Allow notifications") }
                        }

                        // No spinner, no toast, no result dialog: the list above re-reads itself
                        // every couple of seconds, and this card turning green is the answer.
                        Fix.AttemptRebind -> {
                            TextButton(
                                onClick = {
                                    Rotblock.audit(context).record(
                                        AuditType.ENFORCEMENT_REVIVAL_ATTEMPTED,
                                        detail = if (EnforcementRevival.attemptRebind(context)) {
                                            "component toggled"
                                        } else {
                                            "component toggle refused"
                                        },
                                    )
                                },
                                contentPadding = PaddingValues(0.dp),
                            ) { Text("Try to restart it") }
                        }

                        // Gated on the same disclosure as opening the accessibility screen would
                        // be. Doing the switching-on ourselves makes accepting it *more* load
                        // bearing, not less: it is the only place the user is told what an
                        // accessibility service can see before one starts running.
                        Fix.SelfEnable -> {
                            TextButton(
                                onClick = {
                                    if (onNeedsConsent != null) {
                                        onNeedsConsent()
                                    } else {
                                        Rotblock.audit(context).record(
                                            AuditType.ENFORCEMENT_RE_ENROLLED,
                                            detail = if (AccessibilityEnrolment.enrol(context)) {
                                                "put back in Android's accessibility list from Setup"
                                            } else {
                                                "could not write Android's accessibility list"
                                            },
                                        )
                                    }
                                },
                                contentPadding = PaddingValues(0.dp),
                            ) { Text("Turn enforcement back on") }
                        }

                        // No button, on purpose: nothing rotblock can do from inside the phone
                        // grants this, and a button that opens something useless is worse than
                        // text that explains why there is no button.
                        is Fix.RunOverAdb -> {
                            Spacer(Modifier.height(8.dp))
                            SelectionContainer {
                                Text(
                                    action.command,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }

                        null -> Unit
                    }
                }
            }
        }
    }
}

private fun actionLabel(screen: SettingsScreen): String = when (screen) {
    SettingsScreen.ACCESSIBILITY -> "Turn on enforcement"
    SettingsScreen.OVERLAY -> "Allow the block screen"
    SettingsScreen.BATTERY -> "Open battery settings"
    SettingsScreen.APP_NOTIFICATIONS -> "Open notification settings"
}

private fun openSettings(context: Context, screen: SettingsScreen) {
    val intent = settingsIntentFor(context, screen) ?: return
    // A phone that has no such screen must not take the app down with it.
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
}

/**
 * What the accessibility service reads, said plainly, before anything sends the user to switch it
 * on.
 *
 * Required by Play for any non-accessibility use of that API, and right regardless: this is the one
 * permission in the app that can see every screen you open, and it should be granted by someone who
 * decided to, not by someone who tapped a button labelled "Open settings".
 *
 * Everything in it is checkable. "No internet permission" in particular is not a promise about our
 * conduct -- it is a line in the manifest that Android enforces, and the user can read it in the
 * app's own permission list.
 */
@Composable
fun AccessibilityConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you turn on enforcement") },
        text = {
            Column {
                Text(
                    "rotblock uses Android's accessibility service, because it is the only way an " +
                        "app can tell what you are looking at.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "While it is on, rotblock can see which app is in front, the text in a " +
                        "browser's address bar, and whether a short-form feed is on screen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "It uses that to decide whether one of your rules applies, and for nothing " +
                        "else. It is compared against your rules on this phone and recorded in the " +
                        "usage history you can read in Insights.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "None of it leaves the device. rotblock has no internet permission at all, so " +
                        "it cannot send anything anywhere — you can check that in Settings → Apps " +
                        "→ rotblock → Permissions. You can turn enforcement off at any time in " +
                        "Settings → Accessibility.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("I understand — open settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}

/**
 * Wires the disclosure to the deep link: records consent, then opens accessibility settings.
 *
 * Returns the callback to hand to [RequirementCard]'s `onNeedsConsent`, or null once consent is on
 * record — at which point the card just opens settings itself.
 */
@Composable
fun rememberConsentGate(settings: RotblockSettings): ConsentGate {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showing by remember { mutableStateOf(false) }

    if (showing) {
        AccessibilityConsentDialog(
            onAccept = {
                showing = false
                scope.launch {
                    Rotblock.settings(context).setAccessibilityConsentAt(System.currentTimeMillis())
                }
                openSettings(context, SettingsScreen.ACCESSIBILITY)
            },
            onDismiss = { showing = false },
        )
    }

    return remember(settings.accessibilityConsentAtMs) {
        ConsentGate(
            needed = settings.accessibilityConsentAtMs == 0L,
            request = { showing = true },
        )
    }
}

/** [needed] is false once the disclosure has been accepted; [request] shows it. */
data class ConsentGate(val needed: Boolean, val request: () -> Unit) {
    /** Hand this to [RequirementCard]: null means "no gate, open settings directly". */
    val onNeedsConsent: (() -> Unit)? get() = if (needed) request else null
}
