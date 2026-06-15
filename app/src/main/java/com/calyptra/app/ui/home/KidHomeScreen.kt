package com.calyptra.app.ui.home

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.calyptra.app.R
import com.calyptra.app.ui.ConflictState
import com.calyptra.app.ui.GatedAction
import com.calyptra.app.ui.MainUiState
import com.calyptra.app.ui.MainViewModel
import com.calyptra.app.ui.components.ConflictBanner
import com.calyptra.app.ui.components.KidStatsRow
import com.calyptra.app.ui.components.ProtectionToggle
import com.calyptra.app.ui.theme.CalyptraTheme
import com.calyptra.app.vpn.VpnController

/**
 * The child's world (F12 §5): hero shield, status, stats — nothing else.
 * All configuration lives behind the PIN-gated gear (PARENT_SETTINGS).
 */
@Composable
fun KidHomeScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.setProtectionEnabled(true)
            VpnController.startVpn(context)
        }
    }

    val enableProtection = {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            viewModel.setProtectionEnabled(true)
            VpnController.startVpn(context)
        }
    }

    // Refresh conflict inputs (VPN permission holder, Private DNS) on resume (CFT-L3/L4).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResumed(prepareNeeded = VpnService.prepare(context) != null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    KidHomeContent(
        uiState = uiState,
        onToggleProtection = {
            if (uiState.isProtectionEnabled) {
                // Disabling reduces protection -> parent PIN (PIN-L3).
                viewModel.requirePin(GatedAction.DISABLE_PROTECTION) {
                    viewModel.setProtectionEnabled(false)
                    VpnController.stopVpn(context)
                }
            } else {
                // Enabling is always a single kid-friendly tap (Constitution II).
                enableProtection()
            }
        },
        onOpenSettings = {
            viewModel.requirePin(GatedAction.PARENT_SETTINGS) { onNavigateToSettings() }
        },
        onReenable = enableProtection,
        onOpenNetworkSettings = {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        },
        modifier = modifier
    )

    // One-time battery exemption explainer after the first enable (FR-9.4).
    if (uiState.isProtectionEnabled && !uiState.batteryPromptShown && !uiState.batteryExempt) {
        AlertDialog(
            onDismissRequest = { viewModel.markBatteryPromptShown() },
            title = { Text(stringResource(R.string.battery_hint_title)) },
            text = { Text(stringResource(R.string.battery_prompt_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.markBatteryPromptShown()
                    context.startActivity(viewModel.batteryExemptionIntent())
                }) {
                    Text(stringResource(R.string.battery_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.markBatteryPromptShown() }) {
                    Text(stringResource(R.string.battery_later))
                }
            }
        )
    }
}

@Composable
fun KidHomeContent(
    uiState: MainUiState,
    onToggleProtection: () -> Unit,
    onOpenSettings: () -> Unit,
    onReenable: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (uiState.conflictState != ConflictState.NONE) {
                    Spacer(modifier = Modifier.height(48.dp))
                    ConflictBanner(
                        conflictState = uiState.conflictState,
                        onReenable = onReenable,
                        onOpenSettings = onOpenNetworkSettings
                    )
                }

                Spacer(modifier = Modifier.height(40.dp))

                ProtectionToggle(
                    isEnabled = uiState.isProtectionEnabled,
                    onToggle = onToggleProtection
                )

                Spacer(modifier = Modifier.height(48.dp))

                KidStatsRow(
                    todayBlocked = uiState.adsBlockedToday,
                    totalBlocked = uiState.adsBlockedTotal,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(40.dp))
            }

            // Composed after the scrollable Column so it stays on top and
            // receives taps (the Column fills the screen).
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.cd_open_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun KidHomeProtectedPreview() {
    CalyptraTheme {
        KidHomeContent(
            uiState = MainUiState(
                isProtectionEnabled = true,
                adsBlockedToday = 42,
                adsBlockedTotal = 1337
            ),
            onToggleProtection = {},
            onOpenSettings = {},
            onReenable = {},
            onOpenNetworkSettings = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun KidHomeUnprotectedWithConflictPreview() {
    CalyptraTheme {
        KidHomeContent(
            uiState = MainUiState(
                isProtectionEnabled = false,
                adsBlockedToday = 7,
                adsBlockedTotal = 451,
                conflictState = ConflictState.OTHER_VPN
            ),
            onToggleProtection = {},
            onOpenSettings = {},
            onReenable = {},
            onOpenNetworkSettings = {}
        )
    }
}
