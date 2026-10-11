package dev.fitnessapp.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.fitnessapp.app.R

/** Bundled Manrope (UX 4.2): one variable font, one instance per weight used. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(R.font.manrope, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Manrope = FontFamily(manrope(500), manrope(700), manrope(800))

/** Every style sets tabular figures so counting digits never jitter (UX 4.2). */
private fun style(size: Float, line: Float, weight: Int, tracking: TextUnit = 0.sp) = TextStyle(
    fontFamily = Manrope,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking,
    fontFeatureSettings = "tnum",
)

/** The type scale of UX 4.2 in the Material 3 slots, so stock components pick it up. */
val FitTypography = Typography(
    displayLarge = style(72f, 76f, 800, (-2).sp),
    displayMedium = style(40f, 44f, 800, (-1).sp),
    displaySmall = style(34f, 40f, 800, (-1).sp),
    headlineLarge = style(34f, 40f, 800, (-1).sp),
    headlineMedium = style(28f, 34f, 800, (-0.6).sp),
    headlineSmall = style(24f, 30f, 800, (-0.5).sp),
    titleLarge = style(22f, 28f, 800, (-0.4).sp),
    titleMedium = style(16f, 22f, 700),
    titleSmall = style(14f, 20f, 700),
    bodyLarge = style(16f, 24f, 500),
    bodyMedium = style(14f, 20f, 500),
    bodySmall = style(12.5f, 18f, 500),
    labelLarge = style(15f, 20f, 700),
    labelMedium = style(13f, 16f, 700),
    labelSmall = style(11f, 14f, 700),
)

/** Custom styles outside the Material slots (UX 4.2). */
object FitType {
    val RestDigits = style(72f, 76f, 800, (-2).sp)
    val Overtime = style(56f, 60f, 800, (-1.5).sp)
    val HoldDigits = style(96f, 100f, 800, (-3).sp)
}
