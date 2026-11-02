package dev.fitnessapp.algo.core.params

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.util.TreeMap
import java.util.TreeSet

/**
 * The resolved `{key: value}` params map with read tracking (ALGORITHMS.md A8, P15). Modules read params
 * only through a view; [readKeys] feeds `DecisionTrace.paramsRead`. A view is cheap: create one per engine
 * call with [fork] so that read tracking is per call.
 */
class ParamView(private val values: JsonObject) {
    private val read = TreeSet<String>()

    // Parsed values per key: the planners read the same keys in inner loops (weekplan's enumeration calls the allocator
    // and TIME-1 thousands of times), and parsing decimal text every time dominated their run time. The values are
    // immutable, so caching changes nothing but speed; tables are copied out so a caller can never alter the cache.
    private val numbers = TreeMap<String, Double>()
    private val tables = TreeMap<String, TreeMap<String, Double>>()
    private val arrayTables = TreeMap<String, TreeMap<String, List<Double>>>()

    val hash: String by lazy { ParamsHashes.hash(values) }

    fun fork(): ParamView = ParamView(values)

    /** The resolved `{key: value}` object behind [hash] (stored in `params_snapshots.params`). Not read-tracked. */
    fun values(): JsonObject = values

    /** Keys read through this view, in code point order. */
    fun readKeys(): List<String> = read.toList()

    fun has(key: String): Boolean = values.containsKey(key)

    fun double(key: String): Double {
        numbers[key]?.let { read += key; return it }
        return number(primitive(key), key).also { numbers[key] = it }
    }

    fun int(key: String): Int {
        val x = double(key)
        require(x == kotlin.math.floor(x)) { "params key $key is not an integer" }
        return x.toInt()
    }

    fun boolean(key: String): Boolean =
        requireNotNull(primitive(key).booleanOrNull) { "params key $key is not a boolean" }

    fun string(key: String): String {
        val p = primitive(key)
        require(p.isString) { "params key $key is not a string" }
        return p.content
    }

    /** An object of numbers (class tables such as `rest.base`), sorted by key. */
    fun table(key: String): TreeMap<String, Double> {
        tables[key]?.let { read += key; return TreeMap(it) }
        return TreeMap(parseTable(key).also { tables[key] = it })
    }

    private fun parseTable(key: String): TreeMap<String, Double> {
        val obj = element(key) as? JsonObject ?: throw IllegalArgumentException("params key $key is not a table")
        val out = TreeMap<String, Double>()
        obj.forEach { (k, v) -> out[k] = number(v as JsonPrimitive, "$key.$k") }
        return out
    }

    /** An object of number arrays (tier tables such as `warmup.tier_high`), sorted by key. */
    fun tableArrays(key: String): TreeMap<String, List<Double>> {
        arrayTables[key]?.let { read += key; return TreeMap(it) }
        return TreeMap(parseTableArrays(key).also { arrayTables[key] = it })
    }

    private fun parseTableArrays(key: String): TreeMap<String, List<Double>> {
        val obj = element(key) as? JsonObject ?: throw IllegalArgumentException("params key $key is not a table")
        val out = TreeMap<String, List<Double>>()
        obj.forEach { (k, v) ->
            val arr = v as? JsonArray ?: throw IllegalArgumentException("params key $key.$k is not an array")
            out[k] = arr.map { number(it as JsonPrimitive, "$key.$k") }
        }
        return out
    }

    /** An array of strings (enum lists such as `rircal.eligible_safety_classes`), in file order. */
    fun strings(key: String): List<String> {
        val arr = element(key) as? JsonArray ?: throw IllegalArgumentException("params key $key is not an array")
        return arr.map {
            val p = it as? JsonPrimitive ?: throw IllegalArgumentException("params key $key holds a non-scalar")
            require(p.isString) { "params key $key holds a non-string" }
            p.content
        }
    }

    fun doubles(key: String): List<Double> {
        val arr = element(key) as? JsonArray ?: throw IllegalArgumentException("params key $key is not an array")
        return arr.map { number(it as JsonPrimitive, key) }
    }

    private fun element(key: String) = values[key].also { read += key }
        ?: throw IllegalArgumentException("params key $key is missing")

    private fun primitive(key: String): JsonPrimitive =
        element(key) as? JsonPrimitive ?: throw IllegalArgumentException("params key $key is not a scalar")

    private fun number(p: JsonPrimitive, key: String): Double {
        require(!p.isString && p.booleanOrNull == null) { "params key $key is not a number" }
        // Decimal text to the nearest double: correctly rounded on every runtime.
        return p.content.toDouble()
    }

    companion object {
        /** A view over a params file (`{params_version, engine_version_range, params: {key: {value, ...}}}`). */
        fun fromParamsFile(text: String): ParamView {
            val doc = Json.parseToJsonElement(text).jsonObject
            return ParamView(ParamsHashes.values(doc.getValue("params").jsonObject))
        }
    }
}
