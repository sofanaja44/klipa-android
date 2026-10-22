package com.example.klipa.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/** Spacing on a 4 dp grid. Related controls sit [xs]–[sm] apart; groups [lg] or more. */
object KlipaSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner radii. Small controls get [control]; floating sheets [sheet]; nothing else is rounded. */
object KlipaShapes {
    val control = RoundedCornerShape(6.dp)
    val clip = RoundedCornerShape(5.dp)
    val sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
}

/**
 * Root theme. The editor is dark-only, like the pro tools it follows: footage and color
 * decisions need a neutral dark surround, and a light theme would fight that.
 * Material 3 components get the Klipa palette mapped onto their color roles.
 */
@Composable
fun KlipaTheme(content: @Composable () -> Unit) {
    val colors = KlipaDarkColors
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentMuted,
        onPrimaryContainer = colors.accent,
        secondary = colors.textSecondary,
        onSecondary = colors.canvas,
        background = colors.canvas,
        onBackground = colors.textPrimary,
        surface = colors.panel,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.raised,
        onSurfaceVariant = colors.textSecondary,
        surfaceContainerLowest = colors.canvas,
        surfaceContainerLow = colors.panel,
        surfaceContainer = colors.panel,
        surfaceContainerHigh = colors.raised,
        surfaceContainerHighest = colors.raised,
        inverseSurface = colors.textPrimary,
        inverseOnSurface = colors.canvas,
        outline = colors.outlineStrong,
        outlineVariant = colors.outline,
        error = colors.danger,
        onError = colors.canvas,
    )
    CompositionLocalProvider(
        LocalKlipaColors provides colors,
        LocalKlipaTextStyles provides KlipaDefaultTextStyles,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = KlipaMaterialTypography,
            shapes = Shapes(small = KlipaShapes.control, medium = KlipaShapes.control, large = KlipaShapes.sheet),
            content = content,
        )
    }
}

/** Klipa tokens that Material's roles do not cover. */
object Klipa {
    val colors: KlipaColors
        @Composable @ReadOnlyComposable
        get() = LocalKlipaColors.current

    val textStyles: KlipaTextStyles
        @Composable @ReadOnlyComposable
        get() = LocalKlipaTextStyles.current
}
