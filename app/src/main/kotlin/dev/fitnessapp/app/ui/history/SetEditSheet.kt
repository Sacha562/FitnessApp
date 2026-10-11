package dev.fitnessapp.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.format.SetLines
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.common.StepperRow
import dev.fitnessapp.app.uidet.SnapResult
import dev.fitnessapp.app.uidet.UiPrefill
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.repo.SetEdit
import kotlinx.coroutines.launch

/** Felt chips of a hold (seconds in reserve, D9); "Easy" is a lower bound. */
private val FELT = listOf("Max" to 0.0, "Hard" to 2.0, "OK" to 5.0, "Easy" to 10.0)

/**
 * Set edit sheet (UX 16.14) for a logged set, or for a forgotten set when [set] is null: steppers for load, reps or
 * seconds; RIR or felt chips that select (not log) the effort; reason chips; the exercise's flags; "Delete set"
 * (with Undo on the screen) and "Save". The load is only rewritten when it was changed, so a load that is no longer
 * loadable after an equipment change stays as logged.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SetEditSheet(
    c: AppContainer,
    session: WorkoutSessionEntity,
    se: SessionExerciseEntity,
    set: SetLogEntity?,
    onDismiss: () -> Unit,
    onDeleted: (eventId: String) -> Unit,
) {
    val ex = c.catalog.byId(se.exercise_id) ?: return
    val scope = rememberCoroutineScope()
    val params = remember { c.params.fork() }
    val mode = LoadMode.of(set?.load_mode ?: se.load_mode)
    val isHold = ex.measure == "duration"
    val unilateral = se.side_mode != "bilateral"
    var points by remember { mutableStateOf<List<LoadPoint>?>(null) }
    var scheme by remember { mutableStateOf<LoadScheme?>(null) }
    var index by remember { mutableStateOf<Int?>(null) }
    var loadTouched by remember { mutableStateOf(false) }
    var reps by remember { mutableStateOf(set?.reps ?: if (isHold) null else 8) }
    var holdS by remember { mutableStateOf(set?.duration_s?.let { Scaled.toDouble(it, 1) } ?: if (isHold) 30.0 else null) }
    var rir by remember { mutableStateOf(set?.rir_reported?.let { Scaled.toDouble(it, 1).toInt() }) }
    var felt by remember { mutableStateOf(set?.hold_reserve_s?.let { Scaled.toDouble(it, 1) }) }
    var reason by remember { mutableStateOf(set?.deviation_reason_code) }
    var flags by remember { mutableStateOf(set?.flags.orEmpty().toSet()) }
    var side by remember { mutableStateOf(if (unilateral) "L" else "both") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(se.id, set?.id) {
        val bw = session.bodyweight_kg?.let { Scaled.toDouble(it, 2) }
        val (resolved, p) = c.equipment.loadables(ex, mode, bw, params)
        scheme = resolved.scheme
        points = p
        val logged = set?.let { s -> (s.assist_pin_native ?: s.input_value)?.let { Scaled.toDouble(it, 2) } }
        index = when {
            p.isEmpty() -> null
            mode == LoadMode.BODYWEIGHT -> 0
            set == null -> {
                // A new set starts from the exercise's last logged load in this session, else the lightest.
                val last = c.sessionRepository.sets(session.id).filter { it.session_exercise_id == se.id }.maxByOrNull { it.completed_at }
                val n = last?.let { (it.assist_pin_native ?: it.input_value)?.let { v -> Scaled.toDouble(v, 2) } }
                n?.let { v -> p.indexOfFirst { (it.assistPinNative ?: it.nativeValue) == v }.takeIf { it >= 0 } } ?: 0
            }
            else -> logged?.let { v -> p.indexOfFirst { (it.assistPinNative ?: it.nativeValue) == v }.takeIf { it >= 0 } }
        }
        if (set == null) loadTouched = true
    }

    fun step(harder: Boolean) {
        val p = points ?: return
        if (p.isEmpty()) return
        val current = index?.let { p[it] } ?: run {
            // The logged load is not loadable any more: start from the nearest loadable value.
            val logged = set?.let { s -> (s.assist_pin_native ?: s.input_value)?.let { Scaled.toDouble(it, 2) } } ?: return@run p.first()
            val sc = scheme ?: return@run p.first()
            when (val r = UiPrefill.snap(logged, sc, p.first().implementCount ?: 1, p, params)) {
                is SnapResult.Snapped -> r.point
                is SnapResult.OutOfRange -> r.limit
            }
        }
        index = p.indexOf(UiPrefill.stepLoad(p, current, harder, params))
        loadTouched = true
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text((if (set == null) "Add a set · " else "Edit set · ") + ex.nameEn, style = MaterialTheme.typography.titleMedium)
            if (set == null && unilateral) ChoiceChips(listOf("L", "R"), side, { if (it == "L") "Left" else "Right" }) { side = it }

            val p = points
            when {
                mode == LoadMode.BODYWEIGHT -> Text("Bodyweight", style = MaterialTheme.typography.titleLarge)
                p == null -> Text("…")
                p.isEmpty() -> Text(set?.let { SetLines.load(it) } ?: "No loadable values known", style = MaterialTheme.typography.titleLarge)
                else -> {
                    val assisted = mode == LoadMode.ASSISTED_MACHINE
                    val logged = set?.let { SetLines.load(it) }
                    val label = index?.let { Numbers.loadMain(p[it], mode) }
                        ?: logged?.let { "$it (as logged)" }
                        ?: if (set != null) "No weight logged" else "—"
                    StepperRow(
                        value = label, secondary = index?.let { Numbers.loadSecondary(p[it], mode, unilateral, session.bodyweight_kg?.let { b -> Scaled.toDouble(b, 2) }) },
                        onMinus = { step(harder = assisted) }, onPlus = { step(harder = !assisted) },
                        minusLabel = if (assisted) "harder" else null, plusLabel = if (assisted) "easier" else null,
                    )
                }
            }
            if (isHold) {
                StepperRow(value = Numbers.oneDecimal(holdS ?: 0.0) + " s", secondary = null, onMinus = { holdS = maxOf(0.0, (holdS ?: 0.0) - 1.0) }, onPlus = { holdS = (holdS ?: 0.0) + 1.0 })
                SectionLabel("How did it feel?")
                ChoiceChips(FELT.map { it.second }, felt, { v -> FELT.first { it.second == v }.first }) { felt = if (felt == it) null else it }
            } else {
                StepperRow(value = "${reps ?: 0} reps", secondary = null, onMinus = { reps = UiPrefill.stepReps(reps ?: 0, false) }, onPlus = { reps = UiPrefill.stepReps(reps ?: 0, true) })
                if (set?.set_kind != "warmup") {
                    SectionLabel("Reps left in the tank")
                    ChoiceChips((0..5).toList(), rir, { if (it == 5) "5+" else it.toString() }) { rir = if (rir == it) null else it }
                }
            }
            SectionLabel("Reason (optional)")
            val reasons = c.reasons.rows.filter { r -> r.active && r.uiContexts.any { it == "easier" || it == "harder" } }
            ChoiceChips(reasons.map { it.code }, reason, { code -> reasons.first { it.code == code }.labelEn }) { reason = if (reason == it) null else it }
            if (ex.flagsOffered.isNotEmpty()) {
                SectionLabel("Flags")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ex.flagsOffered.forEach { f ->
                        FilterChip(selected = f in flags, onClick = { flags = if (f in flags) flags - f else flags + f }, label = { Text(f.replace('_', ' ')) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            val canSave = !saving && (if (isHold) (holdS ?: 0.0) > 0.0 else (reps ?: 0) > 0) && (set != null || mode == LoadMode.BODYWEIGHT || index != null)
            Button(
                enabled = canSave,
                onClick = {
                    saving = true
                    val edit = SetEdit(
                        load = if (loadTouched) index?.let { points?.getOrNull(it) } else null,
                        reps = if (isHold) null else reps, durationS = if (isHold) holdS else null,
                        rir = if (isHold) null else rir?.toDouble(), rirLowerBound = !isHold && rir == 5,
                        holdReserveS = if (isHold) felt else null, holdReserveLowerBound = isHold && felt == 10.0,
                        flags = flags.toList(), reason = reason,
                    )
                    scope.launch {
                        if (set == null) c.history.addSetLater(se.id, side, false, edit) else c.history.editSet(set.id, edit, "history")
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (set == null) "Add set" else "Save") }
            if (set != null) {
                OutlinedButton(onClick = {
                    scope.launch { c.history.deleteSet(set.id, "history")?.let(onDeleted) ?: onDismiss() }
                }, modifier = Modifier.fillMaxWidth()) { Text("Delete set") }
            }
            Row { Spacer(Modifier.height(24.dp)) }
        }
    }
}
