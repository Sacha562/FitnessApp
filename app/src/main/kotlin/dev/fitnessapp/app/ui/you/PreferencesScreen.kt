package dev.fitnessapp.app.ui.you

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.prescription.warnings.SchemeFacts
import dev.fitnessapp.algo.prescription.warnings.Severity
import dev.fitnessapp.algo.prescription.warnings.Warning
import dev.fitnessapp.algo.prescription.warnings.Warnings
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.format.WarnTexts
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.StepperRow
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.live.AddExerciseSheet
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.repo.RepPrefValues
import dev.fitnessapp.data.repo.RepPreferenceRepository
import dev.fitnessapp.data.repo.SessionRepository
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** One editable scope of the Sets and reps section: its key, label, and the facts its evidence check needs. */
private data class PrefScope(val key: String, val label: String, val compound: Boolean, val highRisk: Boolean)

/**
 * You > Preferences (UX 15.4, D49): the Sets and reps section (the global default, compound and isolation overrides,
 * per-exercise overrides) with the evidence note of `warnings` for each (D10: the preference is kept; "use suggested"
 * writes the suggestion), and the opt-in RIR check sets of the Sessions settings (F14).
 */
@Composable
fun PreferencesRoute(c: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rows by remember { c.repPreferences.observe() }.collectAsState(initial = emptyList())
    val profile by remember { c.profiles.observe() }.collectAsState(initial = null)
    var editing by remember { mutableStateOf<PrefScope?>(null) }
    var picking by remember { mutableStateOf(false) }
    val params = c.params
    val byKey = rows.associateBy { it.scope_key }
    val fixed = listOf(
        PrefScope(RepPreferenceRepository.GLOBAL, "All exercises", compound = true, highRisk = false),
        PrefScope("mechanics:compound", "Compound lifts", compound = true, highRisk = false),
        PrefScope("mechanics:isolation", "Isolation lifts", compound = false, highRisk = false),
    )
    val perExercise = rows.filter { it.scope_kind == "exercise" && it.exercise_id != null }.mapNotNull { r ->
        c.catalog.byId(r.exercise_id!!)?.let { ex -> PrefScope(r.scope_key, ex.nameEn, ex.mechanics != "isolation", ex.safetyClass == "high_risk") }
    }.sortedBy { it.label }

    FitScaffold("Preferences", onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Tile(spacing = 0.dp, padding = PaddingValues(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 6.dp)) {
                TileLabel("Sets and reps", Modifier.padding(start = 10.dp, bottom = 4.dp))
                Text(
                    "Leave a field on Default and the plan picks evidence-based values. What you set is kept; a note explains when it differs from the evidence.",
                    style = MaterialTheme.typography.bodySmall, color = Fit.Muted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
                (fixed + perExercise).forEachIndexed { i, s ->
                    if (i > 0) TileDivider(Modifier.padding(horizontal = 10.dp))
                    val values = byKey[s.key]?.let { RepPreferenceRepository.values(it) } ?: RepPrefValues()
                    PrefRow(s, values, notes(s, values, params)) { editing = s }
                }
                LinkButton("+ Exercise override", { picking = true }, Modifier.padding(start = 4.dp))
            }
            Tile(spacing = 8.dp) {
                TileLabel("Sessions")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("RIR check sets", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "At most once a week, the last set of one machine, cable or isolation exercise: tap how many reps you think are left, then keep going to failure. It calibrates your reps-in-reserve.",
                            style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
                        )
                    }
                    Switch(checked = profile?.rir_calibration_opt_in == true, onCheckedChange = { on -> scope.launch { c.profiles.setRirCalibrationOptIn(on) } })
                }
            }
        }
    }
    editing?.let { s ->
        val values = byKey[s.key]?.let { RepPreferenceRepository.values(it) } ?: RepPrefValues()
        PrefEditor(s, values, params, onDismiss = { editing = null }) { next, ruleIds ->
            editing = null
            scope.launch { c.repPreferences.save(s.key, next, ruleIds) }
        }
    }
    if (picking) AddExerciseSheet(c, onDismiss = { picking = false }) { ex ->
        picking = false
        editing = PrefScope("exercise:" + c.catalog.idOf(ex), ex.nameEn, ex.mechanics != "isolation", ex.safetyClass == "high_risk")
    }
    // `warning_shown` for the notes on screen, once per screen visit (UX 17).
    val shown = remember { HashSet<String>() }
    (fixed + perExercise).forEach { s ->
        val values = byKey[s.key]?.let { RepPreferenceRepository.values(it) } ?: return@forEach
        notes(s, values, params).forEach { w ->
            if (shown.add(w.ruleId + "|" + w.entity)) scope.launch {
                c.sessionRepository.event(
                    EventDraft(
                        type = "warning_shown", screen = "you.preferences", entityType = "rep_preference",
                        payload = mapOf(
                            "rule_id" to JsonPrimitive(w.ruleId), "severity" to JsonPrimitive(w.severity.code), "entity" to JsonPrimitive(w.entity),
                            "magnitude" to JsonPrimitive(w.magnitude), "surface" to JsonPrimitive("you.preferences"),
                        ),
                    ),
                )
            }
        }
    }
}

