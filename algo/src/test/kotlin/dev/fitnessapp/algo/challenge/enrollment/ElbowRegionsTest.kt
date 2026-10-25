package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * D45 (2026-10-23 on the phone: "forearm/back of elbow" logged as Other): pain at the back of the elbow or in the
 * forearm sets the elbow trace like the other elbow regions; it does not start the elbow protocol, and Other still
 * does nothing. The Pallof press items are per side.
 */
class ElbowRegionsTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()

    private fun pain(region: String) = OapFixture.event(
        EventTypes.PAIN, "2026-10-14", "20:00", EventPayload.Pain(region, "R", listOf("dull_ache_during"), "mild", 2),
    )

    private fun trace(s: EnrollmentState) = (s.vars.getValue("elbowTrace") as BoolV).b

    @Test
    fun `pain at the back of the elbow or in the forearm sets the elbow trace, not the protocol`() {
        val s = OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false))
        trace(s) shouldBe false
        for (region in listOf("elbow_posterior", "forearm", "elbow_medial")) {
            val after = EnrollmentFold.apply(def, params, s, pain(region)).state
            trace(after) shouldBe true
            after.mode shouldBe s.mode
        }
        trace(EnrollmentFold.apply(def, params, s, pain("other")).state) shouldBe false
    }

    @Test
    fun `the Pallof press is per side in A, B and C`() {
        val templates = Json.parseToJsonElement(ChallengeTestSupport.oapText).jsonObject.getValue("templates").jsonObject
        for (id in listOf("A1", "B1", "C1")) {
            val pallof = templates.getValue(id).jsonObject.getValue("items").jsonArray.map { it.jsonObject }
                .single { it["key"]?.jsonPrimitive?.content == "pallof" }
            pallof.getValue("perSide").jsonPrimitive.content shouldBe "true"
        }
    }
}
