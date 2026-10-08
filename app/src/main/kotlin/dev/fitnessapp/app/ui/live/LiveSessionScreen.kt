package dev.fitnessapp.app.ui.live

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.timer.RestDisplay
import dev.fitnessapp.app.timer.RestTimerReducer
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.common.StepperRow
import dev.fitnessapp.app.ui.common.TabularNums
import dev.fitnessapp.app.ui.theme.AdvisoryAmber
import dev.fitnessapp.app.ui.theme.SafetyRed
import dev.fitnessapp.app.uidet.ChipContext
import dev.fitnessapp.app.uidet.ChipRoute
import dev.fitnessapp.app.uidet.UiReasonChips
import kotlinx.coroutines.delay

private sealed interface Sheet {
    data object Add : Sheet
    data class Skip(val seId: String) : Sheet
    data class Occupied(val seId: String) : Sheet
    data class End(val seId: String) : Sheet
    data object Finish : Sheet
    data object Pain : Sheet
    data object Keypad : Sheet
    data object Abandon : Sheet
}

@Composable
fun LiveSessionRoute(c: AppContainer, sessionId: String, onMinimise: () -> Unit, onFinished: (String) -> Unit, onAbandoned: () -> Unit) {
    val vm: LiveSessionViewModel = viewModel(key = sessionId, factory = viewModelFactory { initializer { LiveSessionViewModel(c, sessionId) } })
    val ui by vm.ui.collectAsState()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val profile by remember { c.profiles.observe() }.collectAsState(initial = null)
    BackHandler(onBack = onMinimise)

    // Keep screen on during the session (F20, ANDROID.md 14.3); holds force it on.
    val view = LocalView.current
    val holding = ui.card?.holdPhase in setOf(HoldPhase.LEAD_IN, HoldPhase.HOLDING)
    DisposableEffect(profile?.keep_screen_on, holding) {
        view.keepScreenOn = (profile?.keep_screen_on ?: true) || holding
        onDispose { view.keepScreenOn = false }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().fillMaxSize()) {
            Header(ui, onMinimise, onFinish = {
                if (vm.openExercises().isEmpty()) vm.finish(null, onFinished) else sheet = Sheet.Finish
            }, onAdd = { sheet = Sheet.Add }, onPain = { sheet = Sheet.Pain }, onAbandon = { sheet = Sheet.Abandon })
            if (ui.banner == "pain") Banner("Pain noted. The exercise ended. If it hurts, stop and get it checked; this app does not give medical advice.", vm::dismissBanner)
            ExerciseList(ui, Modifier.weight(0.38f), onFocus = vm::focus, onAdd = { sheet = Sheet.Add })
            HorizontalDivider()
            Column(Modifier.weight(0.62f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                ui.rest?.let { RestCard(c, it, ui, vm) }
                if (ui.switchHint) Text("Switch to your right side", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                val card = ui.card
                if (card == null) {
                    EmptyAction(ui, onAdd = { sheet = Sheet.Add }, onFinish = { vm.finish(null, onFinished) })
                } else {
                    SetCard(card, vm, onMenu = { action ->
                        when (action) {
                            "skip" -> sheet = Sheet.Skip(card.item.se.id)
                            "end" -> sheet = Sheet.End(card.item.se.id)
                            "pain" -> sheet = Sheet.Pain
                        }
                    }, onKeypad = { sheet = Sheet.Keypad }, onPainReason = { sheet = Sheet.Pain })
                }
            }
            UndoBar(ui, vm)
        }
    }

    when (val s = sheet) {
        Sheet.Add -> AddExerciseSheet(c, onDismiss = { sheet = null }) { ex -> vm.addExercise(ex); sheet = null }
        is Sheet.Skip -> ReasonSheet(c, ChipContext.SKIP, "Skip ${nameOf(ui, s.seId)}?", onDismiss = { sheet = null }) { code, pos ->
            when (UiReasonChips.route(code, ChipContext.SKIP)) {
                ChipRoute.DO_LATER -> sheet = Sheet.Occupied(s.seId)
                ChipRoute.PAIN_SHEET -> { vm.skip(s.seId, code, pos); sheet = Sheet.Pain }
                else -> { vm.skip(s.seId, code, pos); sheet = null }
            }
        }
        is Sheet.Occupied -> OccupiedSheet(nameOf(ui, s.seId), vm.canDoLater(s.seId), onDismiss = { sheet = null },
            onDoLater = { vm.doLater(s.seId); sheet = null }, onSkip = { vm.skip(s.seId, "occupied", 1); sheet = null })
        is Sheet.End -> ReasonSheet(c, ChipContext.SKIP, "End ${nameOf(ui, s.seId)}? Logged sets stay.", onDismiss = { sheet = null }) { code, _ ->
            vm.endExercise(s.seId, code)
            sheet = if (code == "pain") Sheet.Pain else null
        }
        Sheet.Finish -> FinishSheet(c, vm.openExercises().map { it.ex.nameEn }, onDismiss = { sheet = null }) { reason ->
            sheet = null
            vm.finish(reason, onFinished)
        }
        Sheet.Pain -> PainSheet(onDismiss = { sheet = null }) { region, side, severity ->
            vm.reportPain(region, side, severity)
            sheet = null
        }
        Sheet.Keypad -> ui.card?.let { card -> KeypadSheet(card, vm, onDismiss = { sheet = null }) }
        Sheet.Abandon -> AlertDialog(
            onDismissRequest = { sheet = null },
            title = { Text("Abandon session?") },
            text = { Text("Logged sets are kept; the session is marked abandoned.") },
            confirmButton = { TextButton(onClick = { sheet = null; vm.abandon(onAbandoned) }) { Text("Abandon") } },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text("Keep going") } },
        )
        null -> {}
    }
}

