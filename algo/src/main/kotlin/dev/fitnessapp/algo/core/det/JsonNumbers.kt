package dev.fitnessapp.algo.core.det

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/** Numbers entering JSON outputs go through the canonical text, never through Double.toString (P13). */
@OptIn(ExperimentalSerializationApi::class)
object JsonNumbers {
    fun grid(x: Double, kind: GridKind): JsonPrimitive = JsonUnquotedLiteral(Canon.esNumber(x, kind))

    fun int(x: Long): JsonPrimitive = JsonUnquotedLiteral(Canon.esInt(x))
}
