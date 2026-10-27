package dev.fitnessapp.app.ui.summary

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.challenges.ChallengeTexts
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.theme.Fit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Next time" for a challenge session (UX 12, GF-UX-21): what the program's rules changed (or kept, and why) when
 * the slot's `session.completed` was folded, read from that event's rule audit in the current generation.
 */
@Composable
fun ChallengeSummaryTile(c: AppContainer, sessionId: String) {
    var lines by remember(sessionId) { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    LaunchedEffect(sessionId) {
        lines = withContext(Dispatchers.Default) { runCatching { load(c, sessionId) }.getOrNull() }
    }
    val l = lines ?: return
    if (l.isEmpty()) return
    Tile(spacing = 4.dp) {
        TileLabel("Next time")
        l.forEach { (text, muted) -> Text(text, style = MaterialTheme.typography.bodyMedium, color = if (muted) Fit.Muted else Fit.Ink) }
    }
}

private suspend fun load(c: AppContainer, sessionId: String): List<Pair<String, Boolean>> {
    val event = c.db.challengeEvents().bySourceSession(sessionId).lastOrNull { it.event_type == EventTypes.SESSION_COMPLETED } ?: return emptyList()
    val enrollment = c.db.enrollments().get(event.enrollment_id) ?: return emptyList()
    val def = c.challenges.definition(enrollment.definition_id)
    return c.db.ruleEvents().forEvent(event.id, enrollment.fold_generation).mapNotNull { r ->
        val changes = ChallengeTexts.changes(r.applied)
        when (r.outcome) {
            "applied" -> (if (changes.isNotEmpty()) "$changes · " else "") + ChallengeTexts.ruleExplain(def, r.rule_id) to false
            "no_effect", "error" -> null
            else -> "Not applied: ${ChallengeTexts.ruleExplain(def, r.rule_id)} · ${ChallengeTexts.outcome(r.outcome)}" to true
        }
    }
}
