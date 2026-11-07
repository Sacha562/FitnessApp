package dev.fitnessapp.app.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.engine.WarmupHabits
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * D107: after two sessions in a row with all warm-ups of an exercise skipped, or fewer done than planned, offer to plan
 * that count from now on. "Keep" silences the offer for that exercise and count; the exercise page changes it later.
 */
@Composable
fun WarmupPromptTile(c: AppContainer, sessionId: String) {
    val scope = rememberCoroutineScope()
    var offer by remember(sessionId) { mutableStateOf<Pair<WarmupHabits.Proposal, String>?>(null) }
    var shownAt by remember { mutableStateOf(0L) }
    LaunchedEffect(sessionId) {
        offer = runCatching {
            c.warmupHabits.proposal(sessionId)?.let { p -> c.catalog.byId(p.exerciseId)?.let { p to it.nameEn } }
        }.getOrNull()
        offer?.let { (p, _) ->
            shownAt = System.currentTimeMillis()
            c.sessionRepository.event(
                EventDraft(
                    type = "prompt_shown", sessionId = sessionId, screen = "session.summary", entityType = "exercise", entityId = p.exerciseId,
                    payload = mapOf("prompt" to JsonPrimitive(WarmupHabits.PROMPT), "phase" to JsonPrimitive("post"), "position" to JsonPrimitive(3), "budget_used" to JsonPrimitive(3), "reduced" to JsonPrimitive(false)),
                ),
            )
        }
    }
    val (p, name) = offer ?: return
    fun answer(set: Boolean) {
        offer = null
        scope.launch {
            c.sessionRepository.event(
                EventDraft(
                    type = "prompt_answered", sessionId = sessionId, screen = "session.summary", entityType = "exercise", entityId = p.exerciseId,
                    payload = mapOf(
                        "prompt" to JsonPrimitive(WarmupHabits.PROMPT), "group" to JsonPrimitive("post"),
                        "value_code" to JsonPrimitive((if (set) "set_" else "keep_") + p.count), "latency_ms" to JsonPrimitive(System.currentTimeMillis() - shownAt),
                    ),
                ),
            )
            if (set) c.preferences.setWarmupSets(p.exerciseId, p.count, "session")
        }
    }
    Tile(spacing = 6.dp) {
        TileLabel("Warm-ups")
        Text(
            if (p.skippedAll) "You skipped the warm-ups for $name twice. Skip them from now on?"
            else "You did ${p.count} warm-up set${if (p.count == 1) "" else "s"} for $name the last two times. Plan ${p.count} from now on?",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkButton(if (p.skippedAll) "Skip from now on" else "Plan ${p.count}", { answer(true) })
            LinkButton("Keep", { answer(false) }, color = Fit.Muted)
        }
    }
}
