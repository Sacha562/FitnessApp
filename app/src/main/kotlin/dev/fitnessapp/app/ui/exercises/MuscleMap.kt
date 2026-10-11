package dev.fitnessapp.app.ui.exercises

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** UX 13.3 shading: group coefficient -> share of the primary colour drawn over the idle muscle colour. */
fun shadeAlpha(coefficient: Double): Float = when {
    coefficient >= 1.0 -> 1f
    coefficient >= 0.75 -> 0.70f
    coefficient >= 0.5 -> 0.45f
    coefficient >= 0.25 -> 0.20f
    else -> 0f
}

private class ParsedShape(val view: MapView, val group: String?, val path: Path)

private val parsed: List<ParsedShape> by lazy {
    MuscleMapData.shapes.map { ParsedShape(it.view, it.group, PathParser().parsePathString(it.pathData).toPath()) }
}

/**
 * Front and back silhouettes shaded by group coefficient (UX 13.3, D26). Decorative for screen readers: the group
 * list next to it is the accessible equivalent (UX 23.2). Tapping a muscle reports its group.
 */
@Composable
fun MuscleMap(coefficients: Map<String, Double>, modifier: Modifier = Modifier, onGroupTap: (String) -> Unit = {}) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = if (dark) Color(0xFF2A3331) else Color(0xFFDAE5E1)
    val idle = if (dark) Color(0xFF3A4643) else Color(0xFFC3D0CB)
    val primary = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (view in MapView.entries) {
            val shapes = remember(view) { parsed.filter { it.view == view } }
            val hitRegions = remember(view) { shapes.filter { it.group != null }.reversed().map { it.group!! to hitRegion(it.path) } }
            Canvas(
                Modifier.weight(1f).aspectRatio(MuscleMapData.VIEWPORT_W / MuscleMapData.VIEWPORT_H).pointerInput(view) {
                    detectTapGestures { o: Offset ->
                        val x = o.x / size.width * MuscleMapData.VIEWPORT_W
                        val y = o.y / size.height * MuscleMapData.VIEWPORT_H
                        hitRegions.firstOrNull { it.second.contains(x.toInt(), y.toInt()) }?.let { onGroupTap(it.first) }
                    }
                },
            ) {
                val s = size.width / MuscleMapData.VIEWPORT_W
                scale(s, s, pivot = Offset.Zero) {
                    for (shape in shapes) {
                        if (shape.group == null) {
                            drawPath(shape.path, base)
                        } else {
                            drawPath(shape.path, idle)
                            val a = shadeAlpha(coefficients[shape.group] ?: 0.0)
                            if (a > 0f) drawPath(shape.path, primary.copy(alpha = a))
                        }
                    }
                }
            }
        }
    }
}

private fun hitRegion(p: Path): android.graphics.Region {
    val ap = p.asAndroidPath()
    val bounds = android.graphics.RectF().also { ap.computeBounds(it, true) }
    val clip = android.graphics.Region(
        kotlin.math.floor(bounds.left).toInt(), kotlin.math.floor(bounds.top).toInt(),
        kotlin.math.ceil(bounds.right).toInt(), kotlin.math.ceil(bounds.bottom).toInt(),
    )
    return android.graphics.Region().apply { setPath(ap, clip) }
}
