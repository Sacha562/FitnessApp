package dev.fitnessapp.app.ui.exercises

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.engine.E1rmPoint

/** One drawable point: added (or entered) kg of the SSE-1 level and its 80 % interval. */
private data class TrendPoint(val atMs: Long, val m: Double, val lo: Double, val hi: Double)

/**
 * The exercise detail's e1RM trend (UX 13.3 History, ALGORITHMS_PLANNING 19.6 band): SSE-1's logged level after each
 * session with its 80 % interval, over the last `ui.progress.window_weeks`. Nothing is recomputed (C3): the values
 * are the snapshot's, shifted to the added load with that session's bodyweight snapshot (`M - f_bw * BW - base`).
 * A bodyweight exercise shows a sign (assisted levels are negative); an external load shows the entered weight.
 */
@Composable
fun E1rmTrend(points: List<E1rmPoint>, fBw: Double, implementBaseKg: Double, windowWeeks: Int, bandOpacity: Float) {
    fun shift(p: E1rmPoint, x: Double): Double? =
        if (fBw == 0.0) x - implementBaseKg else p.bodyweightKg?.let { x - fBw * it - implementBaseKg }
    val latest = points.maxOfOrNull { it.startedAtMs } ?: return
    val from = latest - windowWeeks * 7L * 86_400_000L
    val sides = points.filter { it.startedAtMs >= from }.groupBy { it.side }.toSortedMap().mapValues { (_, ps) ->
        ps.mapNotNull { p ->
            val m = shift(p, p.mKg) ?: return@mapNotNull null
            TrendPoint(p.startedAtMs, m, shift(p, p.ci80LoKg)!!, shift(p, p.ci80HiKg)!!)
        }
    }.filterValues { it.isNotEmpty() }
    if (sides.isEmpty()) return

    fun kg(x: Double) = if (fBw == 0.0) Numbers.num(x, 1) + " kg" else (if (x >= 0) "+" else "−") + Numbers.num(kotlin.math.abs(x), 1) + " kg"
    val line = sides.entries.joinToString(" · ") { (side, ps) ->
        val p = ps.last()
        val label = when (side) { "L" -> "Left "; "R" -> "Right "; else -> "" }
        "$label${kg(p.m)} (${kg(p.lo)} to ${kg(p.hi)})"
    }
    Column(Modifier.padding(bottom = 8.dp)) {
        Text("Estimated 1RM $line", style = MaterialTheme.typography.bodyMedium)
        val all = sides.values.flatten()
        val minY = all.minOf { it.lo }
        val maxY = all.maxOf { it.hi }
        val minX = all.minOf { it.atMs }
        val maxX = all.maxOf { it.atMs }
        Canvas(
            Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp).semantics { contentDescription = "Estimated 1RM trend: $line" },
        ) {
            fun x(ms: Long) = if (maxX == minX) size.width / 2 else (ms - minX).toFloat() / (maxX - minX) * size.width
            fun y(v: Double) = if (maxY == minY) size.height / 2 else (size.height - ((v - minY) / (maxY - minY) * size.height)).toFloat()
            sides.values.forEachIndexed { i, ps ->
                val color = if (i == 0) Fit.Accent else Fit.Ink
                if (ps.size > 1) {
                    val band = Path().apply {
                        moveTo(x(ps.first().atMs), y(ps.first().hi))
                        ps.drop(1).forEach { lineTo(x(it.atMs), y(it.hi)) }
                        ps.reversed().forEach { lineTo(x(it.atMs), y(it.lo)) }
                        close()
                    }
                    drawPath(band, color.copy(alpha = bandOpacity))
                    val path = Path().apply {
                        moveTo(x(ps.first().atMs), y(ps.first().m))
                        ps.drop(1).forEach { lineTo(x(it.atMs), y(it.m)) }
                    }
                    drawPath(path, color, style = Stroke(2.dp.toPx()))
                } else {
                    drawLine(color.copy(alpha = bandOpacity * 3), Offset(x(ps[0].atMs), y(ps[0].hi)), Offset(x(ps[0].atMs), y(ps[0].lo)), 6.dp.toPx())
                }
                ps.forEach { drawCircle(color, 3.dp.toPx(), Offset(x(it.atMs), y(it.m))) }
            }
        }
    }
}