/** WE-1's scheme rules for the fields the user set (D10: engine defaults need no note). */
private fun notes(s: PrefScope, v: RepPrefValues, params: ParamView): List<Warning> {
    if (v.empty) return emptyList()
    val p = params.fork()
    val rLo = v.repMin ?: p.int("rx.default_hyp_r_lo")
    val rHi = v.repMax ?: maxOf(rLo, p.int("rx.default_hyp_r_hi"))
    val strength = v.repScheme == "fixed" && rHi <= STRENGTH_MAX_REPS
    val q = v.rirTarget ?: if (s.compound) p.double("rx.default_q_hyp_compound") else p.double("rx.default_q_hyp_isolation")
    val facts = SchemeFacts(
        rLo = rLo, rHi = rHi, qStar = if (s.highRisk) maxOf(q, p.double("rx.min_q_high_risk")) else q, qStarAsked = q,
        setsPref = v.setsPref, restS = v.restPrefS?.toDouble(), strength = strength, compound = s.compound, highRisk = s.highRisk,
    )
    return Warnings.rank(Warnings.scheme("rep_preference:" + s.key, facts, p))
}

@Composable
private fun PrefRow(s: PrefScope, v: RepPrefValues, notes: List<Warning>, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(s.label, style = MaterialTheme.typography.titleMedium)
        Text(summary(v), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        notes.firstOrNull()?.let { w ->
            Text(
                WarnTexts.text(w, s.label) + if (w.severity != Severity.SAFETY) " · your choice" else "",
                style = MaterialTheme.typography.bodySmall, color = if (w.severity == Severity.INFO) Fit.Muted else Fit.Advisory,
            )
        }
    }
}

