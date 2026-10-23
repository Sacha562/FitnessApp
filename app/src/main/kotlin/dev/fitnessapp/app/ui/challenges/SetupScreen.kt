package dev.fitnessapp.app.ui.challenges

import androidx.activity.compose.BackHandler
import dev.fitnessapp.app.ui.common.TileDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.ProposalSpec
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.DockedBar
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.MoreSection
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.ProgressBar
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.theme.Fit
import java.time.LocalDate

/** The challenge setup wizard (UX 14.8, D35): three steps, Start on the last. */
@Composable
fun ChallengeSetupRoute(c: AppContainer, definitionId: String, onBack: () -> Unit, onStarted: (String) -> Unit) {
    val vm: SetupViewModel = viewModel(key = "setup:$definitionId", factory = viewModelFactory { initializer { SetupViewModel(c, definitionId) } })
    val ui by vm.ui.collectAsState()
    val def = ui.def
    val step = ui.draft.step
    val last = SetupViewModel.STEPS.lastIndex
    var customize by rememberSaveable { mutableStateOf(false) }
    if (customize && def != null) {
        BackHandler { customize = false }
        val decisions = vm.decisions(def, ui.draft)
        ProposalsScreen(
            def.doc.proposals.map { p -> proposalUi(p, decisions[p.id]) },
            onBack = { customize = false },
            onDecide = { id, decision -> vm.update { it.copy(decisions = it.decisions + (id to decision)) } },
            onViewed = {},
        )
        return
    }
    BackHandler(enabled = step > 0) { vm.go(step - 1) }
    FitScaffold(
        title = def?.let { ChallengeTexts.title(it) } ?: "Set up",
        onBack = { if (step > 0) vm.go(step - 1) else onBack() },
        bottomBar = {
            DockedBar {
                ui.error?.let { Banner(it, BannerKind.SAFETY) }
                if (step < last) {
                    val canNext = step != 1 || vm.startComplete(ui)
                    val label = when {
                        canNext -> "Continue"
                        !vm.positionValid(ui) -> "Check phase, block and week to continue"
                        else -> "Fill in your numbers to continue"
                    }
                    PrimaryButton(label, { vm.go(step + 1) }, Modifier.fillMaxWidth(), enabled = def != null && canNext)
                } else {
                    PrimaryButton("Start challenge", { vm.start(onStarted) }, Modifier.fillMaxWidth(), enabled = !ui.busy)
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProgressBar((step + 1f) / (last + 1f))
            Text("Step ${step + 1} of ${last + 1}", style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
            if (def == null) {
                Text("Loading…", color = Fit.Muted)
                return@Column
            }
            when (step) {
                0 -> Overview(def, ui, vm)
                1 -> StartingPoint(def, ui, vm)
                2 -> ReviewStep(def, ui, vm) { customize = true }
            }
        }
    }
}

@Composable
private fun Overview(def: CompiledDefinition, ui: SetupUi, vm: SetupViewModel) {
    ChallengeTexts.tagline(def)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    val normal = def.doc.schedule.slotsPerWeek["normal"].orEmpty()
    val minutes = def.templates.values.filter { it.slot in normal }.mapNotNull { it.bookedMin }.maxOrNull()
    Text(
        "${normal.size} sessions a week" + (minutes?.let { " · about $it min" } ?: "") + if (def.doc.habits.isNotEmpty()) " · short home habits" else "",
        style = MaterialTheme.typography.bodyMedium, color = Fit.Muted,
    )
    val enums = def.doc.params.filter { (k, p) -> p.userEditable && !k.startsWith("approved_") && p.type == "enum" }
    // The grip is the one choice every test depends on; other choices keep their defaults unless opened (D35).
    enums["grip"]?.let { p -> ParamChips("grip", p.values, ui, vm) }
    val other = enums.filterKeys { it != "grip" }
    if (other.isNotEmpty()) MoreSection("More options") { other.forEach { (name, p) -> ParamChips(name, p.values, ui, vm) } }
    val checklist = def.doc.challengeRules.attemptChecklist.mapNotNull { it["en"] }
    if (checklist.isNotEmpty()) MoreSection("What counts as a rep") { checklist.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) } }
    Text("If something hurts, report it and the plan adapts. Not medical advice.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
}

@Composable
private fun ParamChips(name: String, values: List<String>, ui: SetupUi, vm: SetupViewModel) {
    SectionLabel(ChallengeTexts.paramLabel(name))
    ChoiceChips(values, ui.draft.params[name], ChallengeTexts::paramValue) { v -> vm.update { d -> d.copy(params = d.params + (name to v)) } }
}

@Composable
private fun StartingPoint(def: CompiledDefinition, ui: SetupUi, vm: SetupViewModel) {
    val d = ui.draft
    Text("Starting point", style = MaterialTheme.typography.titleLarge)
    ChoiceChips(listOf("baseline", "numbers"), if (d.start == "baseline") "baseline" else "numbers", {
        if (it == "baseline") "Start with a test week" else "Enter my numbers"
    }) { v -> vm.update { it.copy(start = if (v == "baseline") "baseline" else "known") } }
    if (d.start == "baseline") {
        Text("You start with a test day; it sets your loads.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
        return
    }
    ui.fields.filter { it.required }.forEach { f -> OnboardInput(f, ui, vm) }
    val optional = ui.fields.filter { !it.required }
    if (optional.isNotEmpty()) MoreSection("More test values (optional)") { optional.forEach { f -> OnboardInput(f, ui, vm) } }
    // The whole row toggles (48 dp target), not only the box.
    Row(
        Modifier.fillMaxWidth().toggleable(d.start == "import", role = Role.Checkbox) { v -> vm.update { it.copy(start = if (v) "import" else "known") } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(d.start == "import", onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Fit.Accent))
        Text("I'm mid-program", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
    if (d.start == "import") MidProgram(def, ui, vm)
}

@Composable
private fun OnboardInput(f: OnboardField, ui: SetupUi, vm: SetupViewModel) {
    FitTextField(
        ui.draft.onboard[f.key] ?: "", { v -> vm.update { it.copy(onboard = it.onboard + (f.key to v.replace(',', '.'))) } },
        Modifier.fillMaxWidth(), label = f.label, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun MidProgram(def: CompiledDefinition, ui: SetupUi, vm: SetupViewModel) {
    val d = ui.draft
    IntField("Phase", d.phase) { v -> vm.update { it.copy(phase = v) } }
    IntField("Block", d.blockIndex) { v -> vm.update { it.copy(blockIndex = v) } }
    IntField("Week of the block", d.blockWeek) { v -> vm.update { it.copy(blockWeek = v) } }
    IntField("Week of the program", d.weekOrdinal) { v -> vm.update { it.copy(weekOrdinal = v) } }
    ChoiceChips(listOf("normal", "deload", "test_pending", "extra_deload", "injury"), d.mode, ChallengeTexts::mode) { v -> vm.update { it.copy(mode = v) } }
    if (d.mode == "injury") {
        FitTextField(
            d.vars[SetupViewModel.INJURY_START] ?: "", { v -> vm.update { it.copy(vars = it.vars + (SetupViewModel.INJURY_START to v.trim())) } },
            Modifier.fillMaxWidth(), label = "Elbow protocol started (yyyy-mm-dd)", singleLine = true,
        )
    }
    FitTextField(d.plannedTestDate ?: "", { v -> vm.update { it.copy(plannedTestDate = v.ifBlank { null }) } }, Modifier.fillMaxWidth(), label = "Next test date (yyyy-mm-dd, optional)", singleLine = true)
    for ((key, name) in vm.blockCountItems(def)) {
        IntField("$name: sessions done in this block", d.blockItemCounts[key] ?: 0) { v -> vm.update { it.copy(blockItemCounts = it.blockItemCounts + (key to maxOf(0, v))) } }
    }
    if (d.pastTests.isNotEmpty()) {
        MoreSection("Earlier tests (${d.pastTests.size})") {
            val units = def.metrics.mapValues { (_, m) ->
                val t = m.type.let { (it as? dev.fitnessapp.algo.challenge.fitexpr.Type.SideT)?.elem ?: it }
                (t as? dev.fitnessapp.algo.challenge.fitexpr.Type.Q)?.dim?.unit.orEmpty()
            }
            d.pastTests.forEachIndexed { i, t -> PastTestRow(t, units, onValid = { v -> vm.update { it.copy(pastTests = it.pastTests.mapIndexed { j, x -> if (j == i) x.copy(validForGate = v) else x }) } }, onRemove = { vm.update { it.copy(pastTests = it.pastTests.filterIndexed { j, _ -> j != i }) } }) }
        }
    }
    MoreSection("Current loads") {
        for ((name, value) in ui.computed) {
            val info = def.stateVarByName[name] ?: continue
            if (info.type !is Type.Q && info.type != Type.BoolT && info.type !is Type.EnumT) continue
            val shown = d.vars[name] ?: when (value) {
                is QV -> value.v.stripTrailingZeros().toPlainString()
                is BoolV -> value.b.toString()
                is StrV -> value.s
                else -> ""
            }
            FitTextField(
                shown, { v -> vm.update { it.copy(vars = it.vars + (name to v.replace(',', '.'))) } }, Modifier.fillMaxWidth(),
                label = ChallengeTexts.varLabel(name), singleLine = true,
            )
        }
    }
}

/** One imported test: its date and values, whether it counts for the gates, and Remove (D40). */
@Composable
private fun PastTestRow(t: DraftTest, units: Map<String, String>, onValid: (Boolean) -> Unit, onRemove: () -> Unit) {
    Tile(spacing = 4.dp) {
        Text(ChallengeTexts.date(LocalDate.parse(t.date)), style = MaterialTheme.typography.titleSmall)
        val values = t.metrics.entries.groupBy { it.key.substringBefore('.') }.map { (metric, entries) ->
            val v = entries.sortedBy { it.key }.joinToString(" / ") { e -> (e.key.substringAfter('.', "").ifEmpty { null }?.let { "$it " } ?: "") + e.value }
            val unit = units[metric].orEmpty().let { if (it.isEmpty()) "" else " $it" }
            ChallengeTexts.metricLabel(metric) + " " + v + unit + if (metric in t.notComparable) " (other protocol)" else ""
        }
        values.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        t.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
        Row(
            Modifier.fillMaxWidth().toggleable(t.validForGate, role = Role.Checkbox) { onValid(it) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(t.validForGate, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Fit.Accent))
            Text("Counts for the gates", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
            LinkButton("Remove", onRemove)
        }
    }
}

@Composable
private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    FitTextField(
        value.toString(), { v -> v.toIntOrNull()?.let(onChange) }, Modifier.fillMaxWidth(), label = label, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun ReviewStep(def: CompiledDefinition, ui: SetupUi, vm: SetupViewModel, onCustomize: () -> Unit) {
    Text("Your first two weeks", style = MaterialTheme.typography.titleLarge)
    if (ui.review.isEmpty()) Text("Computing…", color = Fit.Muted)
    ui.review.forEach { w ->
        Tile(spacing = 4.dp) {
            Text(w.title, style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
            w.sessions.forEachIndexed { i, session ->
                if (i > 0) TileDivider()
                ReviewSessionRow(session)
            }
            w.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
        }
    }
    if (def.doc.proposals.isNotEmpty()) {
        val on = vm.decisions(def, ui.draft).values.count { it == "approved" }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Rule changes: $on of ${def.doc.proposals.size} on", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            LinkButton("Customize", onCustomize)
        }
    }
    Text("Starting makes the app the source of truth for this program.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
    if (ui.busy) Text("Starting…", color = Fit.Muted)
}

/** One session of the review: name, theme and time; its exercises, one per row, open on tap. */
@Composable
private fun ReviewSessionRow(session: ReviewSession) {
    var open by rememberSaveable(session.name, session.whenText) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(session.name + (session.theme?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
                Text(session.whenText, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            }
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, if (open) "Hide exercises" else "Show exercises", tint = Fit.Muted)
        }
        if (open) ExerciseRows(session.exercises)
    }
}

/** A proposal's tile model (14.4) with its [decision] (`approved`, `declined`, or null when undecided). */
internal fun proposalUi(p: ProposalSpec, decision: String?) =
    ProposalUi(p.id, p.title["en"] ?: p.id, p.rationale["en"] ?: "", p.ifDeclined["en"] ?: "", decision)
