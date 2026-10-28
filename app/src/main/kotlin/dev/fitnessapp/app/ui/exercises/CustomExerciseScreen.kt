package dev.fitnessapp.app.ui.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.selection.catalog.CatalogDomains
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.catalog.CatalogValidator
import dev.fitnessapp.algo.selection.catalog.CustomDraft
import dev.fitnessapp.algo.selection.catalog.Prototypes
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.DockedBar
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.Segmented
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.theme.Fit
import kotlinx.coroutines.launch

/** The family headings of step 1 (ALGORITHMS_PLANNING 10.6), in the order the list shows them. */
private val FAMILY_LABELS = linkedMapOf(
    "squat" to "Legs: squat", "hinge" to "Legs: hinge", "legiso" to "Legs: machines", "hip" to "Hips", "calf" to "Calves",
    "press" to "Chest press", "fly" to "Chest fly", "vpush" to "Overhead press", "raise" to "Shoulders", "hpull" to "Rows",
    "vpull" to "Pulldowns", "trap" to "Traps", "curl" to "Biceps", "ext" to "Triceps", "forearm" to "Forearms and grip",
    "core" to "Core", "prehab" to "Rotator cuff", "skill" to "Hangs and skills", "cardio" to "Cardio",
)

/**
 * Step 1 lists these patterns under the one they fold into (D54): the equipment of step 2 picks the pattern, so
 * "Squat" with a machine is `squat_machine`.
 */
private val FOLDED_INTO = mapOf("squat_machine" to "squat")

/** Groups of the gym equipment list (D54), by `equipment_types` key; keys in none of them fall under Machines. */
private val STATION_GROUPS = linkedMapOf(
    "Racks, bars and benches" to setOf("pullup_bar", "fixed_neutral_handle", "dip_station", "smith_machine", "adjustable_bench", "cable_station", "back_extension"),
    "Free weights" to setOf("barbell", "dumbbells", "wide_bar", "rings"),
    "Accessories" to setOf("dip_belt", "chalk"),
)

private val EQUIPMENT_LABELS = mapOf(
    "barbell" to "Barbell", "dumbbell" to "Dumbbell", "machine" to "Machine", "cable" to "Cable", "bodyweight" to "Bodyweight",
    "smith" to "Smith machine", "band" to "Band", "kettlebell" to "Kettlebell", "other" to "Other",
)

private val LOAD_MODE_LABELS = mapOf(
    "external" to "Weight", "bodyweight" to "Bodyweight", "bodyweight_plus_external" to "Bodyweight + weight",
    "assisted_machine" to "Assisted machine", "assisted_ordinal" to "Assistance level",
)

private val LEVELS = listOf(0.0, 0.25, 0.5, 0.75, 1.0)

