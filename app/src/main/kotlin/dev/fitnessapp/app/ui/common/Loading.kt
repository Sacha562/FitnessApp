package dev.fitnessapp.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.ui.theme.Fit
import kotlinx.coroutines.delay

/** The lines the Today card's loader cycles through while the planner runs. */
val PLANNER_LINES = listOf("Warming up the planner…", "Counting this week's sets…", "Weighing today's options…")

/** The lines the New session sheet's loader cycles through while it builds the options. */
val SHEET_LINES = listOf("Warming up the planner…", "Building your options…")

/**
 * A loading indicator (D73): a dumbbell that lifts and settles over its shadow, with [lines] fading one into the next
 * every two seconds. [height] is the drawing's height; the whole block is announced once as "Loading".
 */
@Composable
fun LoadingDumbbell(lines: List<String>, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    val t = rememberInfiniteTransition(label = "dumbbell")
    val lift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lift")
    val tilt by t.animateFloat(-4f, 4f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "tilt")
    var line by remember(lines) { mutableIntStateOf(0) }
    LaunchedEffect(lines) {
        while (lines.size > 1) {
            delay(2000)
            line = (line + 1) % lines.size
        }
    }
    Column(
        modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = "Loading"; liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(height * 2f, height)) { dumbbell(lift, tilt) }
        AnimatedContent(lines.getOrElse(line) { "" }, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) }, label = "line") { text ->
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted, textAlign = TextAlign.Center)
        }
    }
}

/** Draws the dumbbell [lift] (0 resting, 1 at the top) of the height above its shadow, tilted by [tilt] degrees. */
private fun DrawScope.dumbbell(lift: Float, tilt: Float) {
    val w = size.width
    val h = size.height
    val shadowY = h * 0.92f
    val shadowW = w * (0.62f - 0.18f * lift)
    drawRoundRect(
        Fit.Line, topLeft = Offset((w - shadowW) / 2f, shadowY - h * 0.04f), size = Size(shadowW, h * 0.08f),
        cornerRadius = CornerRadius(h * 0.04f), alpha = 0.9f - 0.4f * lift,
    )
    val cy = h * 0.52f - h * 0.28f * lift
    val cx = w / 2f
    rotate(tilt * lift, pivot = Offset(cx, cy)) {
        // The bar, then per side a collar, a large inner plate and a smaller outer plate.
        val barH = h * 0.08f
        drawRoundRect(Fit.Outline, topLeft = Offset(w * 0.08f, cy - barH / 2f), size = Size(w * 0.84f, barH), cornerRadius = CornerRadius(barH / 2f))
        for (side in listOf(-1f, 1f)) {
            fun plate(fromCentre: Float, pw: Float, ph: Float, color: androidx.compose.ui.graphics.Color) {
                val x = cx + side * fromCentre - pw / 2f
                drawRoundRect(color, topLeft = Offset(x, cy - ph / 2f), size = Size(pw, ph), cornerRadius = CornerRadius(pw * 0.35f))
            }
            plate(w * 0.24f, w * 0.03f, h * 0.22f, Fit.Ink)
            plate(w * 0.30f, w * 0.07f, h * 0.62f, Fit.Accent)
            plate(w * 0.375f, w * 0.055f, h * 0.44f, Fit.Accent.copy(alpha = 0.75f))
        }
    }
}
