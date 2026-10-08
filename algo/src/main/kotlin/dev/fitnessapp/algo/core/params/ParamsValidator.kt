package dev.fitnessapp.algo.core.params

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Checks a resolved `{key: value}` map against the declared specs (ALGORITHMS.md A8.2): every declared key
 * is present, types match and numbers are within bounds. Keys owned by modules that are not implemented
 * yet are not checked here; `ParamsCoverageTest` lists them until every module declares its keys.
 */
object ParamsValidator {
    fun problems(values: JsonObject, specs: List<ParamSpec>): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (spec in specs.sortedBy { it.key }) {
            if (!seen.add(spec.key)) out += "${spec.key}: declared twice"
            val v = values[spec.key]
            if (v == null) {
                out += "${spec.key}: missing"
                continue
            }
            out += check(spec, v)
        }
        return out
    }

    private fun check(spec: ParamSpec, v: JsonElement): List<String> = when (spec.type) {
        ParamType.NUMBER, ParamType.INTEGER -> scalar(spec, v)
        ParamType.BOOLEAN ->
            if (v is JsonPrimitive && v.booleanOrNull != null) emptyList() else listOf("${spec.key}: not a boolean")
        ParamType.ENUM, ParamType.REGEX ->
            if (v is JsonPrimitive && v.isString && (spec.enumValues.isEmpty() || v.content in spec.enumValues)) emptyList()
            else listOf("${spec.key}: not an allowed string")
        ParamType.NUMBER_ARRAY -> (v as? JsonArray)?.flatMap { scalar(spec, it) } ?: listOf("${spec.key}: not an array")
        ParamType.ENUM_ARRAY ->
            if (v is JsonArray && v.all { it is JsonPrimitive && it.isString && (spec.enumValues.isEmpty() || it.content in spec.enumValues) }) emptyList()
            else listOf("${spec.key}: not an enum array")
        ParamType.NUMBER_TABLE -> (v as? JsonObject)?.values?.flatMap { e ->
            if (e is JsonArray) e.flatMap { scalar(spec, it) } else scalar(spec, e)
        } ?: listOf("${spec.key}: not a table")
    }

    private fun scalar(spec: ParamSpec, v: JsonElement): List<String> {
        if (v !is JsonPrimitive || v.isString || v.booleanOrNull != null) return listOf("${spec.key}: not a number")
        val x = v.content.toDouble()
        val out = mutableListOf<String>()
        if (spec.type == ParamType.INTEGER && x != kotlin.math.floor(x)) out += "${spec.key}: not an integer"
        if (spec.min != null && x < spec.min) out += "${spec.key}: below ${spec.min}"
        if (spec.max != null && x > spec.max) out += "${spec.key}: above ${spec.max}"
        return out
    }
}
