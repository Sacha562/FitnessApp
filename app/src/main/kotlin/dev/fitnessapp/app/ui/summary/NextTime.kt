package dev.fitnessapp.app.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.prescription.PrescriptionJson
import dev.fitnessapp.algo.prescription.rx.RxOutput
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.format.RxTexts
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.WhyContent
import dev.fitnessapp.app.ui.common.WhySheet
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.engine.RxTarget
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One "Next time" line (UX 12.3): exercise, today's plan, next time's plan, the reason and its Why. */
data class NextLine(val name: String, val from: String, val to: String, val reason: String, val why: WhyContent)

/**
 * Summary "Next time" for the exercises RX-1 prescribed (UX 12.3, M3.4): runs the L8 update of the finished session,
 * then RX-1's preview for each prescribed exercise; only changes are listed. Challenge rule changes stay in the
 * challenge tile.
 */
suspend fun nextTimeLines(c: AppContainer, sessionId: String): List<NextLine> {
    runCatching { c.estimation.run() }
    val session = c.db.sessions().get(sessionId) ?: return emptyList()
    val bw = session.bodyweight_kg?.let { Scaled.toDouble(it, 2) }
    val sets = c.db.setLogs().bySession(sessionId)
    val out = ArrayList<NextLine>()
    for (se in c.db.sessionExercises().bySession(sessionId).filter { it.recommendation_id != null }) {
        if (sets.none { it.session_exercise_id == se.id && it.set_kind != "warmup" }) continue
        val ex = c.catalog.byId(se.exercise_id) ?: continue
        val (now, next) = runCatching { c.prescriptions.preview(se.id) }.getOrNull() ?: continue
        val mode = runCatching { LoadMode.of(se.load_mode) }.getOrNull() ?: continue
        val points = runCatching { c.equipment.loadables(ex, mode, bw, c.params.fork()).second }.getOrDefault(emptyList())
        val side = if (now.sets.any { it.side == "both" }) "both" else "L"
        val from = plan(now.sets.filter { it.side == side }, points, mode)
        val nextSets = next.sets.filter { it.side == side }.map { RxTarget(it.setIndex, it.side, it.targetReps, it.rir, it.wSys, it.rirPred, it.restS) }
        val to = plan(nextSets, points, mode)
        if (from == to) continue
        val reason = RxTexts.reason(next.trace, next.mode.code, next.scheme.qStar, next.probe)
        out += NextLine(ex.nameEn, from, to, reason, why(ex.nameEn, to, reason, next, now.recommendationId))
    }
    return out
}

private fun plan(sets: List<RxTarget>, points: List<LoadPoint>, mode: LoadMode): String {
    if (sets.isEmpty()) return "—"
    val w = sets.first().wSys
    val load = when {
        mode == LoadMode.BODYWEIGHT -> "BW"
        w == null -> "a weight to pick"
        else -> points.firstOrNull { p -> p.wSys != null && kotlin.math.abs(p.wSys!! - w) < 0.005 }?.let { Numbers.loadMain(it, mode) }
            ?: (Numbers.num(w, 2) + " kg")
    }
    return load + " × " + sets.joinToString(" ") { it.targetReps?.toString() ?: "?" }
}

private fun why(name: String, to: String, reason: String, next: RxOutput, todayId: String): WhyContent {
    val outputs = PrescriptionJson.rx(next)
    val trace = PrescriptionJson.rxTrace(next)
    return WhyContent(
        title = "Why $to next time?",
        summary = reason,
        numbers = buildList {
            add("$name: $to")
            add("Range ${next.scheme.rLo}-${next.scheme.rHi} reps, aim ${Numbers.num(next.scheme.qStar, 1)} in reserve")
            next.mUse?.let { add("Strength used  " + Numbers.num(it, 1) + " kg max (cautious estimate)") }
        },
        module = "rx@${dev.fitnessapp.algo.prescription.rx.Rx.ref.version} · preview, made again at the next session",
        evidence = RxTexts.evidence(next.mode.code),
        trace = JsonText.canonical(JsonObject(mapOf("preview_of" to JsonPrimitive(todayId), "outputs" to outputs, "trace" to trace))),
        recommendationId = todayId,
        codes = next.trace,
    )
}

@Composable
fun NextTimeTile(c: AppContainer, sessionId: String, lines: List<NextLine>) {
    var open by remember { mutableStateOf<WhyContent?>(null) }
    Tile(spacing = 0.dp) {
        TileLabel("Next time", Modifier.padding(bottom = 6.dp))
        lines.take(MAX_LINES).forEachIndexed { i, l ->
            if (i > 0) TileDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${l.name}: ${l.from} → ${l.to}", style = MaterialTheme.typography.bodyLarge)
                    Text(l.reason, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                }
                IconButton(onClick = { open = l.why }) { Icon(Icons.Outlined.Info, "Why", tint = Fit.Muted) }
            }
        }
    }
    open?.let { w ->
        WhySheet(
            w, onDismiss = { open = null },
            onShown = {
                c.appScope.launch {
                    c.sessionRepository.event(
                        EventDraft(
                            type = "why_opened", sessionId = sessionId, screen = "session.summary",
                            payload = mapOf("subject_kind" to JsonPrimitive("prescription"), "subject_id" to JsonPrimitive(w.recommendationId), "codes" to JsonArray(w.codes.map { JsonPrimitive(it) })),
                        ),
                    )
                }
            },
            onCopied = {
                c.appScope.launch {
                    c.sessionRepository.event(EventDraft(type = "trace_exported", sessionId = sessionId, screen = "session.summary", payload = mapOf("via" to JsonPrimitive("clipboard"))))
                }
            },
        )
    }
}

/** UX 12.3: at most six lines on the Summary. */
private const val MAX_LINES = 6
