package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.planning.today.Excluded
import dev.fitnessapp.algo.planning.today.PlannedToday
import dev.fitnessapp.algo.planning.today.Reason
import dev.fitnessapp.algo.planning.today.RestAdvice
import dev.fitnessapp.algo.planning.today.TodayOption
import dev.fitnessapp.algo.planning.today.TodayOptions
import dev.fitnessapp.algo.planning.today.TodayRecommender
import dev.fitnessapp.algo.planning.weekplan.Band
import dev.fitnessapp.algo.planning.weekplan.Visits
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** ALGORITHMS_PLANNING 19.1 tests for `ui.todaycard` on hand-made `today` outputs (the engine's own are its goldens). */
class UiTodayCardTest {
    private val params = ParamView.fromParamsFile(requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.8.json")).readText(Charsets.UTF_8))
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val wed = Instant.parse("2026-10-07T10:00:00Z")
    private val week = WeekPlan(
        "2026-W41", "id", false, emptyList(), Visits(1, 2, 1, 4), 4, emptyMap(), 4.0, emptyMap(), Band(4.0, 0.05, 1, "only"),
        emptyList(), emptyList(), 1, 1, false, emptyList(),
    )

    private fun opt(id: String, type: String?, score: Double, reasons: List<Reason> = emptyList()) =
        TodayOption(id, type, id, score, score - 0.5, 50.0, emptyMap(), emptyMap(), null, reasons)

    private fun today(verdict: String, options: List<TodayOption>, primary: String?, rest: List<Reason> = emptyList(), planned: PlannedToday? = null) =
        TodayOptions(
            LocalDate.parse("2026-10-07"), 75.0, "default", verdict, planned, options, primary, rest,
            RestAdvice(verdict != TodayRecommender.TRAIN, verdict == TodayRecommender.REST_HARD, rest.firstOrNull()?.code, emptyMap()),
            listOf(Excluded("PULL", "PROTECT_REST", 1, mapOf("groups" to "lats,biceps,forearms", "slot" to "A", "when" to "2026-10-08T17:45:00Z"))),
            "id", week,
        )

    private fun card(t: TodayOptions?, active: Boolean = false, finished: Boolean = false) =
        UiTodayCard.card(t, active, finished, false, false, wed, zone, params) { it.replaceFirstChar(Char::uppercase) }

    @Test
    fun `wedFixtureSoftRest - REST soft, no primary, the rest chips in class order`() {
        val rest = listOf(
            Reason("BETTER_OTHER_DAY", 3, mapOf("type" to "Legs + push + rear delts", "day" to "FRIDAY"), 0.12),
            Reason("NEXT_FIXED", 1, mapOf("slot" to "A", "at" to "2026-10-08T17:45:00Z"), 0.1),
        )
        val c = card(today(TodayRecommender.REST_SOFT, listOf(opt("opt:FULL", "FULL", 0.441), opt("opt:PUSH+LEGS", "PUSH+LEGS", 0.438)), "opt:FULL", rest))
        c.state shouldBe CardState.REST
        c.variant shouldBe "SOFT"
        c.primary shouldBe null
        c.chips.map { it.text } shouldBe listOf("A Thu 19:45", "Legs + push + rear delts fits better on Friday")
        c.notToday shouldBe listOf("Pull: Lats, Biceps and Forearms are protected; session A tomorrow")
    }

    @Test
    fun `capacity19CloseCall - the second option within 10 percent is inline`() {
        val c = card(today(TodayRecommender.TRAIN, listOf(opt("opt:FULL", "FULL", 0.478), opt("opt:PUSH+LEGS", "PUSH+LEGS", 0.471)), "opt:FULL"))
        c.state shouldBe CardState.OPTIONAL
        c.variant shouldBe "CLOSE"
        c.alternativesInline shouldBe true
    }

    @Test
    fun `friOptionalClear - GF-UX-01's boundary`() {
        val clear = card(today(TodayRecommender.TRAIN, listOf(opt("a", "FULL", 1.84), opt("b", "UPPER", 1.6559)), "a"))
        clear.variant shouldBe "CLEAR"
        val close = card(today(TodayRecommender.TRAIN, listOf(opt("a", "FULL", 1.84), opt("b", "UPPER", 1.656)), "a"))
        close.variant shouldBe "CLOSE"
    }

    @Test
    fun `primaryIsEnginesChoice - the card never re-sorts`() {
        val c = card(today(TodayRecommender.TRAIN, listOf(opt("opt:PUSH+LEGS", "PUSH+LEGS", 0.6), opt("opt:FULL", "FULL", 0.9)), "opt:PUSH+LEGS"))
        c.primary?.optionId shouldBe "opt:PUSH+LEGS"
    }

    @Test
    fun `satHardRest and testIgnoresRestHard`() {
        val rest = listOf(Reason("REST_BEFORE", 1, mapOf("slot" to "TEST", "when" to "2026-10-08T11:00:00Z"), 0.4))
        val sat = card(today(TodayRecommender.REST_HARD, emptyList(), null, rest))
        sat.state shouldBe CardState.REST
        sat.variant shouldBe "HARD"
        sat.chips.first().text shouldBe "Rest before test tomorrow"
        val test = PlannedToday("test", "TEST", null, wed, wed.plusSeconds(7200), "t", "Test day")
        card(today(TodayRecommender.REST_HARD, listOf(opt("opt:TEST:none", null, 0.5)), "opt:TEST:none", rest, test)).state shouldBe CardState.PLANNED
    }

    @Test
    fun `thuPlannedChallenge, inProgressWins and doneShowsNext`() {
        val a = PlannedToday("challenge", "A", null, wed, wed.plusSeconds(5400), "a", "Session A")
        val t = today(TodayRecommender.TRAIN, listOf(opt("opt:A:LEGS", null, 0.84), opt("opt:A:none", null, 0.5)), "opt:A:LEGS", planned = a)
        card(t).let { it.state shouldBe CardState.PLANNED; it.variant shouldBe "CHALLENGE"; it.primary?.optionId shouldBe "opt:A:LEGS" }
        card(t, active = true).state shouldBe CardState.IN_PROGRESS
        card(today(TodayRecommender.REST_SOFT, emptyList(), null), finished = true).state shouldBe CardState.DONE
    }

    @Test
    fun `reentryNamesTheGroup - D71`() {
        val r = Reason("REENTRY", 3, mapOf("group" to "calves", "family" to "legs", "sessions_remaining" to "1"), 0.5)
        val t = today(TodayRecommender.TRAIN, listOf(opt("opt:LEGS", "LEGS", 0.6, listOf(r))), "opt:LEGS")
        card(t).chips.single { it.code == "REENTRY" }.text shouldBe "Lighter start for calves"
    }
}