private fun nameOf(ui: LiveUi, seId: String) = ui.items.firstOrNull { it.se.id == seId }?.ex?.nameEn ?: "exercise"

@Composable
private fun Header(ui: LiveUi, onMinimise: () -> Unit, onFinish: () -> Unit, onAdd: () -> Unit, onPain: () -> Unit, onAbandon: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    var menu by remember { mutableStateOf(false) }
    val started = ui.session?.started_at ?: now
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMinimise) { Icon(Icons.Filled.KeyboardArrowDown, "Minimise") }
        Text("Free session", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (ui.pendingCount > 0) Icon(Icons.Filled.CloudOff, "Changes waiting to upload", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(Numbers.clock((now - started) / 1000), style = MaterialTheme.typography.titleMedium.merge(TabularNums), modifier = Modifier.padding(horizontal = 8.dp))
        TextButton(onClick = onFinish) { Text("Finish") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Session menu") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Add exercise") }, onClick = { menu = false; onAdd() })
                DropdownMenuItem(text = { Text("Report pain") }, onClick = { menu = false; onPain() })
                DropdownMenuItem(text = { Text("Abandon session") }, onClick = { menu = false; onAbandon() })
            }
        }
    }
    val done = ui.items.sumOf { minOf(it.workingDone, maxOf(it.planned, it.workingDone)) }
    val total = ui.items.filter { it.se.status != "skipped" }.sumOf { maxOf(it.planned, it.workingDone) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.weight(1f).height(8.dp))
        Text("  $done / $total sets", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Banner(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(SafetyRed).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK", color = androidx.compose.ui.graphics.Color.White) }
    }
}

