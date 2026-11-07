package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.cds.Cds
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.materialize.MigrationPreviews
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.TreeMap

/** CHALLENGE_ENGINE 4.12 (D44): `definition.migrated` from oap@1.4.0 to 1.5.0 (no rower in the warm-up). */
class DefinitionMigrationTest {
    private val old: CompiledDefinition = Cds.load(ChallengeTestSupport.resource("challenges/oap.challenge.v1.4.0.json"), ChallengeTestSupport.context).orThrow()
    private val new: CompiledDefinition = Cds.load(ChallengeTestSupport.resource("challenges/oap.challenge.v1.5.0.json"), ChallengeTestSupport.context).orThrow()
    private val params = OapFixture.params()
    private val catalog: CatalogDoc by lazy { CatalogDoc.parse(ChallengeTestSupport.resource("catalog/catalog-1.0.5.json")) }
    private val imp = OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false)

    private fun migrated(date: String = "2026-10-09", hash: String = "preview") = OapFixture.event(
        EventTypes.MIGRATED, date, "18:00", EventPayload.Migrated(old.version, new.version, new.jcsSha256, TreeMap(), hash), name = "migrated",
    )

    private fun tick(date: String) = OapFixture.event(EventTypes.DAY_TICK, date, "00:00", EventPayload.DayTick(LocalDate.parse(date)))

    private fun history() = listOf(OapFixture.created(imp, definition = old)) + OapFixture.decisions(true, definition = old) +
        listOf(tick("2026-10-08"), tick("2026-10-09"), migrated(), tick("2026-10-10"))

    @Test
    fun `1_5_0 is a newer version with a migration from 1_4_0`() {
        old.version shouldBe "1.4.0"
        new.version shouldBe "1.5.0"
        EnrollmentFold.migrationOf(old, new) shouldNotBe null
        EnrollmentFold.migrationOf(new, old) shouldBe null
        EnrollmentFold.migrationOf(new, new) shouldBe null
    }

    @Test
    fun `events after the migration fold under the new version`() {
        val fold = EnrollmentFold.fold(mapOf(old.version to old, new.version to new), params, history())
        fold.def shouldBe new
        fold.state.definitionVersion shouldBe "1.5.0"
        fold.state.definitionSha shouldBe new.jcsSha256
        fold.trail.first { it.first.type == EventTypes.DAY_TICK }.second.definitionVersion shouldBe "1.4.0"
        // No state var changes: the vars equal those of the same history without the update.
        val stay = EnrollmentFold.fold(old, params, history().filter { it.type != EventTypes.MIGRATED })
        fold.state.vars shouldBe stay.state.vars
        fold.state.stateRev shouldBe stay.state.stateRev + 1
    }

    @Test
    fun `a refold of the stored events gives the same state`() {
        val events = history().map { e ->
            val def = if (e.occurredAt < migrated().occurredAt) old else new
            EventCodec.decode(def, e.id, e.type, e.occurredAt, e.localDate, EventCodec.encode(def, e), e.supersedesEventId)
        }
        val defs = mapOf(old.version to old, new.version to new)
        StateJson.hash(EnrollmentFold.fold(defs, params, events).state) shouldBe StateJson.hash(EnrollmentFold.fold(defs, params, history()).state)
    }

    @Test
    fun `the migration needs both versions and the enrollment's version`() {
        shouldThrow<IllegalArgumentException> { EnrollmentFold.fold(old, params, history()) }
        val s = OapFixture.start(imp, definition = old)
        shouldThrow<IllegalArgumentException> { EnrollmentFold.apply(old, params, s, migrated()) }
        shouldThrow<IllegalArgumentException> { EnrollmentFold.migrate(new, new, params, s, migrated()) }
    }

    @Test
    fun `the preview shows the rower leaving every warm-up and nothing else`() {
        val s = OapFixture.start(imp, definition = old)
        val sessions = listOf("C" to "2026-10-14", "A" to "2026-10-16", "B" to "2026-10-18").map { it.first to LocalDate.parse(it.second) }
        val p = MigrationPreviews.preview(old, new, params, s, OapFixture.facts, catalog, sessions, LocalDate.parse("2026-10-13"), 5)
        p.varMap shouldBe TreeMap()
        p.rules shouldBe emptyList()
        p.newProposals shouldBe emptyList()
        p.sessions.map { d -> d.slot to d.changes.map { it.kind to it.key } } shouldBe listOf(
            "C" to listOf("removed" to "wu_row"), "A" to listOf("removed" to "wu_row"), "B" to listOf("removed" to "wu_row"),
        )
        MigrationPreviews.preview(old, new, params, s, OapFixture.facts, catalog, sessions, LocalDate.parse("2026-10-13"), 5).hash shouldBe p.hash
    }
}
