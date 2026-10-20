package dev.fitnessapp.app.ui.challenges

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.DockedBar
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.ProgressBar
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.theme.Fit

/** The challenge setup wizard (UX 14.8): ten steps, Start on the last. */
@Composable
fun ChallengeSetupRoute(c: AppContainer, definitionId: String, onBack: () -> Unit, onStarted: (String) -> Unit) {
    val vm: SetupViewModel = viewModel(key = "setup:$definitionId", factory = viewModelFactory { initializer { SetupViewModel(c, definitionId) } })
    val ui by vm.ui.collectAsState()
    val def = ui.def
    val step = ui.draft.step
    val last = SetupViewModel.STEPS.lastIndex
    BackHandler(enabled = step > 0) { vm.go(step - 1) }
    FitScaffold(
        title = def?.let { "Set up: ${ChallengeTexts.title(it)}" } ?: "Set up",
        onBack = { if (step > 0) vm.go(step - 1) else onBack() },
        bottomBar = {
            DockedBar {
                ui.error?.let { Banner(it, BannerKind.SAFETY) }
                val canNext = when (step) {
                    7 -> def != null && def.doc.proposals.all { it.id in ui.draft.decisions }
                    else -> true
                }
                if (step < last) {
                    PrimaryButton(if (step == 7 && !canNext) "Decide every proposal to continue" else "Continue", { vm.go(step + 1) }, Modifier.fillMaxWidth(), enabled = canNext)
                } else {
                    PrimaryButton("Start challenge", { vm.start(onStarted) }, Modifier.fillMaxWidth(), enabled = ui.draft.cutover && !ui.busy)
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
                0 -> Overview(def)
                1 -> RulesStep(def, ui, vm)
                2 -> EquipmentStep(def)
                3 -> StartingPoint(def, ui, vm)
                4 -> GateMethod()
                5 -> ScheduleStep(def)
                6 -> CalendarStep()
                7 -> ProposalsStep(def, ui, vm)
                8 -> ReviewStep(ui)
                9 -> CutoverStep(ui, vm)
            }
        }
    }
}

@Composable
private fun Overview(def: CompiledDefinition) {
    Text(ChallengeTexts.title(def), style = MaterialTheme.typography.headlineSmall)
    def.doc.summary["en"]?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Tile {
        val slots = def.doc.schedule.slotsPerWeek["normal"].orEmpty()
        Text("${slots.size} sessions a week (${slots.joinToString(", ")}), about 90 minutes each, plus short home habits.", style = MaterialTheme.typography.bodyMedium)
        def.doc.provenance.evidenceNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Advisory) }
    }
    Tile {
        Text("Safety", style = MaterialTheme.typography.titleSmall)
        def.doc.messages["elbow_protocol"]?.get("en")?.let { Text("If an elbow stop signal appears: $it", style = MaterialTheme.typography.bodySmall) }
        Text("This app is not medical advice.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
    }
}

