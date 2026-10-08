package dev.fitnessapp.algo.core.det

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

/** RFC 8785 examples (sections 3.2.2, 3.2.3) and the JSON shapes snapshots use. */
class JcsConformanceTest {
    private fun jcs(text: String) = Jcs.canonicalize(Json.parseToJsonElement(text))

    @Test
    fun `property sorting by UTF-16 code units`() {
        val input = """
            {
              "\u20ac": "Euro Sign",
              "\r": "Carriage Return",
              "\ufb33": "Hebrew Letter Dalet With Dagesh",
              "1": "One",
              "\ud83d\ude00": "Emoji: Grinning Face",
              "\u0080": "Control",
              "\u00f6": "Latin Small Letter O With Diaeresis"
            }
        """.trimIndent()
        jcs(input) shouldBe
            "{\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\u0080\":\"Control\"," +
            "\"\u00f6\":\"Latin Small Letter O With Diaeresis\",\"\u20ac\":\"Euro Sign\"," +
            "\"\ud83d\ude00\":\"Emoji: Grinning Face\",\"\ufb33\":\"Hebrew Letter Dalet With Dagesh\"}"
    }

    @Test
    fun `string escaping`() {
        val input = """{"string": "\u20ac$\u000F\u000aA'\u0042\u0022\u005c\\\"\/"}"""
        jcs(input) shouldBe "{\"string\":\"\u20ac$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}"
    }

    @Test
    fun `literals and numbers`() {
        val input = """
            {"numbers": [1E30, 4.50, 2e-3, 0.000000000000000000000000001], "literals": [null, true, false]}
        """.trimIndent()
        jcs(input) shouldBe "{\"literals\":[null,true,false],\"numbers\":[1e+30,4.5,0.002,1e-27]}"
    }

    @Test
    fun `nested objects and grid numbers`() {
        val obj = buildJsonObject {
            put("z", JsonNumbers.grid(27.5, GridKind.KG))
            put("a", buildJsonObject {
                put("title", JsonPrimitive("Gym \u2014 sessie A (\u00e9\u00e9n arm zwaar)"))
                put("k", JsonNumbers.grid(30.63, GridKind.RATIO))
            })
        }
        Jcs.canonicalize(obj) shouldBe
            "{\"a\":{\"k\":30.63,\"title\":\"Gym \u2014 sessie A (\u00e9\u00e9n arm zwaar)\"},\"z\":27.5}"
    }

    @Test
    fun `sha256 of the empty object`() {
        Jcs.sha256Hex(buildJsonObject { }) shouldBe
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
    }
}
