package dev.fitnessapp.app.ui.testday

import dev.fitnessapp.app.platform.AppClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.ui.challenges.ChallengeTexts
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.DockedBar
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.MoreSection
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.RoundIconButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.live.PainSheet
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.ui.today.ElbowCheckDialog
import dev.fitnessapp.app.uidet.UiGateRows
import kotlinx.coroutines.delay

/** The test-day wizard (UX 14.9): overview, one card per step, review and Submit, then the recalculation report. */
@Composable
fun TestDayRoute(c: AppContainer, enrollmentId: String, sessionId: String?, onStarted: (String) -> Unit, onMinimise: () -> Unit, onDone: () -> Unit) {
    val vm: TestDayViewModel = viewModel(key = "test:$enrollmentId:${sessionId ?: ""}", factory = viewModelFactory { initializer { TestDayViewModel(c, enrollmentId, sessionId) } })
    val u by vm.ui.collectAsState()
    // Start created the session: the back stack names it, so a restart comes back to this test.
    LaunchedEffect(u.sessionId) { u.sessionId?.takeIf { sessionId == null }?.let(onStarted) }
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    BackHandler { if (u.phase == Phase.REPORT) onDone() else onMinimise() }

    val title = when (u.phase) {
        Phase.STEP -> u.current?.let { "Step ${it.number} of ${u.steps.size}" } ?: u.title
        Phase.REVIEW -> "Review"
        else -> u.title
    }
    FitScaffold(
        title = title, onBack = { if (u.phase == Phase.REPORT) onDone() else onMinimise() },
        actions = {
            if (u.phase == Phase.STEP || u.phase == Phase.REVIEW) {
                Box {
                    RoundIconButton(Icons.Rounded.MoreVert, "Menu", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Report pain") }, onClick = { menu = false; sheet = "pain" })
                        DropdownMenuItem(text = { Text("Stop test") }, onClick = { menu = false; sheet = "stop" })
                    }
                }
            }
        },
        bottomBar = { Docked(u, vm, onDone) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            u.notice?.let { Banner(it, BannerKind.SAFETY, action = "OK", onAction = vm::dismissNotice) }
            when (u.phase) {
                Phase.LOADING -> Text("Loading…", color = Fit.Muted)
                Phase.OVERVIEW -> TestOverview(u)
                Phase.STEP -> StepContent(u, vm)
                Phase.REVIEW -> u.review?.let { TestReview(it) } ?: Text("Checking your results…", color = Fit.Muted)
                Phase.REPORT -> u.report?.let { TestReport(it) }
            }
        }
    }
    if (u.elbowCheck) ElbowCheckDialog(onDismiss = vm::cancelElbow) { score, kinds -> vm.submitElbow(score, kinds) }
    when (sheet) {
        "pain" -> PainSheet(onDismiss = { sheet = null }) { region, side, severity, symptoms ->
            sheet = null
            vm.reportPain(region, side, severity, symptoms)
        }
        "stop" -> AlertDialog(
            onDismissRequest = { sheet = null },
            title = { Text("Stop the test?") },
            text = { Text("Nothing from today is sent to your program.") },
            confirmButton = { TextButton({ sheet = null; vm.abandon(onDone) }) { Text("Stop test", color = Fit.Safety) } },
            dismissButton = { TextButton({ sheet = null }) { Text("Keep going") } },
        )
    }
}

/** The one main action of each phase (P1), docked at the bottom. */
@Composable
private fun Docked(u: TestDayUi, vm: TestDayViewModel, onDone: () -> Unit) {
    val action: Pair<String, () -> Unit>? = when (u.phase) {
        Phase.OVERVIEW -> (if (u.validity?.valid == false) "Start anyway" else "Start test") to vm::start
        Phase.STEP -> when {
            u.current?.kind == StepKind.WARMUP -> "Warm-up done" to vm::warmupAllDone
            u.current?.kind == StepKind.WEIGH -> null
            u.side == null -> "Next" to { vm.next() }
            else -> null
        }
        Phase.REVIEW -> if (u.review != null) "Submit test" to vm::submit else null
        Phase.REPORT -> "Done" to onDone
        Phase.LOADING -> null
    }
    val endEarly = u.phase == Phase.STEP && u.side != null && u.current?.kind == StepKind.ATTEMPTS &&
        u.rows.any { it.itemKey == u.current.item.key && it.setKind == "test" }
    if (action == null && !endEarly) return
    DockedBar {
        if (endEarly) TonalButton("Done with this test", { vm.next() }, Modifier.fillMaxWidth(), enabled = !u.busy)
        action?.let { (text, onClick) -> PrimaryButton(text, onClick, Modifier.fillMaxWidth(), enabled = !u.busy) }
    }
}

