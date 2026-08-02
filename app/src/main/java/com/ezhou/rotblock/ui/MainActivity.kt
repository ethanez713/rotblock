package com.ezhou.rotblock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Tab(val label: String, val icon: ImageVector) {
    Blocks("Blocks", Icons.Filled.Lock),
    Insights("Insights", Icons.AutoMirrored.Filled.List),
    Setup("Setup", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    private val viewModel: RotblockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RotblockTheme {
                Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    RotblockApp(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

@Composable
private fun RotblockApp(viewModel: RotblockViewModel) {
    var tab by remember { mutableStateOf(Tab.Blocks) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsLoaded by viewModel.settingsLoaded.collectAsStateWithLifecycle()
    val policies by viewModel.policies.collectAsStateWithLifecycle()

    // Nothing at all until the real settings arrive: "never onboarded" is the default value of a
    // stored one, so drawing anything before it loads means flashing the walkthrough at someone who
    // finished it months ago. A blank frame or two is invisible; that is not.
    if (!settingsLoaded) return

    // Before anything else, once. The tabs are useless to someone who has not granted anything:
    // rules save fine and then sit there doing nothing, which is the impression this app can least
    // afford to make.
    if (settings.onboardedAtMs == 0L) {
        OnboardingScreen(
            settings = settings,
            policies = policies,
            onDone = viewModel::completeOnboarding,
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Blocks -> BlocksScreen(viewModel, settings, onOpenSetup = { tab = Tab.Setup })
                Tab.Insights -> InsightsScreen(viewModel)
                Tab.Setup -> SetupScreen(viewModel, settings)
            }
        }
    }
}
