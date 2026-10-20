package dev.fitnessapp.algo.challenge.fitexpr

import dev.fitnessapp.algo.core.det.Canon
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Values as JSON for hashes, audit rows and golden snapshots. Numbers are written in their canonical
 * ECMAScript form (RFC 8785), so they must already be on a grid (at most 15 significant digits); a number
 * is tagged with its dimension only where the reader cannot infer it ([tagged]).
 */
@OptIn(ExperimentalSerializationApi::class)
object ValueJson {
    private val DISPLAY = MathContext(Canon.MAX_SIGNIFICANT_DIGITS, RoundingMode.HALF_EVEN)

    fun number(v: BigDecimal): JsonPrimitive = JsonUnquotedLiteral(Canon.esDecimal(v))

    /** For bindings and explanations: rounded to 15 significant digits first (FitExpr keeps 34). */
    fun display(v: BigDecimal): JsonPrimitive = number(v.round(DISPLAY))

    fun encode(v: Value, displayRounding: Boolean = false): JsonElement = when (v) {
        is QV -> if (displayRounding) display(v.v) else number(v.v)
        is BoolV -> JsonPrimitive(v.b)
        is StrV -> JsonPrimitive(v.s)
        is DateV -> JsonPrimitive(v.d.toString())
        is NullV -> JsonNull
        is ListV -> JsonArray(v.items.map { encode(it, displayRounding) })
        is SideV -> JsonObject(
            sortedMapOf<String, JsonElement>().also { m ->
                v.l?.let { m["L"] = encode(it, displayRounding) }
                v.r?.let { m["R"] = encode(it, displayRounding) }
            },
        )
        is MapV -> JsonObject(v.entries.mapValues { encode(it.value, displayRounding) }.toSortedMap())
        is RecordV -> JsonObject(v.fields.mapValues { encode(it.value, displayRounding) }.toSortedMap())
    }

    /** `{"dim": "MASS", "v": 22.5}` style for numbers whose dimension a reader needs. */
    fun tagged(v: Value): JsonElement = when (v) {
        is QV -> JsonObject(sortedMapOf("dim" to JsonPrimitive(v.dim.name), "v" to number(v.v)))
        is SideV -> JsonObject(
            sortedMapOf<String, JsonElement>().also { m ->
                v.l?.let { m["L"] = tagged(it) }
                v.r?.let { m["R"] = tagged(it) }
            },
        )
        is ListV -> JsonArray(v.items.map(::tagged))
        else -> encode(v)
    }

    /** Reads a value written by [encode] back as [type]; numbers are normalized (`Dec.norm`). */
    fun decode(type: Type, e: JsonElement): Value {
        if (e is JsonNull) return NullV
        return when (type) {
            is Type.Q -> QV(type.dim, Dec.norm(BigDecimal((e as JsonPrimitive).content)))
            Type.BoolT -> BoolV.of((e as JsonPrimitive).content.toBooleanStrict())
            Type.DateT -> DateV(java.time.LocalDate.parse((e as JsonPrimitive).content))
            is Type.StrT, is Type.EnumT -> StrV((e as JsonPrimitive).content)
            is Type.SideT -> (e as JsonObject).let { o -> SideV(o["L"]?.let { decode(type.elem, it) }, o["R"]?.let { decode(type.elem, it) }) }
            is Type.ListT -> ListV((e as JsonArray).map { decode(type.elem, it) })
            is Type.MapT -> MapV((e as JsonObject).mapValues { decode(type.value, it.value) }.toSortedMap())
            Type.NullT -> NullV
            is Type.RecordT, Type.AnyT -> untagged(e)
        }
    }

    /** Reads a value written by [tagged] (runtime-typed values such as `onboard.*`). */
    fun untagged(e: JsonElement): Value = when (e) {
        is JsonNull -> NullV
        is JsonPrimitive -> when {
            e.isString -> StrV(e.content)
            e.content == "true" || e.content == "false" -> BoolV.of(e.content.toBooleanStrict())
            else -> QV(Dim.NUM, Dec.norm(BigDecimal(e.content)))
        }
        is JsonArray -> ListV(e.map(::untagged))
        is JsonObject -> when {
            e.containsKey("dim") && e.containsKey("v") ->
                QV(Dim.valueOf((e.getValue("dim") as JsonPrimitive).content), Dec.norm(BigDecimal((e.getValue("v") as JsonPrimitive).content)))
            e.keys.all { it == "L" || it == "R" } -> SideV(e["L"]?.let(::untagged), e["R"]?.let(::untagged))
            else -> MapV(e.mapValues { untagged(it.value) }.toSortedMap())
        }
    }

    /** Short text for audit bindings and explanation placeholders. */
    fun text(v: Value): String = when (v) {
        is QV -> Canon.esDecimal(v.v.round(DISPLAY)).let { if (v.dim.unit.isEmpty()) it else it + " " + v.dim.unit }
        is StrV -> v.s
        is BoolV -> v.b.toString()
        is DateV -> v.d.toString()
        is NullV -> "null"
        is ListV -> v.items.joinToString(", ", "[", "]") { text(it) }
        is SideV -> "L " + (v.l?.let(::text) ?: "-") + " / R " + (v.r?.let(::text) ?: "-")
        is MapV -> v.entries.entries.joinToString(", ", "{", "}") { it.key + ": " + text(it.value) }
        is RecordV -> "{" + v.path + "}"
    }
}
