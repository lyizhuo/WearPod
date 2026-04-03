package site.whitezaak.wearpod.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

@Composable
fun WearPodTheme(
    content: @Composable () -> Unit
) {
    val monochromeColorScheme = ColorScheme(
        primary = Color(0xFFE8E8E8),
        primaryDim = Color(0xFFCFCFCF),
        primaryContainer = Color(0xFF9A9A9A),
        onPrimary = Color(0xFF111111),
        onPrimaryContainer = Color(0xFF0D0D0D),
        secondary = Color(0xFFD0D0D0),
        secondaryDim = Color(0xFFB6B6B6),
        secondaryContainer = Color(0xFF8B8B8B),
        onSecondary = Color(0xFF121212),
        onSecondaryContainer = Color(0xFF0F0F0F),
        tertiary = Color(0xFFBDBDBD),
        tertiaryDim = Color(0xFF9F9F9F),
        tertiaryContainer = Color(0xFF757575),
        onTertiary = Color(0xFF111111),
        onTertiaryContainer = Color(0xFFFAFAFA),
        surfaceContainerLow = Color(0xFF1A1A1A),
        surfaceContainer = Color(0xFF202020),
        surfaceContainerHigh = Color(0xFF2C2C2C),
        onSurface = Color(0xFFEDEDED),
        onSurfaceVariant = Color(0xFFC4C4C4),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF1F1F1),
        outline = Color(0xFF6D6D6D),
        outlineVariant = Color(0xFF4C4C4C),
    )

    MaterialTheme(
        colorScheme = monochromeColorScheme,
        content = content
    )
}