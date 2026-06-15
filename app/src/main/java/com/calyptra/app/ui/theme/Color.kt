package com.calyptra.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// "Calyptra Greens" palette (F12). Nature-green brand for the protected state,
// red-amber reserved for unprotected/warning semantics.

// Light scheme
val Fern = Color(0xFF2C7A57)
val Mint = Color(0xFFBFEAD2)
val Pine = Color(0xFF0B3B26)
val Lagoon = Color(0xFF2F6D8C)
val LagoonContainer = Color(0xFFC9E6F5)
val LagoonDeep = Color(0xFF0C3346)
val Honey = Color(0xFF9A6A00)
val HoneyContainer = Color(0xFFFFE3A8)
val HoneyDeep = Color(0xFF4A3000)
val Coral = Color(0xFFC73A33)
val CoralContainer = Color(0xFFFFDAD6)
val CoralDeep = Color(0xFF410002)
val Cloud = Color(0xFFF5FAF6)
val SurfaceLight = Color(0xFFFBFDF9)
val Ink = Color(0xFF18211C)
val Sage = Color(0xFFE1EAE2)
val SageInk = Color(0xFF41514A)
val OutlineLight = Color(0xFF71857B)

// Dark scheme
val MintBright = Color(0xFF8FD7B0)
val PineDark = Color(0xFF0C3D27)
val FernContainer = Color(0xFF1E5A3E)
val LagoonBright = Color(0xFF9CCDE8)
val LagoonContainerDark = Color(0xFF234D63)
val HoneyBright = Color(0xFFF2C66B)
val HoneyContainerDark = Color(0xFF5C4300)
val CoralBright = Color(0xFFFFB4AB)
val CoralOnDark = Color(0xFF690005)
val CoralContainerDark = Color(0xFF93000A)
val Night = Color(0xFF101513)
val SurfaceDark = Color(0xFF131A16)
val Snow = Color(0xFFDFE5DF)
val SageDark = Color(0xFF2A332D)
val SageSnow = Color(0xFFBCC9BF)
val OutlineDark = Color(0xFF86988C)

/**
 * Protection-state colors (F12 §3). Components read these via
 * [LocalShieldColors] so the protected/unprotected semantics stay consistent
 * across light and dark mode — never hardcode state hex in composables.
 */
@Immutable
data class ShieldColors(
    val protected: Color,
    val onProtected: Color,
    val protectedGlow: Color,
    val unprotected: Color,
    val onUnprotected: Color,
    val unprotectedGlow: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

val LightShieldColors = ShieldColors(
    protected = Fern,
    onProtected = Color.White,
    protectedGlow = Mint,
    unprotected = Color(0xFFD9483B),
    onUnprotected = Color.White,
    unprotectedGlow = Color(0xFFFFD9D3),
    warningContainer = HoneyContainer,
    onWarningContainer = HoneyDeep
)

val DarkShieldColors = ShieldColors(
    protected = MintBright,
    onProtected = PineDark,
    protectedGlow = FernContainer,
    unprotected = Color(0xFFFFB3A9),
    onUnprotected = Color(0xFF5C1710),
    unprotectedGlow = Color(0xFF7A2E26),
    warningContainer = HoneyContainerDark,
    onWarningContainer = HoneyContainer
)
