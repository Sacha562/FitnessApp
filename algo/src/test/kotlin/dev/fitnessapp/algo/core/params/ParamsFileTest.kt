package dev.fitnessapp.algo.core.params

import dev.fitnessapp.algo.core.registry.ModuleRegistry
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Structural checks of the bundled params file (ALGORITHMS.md A8.1-A8.3) until the per-module ParamSpecs
 * and ParamsCoverageTest exist (M2/M3). The pinned hash is the one migration 019 stored on the server (1.0.1); a
 * changed file must come with a new params version and a new data migration.
 */
class ParamsFileTest {
    private val text = requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.1.json")) {
        "bundled params file missing"
    }.readText(Charsets.UTF_8)
    private val doc = Json.parseToJsonElement(text).jsonObject
    private val params = doc.getValue("params").jsonObject

    @Test
    fun `version, key format and module prefixes`() {
        doc.getValue("params_version").jsonPrimitive.content shouldBe "1.0.1"
        val keyPattern = Regex("^[a-z]+(\\.[a-z0-9_]+)+$")
        params.keys.forEach { key ->
            require(keyPattern.matches(key)) { "bad key $key" }
            ParamsHashes.moduleOf(key) // throws when no registered module prefixes the key
        }
        params.keys.toList() shouldBe params.keys.sorted()
    }

    @Test
    fun `values are within bounds and regexes compile`() {
        params.forEach { (key, e) ->
            val entry = e.jsonObject
            val value = entry.getValue("value")
            if (entry["type"]?.jsonPrimitive?.content == "regex") {
                Regex((value as JsonPrimitive).content)
                return@forEach
            }
            val min = entry["min"]?.jsonPrimitive?.doubleOrNull
            val max = entry["max"]?.jsonPrimitive?.doubleOrNull
            numbers(value).forEach { x ->
                if (min != null) require(x >= min) { "$key: $x < $min" }
                if (max != null) require(x <= max) { "$key: $x > $max" }
            }
            significantDigits(value).forEach { d -> require(d <= 10) { "$key: more than 10 significant digits" } }
        }
    }

    @Test
    fun `params hash equals the seeded server value`() {
        ParamsHashes.hash(ParamsHashes.values(params)) shouldBe
            "e9c5f0ed0b315a8b51ce06ee2268a6d1402dd7c01ff6d620eed406d3f97e8469"
        ParamsHashes.slices(ParamsHashes.values(params)).keys shouldBe
            ModuleRegistry.entries.map { it.ref.id.value }.toSet()
    }

    private fun numbers(e: JsonElement): List<Double> = when (e) {
        is JsonPrimitive -> if (e.isString || e.booleanOrNull != null) emptyList() else listOfNotNull(e.doubleOrNull)
        is JsonArray -> e.flatMap(::numbers)
        is JsonObject -> e.values.flatMap(::numbers)
        else -> emptyList()
    }

    private fun significantDigits(e: JsonElement): List<Int> = when (e) {
        is JsonPrimitive -> if (e.isString || e.booleanOrNull != null) emptyList()
        else listOf(BigDecimal(e.content).stripTrailingZeros().precision())
        is JsonArray -> e.flatMap(::significantDigits)
        is JsonObject -> e.values.flatMap(::significantDigits)
        else -> emptyList()
    }
}