@Composable
private fun ExerciseList(ui: LiveUi, modifier: Modifier, onFocus: (String) -> Unit, onAdd: () -> Unit) {
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        items(ui.items, key = { it.se.id }) { item ->
            val current = item.se.id == ui.focusedId
            val skipped = item.se.status == "skipped"
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !skipped) { onFocus(item.se.id) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    item.se.status == "done" -> Icon(Icons.Filled.CheckCircle, "Done", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    current -> Icon(Icons.Filled.PlayArrow, "Current", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    else -> Icon(Icons.Outlined.Circle, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    item.ex.nameEn + if (item.unilateral) " (L/R)" else "",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        textDecoration = if (skipped) TextDecoration.LineThrough else null,
                    ),
                    modifier = Modifier.weight(1f),
                )
                val tail = when {
                    skipped -> item.se.status_reason_code?.replace('_', ' ') ?: "skipped"
                    item.se.status == "deferred" -> "later · ${item.workingDone}/${item.planned}"
                    else -> "${item.workingDone}/${maxOf(item.planned, item.workingDone)}"
                }
                Text(tail, style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onAdd).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Add exercise", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun EmptyAction(ui: LiveUi, onAdd: () -> Unit, onFinish: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.items.isEmpty()) {
            Text("Add your first exercise", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Add exercise") }
        } else {
            Text("All exercises done", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Finish session") }
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add another exercise") }
        }
    }
}

/** UX 11.13.2: countdown, -15 / Skip / +15 (500 ms input guard), next-set preview; overtime counts up. */
@Composable
private fun RestCard(c: AppContainer, t: dev.fitnessapp.data.db.local.RestTimerEntity, ui: LiveUi, vm: LiveSessionViewModel) {
    var display by remember(t.id) { mutableStateOf<RestDisplay?>(null) }
    val haptic = LocalHapticFeedback.current
    var prewarned by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(t) {
        while (true) {
            val r = RestTimerReducer.remainingMs(t, System.currentTimeMillis(), c.device.elapsedRealtime(), c.device.bootCount())
            display = RestTimerReducer.display(t, System.currentTimeMillis(), c.device.elapsedRealtime(), c.device.bootCount())
            // Pre-warning: one light tick at 10 s left, only while the UI is visible (ANDROID.md 12.4, C72).
            if (!prewarned && t.state == "running" && r in 1..(c.platformConfig.timerPrewarnS * 1000L)) {
                prewarned = true
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            delay(200)
        }
    }
    val shownAt = remember(t.id) { System.currentTimeMillis() }
    val guard = { System.currentTimeMillis() - shownAt > 500 }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("REST", style = MaterialTheme.typography.labelLarge)
            when (val d = display) {
                is RestDisplay.Countdown -> {
                    Text(d.digits, fontSize = 72.sp, style = TabularNums)
                    LinearProgressIndicator(progress = { d.barFraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp))
                }
                is RestDisplay.Overtime -> Text(d.digits, fontSize = 56.sp, color = AdvisoryAmber, style = TabularNums)
                null -> Text(" ", fontSize = 72.sp)
            }
            if (t.alarm_mode == "inexact") Text("Alarm may be late (exact alarms off)", color = AdvisoryAmber, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = t.state == "running"
                FilledTonalButton(onClick = { if (guard()) vm.adjustRest(-c.platformConfig.timerAdjustStepS) }, enabled = running, modifier = Modifier.weight(1f).height(64.dp)) { Text("-15 s") }
                FilledTonalButton(onClick = { if (guard()) vm.skipRest() }, modifier = Modifier.weight(1f).height(64.dp)) { Text("Skip") }
                FilledTonalButton(onClick = { if (guard()) vm.adjustRest(c.platformConfig.timerAdjustStepS) }, enabled = running, modifier = Modifier.weight(1f).height(64.dp)) { Text("+15 s") }
            }
            ui.restNext?.let { Text("Next: $it", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetCard(card: Card, vm: LiveSessionViewModel, onMenu: (String) -> Unit, onKeypad: () -> Unit, onPainReason: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val item = card.item
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.ex.nameEn, style = MaterialTheme.typography.titleLarge)
                val sideLabel = when (card.side) { "L" -> " · LEFT"; "R" -> " · RIGHT"; else -> "" }
                Text(
                    (if (card.warmup) "Warm-up set" else "Set ${card.setNumber} of ${maxOf(item.planned, card.setNumber)}") + sideLabel,
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Exercise menu") }
                ExerciseMenu(menu, card, vm, onDismiss = { menu = false }, onAction = { menu = false; onMenu(it) })
            }
        }
        card.lastLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (card.sideCapApplied) Text("Right ≤ left: ${card.reps} clean reps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (card.deviation.fields.any { it.field == "side_rule" }) Text("Right above left", style = MaterialTheme.typography.bodySmall, color = AdvisoryAmber)

        // Load row (UX 11.7).
        when {
            card.mode == LoadMode.BODYWEIGHT -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BW", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (LoadMode.BODYWEIGHT_PLUS_EXTERNAL.code in item.ex.allowedLoadModes) TextButton(onClick = { vm.setLoadMode(LoadMode.BODYWEIGHT_PLUS_EXTERNAL) }) { Text("+ add weight") }
            }
            card.points.isEmpty() -> Text("No loadable values known for this machine. Set them in You > Equipment.", style = MaterialTheme.typography.bodyMedium, color = AdvisoryAmber)
            else -> {
                val assisted = card.mode == LoadMode.ASSISTED_MACHINE
                StepperRow(
                    value = vm.loadLabel(card), secondary = vm.loadSecondary(card),
                    // Assisted: lower pin = harder; the buttons are labelled by effect (UX 6.1).
                    onMinus = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); if (assisted) vm.stepLoad(harder = true) else vm.stepLoad(harder = false) },
                    onPlus = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); if (assisted) vm.stepLoad(harder = false) else vm.stepLoad(harder = true) },
                    minusLabel = if (assisted) "harder" else null, plusLabel = if (assisted) "easier" else null,
                    onValueClick = onKeypad,
                )
                card.keypadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AdvisoryAmber) }
                if (card.mode == LoadMode.BODYWEIGHT_PLUS_EXTERNAL && LoadMode.BODYWEIGHT.code in item.ex.allowedLoadModes) {
                    TextButton(onClick = { vm.setLoadMode(LoadMode.BODYWEIGHT) }) { Text("Bodyweight only") }
                }
            }
        }

        if (card.isHold) HoldSection(card, vm) else {
            StepperRow(value = "${card.reps ?: 0} reps", secondary = null, onMinus = { vm.stepReps(false) }, onPlus = { vm.stepReps(true) })
        }

        // Reason chips: optional, only when deviating (UX 11.8).
        card.chips?.let { chips ->
            Text("Why the change? (optional)", style = MaterialTheme.typography.labelLarge)
            var more by remember { mutableStateOf(false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (chips.visible + if (more) chips.more else emptyList()).forEach { chip ->
                    FilterChip(selected = card.reason == chip.code, onClick = {
                        vm.chooseReason(chip.code, chip.position, chip.position >= chips.visible.size)
                        if (chip.code == "pain") onPainReason()
                    }, label = { Text(chip.label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
                if (!more && chips.more.isNotEmpty()) TextButton(onClick = { more = true }) { Text("More") }
            }
        }

        // Flags offered by the exercise (C74, UX 11.9).
        if (item.ex.flagsOffered.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.ex.flagsOffered.forEach { f ->
                FilterChip(selected = f in card.flags, onClick = { vm.toggleFlag(f) }, label = { Text(flagLabel(f)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }

        // Log row (D9): one tap logs the set.
        when {
            card.warmup -> Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); vm.log(control = "done") }, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("Done") }
            card.isHold -> if (card.holdPhase == HoldPhase.RESULT) {
                Text("How did it feel?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Max" to 0.0, "Hard" to 2.0, "OK" to 5.0, "Easy" to 10.0).forEach { (label, s) ->
                        FilledTonalButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.log(feltReserveS = s, feltLowerBound = s == 10.0, control = "felt_chip")
                        }, modifier = Modifier.weight(1f).height(64.dp)) { Text(label) }
                    }
                }
            }
            else -> {
                Text((if (card.side != "both") (if (card.side == "L") "LEFT · " else "RIGHT · ") else "") + "Reps left in the tank?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..5).forEach { r ->
                        FilledTonalButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.log(rir = r, control = "rir_chip")
                        }, modifier = Modifier.weight(1f).height(64.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(if (r == 5) "5+" else r.toString(), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

private fun flagLabel(f: String) = when (f) {
    "grip_slip_before_last" -> "Grip slipped before last rep"
    "torso_rotation" -> "Torso rotated"
    "jerky" -> "Jerky lowering"
    "hip_rotation" -> "Hips rotated"
    "hand_shift" -> "Hand shifted"
    "technique_breakdown" -> "Technique broke down"
    "failed_rep" -> "Failed rep"
    "used_straps" -> "Used straps"
    else -> f.replace('_', ' ')
}

@Composable
private fun ExerciseMenu(open: Boolean, card: Card, vm: LiveSessionViewModel, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    val se = card.item.se
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        if (card.item.workingDone == 0) DropdownMenuItem(text = { Text("Skip") }, onClick = { onAction("skip") })
        if (vm.canDoLater(se.id)) DropdownMenuItem(text = { Text("Do later") }, onClick = { onDismiss(); vm.doLater(se.id) })
        DropdownMenuItem(text = { Text("Add a set") }, onClick = { onDismiss(); vm.addSet(se.id) })
        DropdownMenuItem(text = { Text(if (card.warmup) "Log working sets" else "Log a warm-up set") }, onClick = { onDismiss(); vm.setWarmup(!card.warmup) })
        if (card.item.workingDone > 0) DropdownMenuItem(text = { Text("End exercise") }, onClick = { onAction("end") })
        if (card.item.sets.isEmpty()) DropdownMenuItem(text = { Text("Remove") }, onClick = { onDismiss(); vm.remove(se.id) })
        DropdownMenuItem(text = { Text("Move up") }, onClick = { onDismiss(); vm.move(se.id, up = true) })
        DropdownMenuItem(text = { Text("Move down") }, onClick = { onDismiss(); vm.move(se.id, up = false) })
        card.item.ex.allowedLoadModes.map(LoadMode::of).filter { it != card.mode && it != LoadMode.ASSISTED_ORDINAL }.forEach { m ->
            val label = when (m) {
                LoadMode.BODYWEIGHT -> "Bodyweight only"
                LoadMode.BODYWEIGHT_PLUS_EXTERNAL -> "Add weight (belt)"
                LoadMode.ASSISTED_MACHINE -> "Use the assisted machine"
                else -> "External load"
            }
            DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); vm.setLoadMode(m) })
        }
        DropdownMenuItem(text = { Text("Something hurts") }, onClick = { onAction("pain") })
    }
}

/** UX 11.7.3: ready, 5 s lead-in with 3-2-1, holding (tap anywhere to stop), result. */
@Composable
private fun HoldSection(card: Card, vm: LiveSessionViewModel) {
    val haptic = LocalHapticFeedback.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(card.holdPhase, card.holdPhaseStart) {
        var lastSecond = -1L
        while (card.holdPhase == HoldPhase.LEAD_IN || card.holdPhase == HoldPhase.HOLDING) {
            now = System.currentTimeMillis()
            val sec = (now - card.holdPhaseStart) / 1000
            if (card.holdPhase == HoldPhase.LEAD_IN) {
                val left = 5 - sec
                if (sec != lastSecond && left in 1..3) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
                if (left <= 0) { haptic.performHapticFeedback(HapticFeedbackType.Confirm); tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150); vm.holdGo(); return@LaunchedEffect }
            } else {
                val target = card.holdTargetS
                if (sec != lastSecond && sec > 0) {
                    if (target != null && sec == target.toLong()) { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200); haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                    else if (sec % 5 == 0L && (target == null || sec < target)) { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60); haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                }
                if (sec >= 300) { vm.stopHold(auto = true); return@LaunchedEffect }
            }
            lastSecond = sec
            delay(100)
        }
    }
    card.holdTargetS?.let { Text("Target " + Numbers.num(it, 1) + " s", style = MaterialTheme.typography.bodyLarge) }
    when (card.holdPhase) {
        HoldPhase.READY -> Button(onClick = vm::startHold, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("START (5 s lead-in)") }
        HoldPhase.LEAD_IN -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(maxOf(1, 5 - (now - card.holdPhaseStart) / 1000).toString(), fontSize = 96.sp, style = TabularNums)
            Text("Get on the bar")
            TextButton(onClick = vm::cancelHold) { Text("Cancel") }
        }
        HoldPhase.HOLDING -> Box(
            Modifier.fillMaxWidth().height(260.dp).background(MaterialTheme.colorScheme.primaryContainer).clickable { vm.stopHold() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Numbers.clock((now - card.holdPhaseStart) / 1000), fontSize = 96.sp, style = TabularNums)
                Text("TAP ANYWHERE TO STOP", style = MaterialTheme.typography.titleMedium)
            }
        }
        HoldPhase.RESULT -> {
            StepperRow(
                value = Numbers.num(card.holdS ?: 0.0, 1) + " s", secondary = card.holdTimerS?.let { "timer ran " + Numbers.oneDecimal(it) + " s" },
                onMinus = { vm.stepHold(false) }, onPlus = { vm.stepHold(true) },
            )
        }
    }
}

