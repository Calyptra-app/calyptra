package com.calyptra.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.calyptra.app.R
import com.calyptra.app.blocklist.SocialCategory

/** Brand names are intentionally not localized. */
private val DISPLAY_NAMES = mapOf(
    SocialCategory.TIKTOK to "TikTok",
    SocialCategory.INSTAGRAM to "Instagram",
    SocialCategory.SNAPCHAT to "Snapchat",
    SocialCategory.FACEBOOK to "Facebook",
    SocialCategory.TWITTER to "X (Twitter)",
    SocialCategory.REDDIT to "Reddit",
    SocialCategory.DISCORD to "Discord",
    SocialCategory.TWITCH to "Twitch",
)

/** "App Blocking" settings section (SOC-L4). Toggle changes are PIN-gated by
 *  the caller (FR-11.5); switches only report intent here. The hosting card
 *  renders the section title (F12); this composable keeps the description and
 *  the per-category switch rows. */
@Composable
fun CategorySection(
    blockedCategories: Set<String>,
    enabled: Boolean,
    onToggle: (SocialCategory, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.category_section_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        for (category in SocialCategory.entries) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        DISPLAY_NAMES.getValue(category),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (category == SocialCategory.FACEBOOK) {
                        Text(
                            stringResource(R.string.category_facebook_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = category.key in blockedCategories,
                    onCheckedChange = { blocked -> onToggle(category, blocked) },
                    enabled = enabled
                )
            }
        }
    }
}
