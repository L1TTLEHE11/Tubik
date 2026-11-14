package dev.tubik.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF3D57)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF7CDCFF),
    background = Color(0xFF0E0E12),
    surface = Color(0xFF16161C),
    surfaceVariant = Color(0xFF22222A),
    onBackground = Color(0xFFEDEDF2),
    onSurface = Color(0xFFEDEDF2),
    onSurfaceVariant = Color(0xFFA0A0AE),
    surfaceContainer = Color(0xFF1B1B22),
    surfaceContainerHigh = Color(0xFF24242C),
)

@Composable
fun TubikTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
