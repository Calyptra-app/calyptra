package com.calyptra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.calyptra.app.ui.AllowlistScreen
import com.calyptra.app.ui.MainViewModel
import com.calyptra.app.ui.WhitelistScreen
import com.calyptra.app.ui.home.KidHomeScreen
import com.calyptra.app.ui.pin.PinPromptDialog
import com.calyptra.app.ui.settings.ParentSettingsScreen
import com.calyptra.app.ui.settings.ProtectionTimelineScreen
import com.calyptra.app.ui.theme.CalyptraTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as CalyptraApp
                return MainViewModel(
                    app.statsRepository, app.preferencesRepository, app.pinManager,
                    app.networkMonitor, app.powerStatusProvider, app.protectionEventRepository
                ) as T
            }
        }
    }

    override fun onStop() {
        // Backgrounding the app ends the parent's PIN grace session (FR-10.6).
        (application as CalyptraApp).pinManager.endGraceSession()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CalyptraTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        KidHomeScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        ParentSettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToWhitelist = { navController.navigate("whitelist") },
                            onNavigateToAllowlist = { navController.navigate("allowlist") },
                            onNavigateToTimeline = { navController.navigate("timeline") }
                        )
                    }
                    composable("whitelist") {
                        WhitelistScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("allowlist") {
                        // Per-domain allowlist — the false-positive escape hatch.
                        // Reached through the PIN-gated settings door (Phase 2).
                        AllowlistScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("timeline") {
                        // Read-only history; reachable only through the gated
                        // settings door, so no GatedAction of its own (FR-13.4).
                        ProtectionTimelineScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                // Hosted once above the NavHost: any screen can trigger the
                // PIN gate (PIN-L2/L3).
                val pinPrompt by viewModel.pinPrompt.collectAsState()
                pinPrompt?.let { prompt ->
                    PinPromptDialog(
                        state = prompt,
                        onSubmitSetup = viewModel::submitPinSetup,
                        onSubmitChallenge = viewModel::submitPinChallenge,
                        onDismiss = viewModel::dismissPinPrompt
                    )
                }
            }
        }
    }
}
