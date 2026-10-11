package dev.fitnessapp.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * The Material 3 scheme mapped onto the Bento palette (D31, UX 4.1), so dialogs, menus, sheets, text fields,
 * switches and checkboxes match the Bento components. Dark only: there is no light scheme.
 */
private val Scheme = darkColorScheme(
    primary = Fit.Accent,
    onPrimary = Fit.OnAccent,
    primaryContainer = Fit.Raised,
    onPrimaryContainer = Fit.Ink,
    inversePrimary = Fit.Accent,
    secondary = Fit.Accent,
    onSecondary = Fit.OnAccent,
    secondaryContainer = Fit.Raised,
    onSecondaryContainer = Fit.Ink,
    tertiary = Fit.Accent,
    onTertiary = Fit.OnAccent,
    tertiaryContainer = Fit.Raised,
    onTertiaryContainer = Fit.Ink,
    background = Fit.Ground,
    onBackground = Fit.Ink,
    surface = Fit.Ground,
    onSurface = Fit.Ink,
    surfaceVariant = Fit.Raised,
    onSurfaceVariant = Fit.Muted,
    surfaceTint = Fit.Ground,
    inverseSurface = Fit.Line,
    inverseOnSurface = Fit.Ink,
    error = Fit.Safety,
    onError = Fit.Ground,
    errorContainer = Fit.SafetyContainer,
    onErrorContainer = Fit.Safety,
    outline = Fit.Outline,
    outlineVariant = Fit.Line,
    surfaceBright = Fit.Raised,
    surfaceDim = Fit.Ground,
    surfaceContainerLowest = Fit.Ground,
    surfaceContainerLow = Fit.Tile,
    surfaceContainer = Fit.Raised,
    surfaceContainerHigh = Fit.Tile,
    surfaceContainerHighest = Fit.Raised,
)

/** UX 4.4 corners for the stock components (menus 12, fields 16, tiles 24, sheets and dialogs 28). */
private val FitShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FitnessTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = FitTypography, shapes = FitShapes, content = content)
}
