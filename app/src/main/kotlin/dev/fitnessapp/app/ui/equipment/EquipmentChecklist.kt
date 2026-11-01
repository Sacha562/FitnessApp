package dev.fitnessapp.app.ui.equipment

import dev.fitnessapp.data.db.entity.GymEquipmentEntity
import dev.fitnessapp.data.repo.EquipmentDraft
import dev.fitnessapp.data.repo.EquipmentType
import dev.fitnessapp.data.repo.GymDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The editable state of the equipment checklist (UX 8.7, F7): Basic-Fit defaults, every value editable,
 * unknown values asked, never guessed silently. Turned into `gyms` / `gym_equipment` rows by [drafts].
 */
data class Checklist(
    val gymName: String = "Basic-Fit Amersfoort Ierse Pond",
    val stations: Map<String, Boolean>,
    /** Gym plate sizes; 1.25 kg is in the list only when the gym has it. Plates are unlimited (F7). */
    val plates: List<Double> = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5),
    val barKg: Double = 20.0,
    /** The plate-loaded 45° leg press's sled mass (D64, D68), stored as its `base_kg`: counted, not shown; null = unknown (F7). */
    val sledKg: Double? = null,
    val dumbbells: List<Double> = defaultDumbbells(),
    /** `kg` or `lb`: most stacks are marked in lb (F7). */
    val stackUnit: String = "lb",
    val stackStep: Double = defaultStackStep("lb"),
    val stackMaxKg: Double = 100.0,
    val heavyStackMaxKg: Double = 200.0,
    val assistedMaxPinLb: Double? = null,
    val assistedOffsetKg: Double = 6.5,
    val assistedRatio: Double = 1.0,
    val ownDipBelt: Boolean = true,
    val beltMaxKg: Double = 60.0,
    val beltMaxItems: Int = 4,
    val chalk: Boolean = true,
    /** Stacks set up per machine in the live session (D43, `measured`): kept as stored, never rewritten from [stackUnit]. */
    val ownStacks: Map<String, JsonObject> = emptyMap(),
) {
    companion object {
        /** Stations that are not common at Basic-Fit start unticked. */
        private val OPTIONAL = setOf("wide_bar", "rings", "home_bar", "chalk", "dip_belt", "barbell", "dumbbells")
        val HEAVY_STACKS = setOf("leg_press_seated", "hack_squat", "calf_machine", "hip_thrust_machine")

        /** Gym plates are unlimited (F7): 20 per size is the top of the count range and never binds. */
        const val GYM_PLATE_COUNT = 20
        const val LB_KG = 0.45359237

        fun defaultStackStep(unit: String): Double = if (unit == "lb") 10.0 else 5.0

        fun unitKg(unit: String): Double = if (unit == "lb") LB_KG else 1.0

        fun defaultDumbbells(): List<Double> = (1..5).map { it * 2.0 } + (0..11).map { 12.5 + it * 2.5 }

        fun defaults(types: List<EquipmentType>): Checklist = Checklist(
            stations = types.filter { it.category in setOf("station", "cardio", "implement") && it.key != "home_bar" }
                .associate { it.key to (it.key !in OPTIONAL || it.key in setOf("barbell", "dumbbells")) }
                .toSortedMap(),
        )

        /** Rebuilds the state from stored rows (You > Equipment), keeping defaults for anything missing. */
        fun fromRows(types: List<EquipmentType>, gymName: String?, rows: List<GymEquipmentEntity>): Checklist {
            var c = defaults(types)
            if (rows.isEmpty()) return c
            val byType = rows.associateBy { it.equipment_type }
            c = c.copy(gymName = gymName ?: c.gymName, stations = c.stations.mapValues { (k, v) -> byType[k]?.available ?: v }.toSortedMap())
            byType["barbell"]?.let { r ->
                val o = Json.parseToJsonElement(r.load_scheme).jsonObject
                val plates = o["plates"]?.jsonArray?.map { it.jsonObject }.orEmpty()
                c = c.copy(
                    barKg = o["bar_kg"]?.jsonPrimitive?.doubleOrNull ?: c.barKg,
                    plates = plates.mapNotNull { it["kg"]?.jsonPrimitive?.doubleOrNull }.ifEmpty { c.plates },
                )
            }
            byType["leg_press"]?.takeIf { it.scheme_type == "plates" }?.let { r ->
                val o = Json.parseToJsonElement(r.load_scheme).jsonObject
                c = c.copy(sledKg = (o["base_kg"] ?: o["bar_kg"])?.jsonPrimitive?.doubleOrNull)
            }
            byType["dumbbells"]?.let { r ->
                val v = Json.parseToJsonElement(r.load_scheme).jsonObject["values"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }
                if (!v.isNullOrEmpty()) c = c.copy(dumbbells = v)
            }
            byType["assisted_pullup_machine"]?.let { r ->
                val o = Json.parseToJsonElement(r.load_scheme).jsonObject
                c = c.copy(
                    assistedMaxPinLb = o["max"]?.jsonPrimitive?.doubleOrNull,
                    assistedOffsetKg = o["offset_kg"]?.jsonPrimitive?.doubleOrNull ?: c.assistedOffsetKg,
                    assistedRatio = o["ratio"]?.jsonPrimitive?.doubleOrNull ?: c.assistedRatio,
                )
            }
            byType["dip_belt"]?.let { r ->
                val o = Json.parseToJsonElement(r.load_scheme).jsonObject
                c = c.copy(
                    ownDipBelt = r.available, beltMaxKg = o["max_kg"]?.jsonPrimitive?.doubleOrNull ?: c.beltMaxKg,
                    beltMaxItems = o["max_items"]?.jsonPrimitive?.intOrNull ?: c.beltMaxItems,
                )
            }
            val (own, shared) = rows.filter { it.scheme_type == "stack" }.partition { it.calibration_source == "measured" }
            // The landmine's kg plate grid (D66) is not a machine stack: it never sets the shared unit or step.
            val machineStacks = shared.filter { it.equipment_type != "landmine" }
            c = c.copy(ownStacks = own.associate { it.equipment_type to Json.parseToJsonElement(it.load_scheme).jsonObject }.toSortedMap())
            machineStacks.firstOrNull()?.let { r ->
                val o = Json.parseToJsonElement(r.load_scheme).jsonObject
                val unit = o["unit"]?.jsonPrimitive?.content?.takeIf { it == "kg" || it == "lb" } ?: c.stackUnit
                c = c.copy(stackUnit = unit, stackStep = o["step"]?.jsonPrimitive?.doubleOrNull ?: defaultStackStep(unit))
            }
            byType["chalk"]?.let { c = c.copy(chalk = it.available) }
            return c
        }
    }

    private fun plateList(): List<Double> = plates.sortedDescending().distinct()

    /** The heaviest stack value in [stackUnit] at or below [capKg], on the step grid. */
    fun stackMaxNative(capKg: Double): Double {
        val steps = kotlin.math.floor(capKg / unitKg(stackUnit) / stackStep + 1e-9)
        return java.math.BigDecimal(steps * stackStep).setScale(2, java.math.RoundingMode.HALF_EVEN).toDouble()
    }

    fun gym(): GymDraft = GymDraft(
        name = gymName.ifBlank { "Gym" },
        locationKeywords = listOf("basic-fit", "basicfit", "ierse pond"),
        openingHoursJson = """{"mon":[["07:00","22:30"]],"tue":[["07:00","22:30"]],"wed":[["07:00","22:30"]],"thu":[["07:00","22:30"]],"fri":[["07:00","22:30"]],"sat":[["09:00","16:00"]],"sun":[["09:00","16:00"]]}""",
    )

    /** One draft per equipment type; `changedFromDefault` feeds `equipment_confirmed.changed_from_default`. */
    fun drafts(types: List<EquipmentType>): List<EquipmentDraft> {
        val def = defaults(types)
        val defaultDrafts = if (this == def) emptyMap() else def.drafts(types).associateBy { it.equipmentType }
        val out = mutableListOf<EquipmentDraft>()
        for (t in types) {
            if (t.key == "home_bar") continue
            val available = when (t.key) {
                "dip_belt" -> ownDipBelt
                "chalk" -> chalk
                else -> stations[t.key] ?: false
            }
            val scheme: JsonObject = when (t.key) {
                "barbell" -> platesScheme(barKg)
                "smith_machine" -> platesScheme(null)
                "leg_press" -> platesScheme(null, base = sledKg)
                "landmine" -> ownStacks[t.key] ?: landmineScheme()
                "dumbbells" -> buildJsonObject {
                    put("type", "dumbbells"); put("unit", "kg"); put("per", "hand")
                    put("values", JsonArray(dumbbells.sorted().distinct().map(::JsonPrimitive)))
                }
                "assisted_pullup_machine" -> buildJsonObject {
                    put("type", "pin_assist"); put("unit", "lb"); put("step", 5); put("min", 0)
                    put("max", assistedMaxPinLb?.let(::JsonPrimitive) ?: JsonNull)
                    put("offset_kg", assistedOffsetKg); put("ratio", assistedRatio)
                }
                "dip_belt" -> buildJsonObject {
                    put("type", "belt"); put("unit", "kg"); put("max_kg", beltMaxKg); put("max_items", beltMaxItems)
                    put("dumbbells_on_belt", true)
                    // Unlimited per size: only max_kg and max_items bind (F7).
                    put("plates", JsonArray(plateList().map { kg -> buildJsonObject { put("kg", kg); put("count", beltMaxItems) } }))
                }
                else -> if (t.defaultSchemeType == "stack") ownStacks[t.key] ?: buildJsonObject {
                    put("type", "stack"); put("unit", stackUnit); put("start", stackStep); put("step", stackStep)
                    put("max", stackMaxNative(if (t.key in HEAVY_STACKS) heavyStackMaxKg else stackMaxKg))
                    put("add_on_steps", JsonArray(emptyList())); put("ratio", 1.0)
                } else buildJsonObject { put("type", "bodyweight") }
            }
            val label = t.labelEn
            val defaultDraft = defaultDrafts[t.key]
            val draft = EquipmentDraft(
                equipmentType = t.key, label = label, available = available, loadScheme = scheme,
                calibrationSource = when {
                    t.key == "assisted_pullup_machine" -> "user_estimate"
                    t.key == "leg_press" && sledKg != null -> "measured"
                    t.key in ownStacks -> "measured"
                    else -> "default"
                },
            )
            out += draft.copy(changedFromDefault = defaultDraft != null && (defaultDraft.available != available || defaultDraft.loadScheme != scheme))
        }
        return out
    }

    /** D66: plates on one sleeve, entered as their total on a kg grid of the smallest plate (the bar's share is the exercise's base). */
    private fun landmineScheme(): JsonObject = buildJsonObject {
        val step = plateList().last()
        put("type", "stack"); put("unit", "kg"); put("start", 0.0); put("step", step)
        put("max", java.math.BigDecimal(kotlin.math.floor(100.0 / step + 1e-9) * step).setScale(2, java.math.RoundingMode.HALF_EVEN).toDouble())
        put("add_on_steps", JsonArray(emptyList())); put("ratio", 1.0)
    }

    private fun platesScheme(bar: Double?, base: Double? = null): JsonObject = buildJsonObject {
        put("type", "plates"); put("unit", "kg")
        put("bar_kg", bar?.let(::JsonPrimitive) ?: JsonNull)
        // D68: a machine's own mass is counted but not part of the shown load.
        if (base != null) put("base_kg", base)
        put("plates", JsonArray(plateList().map { kg -> buildJsonObject { put("kg", kg); put("count", GYM_PLATE_COUNT) } }))
    }
}
