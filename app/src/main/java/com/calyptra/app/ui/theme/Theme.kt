package com.calyptra.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = MintBright,
    onPrimary = PineDark,
    primaryContainer = FernContainer,
    onPrimaryContainer = Mint,
    secondary = LagoonBright,
    onSecondary = LagoonDeep,
    secondaryContainer = LagoonContainerDark,
    onSecondaryContainer = LagoonContainer,
    tertiary = HoneyBright,
    onTertiary = HoneyDeep,
    tertiaryContainer = HoneyContainerDark,
    onTertiaryContainer = HoneyContainer,
    error = CoralBright,
    onError = CoralOnDark,
    errorContainer = CoralContainerDark,
    onErrorContainer = CoralContainer,
    background = Night,
    onBackground = Snow,
    surface = SurfaceDark,
    onSurface = Snow,
    surfaceVariant = SageDark,
    onSurfaceVariant = SageSnow,
    outline = OutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = Fern,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = Pine,
    secondary = Lagoon,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = LagoonContainer,
    onSecondaryContainer = LagoonDeep,
    tertiary = Honey,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = HoneyContainer,
    onTertiaryContainer = HoneyDeep,
    error = Coral,
    onError = androidx.compose.ui.graphics.Color.White,
    errorContainer = CoralContainer,
    onErrorContainer = CoralDeep,
    background = Cloud,
    onBackground = Ink,
    surface = SurfaceLight,
    onSurface = Ink,
    surfaceVariant = Sage,
    onSurfaceVariant = SageInk,
    outline = OutlineLight
)

/** Protection-state colors for the current theme. See [ShieldColors]. */
val LocalShieldColors = staticCompositionLocalOf { LightShieldColors }

@Composable
fun CalyptraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // No dynamic color: the brand palette carries the protected/unprotected
    // state semantics (Constitution II), so it must not vary per device.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val shieldColors = if (darkTheme) DarkShieldColors else LightShieldColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalShieldColors provides shieldColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
