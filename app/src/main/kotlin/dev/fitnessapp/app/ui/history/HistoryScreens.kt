package dev.fitnessapp.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.format.SetLines
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.common.showUndo
import dev.fitnessapp.app.ui.live.AddExerciseSheet
import dev.fitnessapp.app.ui.live.PainSheet
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.WellnessLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.repo.HistoryRow
import dev.fitnessapp.data.repo.ReopenResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** The quick check stays editable for 24 h after the session (UX 15.3). */
private const val QUICK_CHECK_EDIT_MS = 24L * 3_600_000L

/** The session name of a general session: its first exercises. */
fun sessionName(c: AppContainer, exerciseIds: List<String>): String {
    val names = exerciseIds.mapNotNull { c.catalog.byId(it)?.nameEn }
    return when {
        names.isEmpty() -> "Empty session"
        names.size <= 2 -> names.joinToString(", ")
        else -> names.take(2).joinToString(", ") + " +" + (names.size - 2)
    }
}

private fun minutes(s: WorkoutSessionEntity): Long? =
    if (s.started_at != null && s.finished_at != null) (s.finished_at!! - s.started_at!!) / 60_000 else null

/** History (UX 15.3): sessions grouped by ISO week, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryRoute(c: AppContainer, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val rows by remember { c.history.observeHistory() }.collectAsState(initial = null)
    Scaffold(topBar = { TopAppBar(title = { Text("History") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        val list = rows
        if (list != null && list.isEmpty()) {
            Text("No sessions yet. Finished and abandoned sessions appear here.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(pad).padding(20.dp))
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            val weeks = list.orEmpty().groupBy { it.session.iso_week }
            weeks.forEach { (week, items) ->
                item(key = "w$week") {
                    Text(
                        "Week ${week.substringAfter("-W").trimStart('0')} · ${items.size} session${if (items.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(items, key = { it.session.id }) { r -> HistoryRowItem(c, r) { onOpen(r.session.id) } }
            }
        }
    }
}

@Composable
private fun HistoryRowItem(c: AppContainer, r: HistoryRow, onClick: () -> Unit) {
    val s = r.session
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        val date = s.started_at?.let { DAY.format(Instant.ofEpochMilli(it).atZone(ZONE)) } ?: s.local_date
        Text("$date · ${sessionName(c, r.exerciseIds)}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val badges = buildList {
            minutes(s)?.let { add("$it min") }
            add("${r.workingSets} working set${if (r.workingSets == 1) "" else "s"}")
            if (s.status == "abandoned") add("abandoned")
            if (r.pain) add("pain")
            if (s.kind == "home") add("home")
            if (s.kind == "test") add("test")
        }
        Text(badges.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

private sealed interface DetailSheet {
    data class Edit(val setId: String) : DetailSheet
    data class Add(val sessionExerciseId: String) : DetailSheet
    data object AddExercise : DetailSheet
    data object Effort : DetailSheet
    data object Pain : DetailSheet
    data object Note : DetailSheet
}

/**
 * Quick check (UX 15.3): the same rows before and after the 24 h edit window; only the links and the "Editable until"
 * hint go away when it closes. Pain is add-only here (a report is retracted only by its 10 s Undo, C33).
 */
@Composable
internal fun QuickCheck(
    srpe: Int?,
    pains: List<WellnessLogEntity>,
    note: String?,
    editableUntil: Long?,
    onEffort: () -> Unit,
    onPain: () -> Unit,
    onNote: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(editableUntil) {
        val until = editableUntil ?: return@LaunchedEffect
        if (until > now) {
            delay(until - now)
            now = System.currentTimeMillis()
        }
    }
    val editable = editableUntil != null && now < editableUntil
    SectionLabel("Quick check")
    CheckRow("Session effort: " + (srpe?.toString() ?: "not answered"), "Change".takeIf { editable }, onEffort)
    val painLines = pains.map { "Pain: ${it.pain_body_region?.replace('_', ' ')} (${it.pain_severity})" }.ifEmpty { listOf("Pain: none") }
    painLines.forEachIndexed { i, line -> CheckRow(line, "Add pain".takeIf { editable && i == painLines.lastIndex }, onPain) }
    CheckRow("Note: " + (note ?: "none"), "Change".takeIf { editable }, onNote)
    if (editable && editableUntil != null) {
        val at = Instant.ofEpochMilli(editableUntil).atZone(ZONE)
        Text(
            "Editable until ${DAY.format(at)} ${TIME.format(at)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(text: String, link: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (link != null) TextButton(onClick = onClick) { Text(link) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortSheet(current: Int?, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Whole session effort, 0-10", style = MaterialTheme.typography.titleMedium)
            ChoiceChips((0..10).toList(), current, { it.toString() }, onSelect = onPick)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteSheet(current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(2000) }, label = { Text("Note") },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Save") }
        }
    }
}

