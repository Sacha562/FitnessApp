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
 * CHALLENGE_ENGINE 2.2: a patch version changes text only. The frozen `oap@1.0.0` and `oap@1.0.1` must be equal once
 * their texts (the `{en, nl}` objects), `version` and `migrations` are set aside, so no computed value can differ, and
 * 1.0.1 declares the empty migration from 1.0.0. The minor `oap@1.1.0` (D36) only adds P21's protocol params and the
 * test items' `stepsIfApproved` on top of 1.0.1; `oap@1.2.0` (D37, first-test rules) is proven by FirstTestTest and the
 * persona replay instead.
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

    /** 1.1.0 without D36's additions: the new params, P21's `affects.params` and every `stepsIfApproved`. */
    private fun withoutSteps(e: JsonElement, path: String = ""): JsonElement = when (e) {
        is JsonObject -> JsonObject(
            e.filterKeys { k -> k != "stepsIfApproved" && !(path == "/params" && k in STEP_PARAMS) }
                .mapValues { (k, v) -> withoutSteps(v, "$path/$k") }
                .filterNot { (k, v) -> path.endsWith("/affects") && k == "params" && v is JsonArray && v.isEmpty() },
        )
        is JsonArray -> JsonArray(e.filterNot { path.endsWith("/affects/params") && it is JsonPrimitive && it.content in STEP_PARAMS }.map { withoutSteps(it, path) })
        else -> e
    }

    @Test
    fun `oap 1_0_1 differs from 1_0_0 in text only`() {
        val oldText = ChallengeTestSupport.resource("challenges/oap.challenge.v1.0.0.json")
        val patchText = ChallengeTestSupport.resource("challenges/oap.challenge.v1.0.1.json")
        val old = Cds.load(oldText, ChallengeTestSupport.context).orThrow()
        val patch = Cds.load(patchText, ChallengeTestSupport.context).orThrow()
        old.version shouldBe "1.0.0"
        patch.version shouldBe "1.0.1"
        logic(patchText) shouldBe logic(oldText)
        val migration = Json.parseToJsonElement(patchText).jsonObject.getValue("migrations").jsonObject.getValue("1.0.0").jsonObject
        migration.getValue("varMap") shouldBe JsonObject(emptyMap())
    }

    @Test
    fun `oap 1_1_0 adds only the structured test protocols`() {
        val patchText = ChallengeTestSupport.resource("challenges/oap.challenge.v1.0.1.json")
        val minorText = ChallengeTestSupport.resource("challenges/oap.challenge.v1.1.0.json")
        Cds.load(minorText, ChallengeTestSupport.context).orThrow().version shouldBe "1.1.0"
        withoutSteps(logic(minorText)) shouldBe logic(patchText)
    }

    @Test
    fun `oap 1_10_0 declares empty migrations from every earlier version`() {
        ChallengeTestSupport.oap.version shouldBe "1.10.0"
        val migrations = Json.parseToJsonElement(ChallengeTestSupport.oapText).jsonObject.getValue("migrations").jsonObject
        for (from in listOf("1.0.0", "1.0.1", "1.1.0", "1.2.0", "1.3.0", "1.4.0", "1.5.0", "1.6.0", "1.7.0", "1.8.0", "1.9.0")) migrations.getValue(from).jsonObject.getValue("varMap") shouldBe JsonObject(emptyMap())
    }

    private companion object {
        val STEP_PARAMS = setOf("testRampFractions", "testAttemptStepKg", "test3rmMaxAttempts", "testLadderOffsetLb", "testLadderMaxAttempts")
    }
}
