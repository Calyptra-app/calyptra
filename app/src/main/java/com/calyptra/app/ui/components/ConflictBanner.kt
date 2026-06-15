package com.calyptra.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calyptra.app.R
import com.calyptra.app.ui.ConflictState
import com.calyptra.app.ui.theme.CalyptraTheme
import com.calyptra.app.ui.theme.LocalShieldColors

/** Amber warning when protection is compromised (CFT-L3: other VPN or Private DNS). */
@Composable
fun ConflictBanner(
    conflictState: ConflictState,
    onReenable: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shield = LocalShieldColors.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = shield.warningContainer,
        contentColor = shield.onWarningContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                val message = when (conflictState) {
                    ConflictState.OTHER_VPN -> stringResource(R.string.conflict_other_vpn)
                    else -> stringResource(R.string.conflict_private_dns)
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            Row(modifier = Modifier.align(Alignment.End)) {
                if (conflictState == ConflictState.OTHER_VPN) {
                    TextButton(onClick = onReenable) {
                        Text(
                            stringResource(R.string.conflict_reenable),
                            color = shield.onWarningContainer,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else {
                    TextButton(onClick = onOpenSettings) {
                        Text(
                            stringResource(R.string.conflict_open_settings),
                            color = shield.onWarningContainer,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConflictBannerOtherVpnPreview() {
    CalyptraTheme {
        ConflictBanner(
            conflictState = ConflictState.OTHER_VPN,
            onReenable = {},
            onOpenSettings = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConflictBannerPrivateDnsPreview() {
    CalyptraTheme {
        ConflictBanner(
            conflictState = ConflictState.PRIVATE_DNS,
            onReenable = {},
            onOpenSettings = {}
        )
    }
}
