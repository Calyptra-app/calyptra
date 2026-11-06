package com.calyptra.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.calyptra.app.ui.AllowlistScreen
import com.calyptra.app.ui.MainViewModel
import com.calyptra.app.ui.NavRoutes
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
                    app.networkMonitor, app.powerStatusProvider, app.protectionEventRepository,
                    app.ioScope
                ) as T
            }
        }
    }

    override fun onStop() {
        // Backgrounding the app ends the parent's PIN grace session (FR-10.6).
        // A configuration change (rotation) is not a background: keeping the
        // session there keeps it consistent with the screen staying open.
        if (!isChangingConfigurations) {
            (application as CalyptraApp).pinManager.endGraceSession()
            // Drop a half-finished PIN prompt with its pending action, so a
            // PIN typed before leaving can't be confirmed by someone else later.
            viewModel.dismissPinPrompt()
        }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app has no deep links, but Navigation turns every route into an
        // implicit one (android-app://androidx.navigation/<route>) and reads
        // them from the launch intent. MainActivity is exported, so strip the
        // data and extras to keep other apps from opening a gated screen.
        intent = Intent(intent).apply {
            data = null
            replaceExtras(Bundle())
        }
        super.onCreate(savedInstanceState)
        setContent {
            CalyptraTheme {
                val navController = rememberNavController()

                // Backgrounding ends the grace session (onStop), so also close any
                // screen reached through the PIN door. Otherwise a whitelist or
                // allowlist left open stays editable without the PIN on return.
                // Config changes (rotation) are not a real background: skip them.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, navController) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP &&
                            !isChangingConfigurations &&
                            NavRoutes.isPinProtected(navController.currentDestination?.route)
                        ) {
                            // If home isn't on the stack, reset to it outright.
                            if (!navController.popBackStack(NavRoutes.HOME, inclusive = false)) {
                                navController.navigate(NavRoutes.HOME) {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                NavHost(navController = navController, startDestination = NavRoutes.HOME) {
                    composable(NavRoutes.HOME) {
                        KidHomeScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) }
                        )
                    }
                    composable(NavRoutes.SETTINGS) {
                        ParentSettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToWhitelist = { navController.navigate(NavRoutes.WHITELIST) },
                            onNavigateToAllowlist = { navController.navigate(NavRoutes.ALLOWLIST) },
                            onNavigateToTimeline = { navController.navigate(NavRoutes.TIMELINE) }
                        )
                    }
                    composable(NavRoutes.WHITELIST) {
                        WhitelistScreen(
                            onBack = { navController.popBackStack() },
                            requirePin = viewModel::requirePin
                        )
                    }
                    composable(NavRoutes.ALLOWLIST) {
                        // Per-domain allowlist — the false-positive escape hatch.
                        // Reached through the PIN-gated settings door (Phase 2).
                        AllowlistScreen(
                            onBack = { navController.popBackStack() },
                            requirePin = viewModel::requirePin
                        )
                    }
                    composable(NavRoutes.TIMELINE) {
                        // Read-only history; reachable only through the gated
                        // settings door, so no GatedAction of its own (FR-13.4).
                        ProtectionTimelineScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                // Hosted once above the NavHost: any screen can trigger the
                // PIN gate (PIN-L2/L3).
                val pinPrompt by viewModel.pinPrompt.collectAsStateWithLifecycle()
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
