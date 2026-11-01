package dev.fitnessapp.algo.core.load

import dev.fitnessapp.algo.core.params.ParamView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One plate size and the number of plates owned (pairs are formed by the loader, B2.4). */
data class PlateCount(val kg: Double, val count: Int)

/**
 * `gym_equipment.load_scheme` shapes (DATA_MODEL 9.3.3, E33). The `load` module validates them ([parse]);
 * the server may additionally check them with pg_jsonschema.
 */
sealed class LoadScheme(val type: String) {
    /**
     * Barbell (with [barKg]: the shown load includes the bar) or Smith / plate-loaded machine ([barKg] null: the shown
     * load is the plates only). [baseKg] is such a machine's own moving mass (the 45° leg press sled, D68): counted in
     * the load, never shown in it; null falls back to the exercise's `implement_base_kg`.
     */
    data class Plates(val barKg: Double?, val plates: List<PlateCount>, val baseKg: Double? = null) : LoadScheme("plates")

    data class Belt(
        val plates: List<PlateCount>,
        val maxKg: Double,
        val maxItems: Int,
        val dumbbellsOnBelt: Boolean,
    ) : LoadScheme("belt")

    /** Values per hand in [unit] (`kg` or `lb`). */
    data class Dumbbells(val unit: String, val values: List<Double>) : LoadScheme("dumbbells")

    /** [unit] `kg`, `lb` or `level`; a level stack without [levelKg] is ordinal (C43). */
    data class Stack(
        val unit: String,
        val start: Double,
        val step: Double,
        val max: Double,
        val addOnSteps: List<Double>,
        val ratio: Double,
        val levelKg: Double?,
    ) : LoadScheme("stack")

    /** C2 calibration of an assisted machine; [max] null = not asked yet (`load.pin_max_unknown_native`). */
    data class PinAssist(
        val unit: String,
        val step: Double,
        val min: Double,
        val max: Double?,
        val offsetKg: Double,
        val ratio: Double,
    ) : LoadScheme("pin_assist")

    data object Bodyweight : LoadScheme("bodyweight")

    data class Fixed(val kg: Double) : LoadScheme("fixed")

    companion object {
        /** Parses and validates a stored scheme; throws [IllegalArgumentException] with the first problem. */
        fun parse(json: JsonObject): LoadScheme {
            val type = json.str("type")
            return when (type) {
                "plates" -> {
                    unit(json, "kg")
                    val bar = json.numOrNull("bar_kg")?.also { nonNegative("bar_kg", it) }
                    val base = json.numOrNull("base_kg")?.also { nonNegative("base_kg", it) }
                    require(bar == null || base == null) { "plates take bar_kg (shown) or base_kg (not shown), not both" }
                    Plates(bar, plates(json), base)
                }
                "belt" -> Belt(
                    plates(json), json.num("max_kg").also { positive("max_kg", it) },
                    json.num("max_items").also { require(it >= 1 && it == kotlin.math.floor(it)) { "max_items must be an integer >= 1" } }.toInt(),
                    json["dumbbells_on_belt"]?.jsonPrimitive?.booleanOrNull ?: false,
                ).also { unit(json, "kg") }
                "dumbbells" -> {
                    val u = unit(json, "kg", "lb")
                    val values = json.nums("values")
                    require(values.isNotEmpty()) { "dumbbells need at least one value" }
                    values.forEach { positive("values", it) }
                    Dumbbells(u, values.distinct().sorted())
                }
                "stack" -> {
                    val u = unit(json, "kg", "lb", "level")
                    val s = Stack(
                        u, json.num("start"), json.num("step"), json.num("max"),
                        json["add_on_steps"]?.let { nums(it) } ?: emptyList(),
                        json.numOrNull("ratio") ?: 1.0,
                        json.numOrNull("level_kg"),
                    )
                    nonNegative("start", s.start); positive("step", s.step); positive("ratio", s.ratio)
                    require(s.max >= s.start) { "stack max below start" }
                    s.addOnSteps.forEach { positive("add_on_steps", it) }
                    require(s.levelKg == null || u == "level") { "level_kg only for unit level" }
                    s.levelKg?.let { positive("level_kg", it) }
                    s
                }
                "pin_assist" -> {
                    val u = unit(json, "lb", "kg")
                    val p = PinAssist(
                        u, json.num("step"), json.num("min"), json.numOrNull("max"),
                        json.num("offset_kg"), json.numOrNull("ratio") ?: 1.0,
                    )
                    positive("step", p.step); nonNegative("min", p.min); nonNegative("offset_kg", p.offsetKg)
                    positive("ratio", p.ratio)
                    p.max?.let { require(it >= p.min) { "pin max below min" } }
                    p
                }
                "bodyweight" -> Bodyweight
                "fixed" -> Fixed(json.num("kg").also { nonNegative("kg", it) }).also { unit(json, "kg") }
                else -> throw IllegalArgumentException("unknown load scheme type $type")
            }
        }

        /** The unverified default barbell of `load.default_plate_inventory` (F7). */
        fun defaultBarbell(params: ParamView): Plates {
            val t = params.table("load.default_plate_inventory")
            val bar = requireNotNull(t["bar_kg"]) { "default plate inventory without bar_kg" }
            val plates = t.filterKeys { it != "bar_kg" }.map { (k, n) -> PlateCount(k.toDouble(), n.toInt()) }
            return Plates(bar, plates.sortedByDescending { it.kg })
        }

        private fun plates(json: JsonObject): List<PlateCount> {
            val arr = json["plates"] as? JsonArray ?: throw IllegalArgumentException("plates must be an array")
            return arr.map {
                val o = it.jsonObject
                val kg = o.num("kg").also { k -> positive("plates.kg", k) }
                val count = o.num("count")
                require(count >= 0 && count == kotlin.math.floor(count)) { "plates.count must be a non-negative integer" }
                PlateCount(kg, count.toInt())
            }.groupBy { it.kg }.map { (kg, l) -> PlateCount(kg, l.sumOf { it.count }) }.sortedByDescending { it.kg }
        }

        private fun unit(json: JsonObject, vararg allowed: String): String {
            val u = json["unit"]?.jsonPrimitive?.content ?: allowed.first()
            require(u in allowed) { "unit $u not allowed here" }
            return u
        }

        private fun positive(name: String, x: Double) = require(x > 0) { "$name must be > 0" }
        private fun nonNegative(name: String, x: Double) = require(x >= 0) { "$name must be >= 0" }

        private fun JsonObject.str(key: String): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key missing")

        private fun JsonObject.num(key: String): Double = numOrNull(key) ?: throw IllegalArgumentException("$key missing")

        private fun JsonObject.numOrNull(key: String): Double? {
            val e = this[key] ?: return null
            if (e is JsonNull) return null
            return number(e, key)
        }

        private fun JsonObject.nums(key: String): List<Double> = nums(this[key] ?: throw IllegalArgumentException("$key missing"))

        private fun nums(e: JsonElement): List<Double> =
            (e as? JsonArray ?: throw IllegalArgumentException("expected an array")).map { number(it, "array element") }

        private fun number(e: JsonElement, key: String): Double {
            val p = e as? JsonPrimitive
            require(p != null && !p.isString && p.booleanOrNull == null) { "$key must be a number" }
            val x = p.content.toDouble()
            require(!x.isNaN() && !x.isInfinite()) { "$key must be finite" }
            return x
        }
    }
}