private fun summary(v: RepPrefValues): String {
    if (v.empty) return "Default"
    return listOfNotNull(
        v.setsPref?.let { "$it sets" },
        if (v.repMin != null || v.repMax != null) {
            val lo = v.repMin
            val hi = v.repMax
            (if (v.repScheme == "fixed" || lo == hi) "${hi ?: lo} reps" else "${lo ?: "?"}-${hi ?: "?"} reps")
        } else null,
        v.rirTarget?.let { Numbers.num(it, 1) + " in reserve" },
        v.restPrefS?.let { "$it s rest" },
    ).joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PrefEditor(s: PrefScope, initial: RepPrefValues, params: ParamView, onDismiss: () -> Unit, onSave: (RepPrefValues, List<String>) -> Unit) {
    var v by remember(s.key) { mutableStateOf(initial) }
    val p = params.fork()
    val defLo = p.int("rx.default_hyp_r_lo")
    val defHi = p.int("rx.default_hyp_r_hi")
    val notes = notes(s, v, params)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.label, style = MaterialTheme.typography.titleLarge)
            Field("Sets", v.setsPref?.toString() ?: "Default (${SessionRepository.DEFAULT_SETS})", onDefault = { v = v.copy(setsPref = null) }) {
                StepperRow(v.setsPref?.let { "$it sets" } ?: "Default", null,
                    onMinus = { v = v.copy(setsPref = ((v.setsPref ?: SessionRepository.DEFAULT_SETS) - 1).coerceIn(1, MAX_SETS)) },
                    onPlus = { v = v.copy(setsPref = ((v.setsPref ?: SessionRepository.DEFAULT_SETS) + 1).coerceIn(1, MAX_SETS)) })
            }
            Field("Reps", if (v.repMin == null) "Default ($defLo-$defHi)" else summary(RepPrefValues(repMin = v.repMin, repMax = v.repMax, repScheme = v.repScheme)),
                onDefault = { v = v.copy(repMin = null, repMax = null, repScheme = null) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton("Range", selected = v.repScheme != "fixed", onClick = { v = v.copy(repScheme = "range", repMin = v.repMin ?: defLo, repMax = v.repMax ?: defHi) })
                    ChipButton("Fixed", selected = v.repScheme == "fixed", onClick = { v = v.copy(repScheme = "fixed", repMin = v.repMax ?: defHi, repMax = v.repMax ?: defHi) })
                }
                if (v.repScheme == "fixed") {
                    StepperRow("${v.repMax ?: defHi} reps", null,
                        onMinus = { val r = ((v.repMax ?: defHi) - 1).coerceIn(1, MAX_REPS); v = v.copy(repMin = r, repMax = r) },
                        onPlus = { val r = ((v.repMax ?: defHi) + 1).coerceIn(1, MAX_REPS); v = v.copy(repMin = r, repMax = r) })
                } else {
                    StepperRow("from ${v.repMin ?: defLo}", null,
                        onMinus = { v = v.copy(repScheme = "range", repMin = ((v.repMin ?: defLo) - 1).coerceIn(1, v.repMax ?: defHi), repMax = v.repMax ?: defHi) },
                        onPlus = { v = v.copy(repScheme = "range", repMin = ((v.repMin ?: defLo) + 1).coerceIn(1, v.repMax ?: defHi), repMax = v.repMax ?: defHi) })
                    StepperRow("to ${v.repMax ?: defHi}", null,
                        onMinus = { v = v.copy(repScheme = "range", repMax = ((v.repMax ?: defHi) - 1).coerceIn(v.repMin ?: defLo, MAX_REPS), repMin = v.repMin ?: defLo) },
                        onPlus = { v = v.copy(repScheme = "range", repMax = ((v.repMax ?: defHi) + 1).coerceIn(v.repMin ?: defLo, MAX_REPS), repMin = v.repMin ?: defLo) })
                }
            }
            Field("Reps in reserve", v.rirTarget?.let { Numbers.num(it, 1) } ?: "Default", onDefault = { v = v.copy(rirTarget = null) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..5).forEach { q -> ChipButton(q.toString(), selected = v.rirTarget == q.toDouble(), onClick = { v = v.copy(rirTarget = q.toDouble()) }) }
                }
            }
            Field("Rest", v.restPrefS?.let { "$it s" } ?: "Default", onDefault = { v = v.copy(restPrefS = null) }) {
                StepperRow(v.restPrefS?.let { "$it s" } ?: "Default", null,
                    onMinus = { v = v.copy(restPrefS = ((v.restPrefS ?: DEFAULT_REST_S) - REST_STEP_S).coerceIn(MIN_REST_S, MAX_REST_S)) },
                    onPlus = { v = v.copy(restPrefS = ((v.restPrefS ?: DEFAULT_REST_S) + REST_STEP_S).coerceIn(MIN_REST_S, MAX_REST_S)) })
            }
            notes.forEach { w ->
                Tile(color = Fit.Raised, radius = 18.dp, padding = PaddingValues(12.dp), spacing = 4.dp) {
                    Text(WarnTexts.text(w, s.label), style = MaterialTheme.typography.bodyMedium, color = if (w.severity == Severity.INFO) Fit.Ink else Fit.Advisory)
                    Text("Evidence: " + w.evidenceGrade, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                    suggested(v, w.suggestion)?.let { next -> LinkButton("Use suggested", { v = next }) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("Clear", { onSave(RepPrefValues(), emptyList()) }, Modifier.weight(1f))
                PrimaryButton("Save", { onSave(v, notes.map { it.ruleId }) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onDefault: () -> Unit, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            LinkButton("Default", onDefault)
        }
        content()
    }
}

/** The preference with a warning's suggestion applied (`field:value` codes of WE-1), or null when it has none to apply. */
private fun suggested(v: RepPrefValues, code: String?): RepPrefValues? {
    val c = code ?: return null
    val value = c.substringAfter(':', "")
    return when (c.substringBefore(':')) {
        "q_star" -> value.toDoubleOrNull()?.let { v.copy(rirTarget = it) }
        "sets" -> value.toIntOrNull()?.let { v.copy(setsPref = it) }
        "rest_s" -> value.toIntOrNull()?.let { v.copy(restPrefS = it) }
        "rep_scheme" -> if (value == "fixed_3_5") v.copy(repScheme = "range", repMin = 3, repMax = 5) else null
        else -> null
    }
}

private const val STRENGTH_MAX_REPS = 6
private const val MAX_SETS = 10
private const val MAX_REPS = 100
private const val DEFAULT_REST_S = 120
private const val REST_STEP_S = 15
private const val MIN_REST_S = 30
private const val MAX_REST_S = 600
