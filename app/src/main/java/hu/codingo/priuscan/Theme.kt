package hu.codingo.priuscan

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App-specific design tokens (night = dark, day = light). Read them in composables via
 * `LocalPriusColors.current` (or the `pc` shortcut in UiKit.kt). The Material colour scheme below
 * is derived from the same values so stock components (Button, Switch, TextField, dialogs) match.
 *
 * Day mode is used in direct sunlight: every text colour here is a DARK tone on white cards
 * (no light-grey-on-white). The `*Text` variants are for coloured TEXT; the plain ones for fills.
 */
@Immutable
data class PriusColors(
    val dark: Boolean,
    val bg: Color,            // screen background
    val card: Color,          // card / tile background
    val cardBorder: Color,    // 1 dp card border
    val borderStrong: Color,  // outlined chips / buttons
    val divider: Color,       // row separators inside cards
    val track: Color,         // empty part of bars / arcs
    val gaugeFrame: Color,    // fuel / battery gauge outline, level rings
    val text: Color,          // primary text / values
    val dim: Color,           // labels, secondary text
    val accent: Color,        // selected tab, primary actions, CHG
    val onAccent: Color,
    val purple: Color,        // selected trip chip
    val onPurple: Color,
    val green: Color,         // fills / icons: OK, EV, regen, city
    val greenText: Color,
    val amber: Color,         // fills: fuel, PWR, weakest block
    val amberText: Color,     // highlight text (was #FFB74D)
    val red: Color,
    val redText: Color,
    val blue: Color,          // high beam / open road icon
)

val NightColors = PriusColors(
    dark = true,
    bg = Color(0xFF0A111C),
    card = Color(0xFF111B2A),
    cardBorder = Color(0xFF22324A),
    borderStrong = Color(0xFF3A4D68),
    divider = Color(0xFF1E2C40),
    track = Color(0xFF26364D),
    gaugeFrame = Color(0xFF6B7A90),
    text = Color(0xFFFFFFFF),
    dim = Color(0xFF9EB0C3),
    accent = Color(0xFF2196F3),
    onAccent = Color(0xFFFFFFFF),
    purple = Color(0xFF7E53D3),
    onPurple = Color(0xFFFFFFFF),
    green = Color(0xFF3DD468),
    greenText = Color(0xFF5BDB7E),
    amber = Color(0xFFFFC234),
    amberText = Color(0xFFFFB74D),
    red = Color(0xFFE53935),
    redText = Color(0xFFFF5252),
    blue = Color(0xFF50AAFF),
)

val DayColors = PriusColors(
    dark = false,
    bg = Color(0xFFEEF1F5),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFD9DFE7),
    borderStrong = Color(0xFFA9B4C2),
    divider = Color(0xFFE3E8EE),
    track = Color(0xFFDDE3EA),
    gaugeFrame = Color(0xFF8D99A8),
    text = Color(0xFF12171C),
    dim = Color(0xFF4A5866),
    accent = Color(0xFF1677E0),
    onAccent = Color(0xFFFFFFFF),
    purple = Color(0xFF7B45C9),
    onPurple = Color(0xFFFFFFFF),
    green = Color(0xFF22B04E),
    greenText = Color(0xFF16803A),
    amber = Color(0xFFF5B000),
    amberText = Color(0xFFA35F00),
    red = Color(0xFFD32F2F),
    redText = Color(0xFFC62828),
    blue = Color(0xFF1677E0),
)

val LocalPriusColors = staticCompositionLocalOf { NightColors }

private fun scheme(c: PriusColors) = if (c.dark) darkColorScheme(
    background = c.bg, onBackground = c.text,
    surface = c.card, onSurface = c.text,
    surfaceVariant = c.card, onSurfaceVariant = c.dim,
    surfaceContainerHigh = c.card, surfaceContainerHighest = c.track,
    primary = c.accent, onPrimary = c.onAccent,
    secondaryContainer = c.purple, onSecondaryContainer = c.onPurple,
    outline = c.borderStrong, outlineVariant = c.cardBorder,
    error = c.redText,
) else lightColorScheme(
    background = c.bg, onBackground = c.text,
    surface = c.card, onSurface = c.text,
    surfaceVariant = c.card, onSurfaceVariant = c.dim,
    surfaceContainerHigh = c.card, surfaceContainerHighest = c.track,
    primary = c.accent, onPrimary = c.onAccent,
    secondaryContainer = c.purple, onSecondaryContainer = c.onPurple,
    outline = c.borderStrong, outlineVariant = c.cardBorder,
    error = c.redText,
)

private val DarkScheme = scheme(NightColors)
private val LightScheme = scheme(DayColors)

/** Dark/light is decided by the caller (car ambient sensor or system setting). */
@Composable
fun PriusTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPriusColors provides if (dark) NightColors else DayColors) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