// ---------------------------------------------------------------------------------------------------- overview

/** Step 0 (UX 14.9): whether the test counts, and the steps in the program's order. Internal for the screenshot test. */
@Composable
internal fun TestOverview(u: TestDayUi) {
    Tile(spacing = 6.dp) {
        val v = u.validity
        if (v == null || v.valid) {
            Text("This test counts for your goals.", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("This test won't count for your goals.", style = MaterialTheme.typography.bodyLarge, color = Fit.Advisory)
            v.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
        }
    }
    Tile(spacing = 0.dp) {
        u.steps.forEachIndexed { i, s ->
            if (i > 0) TileDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${s.number}", style = MaterialTheme.typography.labelLarge, color = Fit.Muted, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stepTitle(s), style = MaterialTheme.typography.bodyLarge)
                    s.skipped?.let { Text("Skipped today", style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
                }
            }
        }
    }
}

/** A step's name: the metric's plain label, "Warm-up" or "Weigh-in" (P15: no ids). */
internal fun stepTitle(s: TestStep): String = when (s.kind) {
    StepKind.WARMUP -> "Warm-up"
    StepKind.WEIGH -> "Weigh-in"
    else -> s.metric?.let(ChallengeTexts::metricLabel) ?: s.item.name["en"] ?: s.key
} + if (s.sides.size > 1) " · L / R" else ""

private fun sideName(side: String?): String? = when (side) {
    "L" -> "Left"
    "R" -> "Right"
    else -> null
}

private fun protocol(s: TestStep): String? = s.item.notes.lastOrNull()?.get("en")

// ---------------------------------------------------------------------------------------------------- steps

@Composable
private fun StepContent(u: TestDayUi, vm: TestDayViewModel) {
    val step = u.current ?: return
    var typing by remember(step.number) { mutableStateOf(false) }
    val type = { typing = true }
    if (typing) TypeDialog(step.kind, onDismiss = { typing = false }) { vm.typeValue(it); typing = false }
    RestLine(u, vm)
    if (step.skipped != null) {
        Tile { Text("${stepTitle(step)} is skipped today.", style = MaterialTheme.typography.bodyLarge) }
        return
    }
    val enabled = !u.busy
    when (step.kind) {
        StepKind.WEIGH -> RecordCard(
            CardHead("Weigh-in", protocol = protocol(step)), Numbers.num(u.draft.kg?.toDouble() ?: 0.0, 1) + " kg", null,
            { vm.stepKg(false) }, { vm.stepKg(true) }, "Record", vm::recordWeighIn, enabled, onValueClick = type,
        )
        StepKind.WARMUP -> WarmupCard(step, u, vm)
        StepKind.ATTEMPTS -> AttemptsStep(step, u, vm, type)
        StepKind.REPS -> if (u.side == null) DoneCard(step, u, vm) else RecordCard(
            CardHead(stepTitle(step), protocol = protocol(step)), "${u.draft.reps ?: 0} reps", null,
            { vm.stepReps(false) }, { vm.stepReps(true) }, "Record", { vm.record(true) }, enabled, onSkip = vm::skipStep, onValueClick = type,
        )
        StepKind.HOLD -> if (u.side == null) DoneCard(step, u, vm) else HoldStep(step, u, vm, type)
        StepKind.LADDER -> if (u.side == null) DoneCard(step, u, vm) else LadderStep(step, u, vm, type)
        StepKind.BOOL -> if (u.side == null) DoneCard(step, u, vm) else AttemptCard(
            head(step, u, "Attempt"), { vm.record(true) }, { vm.record(false) }, enabled,
            onUndo = vm::undoLast.takeIf { lastRow(step, u) }, onSkip = vm::skipStep,
        )
    }
}

private fun lastRow(step: TestStep, u: TestDayUi) = u.rows.any { it.itemKey == step.item.key && it.setKind == "test" }

/** Side and attempt line of a per-side card; the best of this side so far. */
private fun head(step: TestStep, u: TestDayUi, word: String): CardHead {
    val side = u.side ?: "both"
    val n = TestDayModel.attempts(step, side, u.rows)
    val max = TestDayModel.maxAttempts(step)
    val attempt = if (max != null && max > 1) "$word ${n + 1} of $max" else if (max == null) "$word ${n + 1}" else null
    val best = TestDayModel.value(step, u.rows)?.let { v ->
        val one = if (v is dev.fitnessapp.algo.challenge.fitexpr.SideV) (if (side == "R") v.r else v.l) else v
        one?.let { "Best so far: " + UiGateRows.text(it, step.metric) }
    }
    return CardHead(stepTitle(step).substringBefore(" · "), sideName(u.side), attempt, best, protocol(step))
}

@Composable
private fun DoneCard(step: TestStep, u: TestDayUi, vm: TestDayViewModel) {
    Tile(spacing = 6.dp) {
        Text(stepTitle(step).substringBefore(" · "), style = MaterialTheme.typography.titleLarge)
        val v = TestDayModel.value(step, u.rows)
        Text(v?.let { UiGateRows.text(it, step.metric) } ?: "No result", style = MaterialTheme.typography.headlineMedium)
        LinkButton("Undo last", vm::undoLast)
    }
}

@Composable
private fun WarmupCard(step: TestStep, u: TestDayUi, vm: TestDayViewModel) {
    Tile(spacing = 0.dp) {
        TileLabel("Warm-up", Modifier.padding(bottom = 4.dp))
        step.items.forEachIndexed { i, item ->
            if (i > 0) TileDivider()
            val done = u.rows.any { it.itemKey == item.key }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name["en"] ?: item.key, style = MaterialTheme.typography.bodyLarge)
                    dose(item)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
                }
                if (done) Icon(Icons.Rounded.Check, "Done", tint = Fit.Accent, modifier = Modifier.size(24.dp))
                else LinkButton("Done", { vm.warmupDone(item.key) }, enabled = !u.busy)
            }
        }
    }
}