@Composable
private fun RulesStep(def: CompiledDefinition, ui: SetupUi, vm: SetupViewModel) {
    Text("Rules and parameters", style = MaterialTheme.typography.titleLarge)
    def.doc.challengeRules.attemptChecklist.mapNotNull { it["en"] }.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    for ((name, p) in def.doc.params.filter { (k, p) -> p.userEditable && !k.startsWith("approved_") && p.type == "enum" }) {
        SectionLabel(if (name == "grip") "Grip (fixed at onboarding; every test uses it)" else name)
        ChoiceChips(p.values, ui.draft.params[name], { it }) { v -> vm.update { d -> d.copy(params = d.params + (name to v)) } }
    }
    Text("Completion with either arm is proposal P16, decided in step 8.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
}

@Composable
private fun EquipmentStep(def: CompiledDefinition) {
    Text("Equipment", style = MaterialTheme.typography.titleLarge)
    Text("The challenge needs:", style = MaterialTheme.typography.bodyMedium)
    def.doc.equipment.required.forEach { e -> Text("• ${e.id.replace('_', ' ')}" + (e.note?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall) }
    Text("Optional:", style = MaterialTheme.typography.bodyMedium)
    def.doc.equipment.optional.forEach { e -> Text("• ${e.id.replace('_', ' ')}" + (e.note?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall) }
    Banner(
        "Calibrate the assisted machine and the belt plates in You → Equipment. To check the machine's offset, stand on a bathroom scale on the platform at pin 0.",
        BannerKind.ADVISORY,
    )
}

@Composable
private fun StartingPoint(def: CompiledDefinition, ui: SetupUi, vm: SetupViewModel) {
    val d = ui.draft
    Text("Starting point", style = MaterialTheme.typography.titleLarge)
    ChoiceChips(listOf("import", "known", "baseline"), d.start, {
        when (it) {
            "import" -> "Import my current state"
            "known" -> "Enter known values"
            else -> "Schedule a baseline test"
        }
    }) { v -> vm.update { it.copy(start = v) } }
    SectionLabel("Your values")
    ui.fields.forEach { f ->
        FitTextField(
            d.onboard[f.key] ?: "", { v -> vm.update { it.copy(onboard = it.onboard + (f.key to v.replace(',', '.'))) } },
            Modifier.fillMaxWidth(), label = f.label, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
    if (d.start == "import") {
        SectionLabel("Program position")
        IntField("Phase", d.phase) { v -> vm.update { it.copy(phase = v) } }
        IntField("Block", d.blockIndex) { v -> vm.update { it.copy(blockIndex = v) } }
        IntField("Week of the block", d.blockWeek) { v -> vm.update { it.copy(blockWeek = v) } }
        ChoiceChips(listOf("normal", "deload", "test_pending", "extra_deload"), d.mode, ChallengeTexts::mode) { v -> vm.update { it.copy(mode = v) } }
        FitTextField(d.plannedTestDate ?: "", { v -> vm.update { it.copy(plannedTestDate = v.ifBlank { null }) } }, Modifier.fillMaxWidth(), label = "Planned test date (yyyy-mm-dd, optional)", singleLine = true)
        SectionLabel("Current loads and values (computed from the values above; edit to match your program)")
        for ((name, value) in ui.computed) {
            val info = def.stateVarByName[name] ?: continue
            if (info.type !is Type.Q && info.type != Type.BoolT && info.type !is Type.EnumT) continue
            val shown = d.vars[name] ?: when (value) {
                is dev.fitnessapp.algo.challenge.fitexpr.QV -> value.v.stripTrailingZeros().toPlainString()
                is dev.fitnessapp.algo.challenge.fitexpr.BoolV -> value.b.toString()
                is dev.fitnessapp.algo.challenge.fitexpr.StrV -> value.s
                else -> ""
            }
            FitTextField(
                shown, { v -> vm.update { it.copy(vars = it.vars + (name to v.replace(',', '.'))) } }, Modifier.fillMaxWidth(),
                label = ChallengeTexts.varLabel(name), singleLine = true,
            )
        }
        Text(
            "Past tests are imported as test values only; no historical sets are created, so the strength gate uses the class prior k = 30 until your own logs give the curve informative data (C59).",
            style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
        )
    }
    if (d.start == "baseline") Text("The challenge starts with a test week; the test is planned for this Sunday.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
}

@Composable
private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    FitTextField(
        value.toString(), { v -> v.toIntOrNull()?.let(onChange) }, Modifier.fillMaxWidth(), label = label, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun GateMethod() {
    Text("Gate method", style = MaterialTheme.typography.titleLarge)
    Text(
        "Your strength gate uses your personal rep curve, fitted to your weighted pull-up data and applied to the test-day 3RM. " +
            "The x1.10 value and an 80 % range are shown next to it. No extra single is added to test days.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ScheduleStep(def: CompiledDefinition) {
    Text("Schedule", style = MaterialTheme.typography.titleLarge)
    Text("Preferred days and times (the week planner moves sessions around your calendar):", style = MaterialTheme.typography.bodyMedium)
    def.doc.schedule.preferred.forEach { p -> Text("• ${p.slot}: ${p.weekday} ${p.start} (${p.durMin} min)", style = MaterialTheme.typography.bodySmall) }
    Text("Home habits:", style = MaterialTheme.typography.bodyMedium)
    def.doc.habits.forEach { h -> Text("• ${h.name["en"]} at ${h.schedule.time}", style = MaterialTheme.typography.bodySmall) }
    Text(
        "The week planner moves sessions around your calendar once calendar access is set up. Habit reminders are off until " +
            "you switch them on in the challenge's dashboard.",
        style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
    )
}

@Composable
private fun CalendarStep() {
    Text("Calendar", style = MaterialTheme.typography.titleLarge)
    Text(
        "The app will add challenge sessions and test days for this week and next to your primary calendar and keep them up to date. " +
            "It only edits events with its marker, and your own edits win. Calendar access is set up in the calendar step of M2b.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ProposalsStep(def: CompiledDefinition, ui: SetupUi, vm: SetupViewModel) {
    Text("Proposals", style = MaterialTheme.typography.titleLarge)
    Text(
        "Decide each proposal. A rule from your program runs as written unless you approve the proposal that replaces it. " +
            "${def.doc.proposals.count { it.id in ui.draft.decisions }} of ${def.doc.proposals.size} decided.",
        style = MaterialTheme.typography.bodyMedium, color = Fit.Muted,
    )
    def.doc.proposals.forEach { p ->
        ProposalTile(
            ProposalUi(
                p.id, p.title["en"] ?: p.id, p.rationale["en"] ?: "", p.ifDeclined["en"] ?: "",
                listOf(p.supersedes.joinToString(", "), (p.affects.rules + p.affects.inSessionRules).joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" -> ").ifEmpty { null },
                ui.draft.decisions[p.id],
            ),
            onDecide = { id, decision -> vm.update { it.copy(decisions = it.decisions + (id to decision)) } },
            onViewed = {},
        )
    }
}

@Composable
private fun ReviewStep(ui: SetupUi) {
    Text("Review: this week and next", style = MaterialTheme.typography.titleLarge)
    if (ui.review.isEmpty()) Text("Computing…", color = Fit.Muted)
    ui.review.forEach { line ->
        Text(line, style = if (line.startsWith("Week")) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Default))
    }
    Text(
        "Pull, grip and elbow-flexor work stays inside challenge sessions; legs and push are allowed on any gap day, including the day before A, but not the day before B or a test.",
        style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
    )
}

@Composable
private fun CutoverStep(ui: SetupUi, vm: SetupViewModel) {
    Text("Cutover", style = MaterialTheme.typography.titleLarge)
    Text("From now on the app is the source of truth for this program.", style = MaterialTheme.typography.bodyMedium)
    listOf(
        "The Todoist session tasks will be retired (outside the app; Claude does it after your confirmation).",
        "The program page is frozen as a reference.",
        "Home habits now come from this app.",
    ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    // The whole row toggles (48 dp target), not only the box.
    Row(
        Modifier.fillMaxWidth().toggleable(ui.draft.cutover, role = Role.Checkbox) { v -> vm.update { it.copy(cutover = v) } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(ui.draft.cutover, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Fit.Accent))
        Text("I confirm the cutover", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
    if (ui.busy) Text("Starting…", color = Fit.Muted)
}
