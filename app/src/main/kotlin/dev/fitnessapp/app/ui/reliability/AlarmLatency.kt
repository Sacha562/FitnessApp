package dev.fitnessapp.app.ui.reliability

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.session.FgsType
import dev.fitnessapp.app.ui.common.MoreSection
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.theme.Fit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.ceil
import kotlin.math.floor

enum class Level { GREEN, AMBER, RED }

/** p50 / p95 / max of the last alarms and the ANDROID.md 15.5 status. */
data class LatencyStats(val n: Int, val p50: Long?, val p95: Long?, val max: Long?, val recoveredDead: Int, val level: Level)

/**
 * ANDROID.md 15.5 rest-alarm latency of the last [window] `rest_alarm_fired` events: amber when p95 > `amberMs`, red
 * when p95 > `redMs` or a timer was recovered as `elapsed_while_dead` in the last 14 days. Percentiles interpolate
 * linearly, as `percentile_cont` in `v_rest_alarm_latency` does, so the screen and the review agree.
 */
object AlarmLatency {
    const val WINDOW = 30
    const val DEAD_LOOKBACK_DAYS = 14

    fun percentile(sorted: List<Long>, q: Double): Long {
        val pos = (sorted.size - 1) * q
        val lo = sorted[floor(pos).toInt()]
        val hi = sorted[ceil(pos).toInt()]
        return Math.round(lo + (hi - lo) * (pos - floor(pos)))
    }

    fun stats(latencies: List<Long>, recoveredDead: Int, amberMs: Long, redMs: Long): LatencyStats {
        if (latencies.isEmpty()) return LatencyStats(0, null, null, null, recoveredDead, if (recoveredDead > 0) Level.RED else Level.GREEN)
        val s = latencies.sorted()
        val p95 = percentile(s, 0.95)
        val level = when {
            recoveredDead > 0 || p95 > redMs -> Level.RED
            p95 > amberMs -> Level.AMBER
            else -> Level.GREEN
        }
        return LatencyStats(s.size, percentile(s, 0.5), p95, s.last(), recoveredDead, level)
    }

    fun latencyOf(payload: String): Long? = runCatching { Json.parseToJsonElement(payload).jsonObject["latency_ms"]?.jsonPrimitive?.longOrNull }.getOrNull()
}

/** The You > Rest timer reliability statistics rows (ANDROID.md 15.5). */
@Composable
fun AlarmLatencyCard(c: AppContainer) {
    val events by remember { c.db.events().observeLatest("rest_alarm_fired", AlarmLatency.WINDOW) }.collectAsState(initial = emptyList())
    val since = remember { System.currentTimeMillis() - AlarmLatency.DEAD_LOOKBACK_DAYS * 86_400_000L }
    val dead by remember { c.db.restTimers().observeRecoveredDeadSince(since) }.collectAsState(initial = 0)
    val stats = AlarmLatency.stats(events.mapNotNull { AlarmLatency.latencyOf(it.payload) }, dead, c.platformConfig.reliabilityLatencyAmberMs, c.platformConfig.reliabilityLatencyRedMs)
    Tile(spacing = 6.dp) {
        run {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (icon, tint, label) = when (stats.level) {
                    Level.GREEN -> Triple(Icons.Rounded.CheckCircle, Fit.Accent, "On time")
                    Level.AMBER -> Triple(Icons.Rounded.Warning, Fit.Advisory, "Sometimes late")
                    Level.RED -> Triple(Icons.Rounded.Error, Fit.Safety, "Unreliable")
                }
                Icon(icon, contentDescription = label, tint = tint)
                Text("Rest alarm timing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                Pill(label, content = tint)
            }
            if (stats.n == 0) {
                Text("No rest alarms measured yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "Last ${stats.n} alarms: usually ${ms(stats.p50!!)} late, slowest ${ms(stats.max!!)}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (stats.recoveredDead > 0) {
                Text(
                    "${stats.recoveredDead} rest${if (stats.recoveredDead == 1) "" else "s"} ended while the app was stopped (last 14 days).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val fgs = FgsType.code(Build.VERSION.SDK_INT, c.device.activityRecognitionGranted())
            MoreSection("Details") {
                Text(
                    "Background mode: " + when (fgs) {
                        "health" -> "fitness"
                        "special_use" -> "basic (allow Physical activity for fitness mode)"
                        else -> "standard"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
                )
            }
        }
    }
}

private fun ms(v: Long): String = if (v < 1000) "$v ms" else String.format(java.util.Locale.ROOT, "%.1f s", v / 1000.0)
