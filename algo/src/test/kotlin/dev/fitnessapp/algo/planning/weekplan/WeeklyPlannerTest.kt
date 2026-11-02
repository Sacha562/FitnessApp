package dev.fitnessapp.algo.planning.weekplan

import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.golden.WeekGolden
import dev.fitnessapp.algo.planning.today.TodayInput
import dev.fitnessapp.algo.planning.today.TodayRecommender
import dev.fitnessapp.algo.targets.Reentry
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

/** ALGORITHMS_PLANNING 8.12 and 9.9 on the F-W41 payload of the golden fixtures, varied one input at a time. */
class WeeklyPlannerTest {
    private val root = File(requireNotNull(System.getProperty("fitness.repoRoot")) { "fitness.repoRoot not set" })
    private val base: JsonObject = Json.parseToJsonElement(File(root, "fixtures/golden/algo/GF-W41-WEEKPLAN.json").readText(Charsets.UTF_8))
        .jsonObject.getValue("input").jsonObject.getValue("payload").jsonObject

    private fun payload(vararg changes: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(base + changes)

    private fun plan(p: JsonObject, params: ParamView = TestParams.defaults()) = WeeklyPlanner.plan(WeekGolden.input(p, params), params)

    private fun day(w: WeekPlan, date: String) = w.days.first { it.date == LocalDate.parse(date) }

    @Test
    fun `deterministic - identical inputs give the identical plan`() {
        val a = plan(base)
        val b = plan(base)
        a.planId shouldBe b.planId
        a shouldBe b
    }

    @Test
    fun `addonNotAVisit - an add-on never counts as a visit`() {
        val w = plan(base)
        w.days.flatMap { it.items }.count { it.kind == WeeklyPlanner.KIND_ADDON } shouldBe 1
        w.visits.recommended shouldBe 1
        w.recommendedN shouldBe 4
    }

    @Test
    fun `maxVisitsBinds - three visits leave no general session`() {
        val w = plan(payload("max_visits" to JsonPrimitive(3)))
        w.days.flatMap { it.items }.count { it.kind == WeeklyPlanner.KIND_RECOMMENDED } shouldBe 0
        day(w, "2026-10-07").restReason?.code shouldBe "MAX_VISITS"
        day(w, "2026-10-09").restReason?.code shouldBe "MAX_VISITS"
    }

    @Test
    fun `pastLocked - replanning on Thursday keeps Monday to Wednesday past`() {
        val w = plan(payload("now" to JsonPrimitive("2026-10-08T10:00:00Z")))
        listOf("2026-10-05", "2026-10-06", "2026-10-07").forEach { day(w, it).status shouldBe "past" }
        day(w, "2026-10-07").items.shouldBeEmpty()
    }

    @Test
    fun `restBefore - the day before the test is blocked`() {
        val w = plan(base)
        day(w, "2026-10-10").status shouldBe "blocked"
        day(w, "2026-10-10").restReason?.code shouldBe "REST_BEFORE"
    }

    @Test
    fun `the gap day before A carries light pull only`() {
        val w = plan(payload("max_visits" to JsonPrimitive(3)))
        val wed = day(w, "2026-10-07")
        wed.lightPullOnly shouldBe true
        wed.allowedFamilies shouldBe listOf("LEGS", "PUSH", "CORE")
    }

    @Test
    fun `stability - the previous plan's day keys are kept when the rest ties`() {
        val first = plan(base)
        val keys = WeeklyPlanner.dayKeys(first)
        val again = WeeklyPlanner.plan(WeekGolden.input(base, TestParams.defaults()).copy(previous = keys), TestParams.defaults())
        WeeklyPlanner.dayKeys(again) shouldBe keys
        again.terms.getValue("stability") shouldNotBe 0.0
    }

    @Test
    fun `evalGuard - kMax shrinks until the plan count fits MAX_EVALS`() {
        val values = TestParams.defaults().values()
        val params = ParamView(JsonObject(values + ("weekplan.max_evals" to JsonPrimitive(100000))))
        val ctx = WeeklyPlanner.Context(WeekGolden.input(noChallenge(7), params), params)
        // 7 free days: k <= 3 gives 19,321 plans, k = 4 gives 162,681 > 100,000.
        ctx.kMax shouldBe 3
        ctx.kMaxReduced shouldBe true
    }

    @Test
    fun `noChallengeUser - sessions spread with a rest day and heavy legs 40 h apart`() {
        val w = plan(noChallenge(3))
        val sessions = w.days.flatMap { d -> d.items.filter { it.kind == WeeklyPlanner.KIND_RECOMMENDED } }
        sessions.size shouldBeGreaterThanOrEqual 2
        w.days.count { it.items.isEmpty() } shouldBeGreaterThanOrEqual 1
        val heavy = sessions.filter { s -> listOf("quads", "hamstrings", "glutes").sumOf { s.credit[it] ?: 0.0 } >= 4.0 }
        heavy.zipWithNext().forEach { (a, b) -> (java.time.Duration.between(a.start, b.start).toHours() >= 40) shouldBe true }
    }

    @Test
    fun `userPlannedEventWins - a planned legs event today makes legs the primary`() {
        val planned = buildJsonObject {
            put("key", "cal-legs"); put("kind", "planned_gym"); put("date", "2026-10-07"); put("start", "2026-10-07T16:00:00Z")
            put("end", "2026-10-07T17:00:00Z"); put("label", "Gym - legs"); put("planned_type", "LEGS")
        }
        val p = payload("fixed" to JsonArray(base.getValue("fixed").let { it as JsonArray } + planned))
        val r = TodayRecommender.recommend(TodayInput(WeekGolden.input(p, TestParams.defaults())), TestParams.defaults())
        r.planned?.kind shouldBe "general"
        r.primaryOptionId shouldBe "opt:LEGS"
        r.budgetSource shouldBe "calendar"
        r.budgetMin shouldBe 55.0
    }

    @Test
    fun `budgetChip45 - the chip shortens the session, not the type`() {
        val p = WeekGolden.input(base, TestParams.defaults())
        val full = TodayRecommender.recommend(TodayInput(p), TestParams.defaults())
        val short = TodayRecommender.recommend(TodayInput(p, chipMin = 45.0), TestParams.defaults())
        short.budgetSource shouldBe "chip"
        // The same type, or the earlier type its shorter allocation now equals (9.5 shows such a type once).
        val fullDirect = full.options.first { it.optionId == full.primaryOptionId }.direct
        (short.primaryOptionId == full.primaryOptionId || short.options.first { it.optionId == short.primaryOptionId }.direct.keys.all { it in fullDirect }) shouldBe true
        val est = short.options.first { it.optionId == short.primaryOptionId }.estMinutes
        (est <= 45.0) shouldBe true
        (est < full.options.first { it.optionId == full.primaryOptionId }.estMinutes) shouldBe true
    }

    @Test
    fun `sat - every option is excluded on the rest day before the test`() {
        val r = TodayRecommender.recommend(TodayInput(WeekGolden.input(payload("now" to JsonPrimitive("2026-10-10T10:00:00Z")), TestParams.defaults())), TestParams.defaults())
        r.verdict shouldBe TodayRecommender.REST_HARD
        r.options.shouldBeEmpty()
        r.primaryOptionId shouldBe null
        r.restAdvice.reasonCode shouldBe "REST_BEFORE"
    }

    @Test
    fun `priorHeavyLegs - a heavy leg day on the previous Sunday keeps Monday free of heavy legs (D74)`() {
        // The week without the challenge, replanned on Monday 08:00 local; a heavy-lower session was logged on Sunday 19:00.
        val prior = buildJsonObject {
            put("key", "sun-legs"); put("kind", "done_general"); put("date", "2026-10-04"); put("start", "2026-10-04T17:00:00Z")
            put("end", "2026-10-04T18:10:00Z"); put("label", "Gym session"); put("heavy_lower_fs", 6.5)
        }
        val monday = JsonObject(noChallenge(2) + ("now" to JsonPrimitive("2026-10-05T06:00:00Z")))
        val withPrior = JsonObject(monday + ("prior_general" to JsonArray(listOf(prior))))
        val params = TestParams.defaults()
        fun heavy(credit: Map<String, Double>) = listOf("quads", "hamstrings", "glutes").sumOf { credit[it] ?: 0.0 } >= params.double("weekplan.heavy_lower_fs") - 1e-9
        val without = TodayRecommender.recommend(TodayInput(WeekGolden.input(monday, params)), params)
        val with = TodayRecommender.recommend(TodayInput(WeekGolden.input(withPrior, params)), params)
        without.options.any { heavy(it.credit) } shouldBe true
        with.options.none { heavy(it.credit) } shouldBe true
        with.excluded.any { it.reasonCode == "HEAVY_LOWER_GAP" } shouldBe true
        // The week plan keeps every heavy-legs session 40 h after Sunday's; the prior session is no visit.
        val w = plan(withPrior)
        w.days.flatMap { it.items }.filter { it.kind == WeeklyPlanner.KIND_RECOMMENDED && heavy(it.credit) }
            .forEach { (java.time.Duration.between(java.time.Instant.parse("2026-10-04T17:00:00Z"), it.start).toHours() >= 40) shouldBe true }
        w.visits.done shouldBe 0
    }

    @Test
    fun `reentryNamesTheGroup - the re-entry group with the most direct sets (D71)`() {
        val params = TestParams.defaults()
        val p0 = WeekGolden.input(base, params)
        val t = p0.targets.copy(deload = p0.targets.deload.copy(active = false), groups = p0.targets.groups.mapValues { (_, gt) -> gt.copy(reentry = Reentry(true, 2)) })
        val r = TodayRecommender.recommend(TodayInput(p0.copy(targets = t)), params)
        val primary = r.options.first { it.optionId == r.primaryOptionId }
        val re = primary.reasons.single { it.code == "REENTRY" }
        re.params["group"] shouldBe primary.direct.entries.filter { it.value > 0.0 }.sortedWith(compareBy({ -it.value }, { it.key })).first().key
        re.params["sessions_remaining"] shouldBe "2"
    }

    /** F-W41 without the challenge: no logged or planned sets, no fixed items or rules, every day free. */
    private fun noChallenge(maxVisits: Int): JsonObject {
        val t = base.getValue("targets").jsonObject
        val goals = t.getValue("goals").jsonObject
        val targets = JsonObject(
            t + ("owned" to JsonArray(emptyList())) + ("contract_active" to JsonPrimitive(false)) + ("deload_week" to JsonPrimitive(false)) +
                ("goals" to goals),
        )
        val windows = buildJsonObject {
            (0..6).forEach { i ->
                val d = LocalDate.parse("2026-10-05").plusDays(i.toLong()).toString()
                put(d, buildJsonArray { add(buildJsonArray { add(JsonPrimitive("18:00")); add(JsonPrimitive("21:00")) }) })
            }
        }
        return payload(
            "now" to JsonPrimitive("2026-10-04T20:00:00Z"), "sets" to JsonArray(emptyList()), "committed_sets" to JsonArray(emptyList()),
            "fixed" to JsonArray(emptyList()), "blocked" to JsonArray(emptyList()), "protected_windows" to JsonArray(emptyList()),
            "owned" to JsonArray(emptyList()), "busy" to JsonArray(emptyList()), "windows" to windows, "targets" to targets,
            "max_visits" to JsonPrimitive(maxVisits), "availability" to JsonObject(emptyMap()),
        )
    }
}
