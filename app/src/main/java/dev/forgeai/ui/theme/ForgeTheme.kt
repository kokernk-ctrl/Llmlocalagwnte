package dev.forgeai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Graphite = darkColorScheme(
    primary = Color(0xFFFFAB74), onPrimary = Color(0xFF3F1700),
    primaryContainer = Color(0xFF45291D), onPrimaryContainer = Color(0xFFFFCBAB),
    secondary = Color(0xFFC1C6D1), secondaryContainer = Color(0xFF30343E),
    onSecondaryContainer = Color(0xFFE4E7F0),
    background = Color(0xFF111215), onBackground = Color(0xFFF1F0F3),
    surface = Color(0xFF191B20), onSurface = Color(0xFFF1F0F3),
    surfaceVariant = Color(0xFF25282F), onSurfaceVariant = Color(0xFFC2C4CF),
    outline = Color(0xFF9295A2), outlineVariant = Color(0xFF393C45),
)

@Composable fun ForgeTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Graphite, content = content)
