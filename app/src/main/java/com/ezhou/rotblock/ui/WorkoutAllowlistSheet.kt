package com.ezhou.rotblock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which apps a workout leaves alone: music, podcasts, a timer — the ones that help rather than
 * distract.
 *
 * Saved on every tap rather than behind a confirm button, because the whole list is the setting
 * and there is no half-edited state worth protecting: this weakens nothing a rule was doing on
 * its own, so there is nothing here for [com.ezhou.rotblock.data.PendingChange] to delay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutAllowlistSheet(
    allowed: Set<String>,
    workoutPackage: String,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val apps by produceState(initialValue = emptyList<InstalledApp>(), context) {
        value = withContext(Dispatchers.IO) {
            loadLaunchableApps(context.packageManager, exclude = context.packageName)
        }
    }

    // Allowed apps first, so the list you have chosen is legible without searching for it. Ordered
    // off the selection the sheet opened with and never re-ordered afterwards: sorting on the live
    // set would make a row jump to the top of the list from under the finger that just ticked it.
    val ordered = remember(apps) {
        apps.sortedWith(compareBy({ it.packageName !in allowed }, { it.label.lowercase() }))
    }
    val filtered = remember(ordered, query) {
        if (query.isBlank()) ordered else ordered.filter { it.label.contains(query, ignoreCase = true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).heightIn(max = 560.dp)) {
            Text(
                "Allowed during workouts",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "These keep working while a workout is on — no block, no greyscale, and no nudge " +
                    "to get back to it. Their own rules still apply.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    // The workout app is exempt whether or not it is on the list, so it is shown
                    // ticked and fixed. Leaving it out of the list entirely would read as an
                    // omission, and letting it be unticked would be a switch that does nothing.
                    val fixed = app.packageName == workoutPackage
                    val checked = fixed || app.packageName in allowed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !fixed) {
                                onChange(
                                    if (checked) allowed - app.packageName else allowed + app.packageName,
                                )
                            }
                            .padding(vertical = 6.dp),
                    ) {
                        app.icon?.let {
                            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(14.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            if (fixed) {
                                Text(
                                    "Your workout app",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !fixed)
                    }
                }
            }
        }
    }
}
