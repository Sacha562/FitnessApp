package dev.fitnessapp.sim.challenge

import dev.fitnessapp.algo.challenge.enrollment.StateJson
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * CHALLENGE_ENGINE 2.2 and 4.12: neither the text-only patch 1.0.1, the test protocols of 1.1.0 (D36, read only
 * by the test-day wizard) nor the first-test rules of 1.2.0 (D37, which only fill empty values) change a fold of an
 * enrollment that starts with its numbers; the test-week persona is the case 1.2.0 fixes and is left out. Every persona, with all proposals approved and all declined, ends in the
 * same state and the same week trajectory under the frozen `oap@1.0.0` and the current definition; only the
 * definition version and hash in the state differ.
 */
class TextPatchReplayTest {
    private val old = SimResources.load(SimResources.text("challenges/oap.challenge.v1.0.0.json")).orThrow()
    // 1.3.0 (D38) moves sessions on purpose (no Sunday-only test), so the proof stops at 1.2.0.
    private val cur = SimResources.load(SimResources.text("challenges/oap.challenge.v1.2.0.json")).orThrow()

    @TestFactory
    fun `personas replay identically under 1_0_0`(): List<DynamicTest> = Persona.all().filter { !it.testWeekStart }.flatMap { p ->
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
