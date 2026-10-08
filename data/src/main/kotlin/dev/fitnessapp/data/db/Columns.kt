package dev.fitnessapp.data.db

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Column representations of ANDROID.md 8.2: every `numeric(p, s)` column is an INTEGER scaled by 10^s in Room
 * and an exact JSON decimal on the wire; wall timestamps are epoch ms in Room and ISO 8601 UTC with
 * milliseconds on the wire; `jsonb` is canonical JSON text in Room and a JSON value on the wire; `text[]` and
 * `uuid[]` are JSON arrays in both.
 */
object Scaled {
    fun toLong(value: BigDecimal, scale: Int): Long = value.setScale(scale, RoundingMode.HALF_EVEN).movePointRight(scale).longValueExact()
    fun toDecimal(value: Long, scale: Int): BigDecimal = BigDecimal.valueOf(value, scale)
    fun fromDouble(x: Double, scale: Int): Long = toLong(BigDecimal(x), scale)
    fun toDouble(value: Long, scale: Int): Double = toDecimal(value, scale).toDouble()
}

@OptIn(ExperimentalSerializationApi::class)
abstract class ScaledSerializer(private val scale: Int) : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Scaled$scale", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        (encoder as JsonEncoder).encodeJsonElement(JsonUnquotedLiteral(Scaled.toDecimal(value, scale).toPlainString()))
    }

    override fun deserialize(decoder: Decoder): Long =
        Scaled.toLong(BigDecimal((decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content), scale)
}

object Scale1 : ScaledSerializer(1)
object Scale2 : ScaledSerializer(2)
object Scale3 : ScaledSerializer(3)
object Scale4 : ScaledSerializer(4)

/** Epoch milliseconds <-> ISO 8601 UTC with milliseconds; server microseconds truncate toward the past. */
object EpochMsIso : KSerializer<Long> {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EpochMsIso", PrimitiveKind.STRING)

    fun format(ms: Long): String = FORMAT.format(Instant.ofEpochMilli(ms))

    fun parse(text: String): Long = java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(format(value))

    override fun deserialize(decoder: Decoder): Long = parse(decoder.decodeString())
}

/** A `jsonb` column: canonical JSON text in Room, the JSON value itself on the wire. */
object JsonText : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("JsonText", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        (encoder as JsonEncoder).encodeJsonElement(Json.parseToJsonElement(value))
    }

    override fun deserialize(decoder: Decoder): String =
        canonical((decoder as JsonDecoder).decodeJsonElement())

    fun canonical(e: JsonElement): String = dev.fitnessapp.algo.core.det.Jcs.canonicalize(e)

    val EMPTY_OBJECT: String = canonical(JsonObject(emptyMap()))
}

/** `text[]` / `uuid[]`: a list in Kotlin, a JSON array on the wire, JSON text in Room (see [Converters]). */
object StringListJson {
    fun encode(list: List<String>): String = Json.encodeToString(JsonArray.serializer(), JsonArray(list.map(::JsonPrimitive)))
    fun decode(text: String): List<String> = (Json.parseToJsonElement(text) as JsonArray).map { it.jsonPrimitive.content }
}
