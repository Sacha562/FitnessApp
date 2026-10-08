package dev.fitnessapp.app.ui.summary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.live.PainSheet
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pain chips on the summary (UX 12.2); a chip other than None opens the Pain sheet with the region preselected where unambiguous. */
private val PAIN_CHIPS = listOf("none" to "None", "elbow" to "Elbow", "shoulder" to "Shoulder", "wrist" to "Wrist", "knee" to "Knee", "back" to "Back", "other" to "Other")
private val PAIN_PRESELECT = mapOf("shoulder" to "shoulder", "wrist" to "wrist", "knee" to "knee", "other" to "other")

/**
 * Session summary in M1a (UX 12): header, what was logged, and the optional quick check (sRPE 0-10, joint pain,
 * note). "New best" and "Next time" need `ui.progress` and `rx`, which arrive with later milestones.
 */
@Composable
fun SummaryRoute(c: AppContainer, sessionId: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session by remember(sessionId) { c.sessionRepository.observeSession(sessionId) }.collectAsState(initial = null)
    val exercises by remember(sessionId) { c.sessionRepository.observeExercises(sessionId) }.collectAsState(initial = emptyList())
    val sets by remember(sessionId) { c.sessionRepository.observeSets(sessionId) }.collectAsState(initial = emptyList())
    var srpe by rememberSaveable { mutableStateOf<Int?>(null) }
    var pain by rememberSaveable { mutableStateOf<String?>(null) }
    var painSheetFor by remember { mutableStateOf<String?>(null) }
    val painReported = remember { mutableStateListOf<String>() }
    var note by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        c.sessionRepository.event(
            EventDraft(
                type = "session_summary_shown", sessionId = sessionId, screen = "session.summary",
                payload = mapOf("prs" to JsonPrimitive(0), "next_changes_count" to JsonPrimitive(0), "pipeline_ms" to JsonPrimitive(0)),
            ),
        )
    }

    fun done() {
        if (saving) return
        saving = true
        scope.launch {
            val s = session
            c.sessionRepository.saveFeedback(sessionId, srpe, note.trim().takeIf { it.isNotEmpty() && it != s?.notes })
            if (srpe == null) {
                c.sessionRepository.event(
                    EventDraft(type = "prompt_skipped", sessionId = sessionId, screen = "session.summary", payload = mapOf("prompt" to JsonPrimitive("srpe"), "group" to JsonPrimitive("post"), "cause" to JsonPrimitive("not_answered"))),
                )
            }
            onDone()
        }
    }
    BackHandler { done() }

    val zone = ZoneId.of("Europe/Amsterdam")
    val working = sets.filter { it.deleted_at == null && it.set_kind == "working" }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Session done", style = MaterialTheme.typography.headlineMedium)
                val s = session
                if (s != null) {
                    val mins = if (s.started_at != null && s.finished_at != null) "${(s.finished_at!! - s.started_at!!) / 60_000} min · " else ""
                    val date = s.started_at?.let { DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(it).atZone(zone)) } ?: s.local_date
                    Text("$mins${working.size} sets · $date", style = MaterialTheme.typography.bodyLarge)
                }

                if (exercises.isNotEmpty()) SectionLabel("What you did")
                exercises.filter { it.deleted_at == null }.sortedWith(compareBy({ it.position }, { it.id })).forEach { se ->
                    val name = c.catalog.byId(se.exercise_id)?.nameEn ?: "Exercise"
                    val lines = working.filter { it.session_exercise_id == se.id }.sortedWith(compareBy({ it.set_index }, { it.side }, { it.id }))
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (lines.isEmpty()) "No working sets" else lines.joinToString(" · ") { setLine(it) },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionLabel("Quick check (optional)")
                Text("Whole session effort, 0-10", style = MaterialTheme.typography.titleSmall)
                ChoiceChips((0..10).toList(), srpe, { it.toString() }) { srpe = if (srpe == it) null else it }
                Text("Any joint pain?", style = MaterialTheme.typography.titleSmall)
                ChoiceChips(PAIN_CHIPS.map { it.first }, pain, { code -> PAIN_CHIPS.first { it.first == code }.second }) { code ->
                    pain = code
                    if (code != "none") painSheetFor = code
                }
                if (painReported.isNotEmpty()) Text("Pain saved: " + painReported.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(2000) }, label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
            }
            Button(onClick = ::done, enabled = !saving, modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp)) { Text("Done") }
        }
    }

    painSheetFor?.let { chip ->
        PainSheet(onDismiss = { painSheetFor = null }, preselectedRegion = PAIN_PRESELECT[chip]) { region, side, severity ->
            painSheetFor = null
            painReported += "$region ($severity)"
            scope.launch { c.sessionRepository.reportPain(sessionId, null, region, side, severity, "post", "summary") }
        }
    }
}

private fun setLine(s: SetLogEntity): String {
    val side = if (s.side == "L" || s.side == "R") "${s.side} " else ""
    val effort = s.reps?.let { "$it" } ?: s.duration_s?.let { Numbers.oneDecimal(Scaled.toDouble(it, 1)) + " s" } ?: "-"
    val load = when {
        s.load_mode == "bodyweight" -> ""
        s.assist_pin_native != null -> " @ pin " + Numbers.num(Scaled.toDouble(s.assist_pin_native!!, 2)) + (s.assist_unit?.let { " $it" } ?: "")
        s.input_value != null -> " @ " + Numbers.num(Scaled.toDouble(s.input_value!!, 2)) + (s.input_unit?.let { " $it" } ?: "")
        s.added_kg != 0L -> " @ +" + Numbers.kg(Scaled.toDouble(s.added_kg, 2))
        else -> ""
    }
    return side + effort + load
}
