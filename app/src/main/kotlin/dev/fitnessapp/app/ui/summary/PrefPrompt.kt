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
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.repo.PreferenceRepository
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The prompt id of PREF-1's "Lower your preference?" (11.3) in `prompt_shown` / `prompt_answered`. */
private const val PROMPT = "pref_lower"

/**
 * PREF-1's prompt on the summary (11.3, D53): after repeated swap-aways with a reason that is about the exercise, offer
 * one step down for one exercise of this session. It never changes anything by itself; an answer (lower or keep)
 * silences the prompt for that exercise for `pref.prompt_window_d`.
 */
@Composable
fun PrefPromptTile(c: AppContainer, sessionId: String, localDate: String) {
    val scope = rememberCoroutineScope()
    var target by remember(sessionId) { mutableStateOf<Pair<CatalogExercise, String>?>(null) }
    var shownAt by remember { mutableStateOf(0L) }
    LaunchedEffect(sessionId) {
        target = runCatching { pick(c, sessionId, LocalDate.parse(localDate)) }.getOrNull()
        target?.let { (ex, _) ->
            shownAt = System.currentTimeMillis()
            c.sessionRepository.event(
                EventDraft(
                    type = "prompt_shown", sessionId = sessionId, screen = "session.summary", entityType = "exercise", entityId = c.catalog.idOf(ex),
                    payload = mapOf("prompt" to JsonPrimitive(PROMPT), "phase" to JsonPrimitive("post"), "position" to JsonPrimitive(2), "budget_used" to JsonPrimitive(2), "reduced" to JsonPrimitive(false)),
                ),
            )
        }
    }
    val (ex, current) = target ?: return
    val lower = lowerOf(current)
    fun answer(value: String) {
        target = null
        scope.launch {
            val id = c.catalog.idOf(ex)
            c.sessionRepository.event(
                EventDraft(
                    type = "prompt_answered", sessionId = sessionId, screen = "session.summary", entityType = "exercise", entityId = id,
                    payload = mapOf("prompt" to JsonPrimitive(PROMPT), "group" to JsonPrimitive("post"), "value_code" to JsonPrimitive(value), "latency_ms" to JsonPrimitive(System.currentTimeMillis() - shownAt)),
                ),
            )
            if (value == "lower") c.preferences.set(id, lower, "session")
        }
    }
    Tile(spacing = 6.dp) {
        TileLabel("Preference")
        Text("You swapped away from ${ex.nameEn} several times lately. Lower your preference to ${lower.replaceFirstChar { it.uppercase() }}?", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkButton("Lower it", { answer("lower") })
            LinkButton("Keep", { answer("keep") }, color = Fit.Muted)
        }
    }
}

private fun lowerOf(p: String): String = PreferenceRepository.STATES.getOrNull(PreferenceRepository.STATES.indexOf(p) + 1)?.takeIf { it != "never" } ?: "dislike"

/** The first due exercise (by name) that was in this session and has no answer within the prompt window. */
private suspend fun pick(c: AppContainer, sessionId: String, today: LocalDate): Pair<CatalogExercise, String>? {
    val due = c.selection.prompts(today).toSet()
    if (due.isEmpty()) return null
    val window = c.params.fork().int("pref.prompt_window_d")
    val zone = ZoneId.of("Europe/Amsterdam")
    val answered = c.sessionRepository.eventsOfTypes(listOf("prompt_answered"))
        .filter { it.payload.contains("\"$PROMPT\"") && Week.calendarDays(Instant.ofEpochMilli(it.occurred_at).atZone(zone).toLocalDate(), today) < window }
        .mapNotNull { it.entity_id }.toSet()
    val inSession = c.sessionRepository.exercises(sessionId).map { it.exercise_id }.toSet()
    val prefs = c.selection.prefs(today)
    return due.mapNotNull { c.catalog.bySlug(it) }
        .filter { c.catalog.idOf(it) in inSession && c.catalog.idOf(it) !in answered }
        .minByOrNull { it.nameEn }?.let { it to (prefs[it.slug]?.tier?.code ?: PreferenceRepository.NEUTRAL) }
}
