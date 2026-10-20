package dev.fitnessapp.app.ui.summary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.ui.live.PainSheet
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
 * Session summary in M1a (UX 12), as Bento tiles (D31): header, duration and set tiles, what was logged, and the optional quick check (sRPE 0-10, joint pain,
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
                payload = mapOf("prs" to kotlinx.serialization.json.JsonArray(emptyList()), "next_changes_count" to JsonPrimitive(0), "pipeline_ms" to JsonPrimitive(0)),
            ),
        )
        // UX 20.5: the session-effort prompt of the quick check (the only post-session prompt shown in M1).
        c.sessionRepository.event(
            EventDraft(
                type = "prompt_shown", sessionId = sessionId, screen = "session.summary",
                payload = mapOf(
                    "prompt" to JsonPrimitive("srpe"), "phase" to JsonPrimitive("post"), "position" to JsonPrimitive(1),
                    "budget_used" to JsonPrimitive(1), "reduced" to JsonPrimitive(false),
                ),
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
    Surface(Modifier.fillMaxSize(), color = Fit.Ground) {
        Column(Modifier.safeDrawingPadding().fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val s = session
                val date = s?.started_at?.let { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH).format(Instant.ofEpochMilli(it).atZone(zone)) } ?: s?.local_date
                Column {
                    if (date != null) Text(date, style = MaterialTheme.typography.titleSmall, color = Fit.Muted)
                    Text("Session done", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                }
                if (s != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val mins = if (s.started_at != null && s.finished_at != null) ((s.finished_at!! - s.started_at!!) / 60_000).toString() else "—"
                        StatTile("Duration", mins, "min", Modifier.weight(1f))
                        StatTile("Working sets", working.size.toString(), if (working.size == 1) "set" else "sets", Modifier.weight(1f))
                    }
                }

                val done = exercises.filter { it.deleted_at == null }.sortedWith(compareBy({ it.position }, { it.id }))
                if (done.isNotEmpty()) {
                    Tile(spacing = 0.dp, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
                        TileLabel("What you did", Modifier.padding(bottom = 6.dp))
                        done.forEachIndexed { i, se ->
                            if (i > 0) TileDivider()
                            val name = c.catalog.byId(se.exercise_id)?.nameEn ?: "Exercise"
                            val lines = working.filter { it.session_exercise_id == se.id }.sortedWith(compareBy({ it.set_index }, { it.side }, { it.id }))
                            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (lines.isEmpty()) "No working sets" else lines.joinToString(" · ") { setLine(it) },
                                    style = MaterialTheme.typography.bodyMedium, color = Fit.Muted,
                                )
                            }
                        }
                    }
                }

                Tile(spacing = 10.dp) {
                    TileLabel("Quick check (optional)")
                    Text("Whole session effort, 0-10", style = MaterialTheme.typography.titleMedium)
                    ChoiceChips((0..10).toList(), srpe, { it.toString() }) { srpe = if (srpe == it) null else it }
                    Text("Any joint pain?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                    ChoiceChips(PAIN_CHIPS.map { it.first }, pain, { code -> PAIN_CHIPS.first { it.first == code }.second }) { code ->
                        pain = code
                        if (code != "none") painSheetFor = code
                    }
                    if (painReported.isNotEmpty()) Text("Pain saved: " + painReported.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Fit.Safety)
                    FitTextField(
                        value = note, onValueChange = { note = it.take(2000) }, label = "Note",
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp), minLines = 2,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                PrimaryButton("Done", ::done, Modifier.fillMaxWidth(), enabled = !saving)
            }
        }
    }

    painSheetFor?.let { chip ->
        PainSheet(onDismiss = { painSheetFor = null }, preselectedRegion = PAIN_PRESELECT[chip]) { region, side, severity, symptoms ->
            painSheetFor = null
            painReported += "$region ($severity)"
            scope.launch {
                val wid = c.sessionRepository.reportPain(sessionId, null, region, side, severity, "post", "summary", symptoms)
                runCatching { c.challengeSessions.forwardPain(wid, region, side, severity, symptoms, sessionId) }
            }
        }
    }
}

private fun setLine(s: SetLogEntity): String = dev.fitnessapp.app.format.SetLines.line(s)

/** A half-width stat tile: label, big number, unit. */
@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier) {
    Tile(modifier, radius = 22.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), spacing = 2.dp) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