private fun dose(item: dev.fitnessapp.algo.challenge.materialize.PlannedItem): String? {
    val r = item.rows.firstOrNull() ?: return null
    return when {
        r.durationS != null -> Numbers.clock(r.durationS!!.toLong())
        r.targetReps != null -> "${item.rows.size} × ${r.targetReps}"
        else -> "${item.rows.size} sets, building up"
    }
}

@Composable
private fun AttemptsStep(step: TestStep, u: TestDayUi, vm: TestDayViewModel, type: () -> Unit) {
    val ramp = step.item.steps?.ramp.orEmpty()
    if (ramp.isNotEmpty()) {
        val done = TestDayModel.rampDone(u.rows, step)
        Tile(spacing = 0.dp) {
            TileLabel("Warm-up sets", Modifier.padding(bottom = 4.dp))
            ramp.forEachIndexed { i, r ->
                if (i > 0) TileDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    val load = if (r.addedKg.signum() == 0) "BW" else "BW + " + Numbers.kg(r.addedKg.toDouble())
                    Text("$load × ${r.repsMin}-${r.repsMax}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (done.any { it.compareTo(r.addedKg) == 0 }) Icon(Icons.Rounded.Check, "Done", tint = Fit.Accent, modifier = Modifier.size(24.dp))
                    else LinkButton("Done", { vm.rampDone(r.addedKg, r.repsMax) }, enabled = !u.busy)
                }
            }
        }
    }
    if (u.side == null) {
        DoneCard(step, u, vm)
        return
    }
    val point = u.draft.loadIndex?.let { u.points.getOrNull(it) }
    val reps = TestDayModel.requiredReps(step.item)
    RecordCard(
        head(step, u, "Attempt"), Numbers.loadMain(point, LoadMode.BODYWEIGHT_PLUS_EXTERNAL),
        Numbers.loadSecondary(point, LoadMode.BODYWEIGHT_PLUS_EXTERNAL, false, u.bwKg?.toDouble()),
        { vm.stepLoad(false) }, { vm.stepLoad(true) }, "Clean × $reps", { vm.record(true) }, !u.busy && point != null,
        secondaryAction = "Not clean" to { vm.record(false) }, onUndo = vm::undoLast.takeIf { lastRow(step, u) }, onSkip = vm::skipStep, onValueClick = type,
    )
}

@Composable
private fun LadderStep(step: TestStep, u: TestDayUi, vm: TestDayViewModel, type: () -> Unit) {
    val point = u.draft.loadIndex?.let { u.points.getOrNull(it) }
    RecordCard(
        head(step, u, "Attempt"), Numbers.loadMain(point, LoadMode.ASSISTED_MACHINE),
        point?.assistKg?.let { "≈ " + Numbers.oneDecimal(it) + " kg help" },
        { vm.stepLoad(true) }, { vm.stepLoad(false) }, "Clean rep", { vm.record(true) }, !u.busy && point != null,
        minusLabel = "less help", plusLabel = "more help",
        secondaryAction = "Failed" to { vm.record(false) }, onUndo = vm::undoLast.takeIf { lastRow(step, u) }, onSkip = vm::skipStep, onValueClick = type,
    )
}

