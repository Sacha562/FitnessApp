package dev.fitnessapp.app.ui.exercises

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.prescription.rest.RestClass
import dev.fitnessapp.algo.prescription.rest.RestInput
import dev.fitnessapp.algo.prescription.rest.RestPolicy
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.format.SetLines
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.MoreSection
import dev.fitnessapp.app.ui.common.Segmented
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.dutchNames
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.repo.PreferenceRepository
import dev.fitnessapp.data.telemetry.EventDraft
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** Helper text of the five states (UX 13.4, C26). */
fun preferenceHelp(p: String): String = when (p) {
    "favorite" -> "Picked first whenever it fits the session."
    "like" -> "Preferred over similar exercises."
    "dislike" -> "Avoided unless it's the only good option."
    "never" -> "Never suggested; you can still add it yourself."
    else -> "No preference."
}

/**
 * Exercise detail (UX 13.3): header, muscle map with the group list (its accessible equivalent), the five-state
 * preference, the e1RM trend (SSE-1, M3.2) above the last five sessions, loading, rest and flags. Alternatives (SUB-1)
 * arrive with M3.6 and "Used by" later (DECISIONS changelog 2026-10-11, D49).
 */
@Composable
fun ExerciseDetailRoute(c: AppContainer, exerciseId: String, from: String, onBack: () -> Unit) {
    val ex = c.catalog.byId(exerciseId) ?: run { onBack(); return }
    val prefs by remember { c.preferences.observe() }.collectAsState(initial = emptyMap())
    val pref = prefs[exerciseId] ?: PreferenceRepository.NEUTRAL
    val history by remember(exerciseId) { c.sessionRepository.observeExerciseHistory(exerciseId, 200) }.collectAsState(initial = emptyList())
    val e1rm by remember(exerciseId) { c.estimates.observeE1rm(exerciseId) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var tapped by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(exerciseId) {
        c.sessionRepository.event(
            EventDraft(type = "exercise_opened", screen = "exercises.detail", entityType = "exercise", entityId = exerciseId, payload = mapOf("exercise_id" to JsonPrimitive(exerciseId), "from" to JsonPrimitive(from))),
        )
    }

    val catalog = c.catalogModel
    val dutch = dutchNames()
    val groups = catalog.taxonomy.activeGroups.map { it to catalog.groupCoeff(ex, it.id) }.filter { it.second > 0.0 }
        .sortedWith(compareByDescending<Pair<dev.fitnessapp.algo.selection.catalog.MuscleGroup, Double>> { it.second }.thenBy { it.first.sort })
    val coeffs = groups.associate { it.first.id to it.second }
    val stationLabels = remember { c.equipment.types.associate { it.key to it.labelEn } }
    fun station(code: String) = stationLabels[code] ?: code.replace('_', ' ')

    FitScaffold(ex.nameEn, onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                listOfNotNull(ex.nameNl?.takeIf { dutch && it != ex.nameEn }, ex.equipment.joinToString(", ", transform = ::station), CatalogQuery.patternLabel(ex.pattern)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = Fit.Muted, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Tile(spacing = 6.dp) {
                TileLabel("Muscles")
                MuscleMap(coeffs, Modifier.padding(vertical = 8.dp).heightIn(max = 320.dp)) { tapped = it }
                tapped?.let { g ->
                    val label = catalog.taxonomy.activeGroups.firstOrNull { it.id == g }?.labelEn ?: g
                    val regions = catalog.taxonomy.regionsOf(g).filter { (ex.stimulus[it.id] ?: 0.0) > 0.0 }.map { it.labelEn }
                    Text(if (regions.isEmpty()) label else "$label: " + regions.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Fit.Ink)
                }
                // The group names are the map's accessible equivalent; bars, numbers and regions sit behind "Muscle details" (D35).
                if (groups.isEmpty()) {
                    Text("No muscle map for this exercise.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                } else {
                    Text(groups.joinToString(", ") { it.first.labelEn }, style = MaterialTheme.typography.bodyMedium)
                    MoreSection("Muscle details") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            groups.forEach { (g, coef) -> CoefficientRow(g.labelEn, coef) }
                            catalog.taxonomy.activeRegions.filter { (ex.stimulus[it.id] ?: 0.0) > 0.0 }.forEach { r ->
                                Text(r.labelEn + " · " + Numbers.num(ex.stimulus.getValue(r.id)), style = MaterialTheme.typography.bodySmall)
                            }
                            ex.evidenceNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
                            Text("Expert estimates.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                        }
                    }
                }
            }

            Tile(spacing = 10.dp) {
                TileLabel("Your preference")
                // No Undo snackbar (D30): tapping the previous state is the way back.
                Segmented(PreferenceRepository.STATES, pref, ::shortPref, onSelect = { st ->
                    if (st != pref) scope.launch { c.preferences.set(exerciseId, st, if (from == "session") "session" else "detail") }
                })
                Text(preferenceHelp(pref), style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
            }

            Tile(spacing = 0.dp, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
                TileLabel("History", Modifier.padding(bottom = 6.dp))
                if (e1rm.isNotEmpty()) {
                    val p = c.params.fork()
                    E1rmTrend(e1rm, ex.bwFraction, ex.implementBaseKg ?: 0.0, p.int("ui.progress.window_weeks"), p.double("ui.progress.band_opacity").toFloat())
                }
                val zone = ZoneId.of("Europe/Amsterdam")
                val sessions = history.groupBy { it.set.session_id }.entries.take(5)
                if (sessions.isEmpty()) Text("Not logged yet.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                sessions.forEachIndexed { i, (_, rows) ->
                    if (i > 0) TileDivider()
                    val first = rows.first()
                    val date = (first.session_started_at ?: first.set.completed_at).let {
                        DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(it).atZone(zone))
                    }
                    val working = rows.map { it.set }.filter { it.set_kind != "warmup" }.sortedWith(compareBy({ it.set_index }, { it.side }))
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(date, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(92.dp))
                        Text(SetLines.compact(working), style = MaterialTheme.typography.bodyMedium, color = Fit.Muted, modifier = Modifier.weight(1f))
                    }
                }
            }

            Tile(spacing = 4.dp) {
                TileLabel("Loading")
                Text(loadingLine(ex, ::station), style = MaterialTheme.typography.bodyMedium)
                TileLabel("Rest", Modifier.padding(top = 8.dp))
                val rest = remember(ex.restClass) { RestPolicy.decide(RestInput(RestClass.of(ex.restClass)), c.params.fork()).timerS.toLong() }
                Text("About ${Numbers.clock(rest)} (${restClassLabel(ex.restClass)})", style = MaterialTheme.typography.bodyMedium)
                val flags = buildList {
                    if (ex.laterality != "bilateral") add("Left and right logged separately")
                    if (ex.measure == "duration") add("Timed hold")
                    ex.flagsOffered.forEach { add(flagText(it)) }
                }
                if (flags.isNotEmpty()) {
                    TileLabel("While logging", Modifier.padding(top = 8.dp))
                    flags.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun CoefficientRow(label: String, coefficient: Double) {
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(140.dp))
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Fit.Raised)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(coefficient.toFloat().coerceIn(0f, 1f)).clip(RoundedCornerShape(5.dp)).background(Fit.Accent.copy(alpha = shadeAlpha(coefficient))))
        }
        Text(Numbers.num(coefficient), style = MaterialTheme.typography.labelMedium, color = Fit.Muted, modifier = Modifier.padding(start = 10.dp).width(36.dp))
    }
}

private fun shortPref(p: String) = when (p) {
    "favorite" -> "Fav"
    else -> prefLabel(p)
}

private fun loadingLine(e: CatalogExercise, station: (String) -> String): String {
    val mode = LoadMode.of(e.loadMode)
    val base = when (mode) {
        LoadMode.BODYWEIGHT -> "Bodyweight, counts ${Numbers.num(e.bwFraction * 100, 0)} % of bodyweight"
        LoadMode.BODYWEIGHT_PLUS_EXTERNAL -> "Bodyweight (${Numbers.num(e.bwFraction * 100, 0)} %) plus the dip belt"
        LoadMode.ASSISTED_MACHINE -> "Assisted machine pin; less help is harder"
        LoadMode.ASSISTED_ORDINAL -> "Assistance level"
        LoadMode.EXTERNAL -> "Weight" + (e.implementBaseKg?.takeIf { it > 0 }?.let { " plus ${Numbers.kg(it)} bar or carriage" } ?: "")
    }
    val extra = buildList {
        if ("bodyweight_plus_external" in e.allowedLoadModes && mode != LoadMode.BODYWEIGHT_PLUS_EXTERNAL) add("can be loaded with the dip belt")
        if ("assisted_machine" in e.allowedLoadModes && mode != LoadMode.ASSISTED_MACHINE) add("can be assisted")
        if (e.requiresStation.isNotEmpty()) add("needs " + e.requiresStation.joinToString(", ", transform = station))
    }
    return (listOf(base) + extra).joinToString("; ")
}

private fun restClassLabel(c: String) = when (c) {
    "strength_heavy" -> "heavy strength"
    "compound_lower" -> "lower compound"
    "compound_upper" -> "upper compound"
    else -> c.replace('_', ' ')
}

private fun flagText(f: String) = when (f) {
    "grip_slip_before_last" -> "Flag: grip slipped before the last rep"
    "jerky" -> "Flag: jerky rep"
    "torso_rotation" -> "Flag: torso rotation"
    "hip_rotation" -> "Flag: hip rotation"
    "technique_breakdown" -> "Flag: technique breakdown"
    "failed_rep" -> "Flag: failed rep"
    "hand_shift" -> "Flag: hand shift"
    "used_straps" -> "Flag: used straps"
    else -> "Flag: " + f.replace('_', ' ')
}
