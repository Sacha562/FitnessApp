package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The live plan survives storage: every slot of every mode round-trips through its stored JSON. */
class PlanJsonTest {
    private val catalog: CatalogDoc by lazy { CatalogDoc.parse(ChallengeTestSupport.resource("catalog/catalog-1.0.2.json")) }

    @Test
    fun `plans round-trip through their stored JSON`() {
        for (mode in listOf("normal", "deload", "test_pending", "injury")) {
            val s = OapFixture.start(OapFixture.import(mode = mode, blockWeek = 2, plannedTest = null, needsCleanSession = false))
            for (slot in listOf("A", "B", "C", "TEST")) {
                val p = Materializer.materialize(OapFixture.def, OapFixture.params(), MaterializeInput(slot, LocalDate.parse("2026-10-14"), s, OapFixture.facts, catalog, changeoverMin = 5))
                val stored = Json.parseToJsonElement(PlanJson.encode(p).toString()).jsonObject
                val back = PlanJson.decode(stored)
                Jcs.canonicalize(PlanJson.encode(back)) shouldBe Jcs.canonicalize(stored)
                back.items.map { it.key } shouldBe p.items.map { it.key }
                back.items.flatMap { it.rows }.size shouldBe p.items.flatMap { it.rows }.size
            }
        }
    }
}
