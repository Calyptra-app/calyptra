package com.calyptra.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calyptra.app.R
import com.calyptra.app.ui.theme.CalyptraTheme
import com.calyptra.app.ui.theme.LocalShieldColors

/**
 * Hero protection toggle (F12 §6): a 200dp circular shield button whose color
 * IS the protection status. Single tap toggles (Constitution II); state changes
 * cross-fade, and a soft halo breathes while protected.
 */
@Composable
fun ProtectionToggle(
    isEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shield = LocalShieldColors.current

    val containerColor by animateColorAsState(
        targetValue = if (isEnabled) shield.protected else shield.unprotected,
        animationSpec = tween(durationMillis = 400),
        label = "shieldColor"
    )
    val glowColor by animateColorAsState(
        targetValue = if (isEnabled) shield.protectedGlow else shield.unprotectedGlow,
        animationSpec = tween(durationMillis = 400),
        label = "glowColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isEnabled) shield.onProtected else shield.onUnprotected,
        animationSpec = tween(durationMillis = 400),
        label = "contentColor"
    )

    // Soft breathing halo while protected; static ring when off.
    val haloScale = rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 1f,
        targetValue = if (isEnabled) 1.12f else 1f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(durationMillis = 1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloScale"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale = animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(232.dp)
                    // Deferred read: the breathing halo runs continuously while
                    // protected, so read the animated value in the draw phase
                    // (graphicsLayer block) instead of recomposing every frame.
                    .graphicsLayer {
                        scaleX = haloScale.value
                        scaleY = haloScale.value
                    }
                    .background(glowColor, CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .graphicsLayer {
                        scaleX = pressScale.value
                        scaleY = pressScale.value
                    }
                    .shadow(elevation = 8.dp, shape = CircleShape)
                    .background(containerColor, CircleShape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = rememberRipple(),
                        onClickLabel = stringResource(
                            if (isEnabled) R.string.disable_protection else R.string.enable_protection
                        ),
                        onClick = onToggle
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        if (isEnabled) R.drawable.ic_shield_check else R.drawable.ic_shield_alert
                    ),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(96.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = stringResource(if (isEnabled) R.string.home_status_on else R.string.home_status_off),
            style = MaterialTheme.typography.headlineMedium,
            color = containerColor
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(if (isEnabled) R.string.home_subtitle_on else R.string.home_subtitle_off),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProtectionToggleOnPreview() {
    CalyptraTheme {
        ProtectionToggle(isEnabled = true, onToggle = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ProtectionToggleOffPreview() {
    CalyptraTheme {
        ProtectionToggle(isEnabled = false, onToggle = {})
    }
}
