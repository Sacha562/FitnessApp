package dev.fitnessapp.sim.challenge

import dev.fitnessapp.algo.challenge.enrollment.StateJson
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * CHALLENGE_ENGINE 2.2 and 4.12: a text-only patch cannot change a computed value. Every persona, with all
 * proposals approved and all declined, ends in the same state and the same week trajectory under the frozen
 * `oap@1.0.0` and the current definition; only the definition version and hash in the state differ.
 */
class TextPatchReplayTest {
    private val old = SimResources.load(SimResources.text("challenges/oap.challenge.v1.0.0.json")).orThrow()
    private val cur = SimResources.oap

    @TestFactory
    fun `personas replay identically under 1_0_0`(): List<DynamicTest> = Persona.all().flatMap { p ->
        listOf(true, false).map { approve ->
            DynamicTest.dynamicTest("${p.id}.${if (approve) "approved" else "declined"}") {
                val params = SimResources.params()
                val a = ChallengeSimulator(old, params, SimResources.catalog, p, approve).run()
                val b = ChallengeSimulator(cur, params, SimResources.catalog, p, approve).run()
                val bAsOld = b.finalState.copy(definitionVersion = a.finalState.definitionVersion, definitionSha = a.finalState.definitionSha)
                StateJson.hash(bAsOld) shouldBe StateJson.hash(a.finalState)
                b.events.size shouldBe a.events.size
                b.audits.size shouldBe a.audits.size
                b.weeks.map { StateJson.hash(it.state.copy(definitionVersion = "", definitionSha = "")) } shouldBe
                    a.weeks.map { StateJson.hash(it.state.copy(definitionVersion = "", definitionSha = "")) }
            }
        }
    }
}