@Composable
private fun HoldStep(step: TestStep, u: TestDayUi, vm: TestDayViewModel, type: () -> Unit) {
    RecordCard(
        head(step, u, "Attempt"), Numbers.num(u.draft.holdS ?: 0.0, 1) + " s", null,
        { vm.stepHold(false) }, { vm.stepHold(true) }, "Record", { vm.record(true) }, !u.busy,
        onUndo = vm::undoLast.takeIf { lastRow(step, u) }, onSkip = vm::skipStep, onValueClick = type,
        extra = { MoreSection("Time it") { Stopwatch(vm::setHold) } },
    )
}

/** Tap on the stepper value (UX 16.7): type the number; loads snap to the nearest loadable point. */
@Composable
private fun TypeDialog(kind: StepKind, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    val typed = text.replace(',', '.').toDoubleOrNull()
    val label = when (kind) {
        StepKind.WEIGH -> "Bodyweight (kg)"
        StepKind.REPS -> "Reps"
        StepKind.HOLD -> "Seconds"
        StepKind.LADDER -> "Pin (lb)"
        else -> "Added load (kg)"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            FitTextField(
                value = text, onValueChange = { text = it }, label = label, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton({ typed?.let(onSet) }, enabled = typed != null) { Text("Set") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** An optional stopwatch for a hold (UX 14.9): Start, then Stop fills the seconds; the stepper stays the record. */
@Composable
private fun Stopwatch(onStop: (Double) -> Unit) {
    var start by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(start) {
        while (start > 0) {
            now = AppClock.nowMs()
            delay(TICK_MS)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val s = if (start > 0) (now - start).coerceAtLeast(0) / 1000.0 else 0.0
        Text(Numbers.num(s, 1) + " s", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (start == 0L) {
            TonalButton("Start", { start = AppClock.nowMs(); now = start })
        } else {
            TonalButton("Stop", {
                onStop((AppClock.nowMs() - start) / 1000.0)
                start = 0L
            })
        }
    }
}

/** The rest between attempts uses the protocol's rest (`rest_s`), with the usual alarm; Skip ends it. */
@Composable
private fun RestLine(u: TestDayUi, vm: TestDayViewModel) {
    val r = u.rest ?: return
    var now by remember { mutableLongStateOf(AppClock.nowMs()) }
    LaunchedEffect(r.id) {
        while (true) {
            now = AppClock.nowMs()
            delay(TICK_MS)
        }
    }
    val left = ((r.end_wall_ms - now) / 1000).coerceAtLeast(0)
    Tile(spacing = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (left > 0) "Rest " + Numbers.clock(left) else "Rest is over", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            LinkButton("Skip", { vm.skipRest() })
        }
    }
}

// ---------------------------------------------------------------------------------------------------- review and report

/** The review against the previous valid test (UX 14.9, D35): a few lines and whether it counts. */
@Composable
internal fun TestReview(r: ReviewUi) {
    Tile(spacing = 0.dp) {
        r.lines.forEachIndexed { i, l ->
            if (i > 0) TileDivider()
            ResultLine(l)
        }
    }
    Tile(spacing = 4.dp) {
        if (r.valid) {
            Text("Counts for your goals.", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("Won't count for your goals.", style = MaterialTheme.typography.bodyLarge, color = Fit.Advisory)
            r.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
        }
        r.goals?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted) }
    }
}

@Composable
private fun ResultLine(l: LineUi) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(l.value, style = MaterialTheme.typography.titleMedium)
        }
        l.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
    }
}

/** The recalculation report (UX 14.9, D35): what changed, in a few plain lines; the D7 extras folded. */
@Composable
internal fun TestReport(r: ReportUi) {
    Tile(spacing = 4.dp) {
        Text(r.headline, style = MaterialTheme.typography.headlineSmall)
        r.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
    }
    r.messages.forEach { Banner(it, BannerKind.ADVISORY) }
    r.estimate?.let { e ->
        Tile(spacing = 2.dp) {
            ResultLine(e)
            r.estimateMore?.let { MoreSection("Other estimates") { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) } }
        }
    }
    if (r.loads.isNotEmpty()) {
        Tile(spacing = 0.dp) {
            TileLabel("New loads", Modifier.padding(bottom = 4.dp))
            r.loads.forEachIndexed { i, l ->
                if (i > 0) TileDivider()
                ResultLine(l)
            }
        }
    }
    val tail = listOfNotNull(r.goals, r.nextTest)
    if (tail.isNotEmpty()) {
        Tile(spacing = 4.dp) { tail.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) } }
    }
}

private const val TICK_MS = 250L