@Composable
private fun UndoBar(ui: LiveUi, vm: LiveSessionViewModel) {
    val l = ui.lastLogged ?: return
    LaunchedEffect(l.setId) { delay(6000); vm.dismissUndo() }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Logged: ${l.label}", color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::undoLast) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
    }
}

// ---------------------------------------------------------------------------------------------------- sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExerciseSheet(c: AppContainer, onDismiss: () -> Unit, onPick: (dev.fitnessapp.algo.selection.catalog.CatalogExercise) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { c.catalog.search(query) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search (English or Dutch)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(results, key = { it.slug }) { ex ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(ex) }.padding(vertical = 10.dp)) {
                        Text(ex.nameEn, style = MaterialTheme.typography.bodyLarge)
                        Text(listOfNotNull(ex.nameNl, ex.equipment.joinToString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReasonSheet(c: AppContainer, context: ChipContext, title: String, onDismiss: () -> Unit, onChoose: (String, Int) -> Unit) {
    val chips = remember(context) { UiReasonChips.chipsFor(context, dev.fitnessapp.app.uidet.ExerciseFlags(emptyList()), c.reasons, c.params.fork()).value }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (chips.visible + chips.more).forEach { chip ->
                    FilledTonalButton(onClick = { onChoose(chip.code, chip.position) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(chip.label) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OccupiedSheet(name: String, canDoLater: Boolean, onDismiss: () -> Unit, onDoLater: () -> Unit, onSkip: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$name is occupied", style = MaterialTheme.typography.titleMedium)
            if (canDoLater) Button(onClick = onDoLater, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Do later") }
            OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip anyway") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** UX 16.13: the remaining exercises and a reason (no time preselected). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinishSheet(c: AppContainer, remaining: List<String>, onDismiss: () -> Unit, onFinish: (String) -> Unit) {
    val chips = remember { UiReasonChips.chipsFor(ChipContext.SESSION_END, dev.fitnessapp.app.uidet.ExerciseFlags(emptyList()), c.reasons, c.params.fork()).value }
    var reason by remember { mutableStateOf(chips.preselected ?: "no_time") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Not done yet", style = MaterialTheme.typography.titleMedium)
            remaining.forEach { Text("· $it") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.visible.forEach { chip -> FilterChip(selected = reason == chip.code, onClick = { reason = chip.code }, label = { Text(chip.label) }) }
            }
            Button(onClick = { onFinish(reason) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Finish") }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Keep going") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** UX 16.2: location, side, severity (mild 2 / moderate 5 / sharp 8, C64). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PainSheet(onDismiss: () -> Unit, preselectedRegion: String? = null, onSubmit: (String, String, String) -> Unit) {
    val regions = listOf(
        "elbow_medial" to "Elbow, inner side", "elbow_lateral" to "Elbow, outer side", "elbow_anterior" to "Elbow crease",
        "shoulder" to "Shoulder", "wrist" to "Wrist", "hand_fingers" to "Hand and fingers", "neck" to "Neck", "upper_back" to "Upper back",
        "lower_back" to "Lower back", "hip" to "Hip", "knee" to "Knee", "ankle_foot" to "Ankle and foot", "other" to "Other",
    )
    var region by remember { mutableStateOf(preselectedRegion) }
    var side by remember { mutableStateOf("none") }
    var severity by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Where does it hurt?", style = MaterialTheme.typography.titleMedium)
            dev.fitnessapp.app.ui.common.ChoiceChips(regions.map { it.first }, region, { code -> regions.first { it.first == code }.second }) { region = it }
            SectionLabel("Side")
            dev.fitnessapp.app.ui.common.ChoiceChips(listOf("L", "R", "both", "none"), side, { mapOf("L" to "Left", "R" to "Right", "both" to "Both", "none" to "Not sided")[it]!! }) { side = it }
            SectionLabel("How bad")
            dev.fitnessapp.app.ui.common.ChoiceChips(listOf("mild", "moderate", "sharp"), severity, { it.replaceFirstChar(Char::uppercase) }) { severity = it }
            Text("This app does not give medical advice. If something hurts, stop and get it checked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onSubmit(region!!, side, severity!!) }, enabled = region != null && severity != null, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Save") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** UX 16.7: type a value; chips with the nearest lower and higher loadable; "Set" snaps (ties to easier). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeypadSheet(card: Card, vm: LiveSessionViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val typed = text.replace(',', '.').toDoubleOrNull()
    val (lo, hi) = remember(typed) { typed?.let { vm.keypadChips(it) } ?: (null to null) }
    val unit = if (card.mode == LoadMode.ASSISTED_MACHINE) "pin, lb" else card.point?.nativeUnit ?: "kg"
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("Load ($unit)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(lo, hi).distinct().forEach { p ->
                    OutlinedButton(onClick = { vm.keypad(p.assistPinNative ?: p.nativeValue ?: 0.0); onDismiss() }) { Text(Numbers.loadMain(p, card.mode)) }
                }
            }
            Button(onClick = { typed?.let(vm::keypad); onDismiss() }, enabled = typed != null, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Set") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
