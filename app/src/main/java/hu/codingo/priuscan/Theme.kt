package hu.codingo.priuscan

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The app follows the system light/dark setting. Background + text colors come from the
// color scheme below; accent/status colors (coolant gradient, warnings, orange highlight)
// stay fixed since they read fine on both. Use MaterialTheme.colorScheme.* in the UI:
//   background      -> screen background
//   onBackground    -> primary text/values (was Color.White)
//   onSurfaceVariant-> secondary/label text (was 0xFF9EB6C3 / 5E7A8A / B9C6CE)
private val DarkColors = darkColorScheme(
    background = Color(0xFF0E1216),
    surface = Color(0xFF161B20),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF9EB6C3),
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF06121C),
)

private val LightColors = lightColorScheme(
    background = Color(0xFFEEF1F5),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF12171C),
    onSurface = Color(0xFF12171C),
    onSurfaceVariant = Color(0xFF4F5E6A),
    primary = Color(0xFF1565C0),
    onPrimary = Color(0xFFFFFFFF),
)

@Composable
fun PriusTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