/**
 * Create a custom exercise (UX 13.5, CAT-1 10.9, D53): pattern, equipment and station, the prototype's coefficients on
 * the muscle map, details, and the optional Advanced step that edits region coefficients. CAT-1's validator runs on
 * save; its errors are shown next to the step they belong to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomExerciseRoute(c: AppContainer, initialName: String, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val table = remember { Prototypes.table(c.catalog.doc) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(1) }
    // The step-1 choice; with the equipment it resolves to the pattern (FOLDED_INTO).
    var choice by remember { mutableStateOf<String?>(null) }
    var equipment by remember { mutableStateOf<String?>(null) }
    fun patternsOf(ch: String) = listOf(ch) + FOLDED_INTO.filterValues { it == ch }.keys.sorted()
    val pattern: String? = choice?.let { ch -> equipment?.let { eq -> patternsOf(ch).firstOrNull { (it to eq) in table } } }
    fun equipmentChoices(ch: String) = CatalogDomains.EQUIPMENT_CLASSES.filter { eq -> patternsOf(ch).any { (it to eq) in table } }
    var stations by remember { mutableStateOf<List<String>>(emptyList()) }
    var nameEn by remember { mutableStateOf(initialName) }
    var loadMode by remember { mutableStateOf<String?>(null) }
    var unilateral by remember { mutableStateOf(false) }
    var timed by remember { mutableStateOf(false) }
    var flags by remember { mutableStateOf<List<String>>(emptyList()) }
    var edited by remember { mutableStateOf<Map<String, Double>?>(null) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    val proto: CatalogExercise? = pattern?.let { p -> equipment?.let { table[p to it] } }
    val stationTypes = remember { c.equipment.types.filter { it.category in setOf("station", "implement", "accessory", "cardio") }.sortedBy { it.sort } }

    fun pickPrototype(p: CatalogExercise) {
        stations = p.requiresStation
        loadMode = p.loadMode
        unilateral = p.laterality != "bilateral"
        timed = p.measure == "duration"
        flags = p.flagsOffered
        edited = null
    }

    fun draft(slug: String) = proto?.let {
        Prototypes.copy(
            it, CustomDraft(slug, nameEn, null, pattern!!, equipment!!, stations, loadMode ?: it.loadMode, unilateral, timed, flags, edited),
            c.catalog.version,
        )
    }

    fun save() {
        if (saving) return
        val p = proto ?: return
        scope.launch {
            saving = true
            val problems = ArrayList<String>()
            if (nameEn.isBlank()) problems += "Give it a name."
            else if (c.customExercises.nameTaken(nameEn)) problems += "Another exercise is already called '${nameEn.trim()}'."
            val slug = c.customExercises.slugFor(nameEn.ifBlank { "exercise" })
            val e = draft(slug)!!
            val stationKeys = c.equipment.types.map { it.key }.toSet()
            problems += CatalogValidator.validateRow(c.catalogModel, e, stationKeys, seed = false).map { issueText(it.code, it.message) }
            errors = problems.distinct()
            if (errors.isEmpty()) onSaved(c.customExercises.create(e, p.slug, coefficientsEdited = edited != null)) else saving = false
        }
    }

    val canNext = when (step) {
        1 -> choice != null
        2 -> proto != null
        4 -> nameEn.isNotBlank()
        else -> true
    }
    FitScaffold(
        "New exercise · step $step of 5", onBack = { if (step > 1) step-- else onBack() },
        bottomBar = {
            DockedBar {
                errors.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fit.Safety) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (step == 4) TonalButton("Save", ::save, Modifier.weight(1f).heightIn(min = 58.dp), enabled = canNext && !saving)
                    if (step < 5) {
                        PrimaryButton("Next", { errors = emptyList(); step++ }, Modifier.weight(1f), enabled = canNext)
                    } else {
                        PrimaryButton("Save", ::save, Modifier.weight(1f), enabled = !saving)
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                1 -> {
                    Text("What kind of movement is it?", style = MaterialTheme.typography.titleLarge)
                    val byFamily = Prototypes.patterns(table).filter { it !in FOLDED_INTO }.groupBy { CatalogDomains.FAMILY_OF_PATTERN.getValue(it) }
                    FAMILY_LABELS.filterKeys { it in byFamily }.forEach { (fam, label) ->
                        Tile(spacing = 8.dp) {
                            TileLabel(label)
                            ChoiceChips(byFamily.getValue(fam), choice, CatalogQuery::patternLabel) { p ->
                                choice = p
                                // D54: a single equipment class is chosen for the user.
                                val only = equipmentChoices(p).singleOrNull()
                                equipment = only
                                only?.let { eq -> patternsOf(p).firstNotNullOfOrNull { table[it to eq] }?.let(::pickPrototype) }
                            }
                        }
                    }
                }
                2 -> {
                    Text("What equipment?", style = MaterialTheme.typography.titleLarge)
                    Tile(spacing = 8.dp) {
                        TileLabel("Equipment")
                        ChoiceChips(equipmentChoices(choice!!), equipment, { EQUIPMENT_LABELS[it] ?: it }) { eq ->
                            equipment = eq
                            patternsOf(choice!!).firstNotNullOfOrNull { table[it to eq] }?.let(::pickPrototype)
                        }
                    }
                    if (proto != null) {
                        Tile(spacing = 10.dp) {
                            TileLabel("Gym equipment it needs")
                            Text("If your gym lacks one of these, the exercise isn't suggested.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                            val groups = stationTypes.groupBy { t -> STATION_GROUPS.entries.firstOrNull { t.key in it.value }?.key ?: "Machines" }
                            (listOf("Machines") + STATION_GROUPS.keys).filter { it in groups }.forEach { g ->
                                Text(g, style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    groups.getValue(g).forEach { t ->
                                        ChipButton(t.labelEn, selected = t.key in stations, role = Role.Checkbox, onClick = {
                                            stations = if (t.key in stations) stations - t.key else (stations + t.key).sorted()
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> {
                    val e = draft("custom_preview")
                    Text("Muscles", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Copied from a similar exercise (an expert estimate). You can change them in the last step, Advanced.",
                        style = MaterialTheme.typography.bodyMedium, color = Fit.Muted,
                    )
                    if (e != null) MusclePreview(c, e, proto!!.nameEn)
                    LinkButton("Adjust muscles", { step = 5 })
                }
                4 -> {
                    Text("Details", style = MaterialTheme.typography.titleLarge)
                    Tile(spacing = 8.dp) {
                        TileLabel("Name")
                        FitTextField(nameEn, { nameEn = it.take(80) }, Modifier.fillMaxWidth(), placeholder = "Name", singleLine = true)
                    }
                    proto?.let { p ->
                        if (p.allowedLoadModes.size > 1) Tile(spacing = 8.dp) {
                            TileLabel("Load")
                            ChoiceChips(p.allowedLoadModes, loadMode, { LOAD_MODE_LABELS[it] ?: it }) { loadMode = it }
                        }
                    }
                    Tile(spacing = 8.dp) {
                        TileLabel("Sides")
                        Segmented(listOf(false, true), unilateral, { if (it) "One side at a time" else "Both sides together" }, { unilateral = it })
                        TileLabel("Measured by", Modifier.padding(top = 8.dp))
                        Segmented(listOf(false, true), timed, { if (it) "Time (hold)" else "Reps" }, { timed = it })
                    }
                    Tile(spacing = 8.dp) {
                        TileLabel("Flags to offer while logging")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CatalogDomains.SET_FLAGS.forEach { f ->
                                ChipButton(flagLabel(f), selected = f in flags, role = Role.Checkbox, onClick = { flags = if (f in flags) flags - f else flags + f })
                            }
                        }
                    }
                }
                else -> {
                    Text("Advanced (optional)", style = MaterialTheme.typography.titleLarge)
                    Text("Change how strongly each muscle region is trained. Leave it as is unless you know better.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                    val base = edited ?: proto!!.stimulus
                    c.catalogModel.taxonomy.activeGroups.forEach { g ->
                        val regions = c.catalogModel.taxonomy.regionsOf(g.id)
                        if (regions.isEmpty()) return@forEach
                        Tile(spacing = 6.dp) {
                            TileLabel(g.labelEn)
                            regions.forEach { r ->
                                Text(r.labelEn, style = MaterialTheme.typography.bodyMedium)
                                Segmented(LEVELS, LEVELS.minBy { kotlin.math.abs(it - (base[r.id] ?: 0.0)) }, { if (it == 0.0) "0" else Numbers.num(it) }, { v ->
                                    edited = (base + (r.id to v)).filterValues { it > 0.0 }
                                })
                            }
                        }
                    }
                    if (edited != null) LinkButton("Reset to the estimate", { edited = null })
                }
            }
        }
    }
}

@Composable
private fun MusclePreview(c: AppContainer, e: CatalogExercise, protoName: String) {
    val catalog = c.catalogModel
    val coeffs = catalog.taxonomy.activeGroups.associate { it.id to catalog.groupCoeff(e, it.id) }.filterValues { it > 0.0 }
    Tile(spacing = 6.dp) {
        TileLabel("Copied from $protoName")
        MuscleMap(coeffs, Modifier.padding(vertical = 8.dp).heightIn(max = 320.dp)) {}
        catalog.taxonomy.activeGroups.filter { (coeffs[it.id] ?: 0.0) > 0.0 }.sortedByDescending { coeffs[it.id] }.forEach { g ->
            Text(g.labelEn + " · " + Numbers.num(coeffs.getValue(g.id)), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** CAT-1 issue codes in the user's words (UX 13.5: errors next to the field). */
private fun issueText(code: String, message: String): String = when (code) {
    "V1" -> "Each muscle value must be 0, 0.25, 0.5, 0.75 or 1."
    "V3" -> "At least one muscle region needs 0.75 or more (Advanced step)."
    "V6" -> "Pick stations from the list."
    else -> "Not valid yet: $message"
}

private fun flagLabel(f: String) = when (f) {
    "grip_slip_before_last" -> "Grip slipped"
    "jerky" -> "Jerky reps"
    "torso_rotation" -> "Torso rotated"
    "hip_rotation" -> "Hips rotated"
    "technique_breakdown" -> "Technique broke down"
    "failed_rep" -> "Failed rep"
    "hand_shift" -> "Hand shifted"
    "used_straps" -> "Used straps"
    else -> f.replace('_', ' ')
}
