package dev.fitnessapp.algo.core.det

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * RFC 8785 JSON Canonicalization Scheme over kotlinx JsonElement (ALGORITHMS.md A9, C24): members sorted
 * by the UTF-16 code units of their keys, no whitespace, minimal string escapes, numbers in ECMAScript
 * form (A3.4). Numbers must already be on a grid (at most 15 significant digits).
 */
object Jcs {
    private val HEX = "0123456789abcdef".toCharArray()

    fun canonicalize(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    fun sha256Hex(element: JsonElement): String = Sha256Hex.ofUtf8(canonicalize(element))

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                // String.compareTo compares UTF-16 code units, exactly what RFC 8785 3.2.3 requires.
                e.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(e.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                e.isString -> writeString(e.content, out)
                e.content == "true" || e.content == "false" -> out.append(e.content)
                else -> out.append(Canon.esDecimal(BigDecimal(e.content)))
            }
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append('\\').append('"')
                c == '\\' -> out.append('\\').append('\\')
                c == '\b' -> out.append('\\').append('b')
                c == '\u000C' -> out.append('\\').append('f')
                c == '\n' -> out.append('\\').append('n')
                c == '\r' -> out.append('\\').append('r')
                c == '\t' -> out.append('\\').append('t')
                c < ' ' -> out.append('\\').append("u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                Character.isHighSurrogate(c) -> {
                    require(i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { "lone surrogate in string" }
                    out.append(c).append(s[i + 1])
                    i++
                }
                Character.isLowSurrogate(c) -> throw IllegalArgumentException("lone surrogate in string")
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }
}
