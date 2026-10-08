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
    val plates: List<Double> = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5),
    val platesPerSize: Int = 4,
    /** "1.25 kg?" Yes / No / Not sure (treated as absent, safer rounding). */
    val smallPlates: Boolean? = null,
    val barKg: Double = 20.0,
    val dumbbells: List<Double> = defaultDumbbells(),
    val stackStepKg: Double = 5.0,
    val stackMaxKg: Double = 100.0,
    val heavyStackMaxKg: Double = 200.0,
    val assistedName: String = "Assisted pull-up machine",
    val assistedMaxPinLb: Double? = null,
    val assistedOffsetKg: Double = 6.5,
    val assistedRatio: Double = 1.0,
    val ownDipBelt: Boolean = true,
    val beltMaxKg: Double = 60.0,
    val beltMaxItems: Int = 4,
    val beltPlatesPerSize: Int = 2,
    val chalk: Boolean = true,
) {
    companion object {
        /** Stations that are not common at Basic-Fit start unticked. */
        private val OPTIONAL = setOf("wide_bar", "rings", "home_bar", "chalk", "dip_belt", "barbell", "dumbbells")
        val HEAVY_STACKS = setOf("leg_press", "hack_squat", "calf_machine", "hip_thrust_machine")

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
                    plates = plates.mapNotNull { it["kg"]?.jsonPrimitive?.doubleOrNull }.filter { it != 1.25 }.ifEmpty { c.plates },
                    platesPerSize = plates.firstOrNull()?.get("count")?.jsonPrimitive?.intOrNull ?: c.platesPerSize,
                    smallPlates = plates.any { it["kg"]?.jsonPrimitive?.doubleOrNull == 1.25 },
                )
            }
            byType["dumbbells"]?.let { r ->
                val v = Json.parseToJsonElement(r.load_scheme).jsonObject["values"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }
                if (!v.isNullOrEmpty()) c = c.copy(dumbbells = v)
            }
            byType["assisted_pullup_machine"]?.let { r ->
                val o = Json.parseToJsonElement(r.load_scheme).jsonObject
                c = c.copy(
                    assistedName = r.label, assistedMaxPinLb = o["max"]?.jsonPrimitive?.doubleOrNull,
                    assistedOffsetKg = o["offset_kg"]?.jsonPrimitive?.doubleOrNull ?: c.assistedOffsetKg,
                    assistedRatio = o["ratio"]?.jsonPrimitive?.doubleOrNull ?: c.assistedRatio,
                )
            }
            byType["dip_belt"]?.let { r ->
                val o = Json.parseToJsonElement(r.load_scheme).jsonObject
                c = c.copy(
                    ownDipBelt = r.available, beltMaxKg = o["max_kg"]?.jsonPrimitive?.doubleOrNull ?: c.beltMaxKg,
                    beltMaxItems = o["max_items"]?.jsonPrimitive?.intOrNull ?: c.beltMaxItems,
                    beltPlatesPerSize = o["plates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull ?: c.beltPlatesPerSize,
                )
            }
            rows.firstOrNull { it.scheme_type == "stack" }?.let { r ->
                val o = Json.parseToJsonElement(r.load_scheme).jsonObject
                c = c.copy(stackStepKg = o["step"]?.jsonPrimitive?.doubleOrNull ?: c.stackStepKg)
            }
            byType["chalk"]?.let { c = c.copy(chalk = it.available) }
            return c
        }
    }

    private fun plateList(): List<Double> = plates + if (smallPlates == true) listOf(1.25) else emptyList()

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
                    put("plates", JsonArray(plateList().map { kg -> buildJsonObject { put("kg", kg); put("count", beltPlatesPerSize) } }))
                }
                else -> if (t.defaultSchemeType == "stack") buildJsonObject {
                    put("type", "stack"); put("unit", "kg"); put("start", stackStepKg); put("step", stackStepKg)
                    put("max", if (t.key in HEAVY_STACKS) heavyStackMaxKg else stackMaxKg)
                    put("add_on_steps", JsonArray(emptyList())); put("ratio", 1.0)
                } else buildJsonObject { put("type", "bodyweight") }
            }
            val label = if (t.key == "assisted_pullup_machine") assistedName.ifBlank { t.labelEn } else t.labelEn
            val defaultDraft = defaultDrafts[t.key]
            val draft = EquipmentDraft(
                equipmentType = t.key, label = label, available = available, loadScheme = scheme,
                calibrationSource = if (t.key == "assisted_pullup_machine") "user_estimate" else "default",
            )
            out += draft.copy(changedFromDefault = defaultDraft != null && (defaultDraft.available != available || defaultDraft.loadScheme != scheme))
        }
        return out
    }

    private fun platesScheme(bar: Double?): JsonObject = buildJsonObject {
        put("type", "plates"); put("unit", "kg")
        put("bar_kg", bar?.let(::JsonPrimitive) ?: JsonNull)
        put("plates", JsonArray(plateList().map { kg -> buildJsonObject { put("kg", kg); put("count", platesPerSize) } }))
    }
}
