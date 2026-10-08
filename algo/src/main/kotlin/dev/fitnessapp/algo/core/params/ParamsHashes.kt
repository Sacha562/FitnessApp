package dev.fitnessapp.algo.core.params

import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Params hashes (ALGORITHMS.md A8.3): `params_hash` is the lower-case hex SHA-256 of JCS({key: value}),
 * metadata excluded; a module's `constants_hash` is the same over its slice of the defaults. Keys belong
 * to the longest registered module id that prefixes them (A8.2, E53).
 */
object ParamsHashes {
    private val KEY = Regex("^[a-z]+(\\.[a-z0-9_]+)+$")

    /** `{key: value}` from the `params` object of a params file (entries carry value + metadata). */
    fun values(params: JsonObject): JsonObject =
        JsonObject(params.entries.associate { (k, entry) -> k to entry.jsonObject.getValue("value") })

    fun hash(values: JsonObject): String = Jcs.sha256Hex(values)

    /** The registry module id that owns [key]. */
    fun moduleOf(key: String): String {
        require(KEY.matches(key)) { "invalid params key: $key" }
        return ModuleRegistry.entries.map { it.ref.id.value }
            .filter { key.startsWith("$it.") }
            .maxByOrNull { it.length }
            ?: throw IllegalArgumentException("params key $key has no registered module prefix")
    }

    /** Module id -> its slice, for every registry module (empty slices included). */
    fun slices(values: JsonObject): Map<String, JsonObject> {
        val byModule = values.entries.groupBy({ moduleOf(it.key) }, { it.key to it.value })
        return ModuleRegistry.entries.associate { e ->
            val id = e.ref.id.value
            id to JsonObject(byModule[id].orEmpty().toMap<String, JsonElement>())
        }
    }
}
