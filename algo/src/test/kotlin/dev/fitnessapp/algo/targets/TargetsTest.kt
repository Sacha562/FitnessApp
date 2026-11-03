package dev.fitnessapp.algo.targets

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.testing.TestCatalog
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** ALGORITHMS_PLANNING 5.8 and the 5.6 presets. */
class TargetsTest {
    private val params = TestParams.defaults()
    private val presets = GoalPresets.parse(TestCatalog.resource("presets/goal-presets-1.0.0.json"))
    private val today = LocalDate.parse("2026-10-07")
    private val oap = listOf("lats", "biceps", "forearms")

    private fun input(
        preset: String = "balanced",
        age: String? = "intermediate",
        owned: List<String> = oap,
        deload: Boolean = false,
        years: Int? = null,
        trained: Map<String, List<LocalDate>> = emptyMap(),
        goals: Map<String, GroupGoal> = presets.goals(preset),
    ) = TargetsInput(
        "2026-W41", goals, age, years, owned, contractActive = owned.isNotEmpty(), challengeDeloadWeek = deload, committed = emptyMap(),
        done = emptyMap(), trainedDates = trained, today = today,
    )

    @Test
    fun `balancedIntermediateWithOap`() {
        val t = Targets.compute(input(), params)
        t.groups.filterValues { it.status == "covered" }.keys shouldBe setOf("lats", "biceps", "forearms")
        t.groups.getValue("chest").let { it.t shouldBe 12.0; it.w shouldBe 1.0 }
        t.groups.getValue("calves").let { it.t shouldBe 4.0; it.w shouldBe 0.6 }
        t.groups.getValue("upper_back").t shouldBe 12.0
        // 5.6: the general engine asks 152 - 12 - 12 - 4 = 124 with the OAP active.
        t.groups.values.sumOf { it.t } shouldBe 124.0
    }

    @Test
    fun `preset sums of 5_6 without a challenge`() {
        fun sum(p: String, age: String) = Targets.compute(input(p, age, owned = emptyList()), params).groups.values.sumOf { it.t }
        sum("balanced", "intermediate") shouldBe 152.0
        sum("balanced", "novice") shouldBe 124.0
        sum("legs_only", "intermediate") shouldBe 80.0
        sum("legs_only", "novice") shouldBe 66.0
        sum("no_legs", "intermediate") shouldBe 104.0
        sum("no_legs", "novice") shouldBe 85.0
        sum("pull_focus", "intermediate") shouldBe 120.0
        sum("pull_focus", "novice") shouldBe 97.0
        presets.ids shouldBe listOf("balanced", "legs_only", "no_legs", "pull_focus")
    }

    @Test
    fun `focusAndMrv`() {
        Targets.compute(input("pull_focus", owned = emptyList()), params).groups.getValue("lats").t shouldBe 16.0
    }

    @Test
    fun `maintain60`() {
        val goals = Groups.CANONICAL.associateWith { GroupGoal("maintain") }
        val g = Targets.compute(input(goals = goals, owned = emptyList(), years = 64), params).groups.getValue("chest")
        g.t shouldBe 6.0
        g.freqTarget shouldBe 2
    }

    @Test
    fun `skipOwnedConflict`() {
        val goals = presets.goals("balanced") + ("lats" to GroupGoal("skip"))
        val lats = Targets.compute(input(goals = goals), params).groups.getValue("lats")
        lats.status shouldBe "covered"
        lats.conflictNote shouldNotBe null
    }

    @Test
    fun `deloadSyncedWeek41 and deloadReentryNoStack`() {
        val t = Targets.compute(input(deload = true), params)
        t.deload shouldBe Deload(true, "challenge", 0.5, 2, emptyList())
        t.groups.getValue("chest").targetEffective shouldBe 6.0
        t.groups.getValue("calves").targetEffective shouldBe 2.0
        // No history: re-entry everywhere, never stacked with the deload (C51).
        t.groups.getValue("chest").transform shouldBe Transform(0.5, 2)
        Targets.compute(input(), params).groups.getValue("chest").transform shouldBe Transform(0.6, 1)
    }

    @Test
    fun `noChallengeNoPlannedDeload and reactiveTriggerR2`() {
        Targets.compute(input(owned = emptyList(), deload = true), params).deload.active shouldBe false
        Targets.reactiveTriggers(ReactiveSignals(gAtStarts = listOf(0.9, 0.55, 0.58)), params) shouldBe listOf("R2")
        Targets.reactiveTriggers(ReactiveSignals(gAtStarts = listOf(0.55, 0.65)), params) shouldBe emptyList()
        val reactive = input(owned = emptyList()).copy(reactiveDeload = true, reactiveTriggers = listOf("R2"))
        Targets.compute(reactive, params).deload.source shouldBe "reactive"
        Targets.compute(reactive.copy(keepNormalWeek = true), params).deload.active shouldBe false
    }