/** Session detail (UX 15.3, D28): targets vs actuals, the quick check, edits and the session actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailRoute(c: AppContainer, sessionId: String, onBack: () -> Unit, onOpenExercise: (String) -> Unit, onReopened: (String) -> Unit) {
    val session by remember(sessionId) { c.sessionRepository.observeSession(sessionId) }.collectAsState(initial = null)
    val exercises by remember(sessionId) { c.sessionRepository.observeExercises(sessionId) }.collectAsState(initial = emptyList())
    val sets by remember(sessionId) { c.sessionRepository.observeSets(sessionId) }.collectAsState(initial = emptyList())
    val wellness by remember(sessionId) { c.history.observeWellness(sessionId) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var sheet by remember { mutableStateOf<DetailSheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        val s = c.sessionRepository.session(sessionId) ?: return@LaunchedEffect
        val days = ChronoUnit.DAYS.between(LocalDate.parse(s.local_date), LocalDate.now(ZONE)).toInt()
        c.history.opened(sessionId, days)
    }
    // Delete: Undo for 10 s (UX 15.3), then back to History.
    LaunchedEffect(deleted) {
        val ev = deleted ?: return@LaunchedEffect
        val at = System.currentTimeMillis()
        if (snackbar.showUndo("Session deleted", context, sessionLevel = true) == SnackbarResult.ActionPerformed) {
            c.history.restoreSession(sessionId, ev, System.currentTimeMillis() - at)
            deleted = null
        } else {
            onBack()
        }
    }

    val s = session
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s?.started_at?.let { DAY.format(Instant.ofEpochMilli(it).atZone(ZONE)) } ?: "Session") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Session actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (s?.status == "abandoned") DropdownMenuItem(text = { Text("Reopen session") }, onClick = {
                            menu = false
                            scope.launch {
                                message = when (c.history.reopen(sessionId)) {
                                    ReopenResult.REOPENED -> { onReopened(sessionId); null }
                                    ReopenResult.OTHER_DAY -> "Only a session from today can be reopened."
                                    ReopenResult.ANOTHER_ACTIVE -> "Finish the open session first."
                                    ReopenResult.NOT_ABANDONED -> null
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text("Add exercise") }, onClick = { menu = false; sheet = DetailSheet.AddExercise })
                        DropdownMenuItem(text = { Text("Delete session") }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (s == null) return@Scaffold
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val live = sets.filter { it.deleted_at == null }
            val working = live.filter { it.set_kind != "warmup" }.sumOf { if (it.side == "both") 2 else 1 } / 2
            val time = listOfNotNull(s.started_at, s.finished_at).joinToString("-") { TIME.format(Instant.ofEpochMilli(it).atZone(ZONE)) }
            Text(
                listOfNotNull(time.ifEmpty { null }, minutes(s)?.let { "$it min" }, "$working working sets", if (s.status == "abandoned") "abandoned" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Text("Edits update future recommendations.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            exercises.filter { it.status != "removed" }.forEach { se ->
                ExerciseBlock(c, se, live.filter { it.session_exercise_id == se.id }, onOpenExercise, onEdit = { sheet = DetailSheet.Edit(it) }, onAdd = { sheet = DetailSheet.Add(se.id) })
            }
            OutlinedButton(onClick = { sheet = DetailSheet.AddExercise }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Filled.Add, null)
                Text("Add a forgotten exercise")
            }

            QuickCheck(
                srpe = wellness.firstOrNull { it.kind == "srpe" }?.srpe,
                pains = wellness.filter { it.kind == "pain" },
                note = s.notes?.takeIf { it.isNotBlank() },
                editableUntil = (s.finished_at ?: s.started_at)?.let { it + QUICK_CHECK_EDIT_MS },
                onEffort = { sheet = DetailSheet.Effort },
                onPain = { sheet = DetailSheet.Pain },
                onNote = { sheet = DetailSheet.Note },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this session?") },
        text = { Text("It disappears from History and from future recommendations. You can undo for 10 seconds.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { deleted = c.history.deleteSession(sessionId) } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )

    when (val sh = sheet) {
        is DetailSheet.Edit -> sets.firstOrNull { it.id == sh.setId }?.let { set ->
            val se = exercises.firstOrNull { it.id == set.session_exercise_id }
            if (s != null && se != null) SetEditSheet(c, s, se, set, onDismiss = { sheet = null }, onDeleted = { ev ->
                sheet = null
                scope.launch {
                    val at = System.currentTimeMillis()
                    val r = snackbar.showUndo("Set ${set.set_index} deleted", context)
                    if (r == SnackbarResult.ActionPerformed) c.history.restoreSet(set.id, ev, System.currentTimeMillis() - at)
                }
            })
        }
        is DetailSheet.Add -> exercises.firstOrNull { it.id == sh.sessionExerciseId }?.let { se ->
            if (s != null) SetEditSheet(c, s, se, null, onDismiss = { sheet = null }, onDeleted = { sheet = null })
        }
        DetailSheet.AddExercise -> AddExerciseSheet(c, onDismiss = { sheet = null }) { ex ->
            scope.launch {
                val id = c.history.addExercise(sessionId, ex)
                sheet = DetailSheet.Add(id)
            }
        }
        DetailSheet.Effort -> EffortSheet(wellness.firstOrNull { it.kind == "srpe" }?.srpe, onDismiss = { sheet = null }) { v ->
            sheet = null
            scope.launch { c.history.editSrpe(sessionId, v) }
        }
        DetailSheet.Pain -> PainSheet(onDismiss = { sheet = null }) { region, side, severity ->
            sheet = null
            scope.launch { c.sessionRepository.reportPain(sessionId, null, region, side, severity, "post", "history") }
        }
        DetailSheet.Note -> NoteSheet(session?.notes, onDismiss = { sheet = null }) { text ->
            sheet = null
            scope.launch { c.history.editNote(sessionId, text) }
        }
        null -> {}
    }
}

@Composable
private fun ExerciseBlock(
    c: AppContainer,
    se: SessionExerciseEntity,
    sets: List<SetLogEntity>,
    onOpenExercise: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val ex = c.catalog.byId(se.exercise_id)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ex?.nameEn ?: "Exercise", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).clickable { onOpenExercise(se.exercise_id) }.padding(vertical = 4.dp),
            )
            val status = when (se.status) {
                "skipped" -> "skipped" + (se.status_reason_code?.let { " · " + reasonLabel(c, it) } ?: "")
                "not_done" -> "not done"
                else -> null
            }
            status?.let { AssistChip(onClick = {}, label = { Text(it) }) }
        }
        val ordered = sets.sortedWith(compareBy({ it.set_kind != "warmup" }, { it.set_index }, { it.side }, { it.completed_at }))
        var working = 0
        ordered.forEach { s ->
            val label = when {
                s.set_kind == "warmup" -> "Warm-up"
                s.side == "R" -> "Set $working R"
                else -> { working++; "Set $working" + if (s.side == "L") " L" else "" }
            }
            SetRow(c, label, s) { onEdit(s.id) }
        }
        TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Add, null)
            Text("Add a set")
        }
        HorizontalDivider()
    }
}

@Composable
private fun SetRow(c: AppContainer, label: String, s: SetLogEntity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp).weight(0.28f))
        Column(Modifier.weight(0.72f)) {
            val main = listOfNotNull(SetLines.load(s)?.takeIf { s.load_mode != "bodyweight" || s.reps == null }, SetLines.perf(s)).joinToString(" × ")
            Text(main, style = MaterialTheme.typography.bodyLarge)
            val planned = s.planned_reps?.let { p -> if (s.reps != null && s.reps != p) "target $p" else null }
            val details = listOfNotNull(
                SetLines.effort(s), planned,
                s.deviation_reason_code?.let { reasonLabel(c, it) },
                // A reason that implies its flag (torso rotation, technique breakdown) is shown once.
                s.flags.filter { it != s.deviation_reason_code }.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.replace('_', ' ') },
                s.rest_before_s?.let { "rest " + Numbers.clock((it / 10)) },
            )
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun reasonLabel(c: AppContainer, code: String): String = c.reasons.get(code)?.labelEn ?: code.replace('_', ' ')
