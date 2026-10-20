package dev.fitnessapp.sim.challenge

import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EventCodec
import dev.fitnessapp.algo.challenge.enrollment.StateJson
import dev.fitnessapp.algo.core.det.Jcs
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The storage format of challenge event payloads (CHALLENGE_ENGINE 4.2): every event of every persona run is
 * written as stored JSON text and read back; the decoded events fold to the same state hash, and encoding
 * the decoded event gives the same bytes.
 */
class EventCodecRoundTripTest {
    private val def = SimResources.oap

    @TestFactory
    fun `every persona event round-trips through the stored payload`(): List<DynamicTest> = Persona.all().flatMap { p ->
        listOf(true, false).map { approve ->
            DynamicTest.dynamicTest("${p.id}.$approve") {
                val r = ChallengeSimulator(def, SimResources.params(), SimResources.catalog, p, approve).run()
                val decoded = r.events.map { e ->
                    val stored = EventCodec.encode(def, e).toString()
                    val payload = Json.parseToJsonElement(stored).jsonObject
                    val back = EventCodec.decode(def, e.id, e.type, e.occurredAt, e.localDate, payload, e.supersedesEventId)
                    Jcs.canonicalize(EventCodec.encode(def, back)) shouldBe Jcs.canonicalize(payload as JsonObject)
                    back
                }
                val params = SimResources.params()
                StateJson.hash(EnrollmentFold.fold(def, params, decoded).state) shouldBe StateJson.hash(EnrollmentFold.fold(def, params, r.events).state)
                r.events.map { it.type }.toSet() shouldContainAll listOf("enrollment.created", "rules.approval_changed", "session.completed", "day.tick", "week.closed")
            }
        }
    }
}
