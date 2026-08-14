package com.ezhou.rotblock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ezhou.rotblock.Rotblock
import com.ezhou.rotblock.data.buildExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A copy of every rule, setting and history row, written wherever the user chooses. */
@Composable
fun ExportRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
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
        "Back up your data",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Every rule, setting and history row, written to a file you choose. A reinstall or a " +
            "lost phone loses all of it otherwise -- this is the only way to keep a copy.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = {
        val name = "rotblock-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json"
        launcher.launch(name)
    }) {
        Text("Export a copy")
    }
}
