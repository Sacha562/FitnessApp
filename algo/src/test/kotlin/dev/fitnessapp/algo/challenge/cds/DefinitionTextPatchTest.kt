package dev.fitnessapp.algo.challenge.cds

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/**
 * CHALLENGE_ENGINE 2.2: a patch version changes text only. The frozen `oap@1.0.0` and the current definition must
 * be equal once their texts (the `{en, nl}` objects), `version` and `migrations` are set aside, so no computed
 * value can differ, and the current version declares the empty migration from 1.0.0.
 */
class DefinitionTextPatchTest {
    private fun isText(o: JsonObject) = "en" in o && o.values.all { it is JsonPrimitive && it.isString }

    private fun strip(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> if (isText(e)) JsonPrimitive("<text>") else JsonObject(e.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(e.map(::strip))
        else -> e
    }

    private fun logic(text: String): JsonElement =
        strip(JsonObject(Json.parseToJsonElement(text).jsonObject.filterKeys { it != "version" && it != "migrations" }))

    @Test
    fun `oap 1_0_1 differs from 1_0_0 in text only`() {
        val oldText = ChallengeTestSupport.resource("challenges/oap.challenge.v1.0.0.json")
        val old = Cds.load(oldText, ChallengeTestSupport.context).orThrow()
        val cur = ChallengeTestSupport.oap
        old.version shouldBe "1.0.0"
        cur.version shouldBe "1.0.1"
        logic(ChallengeTestSupport.oapText) shouldBe logic(oldText)
        val migration = Json.parseToJsonElement(ChallengeTestSupport.oapText).jsonObject.getValue("migrations").jsonObject.getValue("1.0.0").jsonObject
        migration.getValue("varMap") shouldBe JsonObject(emptyMap())
    }
}
