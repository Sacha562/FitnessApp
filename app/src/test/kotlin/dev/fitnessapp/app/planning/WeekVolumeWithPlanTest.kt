package dev.fitnessapp.app.planning

import dev.fitnessapp.algo.accounting.setcredit.SetCredit
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.planning.weekplan.Band
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.PlanDay
import dev.fitnessapp.algo.planning.weekplan.PlanItem
import dev.fitnessapp.algo.planning.weekplan.Visits
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import dev.fitnessapp.algo.prescription.warnings.Severity
import dev.fitnessapp.algo.targets.GroupGoal
import dev.fitnessapp.algo.targets.Targets
import dev.fitnessapp.algo.targets.TargetsInput
import dev.fitnessapp.app.uidet.VolumeState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** D113: the Week tab's bars count the week plan's general sessions beside the challenge's committed volume (19.4). */
class WeekVolumeWithPlanTest {
    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.8.json")).readText(Charsets.UTF_8),
    )
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val monday = LocalDate.parse("2026-11-09")
    private val now = Week.atLocal(LocalDate.parse("2026-11-10"), LocalTime.of(8, 0), zone)

    /** Tuesday morning: chest and biceps improve, lats owned by the challenge with 6 Vhyp committed, nothing done yet. */
    private fun base(): WeekVolume {
        val goals = mapOf("chest" to GroupGoal("improve"), "biceps" to GroupGoal("improve"), "lats" to GroupGoal("improve"))
        val committed = mapOf("lats" to 6.0)
        val targets = Targets.compute(
            TargetsInput(
                isoWeek = Week.isoWeek(monday), goals = goals, trainingAge = null, age = null, ownedGroups = listOf("lats"),
                contractActive = true, challengeDeloadWeek = false, committed = committed, done = emptyMap(), trainedDates = emptyMap(),
                today = LocalDate.parse("2026-11-10"),
            ),
            params,
        )
        val account = SetCredit.account(emptyList(), listOf(committed), emptyList(), Week.atLocal(monday, LocalTime.MIDNIGHT, zone), now, zone, params)
        val (warnings, bars) = WeekVolume.present(targets, account, params)
        return WeekVolume(monday, Week.isoWeek(monday), true, goals, targets, account, bars, warnings, emptyMap(), emptyList(), null)
    }

    private fun item(key: String, kind: String, date: String, from: String, label: String, vhyp: Map<String, Double>) = PlanItem(
        key = key, kind = kind, slot = null, type = "push", label = label, start = Week.atLocal(LocalDate.parse(date), LocalTime.parse(from), zone),
        end = Week.atLocal(LocalDate.parse(date), LocalTime.parse(from), zone).plusSeconds(3600), estMinutes = 60.0, writePolicy = "never",
        source = "weekplan", vhyp = vhyp,
    )

    private fun plan(isoWeek: String, vararg days: Pair<String, List<PlanItem>>) = WeekPlan(
        isoWeek = isoWeek, planId = "", provisional = false,
        days = days.map { (d, items) -> PlanDay(LocalDate.parse(d), "free", items, null) },
        visits = Visits(0, 0, days.size, 4), recommendedN = days.size, projected = emptyMap(), j = 0.0, terms = emptyMap(),
        band = Band(0.0, 0.0, 1, "only"), warnings = emptyList(), top = emptyList(), evaluated = 1, kMax = 1, kMaxReduced = false,
        planDoses = emptyList(),
    )

    private val week = plan(
        "2026-W46",
        "2026-11-11" to listOf(
            item("C:2026-11-11", FixedItem.KIND_CHALLENGE, "2026-11-11", "19:00", "Session C", emptyMap()),
            item("C:2026-11-11:addon", WeeklyPlanner.KIND_ADDON, "2026-11-11", "20:15", "Push add-on", mapOf("chest" to 2.0)),
        ),
        "2026-11-12" to listOf(
            item("planned-gym", FixedItem.KIND_PLANNED_GYM, "2026-11-12", "18:00", "Push + biceps", mapOf("chest" to 4.5, "biceps" to 0.5)),
        ),
        "2026-11-14" to listOf(
            item("gen:2026-11-14", WeeklyPlanner.KIND_RECOMMENDED, "2026-11-14", "10:00", "Pull + arms", mapOf("biceps" to 3.0)),
        ),
    )

    @Test
    fun plannedGeneralJoinsCommitted() {
        val b = base()
        b.bars.rows.first { it.group == "chest" }.planned shouldBe 0.0
        b.warnings.first { it.entity == "group:chest" }.severity shouldBe Severity.STRONG

        val w = b.withPlan(week, params)
        w.account.plannedGeneral shouldBe mapOf("biceps" to 3.5, "chest" to 6.5)
        w.account.committed shouldBe mapOf("lats" to 6.0)
        // Chest: the add-on and the planned gym event (each at least the trained minimum); biceps: only Saturday's 3.0, not 0.5.
        w.account.sessionsTraining["chest"] shouldBe 2
        w.account.sessionsTraining["biceps"] shouldBe 1
        val chest = w.bars.rows.first { it.group == "chest" }
        chest.planned shouldBe 6.5
        chest.text.substringBefore(" / ") shouldBe "0 (+6.5)"
        // 6.5 planned lifts chest out of the WARN band: INFO only, so no DEFICIT and no `!`.
        w.warnings.first { it.entity == "group:chest" }.severity shouldBe Severity.INFO
        chest.state shouldBe VolumeState.OK
        // Covered lats keep the challenge's committed volume; the targets are the ones the planner saw.
        w.bars.covered.first { it.group == "lats" }.planned shouldBe 6.0
        w.targets shouldBe b.targets
    }

    @Test
    fun sheetListsPlannedSessions() {
        val rows = base().withPlan(week, params).contributions.getValue("chest")
        rows.map { Triple(it.label, it.date, it.vhyp) } shouldBe listOf(
            Triple("Push + biceps", LocalDate.parse("2026-11-12"), 4.5),
            Triple("Push add-on", LocalDate.parse("2026-11-11"), 2.0),
        )
        rows.all { it.committed } shouldBe true
    }

    @Test
    fun otherWeekOrNoGeneralVolumeLeavesWeekAsIs() {
        val b = base()
        b.withPlan(week.copy(isoWeek = "2026-W47"), params) shouldBeSameInstanceAs b
        b.withPlan(plan("2026-W46", "2026-11-11" to listOf(week.days[0].items[0])), params) shouldBeSameInstanceAs b
    }
}