    @Test
    fun `reentryLegsFirstWeek and the count after a gap`() {
        Targets.reentry(emptyList(), today, params) shouldBe Reentry(true, 2)
        Targets.reentry(listOf(LocalDate.parse("2026-10-05")), today, params) shouldBe Reentry(true, 1)
        Targets.reentry(listOf(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-05")), today, params) shouldBe Reentry(false, 0)
        // A 25-day gap restarts the count.
        Targets.reentry(listOf(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-03"), LocalDate.parse("2026-10-05")), today, params) shouldBe Reentry(true, 1)
        Targets.reentry(listOf(LocalDate.parse("2026-09-01")), today, params) shouldBe Reentry(true, 2)
    }

    @Test
    fun `rampCap`() {
        val closing = Targets.compute(input(owned = emptyList()), params)
        val up = Targets.closeWeek(
            WeekCloseInput(closing, emptyMap(), mapOf("chest" to RampSignals(listOf(0.03), listOf("never_sore"))), ReactiveSignals(), 2, false),
            params,
        )
        up.ramp.getValue("chest").t shouldBe 14.0 // +2, capped at max(1, floor(2.4)) = 2
        up.rampTrace.first { it.group == "chest" }.let { it.p shouldBe 1; it.s shouldBe 1; it.delta shouldBe 2 }
        // T 4 (lower back as an improve group, special 4): the cap is 1.
        val goals = presets.goals("balanced") + ("lower_back" to GroupGoal("improve"))
        val c4 = Targets.compute(input(goals = goals, owned = emptyList()), params)
        Targets.closeWeek(WeekCloseInput(c4, emptyMap(), mapOf("lower_back" to RampSignals(listOf(0.05), listOf("never_sore"))), ReactiveSignals(), 2, false), params)
            .ramp.getValue("lower_back").t shouldBe 5.0
        // Missing soreness: no change; a big drop: -2, floored at T_base - 2.
        Targets.closeWeek(WeekCloseInput(closing, emptyMap(), mapOf("chest" to RampSignals(listOf(0.03))), ReactiveSignals(), 2, false), params)
            .ramp.getValue("chest").t shouldBe 12.0
        Targets.closeWeek(WeekCloseInput(closing, emptyMap(), mapOf("chest" to RampSignals(listOf(-0.05), listOf("healed"))), ReactiveSignals(), 2, false), params)
            .ramp.getValue("chest").t shouldBe 10.0
        // Never into a deload week, never in week 1 before the start week.
        Targets.closeWeek(WeekCloseInput(closing, emptyMap(), mapOf("chest" to RampSignals(listOf(0.03), listOf("never_sore"))), ReactiveSignals(), 2, true), params)
            .ramp.getValue("chest").t shouldBe 12.0
    }

    @Test
    fun `new mesocycle starts halfway between the tolerated and the base target`() {
        val closing = Targets.compute(input(owned = emptyList()).copy(ramp = mapOf("chest" to RampState(16.0, 12.0, 16.0))), params)
        closing.groups.getValue("chest").t shouldBe 16.0
        val out = Targets.closeWeek(
            WeekCloseInput(closing, mapOf("chest" to RampState(16.0, 12.0, 16.0)), mapOf("chest" to RampSignals(listOf(0.0), listOf("healed"))), ReactiveSignals(), 1, false),
            params,
        )
        out.ramp.getValue("chest") shouldBe RampState(14.0, 12.0, 16.0)
    }

    @Test
    fun `utilityShape`() {
        val chest = Targets.compute(input(owned = emptyList()), params).groups.getValue("chest")
        Targets.utility(chest, 3.0, 12.0, params) shouldBe (0.5 plusOrMinus 1e-9)
        Targets.utility(chest, 12.0, 12.0, params) shouldBe (1.0 plusOrMinus 1e-9)
        Targets.utility(chest, 22.0, 12.0, params) shouldBe (1.354 plusOrMinus 0.0005)
        Targets.utility(chest, 24.0, 12.0, params) shouldBe (1.2707 plusOrMinus 0.0005)
    }

    @Test
    fun `setup needed without goals, and the novice default`() {
        Targets.compute(input(goals = emptyMap()), params).status shouldBe "setup_needed"
        Targets.compute(input(age = null), params).let {
            it.trainingAge shouldBe "novice"
            it.groups.getValue("chest").t shouldBe 10.0
            it.groups.getValue("calves").t shouldBe 3.0
        }
    }
}
