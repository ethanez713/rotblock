package com.ezhou.rotblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ezhou.rotblock.data.RotblockSettings
import com.ezhou.rotblock.policy.Policy
import com.ezhou.rotblock.setup.Need
import com.ezhou.rotblock.setup.blocking

/**
 * The first thing a new phone sees.
 *
 * Without it the app opens on an empty list with a working "Add block" button and no hint that
 * three separate Android permissions stand between a saved rule and anything happening. Someone
 * would add a rule, watch it do nothing, and delete the app -- and they would be right to.
 *
 * It is the same requirement list the Setup tab shows, not a parallel wizard with its own copy of
 * the truth. That matters more than it sounds: onboarding is the one screen guaranteed to go stale,
 * because it is written once and then never looked at again by the person who wrote it.
 *
 * Skippable, always. A walkthrough that cannot be dismissed is a walkthrough that traps anyone
 * whose phone does not have the screen it is pointing at -- and the Blocks banner will keep saying
 * so anyway, which is the honest way to nag.
 */
@Composable
fun OnboardingScreen(
    settings: RotblockSettings,
    policies: List<Policy>,
    onDone: () -> Unit,
) {
    val requirements = rememberRequirements(settings, policies)
    val consent = rememberConsentGate(settings)
    val ready = requirements.blocking.isEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "rotblock",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Limits you set once, that you cannot quietly talk yourself out of later.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "You write rules — an app, a website, or a feed inside an app — and rotblock enforces " +
                "them. Every way of getting around one is a delay rather than a switch: a pause " +
                "ends by itself, and loosening a rule waits before it applies. Nothing about that " +
                "works until Android lets rotblock see what is in front of you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
        Text(
            "What it needs",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Optional requirements are absent on purpose: they only exist because of a setting
            // that a first-run user has not had the chance to switch on.
            requirements
                .filter { it.need != Need.OPTIONAL }
                .forEach { requirement ->
                    RequirementCard(requirement, onNeedsConsent = consent.onNeedsConsent)
                }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "rotblock has no internet permission, so nothing it sees can leave the phone. There " +
                "is no account and nothing to sign into.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onDone,
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (ready) "Write your first rule" else "Waiting on the steps above")
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (ready) "Skip for now" else "Set this up later",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
