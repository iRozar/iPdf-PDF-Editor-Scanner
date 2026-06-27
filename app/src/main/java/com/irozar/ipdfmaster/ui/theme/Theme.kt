package com.irozar.ipdfmaster.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fixed brand color scheme (no wallpaper/dynamic color, no broken dark mode) so dialogs,
// buttons, switches, chips and text fields all match the app's look everywhere.
private val BrandColors =
    lightColorScheme(
        primary = Color(0xFF2563EB),            // brand blue (controls, links, focus)
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE7FF),
        onPrimaryContainer = Color(0xFF001B3D),
        secondary = Color(0xFFD32F2F),          // brand red accent
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFDAD5),
        onSecondaryContainer = Color(0xFF410002),
        tertiary = Color(0xFF7C3AED),           // purple accent (AI)
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFEADDFF),
        onTertiaryContainer = Color(0xFF24005A),
        background = Color(0xFFFCFCFF),
        onBackground = Color(0xFF1B1B1F),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1B1B1F),
        surfaceVariant = Color(0xFFF1F2F5),
        onSurfaceVariant = Color(0xFF45464A),
        // Neutral white container roles so dialogs, menus and sheets are clean (not lavender).
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF8F8FA),
        surfaceContainer = Color(0xFFF4F4F7),
        surfaceContainerHigh = Color(0xFFFFFFFF),
        surfaceContainerHighest = Color(0xFFEFEFF2),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFE6E6E9),
        outline = Color(0xFFC6C6CA),
        outlineVariant = Color(0xFFE3E3E6),
        error = Color(0xFFD32F2F),
        onError = Color.White,
    )

@Composable
fun MyApplicationTheme(
    // Kept for call-site compatibility; the app uses one fixed light brand theme.
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = BrandColors, typography = Typography, content = content)
}
