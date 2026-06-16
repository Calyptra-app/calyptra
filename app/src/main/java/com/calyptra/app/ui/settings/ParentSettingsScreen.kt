package com.calyptra.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.calyptra.app.blocklist.SocialCategory
import com.calyptra.app.ui.GatedAction
import com.calyptra.app.ui.MainUiState
import com.calyptra.app.ui.MainViewModel
import com.calyptra.app.ui.theme.CalyptraTheme

// Open-source / legal links surfaced in the About card: the AGPL-3.0 §6 written
// source offer (for sideloaded APKs) plus the privacy policy, reachable behind
// the PIN-gated settings door.
private const val URL_SOURCE = "https://github.com/Calyptra-app/calyptra"
private const val URL_LICENSE = "https://github.com/Calyptra-app/calyptra/blob/main/LICENSE"
private const val URL_PRIVACY = "https://github.com/Calyptra-app/calyptra/blob/main/PRIVACY.md"

/**
 * The parent's world (F12 §5), reached only through the PIN-gated gear on the
 * kid home. Each gated toggle keeps its exact GatedAction wiring (FR-10/FR-11);
 * the grace session opened at the door makes them frictionless in practice.
 */
@Composable
fun ParentSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToWhitelist: () -> Unit,
    onNavigateToAllowlist: () -> Unit,
    onNavigateToTimeline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Battery exemption and conflict inputs can change while the parent is in
    // system settings and back — same ON_RESUME refresh as the home screen.
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

    ParentSettingsContent(
        uiState = uiState,
        onBack = onBack,
        onGameAdsChange = { allowed ->
            viewModel.requirePin(GatedAction.GAME_ADS) { viewModel.setGameAdsAllowed(allowed) }
        },
        onSafeSearchChange = { enabled ->
            viewModel.requirePin(GatedAction.SAFE_SEARCH) { viewModel.setSafeSearchEnabled(enabled) }
        },
        onYoutubeLevelChange = { level ->
            viewModel.requirePin(GatedAction.YOUTUBE_LEVEL) { viewModel.setYoutubeRestrictLevel(level) }
        },
        onCategoryToggle = { category, blocked ->
            viewModel.requirePin(GatedAction.CATEGORIES) { viewModel.setCategoryBlocked(category, blocked) }
        },
        onNsfwToggle = { blocked ->
            // Adult content reuses the CATEGORIES gate — same PIN policy (PIN-L3).
            viewModel.requirePin(GatedAction.CATEGORIES) {
                viewModel.setCategoryBlocked(SocialCategory.NSFW, blocked)
            }
        },
        onWhitelistClick = {
            viewModel.requirePin(GatedAction.WHITELIST) { onNavigateToWhitelist() }
        },
        onAllowlistClick = {
            viewModel.requirePin(GatedAction.DOMAIN_ALLOWLIST) { onNavigateToAllowlist() }
        },
        // Read-only history — the gated settings door already covered it (FR-13.4).
        onTimelineClick = onNavigateToTimeline,
        onBatteryAllow = { context.startActivity(viewModel.batteryExemptionIntent()) },
        onOpenVpnSettings = { context.startActivity(viewModel.vpnSettingsIntent()) },
        onOpenUrl = { url ->
            // No-op if the device has no browser, rather than crashing on ActivityNotFound.
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSettingsContent(
    uiState: MainUiState,
    onBack: () -> Unit,
    onGameAdsChange: (Boolean) -> Unit,
    onSafeSearchChange: (Boolean) -> Unit,
    onYoutubeLevelChange: (String) -> Unit,
    onCategoryToggle: (SocialCategory, Boolean) -> Unit,
    onNsfwToggle: (Boolean) -> Unit,
    onWhitelistClick: () -> Unit,
    onAllowlistClick: () -> Unit,
    onTimelineClick: () -> Unit,
    onBatteryAllow: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsCard(title = stringResource(R.string.settings_section_filters)) {
                SettingSwitchRow(
                    title = stringResource(R.string.game_ads_title),
                    description = stringResource(R.string.game_ads_description),
                    checked = uiState.gameAdsAllowed,
                    enabled = uiState.isProtectionEnabled,
                    onCheckedChange = onGameAdsChange
                )
                SettingSwitchRow(
                    title = stringResource(R.string.safesearch_title),
                    description = stringResource(R.string.safesearch_description),
                    checked = uiState.safeSearchEnabled,
                    enabled = uiState.isProtectionEnabled,
                    onCheckedChange = onSafeSearchChange
                )
                // Adult content (NSFW) — default OFF, its own labeled row rather
                // than a chip in the social grid (F11 / Phase 2).
                SettingSwitchRow(
                    title = stringResource(R.string.nsfw_category_title),
                    description = stringResource(R.string.nsfw_category_description),
                    checked = SocialCategory.NSFW.key in uiState.blockedCategories,
                    enabled = uiState.isProtectionEnabled,
                    onCheckedChange = onNsfwToggle
                )

                Column {
                    Text(
                        stringResource(R.string.youtube_restrict_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.youtube_restrict_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val youtubeOptions = listOf("off", "moderate", "strict")
                    val youtubeLabels = listOf(
                        stringResource(R.string.youtube_off),
                        stringResource(R.string.youtube_moderate),
                        stringResource(R.string.youtube_strict)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        youtubeOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = uiState.youtubeRestrictLevel == option,
                                onClick = { onYoutubeLevelChange(option) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = youtubeOptions.size
                                ),
                                enabled = uiState.isProtectionEnabled
                            ) {
                                Text(youtubeLabels[index])
                            }
                        }
                    }
                }
            }

            SettingsCard(title = stringResource(R.string.category_section_title)) {
                CategorySection(
                    blockedCategories = uiState.blockedCategories,
                    enabled = uiState.isProtectionEnabled,
                    onToggle = onCategoryToggle
                )
            }

            // Protection health (PWR-L3): each hint only while relevant.
            if (!uiState.batteryExempt || (uiState.isProtectionEnabled && !uiState.alwaysOnActive)) {
                SettingsCard(title = stringResource(R.string.settings_section_health)) {
                    if (!uiState.batteryExempt) {
                        SettingActionRow(
                            title = stringResource(R.string.battery_hint_title),
                            description = stringResource(R.string.battery_hint_desc),
                            actionLabel = stringResource(R.string.battery_allow),
                            onAction = onBatteryAllow
                        )
                    }
                    if (uiState.isProtectionEnabled && !uiState.alwaysOnActive) {
                        SettingActionRow(
                            title = stringResource(R.string.alwayson_hint_title),
                            description = stringResource(R.string.alwayson_hint_desc),
                            actionLabel = stringResource(R.string.alwayson_open),
                            onAction = onOpenVpnSettings
                        )
                    }
                }
            }

            SettingsCard {
                SettingNavRow(
                    title = stringResource(R.string.whitelist_title),
                    description = stringResource(R.string.whitelist_row_desc),
                    onClick = onWhitelistClick
                )
            }

            // Per-domain allowlist — the false-positive escape hatch (Phase 2).
            SettingsCard {
                SettingNavRow(
                    title = stringResource(R.string.allowlist_title),
                    description = stringResource(R.string.allowlist_row_desc),
                    onClick = onAllowlistClick
                )
            }

            // F13 protection timeline (TML-L3).
            SettingsCard {
                SettingNavRow(
                    title = stringResource(R.string.timeline_title),
                    description = stringResource(R.string.timeline_row_desc),
                    onClick = onTimelineClick
                )
            }

            // About / open source: satisfies the AGPL-3.0 §6 written source offer
            // for sideloaded APKs and surfaces the privacy policy to the parent.
            SettingsCard(title = stringResource(R.string.settings_section_about)) {
                Text(
                    stringResource(R.string.about_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingNavRow(
                    title = stringResource(R.string.about_source_title),
                    description = stringResource(R.string.about_source_desc),
                    onClick = { onOpenUrl(URL_SOURCE) }
                )
                SettingNavRow(
                    title = stringResource(R.string.about_license_title),
                    description = stringResource(R.string.about_license_desc),
                    onClick = { onOpenUrl(URL_LICENSE) }
                )
                SettingNavRow(
                    title = stringResource(R.string.about_privacy_title),
                    description = stringResource(R.string.about_privacy_desc),
                    onClick = { onOpenUrl(URL_PRIVACY) }
                )
            }

            val lastUpdate = if (uiState.lastUpdate > 0) {
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(uiState.lastUpdate))
            } else {
                stringResource(R.string.blocklist_never)
            }
            Text(
                text = stringResource(R.string.blocklist_updated, lastUpdate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** Rounded section card grouping related settings rows (F12 §6). */
@Composable
private fun SettingsCard(
    title: String? = null,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingNavRow(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ParentSettingsPreview() {
    CalyptraTheme {
        ParentSettingsContent(
            uiState = MainUiState(
                isProtectionEnabled = true,
                safeSearchEnabled = true,
                youtubeRestrictLevel = "strict",
                batteryExempt = false,
                blockedCategories = setOf("tiktok", "snapchat")
            ),
            onBack = {},
            onGameAdsChange = {},
            onSafeSearchChange = {},
            onYoutubeLevelChange = {},
            onCategoryToggle = { _, _ -> },
            onNsfwToggle = {},
            onWhitelistClick = {},
            onAllowlistClick = {},
            onTimelineClick = {},
            onBatteryAllow = {},
            onOpenVpnSettings = {},
            onOpenUrl = {}
        )
    }
}
