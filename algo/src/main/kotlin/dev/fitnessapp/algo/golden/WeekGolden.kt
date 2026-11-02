package dev.fitnessapp.algo.golden

import dev.fitnessapp.algo.accounting.readiness.Dose
import dev.fitnessapp.algo.accounting.setcredit.CreditSet
import dev.fitnessapp.algo.accounting.setcredit.CreditedSet
import dev.fitnessapp.algo.accounting.setcredit.SetCredit
import dev.fitnessapp.algo.challenge.contract.ContractWindow
import dev.fitnessapp.algo.challenge.contract.GapRule
import dev.fitnessapp.algo.challenge.plan.BlockedDay
import dev.fitnessapp.algo.challenge.plan.Interval
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.golden.PlanningGolden.d
import dev.fitnessapp.algo.golden.PlanningGolden.groups
import dev.fitnessapp.algo.golden.PlanningGolden.numbers
import dev.fitnessapp.algo.golden.PlanningGolden.ratio
import dev.fitnessapp.algo.golden.PlanningGolden.str
import dev.fitnessapp.algo.planning.SessionTagRule
import dev.fitnessapp.algo.planning.allocator.AddOnSlot
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.today.TodayInput
import dev.fitnessapp.algo.planning.today.TodayRecommender
import dev.fitnessapp.algo.planning.weekplan.AddOnFamily
import dev.fitnessapp.algo.planning.weekplan.AddOnSpec
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.GroupAvailability
import dev.fitnessapp.algo.planning.weekplan.ReasonCode
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeekPlanInput
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TreeMap

/**
 * Golden adapters for the M4.5 planner (ALGORITHMS_PLANNING 24.2: W41_weekplan, the W41_today days, W41_trainAnywayWed,
 * the capacity and novice variants, W41_testPostponed). A payload carries the W41 accounting payload (catalog rows, the
 * logged and planned sets; every set at or before `now` counts as done, later ones as committed), optional inline
 * doses for logged general sessions, the targets payload, the fixed items, the challenge's windows and rules, and the
 * group availability on gap days.
 */
internal object WeekGolden {
    fun weekplan(p: JsonObject, params: ParamView): JsonObject = planJson(WeeklyPlanner.plan(input(p, params), params), full = true)

    fun today(p: JsonObject, params: ParamView): JsonObject {
        val t = p["today"]?.jsonObject ?: JsonObject(emptyMap())
        val r = TodayRecommender.recommend(
            TodayInput(
                input(p, params), chipMin = t["chip_min"]?.let { d(it) },
                safetyRest = t["safety_rest"]?.jsonPrimitive?.content?.let { ReasonCode(it) },
            ),
            params,
        )
        return buildJsonObject {
            put("verdict", r.verdict)
            put("budget_min", JsonNumbers.grid(r.budgetMin, GridKind.S))
            put("budget_source", r.budgetSource)
            r.planned?.let { pl -> put("planned", buildJsonObject { put("kind", pl.kind); pl.slot?.let { put("slot", it) }; put("label", pl.label) }) }
            r.primaryOptionId?.let { put("primary", it) }
            put("options", buildJsonArray {
                r.options.forEach { o ->
                    add(buildJsonObject {
                        put("id", o.optionId); put("label", o.label); put("score", ratio(o.score)); put("dj", ratio(o.dJ))
                        put("est_min", JsonNumbers.grid(o.estMinutes, GridKind.S))
                        o.addon?.let { put("addon", it) }
                        put("direct", groups(o.direct))
                        if (o.laterTypes.isNotEmpty()) put("later", buildJsonArray { o.laterTypes.forEach { add(JsonPrimitive(it)) } })
                        put("reasons", buildJsonArray { o.reasons.forEach { x -> add(reasonJson(x.code, x.params, x.cls, x.weight)) } })
                    })
                }
            })
            put("rest_reasons", buildJsonArray { r.restReasons.forEach { x -> add(reasonJson(x.code, x.params, x.cls, x.weight)) } })
            put("excluded", buildJsonArray { r.excluded.forEach { x -> add(buildJsonObject { put("subject", x.subject); put("code", x.reasonCode) }) } })
            put("week", planJson(r.weekPlan, full = false))
        }
    }

    private fun reasonJson(code: String, params: Map<String, String>, cls: Int, weight: Double) = buildJsonObject {
        put("code", code); put("class", JsonNumbers.int(cls.toLong())); put("weight", ratio(weight))
        if (params.isNotEmpty()) put("params", buildJsonObject { params.toSortedMap().forEach { (k, v) -> put(k, v) } })
    }

    private fun planJson(w: WeekPlan, full: Boolean) = buildJsonObject {
        put("j", ratio(w.j))
        put("recommended_n", JsonNumbers.int(w.recommendedN.toLong()))
        put("visits", buildJsonObject {
            put("done", JsonNumbers.int(w.visits.done.toLong())); put("fixed", JsonNumbers.int(w.visits.fixed.toLong()))
            put("recommended", JsonNumbers.int(w.visits.recommended.toLong())); put("max", JsonNumbers.int(w.visits.max.toLong()))
        })
        put("band", buildJsonObject {
            put("j_star", ratio(w.band.jStar)); put("in_band", JsonNumbers.int(w.band.candidatesInBand.toLong())); put("tie_key_used", w.band.tieKeyUsed)
        })
        put("days", buildJsonArray {
            w.days.forEach { day ->
                add(buildJsonObject {
                    put("date", day.date.toString()); put("status", day.status)
                    put("items", buildJsonArray {
                        day.items.forEach { i ->
                            add(buildJsonObject {
                                put("kind", i.kind); i.slot?.let { put("slot", it) }; i.type?.let { put("type", it) }; put("label", i.label)
                                put("start", i.start.toString()); put("est_min", JsonNumbers.grid(i.estMinutes, GridKind.S)); put("write_policy", i.writePolicy)
                                if (full && i.direct.isNotEmpty()) put("direct", groups(i.direct))
                            })
                        }
                    })
                    day.restReason?.let { rr -> put("rest_reason", reasonJson(rr.code, rr.params.filterKeys { it != "when" || full }, 0, 0.0).let { JsonObject(it - "class" - "weight") }) }
                    day.allowedFamilies?.let { f -> put("allowed_families", buildJsonArray { f.forEach { add(JsonPrimitive(it)) } }) }
                    if (day.lightPullOnly) put("light_pull_only", true)
                })
            }
        })
        if (full) {
            put("terms", buildJsonObject { w.terms.forEach { (k, v) -> put(k, ratio(v)) } })
            put("evaluated", JsonNumbers.int(w.evaluated.toLong()))
            put("k_max", JsonNumbers.int(w.kMax.toLong()))
            put("top", buildJsonArray { w.top.take(8).forEach { s -> add(buildJsonObject { put("j", ratio(s.j)); put("choices", s.choices) }) } })
            put("projected_end", groups(w.projected.filterValues { it.end > 0.0 }.mapValues { it.value.end }))
            put("warnings", buildJsonArray { w.warnings.forEach { add(JsonPrimitive(it.code)) } })
        }
    }

    /** A general session or add-on logged earlier this week, as the planner allocated it at its start. */
    private class Logged(val fixed: FixedItem, val vhyp: Map<String, Double>, val dose: Dose?)

    /**
     * The planner's input at the fixture's `now`. Each `logged` entry (a general session or an add-on after `parent`) is
     * first allocated by the planner itself one minute before its start, then counts as done: its Vhyp, its dose, its
     * sessions trained and its heavy-lower FS. A challenge session or test whose end is past counts as done.
     */
    internal fun input(p: JsonObject, params: ParamView): WeekPlanInput {
        val logged = ArrayList<Logged>()
        for (e in p["logged"]?.jsonArray.orEmpty()) {
            val o = e.jsonObject
            val start = Instant.parse(str(o, "start"))
            val ctx = WeeklyPlanner.Context(input(p, params, start.minusSeconds(60), logged), params)
            val type = SessionType.of(str(o, "type"))
            val parent = o["parent"]?.jsonPrimitive?.content
            val pt = ctx.points.first { if (parent != null) it.addOn != null && it.fixed?.key == parent else it.kind == "free" && it.start == start }
            val c = IntArray(ctx.points.size)
            c[pt.index] = pt.options.indexOf(type)
            val sim = requireNotNull(ctx.simulate(c)) { "logged ${str(o, "key")} infeasible: ${ctx.failure}" }.sessions.first { it.point.index == pt.index }
            val lower = listOf("quads", "hamstrings", "glutes").sumOf { sim.alloc.credit[it] ?: 0.0 }
            val end = start.plusMillis((sim.alloc.estMinutes * 60_000.0).toLong())
            logged += Logged(
                FixedItem(str(o, "key"), FixedItem.KIND_DONE_GENERAL, Week.localDate(start, ZoneId.of(str(p, "zone"))), start, end, sim.alloc.label, heavyLowerFs = lower, tags = sim.alloc.predictedTags),
                sim.alloc.vhyp, sim.alloc.dose?.copy(id = "logged:${str(o, "key")}"),
            )
        }
        return input(p, params, Instant.parse(str(p, "now")), logged)
    }

    private fun input(p: JsonObject, params: ParamView, now: Instant, logged: List<Logged>): WeekPlanInput {
        val zone = ZoneId.of(str(p, "zone"))
        val monday = LocalDate.parse(str(p, "week_start"))
        val catalog = PlanningGolden.catalog(p)
        val last = p["last_done"]?.jsonObject?.mapValues { LocalDate.parse(it.value.jsonPrimitive.content) }.orEmpty()
        val all = (p["sets"]?.jsonArray.orEmpty() + p["committed_sets"]?.jsonArray.orEmpty()).map { e ->
            val o = e.jsonObject
            CreditSet(
                id = str(o, "id"), sessionId = str(o, "session"), exerciseSlug = str(o, "exercise"), side = str(o, "side"),
                setKind = o["set_kind"]?.jsonPrimitive?.content ?: "working", completed = true, ts = Instant.parse(str(o, "ts")),
                durationS = o["duration_s"]?.let { d(it) }, felt = o["felt"]?.jsonPrimitive?.content,
                rirReported = o["rir"]?.let { d(it) }, plannedRir = o["planned_rir"]?.let { d(it) },
            )
        }.sortedWith(compareBy({ it.ts }, { it.id }))
        val seen = TreeMap<String, LocalDate>()
        val credited = all.map { s ->
            val day = Week.localDate(s.ts, zone)
            val prior = listOfNotNull(last[s.exerciseSlug], seen[s.exerciseSlug]?.takeIf { it < day }).maxOrNull()
            if ((seen[s.exerciseSlug] ?: LocalDate.MIN) < day) seen[s.exerciseSlug] = day
            CreditedSet(s, SetCredit.credit(s, requireNotNull(catalog.exercise(s.exerciseSlug)) { "fixture lacks ${s.exerciseSlug}" }, catalog, prior, zone, params))
        }
        val history = credited.filter { !it.set.ts.isAfter(now) }
        val ahead = credited.filter { it.set.ts.isAfter(now) }
        val bySession = ahead.groupBy { it.set.sessionId }.toSortedMap().values.map { s -> SetCredit.sessionVhyp(s.map { it.credit }) }
        val from = Week.atLocal(monday, LocalTime.MIDNIGHT, zone)
        val acc = SetCredit.account(history, bySession, emptyList(), from, now, zone, params)
        val extraDone = TreeMap<String, Double>()
        logged.forEach { l -> l.vhyp.forEach { (g, v) -> extraDone[g] = (extraDone[g] ?: 0.0) + v } }
        val done = TreeMap<String, Double>()
        Groups.CANONICAL.forEach { g -> val v = (acc.done[g]?.vhyp ?: 0.0) + (extraDone[g] ?: 0.0); if (v != 0.0) done[g] = v }
        val trained = TreeMap(acc.sessionsTraining)
        val minCredit = params.double("setcredit.trained_min_credit")
        logged.forEach { l -> Groups.CANONICAL.filter { (l.vhyp[it] ?: 0.0) >= minCredit }.forEach { trained[it] = (trained[it] ?: 0) + 1 } }
        val doses = credited.map { Dose.of(it.credit, it.set.ts) } + logged.mapNotNull { it.dose }
        val tp = p.getValue("targets").jsonObject
        val targets = PlanningGolden.weeklyTargets(
            JsonObject(tp + ("done" to groups(done)) + ("committed" to groups(acc.committed))), params,
        )
        val unavailable = strings(p["availability"]?.jsonObject?.get("gap_day_unavailable")).toSet()
        val availability = GroupAvailability { gap, tags, _ -> Groups.CANONICAL.associateWith { !(gap && tags.isNotEmpty() && it in unavailable) } }
        val windows = TreeMap<LocalDate, List<Interval>>()
        p.getValue("windows").jsonObject.forEach { (date, v) ->
            val d = LocalDate.parse(date)
            windows[d] = v.jsonArray.map { w ->
                val a = w.jsonArray
                Interval(Week.atLocal(d, LocalTime.parse(a[0].jsonPrimitive.content), zone), Week.atLocal(d, LocalTime.parse(a[1].jsonPrimitive.content), zone))
            }
        }
        return WeekPlanInput(
            isoWeek = Week.isoWeek(monday), monday = monday, zone = zone, now = now, windows = windows,
            busy = p["busy"]?.jsonArray.orEmpty().map { val a = it.jsonArray; Interval(Instant.parse(a[0].jsonPrimitive.content), Instant.parse(a[1].jsonPrimitive.content)) },
            fixed = p["fixed"]?.jsonArray.orEmpty().map { fixed(it.jsonObject) }.map { f ->
                if ((f.kind == FixedItem.KIND_CHALLENGE || f.kind == FixedItem.KIND_TEST) && !f.end.isAfter(now)) f.copy(kind = FixedItem.KIND_DONE, addOn = null) else f
            } + logged.map { it.fixed },
            priorGeneral = p["prior_general"]?.jsonArray.orEmpty().map { fixed(it.jsonObject) },
            blocked = p["blocked"]?.jsonArray.orEmpty().map { e ->
                val o = e.jsonObject
                BlockedDay(LocalDate.parse(str(o, "date")), str(o, "scope"), strings(o["tags"]), str(o, "reason"), str(o, "rule_id"))
            },
            gapRule = p["gap_rule"]?.jsonObject?.let { GapRule(it.getValue("days").jsonPrimitive.content.toInt(), strings(it["applies_to_tags"])) },
            protectedWindows = p["protected_windows"]?.jsonArray.orEmpty().map { e ->
                val o = e.jsonObject
                ContractWindow(
                    str(o, "id"), Instant.parse(str(o, "from")), Instant.parse(str(o, "to")), str(o, "mode"), strings(o["groups"]), strings(o["regions"]),
                    strings(o["session_tags"]), o["scope"]?.jsonPrimitive?.content, o["r_min"]?.let { d(it) }, numbers(o["tissue_r_min"]), "D", str(o, "slot"),
                )
            },
            ownedGroups = strings(p["owned"]).toSet(),
            tagRules = p["tag_rules"]?.jsonObject?.let { SessionTagRule.parse(it) }.orEmpty(),
            targets = targets, done = done, committed = acc.committed, sessionsTraining = trained, doses = doses,
            maxVisits = p.getValue("max_visits").jsonPrimitive.content.toInt(), defaultSessionMin = d(p.getValue("default_session_min")),
            changeoverMin = d(p.getValue("changeover_min")), availability = availability,
        )
    }

    private fun fixed(o: JsonObject): FixedItem {
        val date = LocalDate.parse(str(o, "date"))
        return FixedItem(
            key = str(o, "key"), kind = str(o, "kind"), date = date, start = Instant.parse(str(o, "start")), end = Instant.parse(str(o, "end")),
            label = str(o, "label"), slot = o["slot"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            heavyLowerFs = o["heavy_lower_fs"]?.let { d(it) } ?: 0.0, tags = strings(o["tags"]),
            plannedType = o["planned_type"]?.jsonPrimitive?.content?.let { SessionType.of(it) }, budgetMin = o["budget_min"]?.let { d(it) },
            addOn = o["add_on"]?.jsonObject?.let { a ->
                AddOnSpec(
                    Instant.parse(str(a, "start")), d(a.getValue("capacity_min")),
                    a.getValue("families").jsonArray.map { f ->
                        val fo = f.jsonObject
                        val type = SessionType.of(str(fo, "type"))
                        AddOnFamily(type, AddOnSlot(type.code, strings(fo["allowed_groups"]).toSet(), strings(a["forbid_groups"]).toSet(), a["max_sets"]?.let { d(it) }, a["min_rir"]?.jsonPrimitive?.content?.toInt()))
                    },
                    strings(a["forbidden_session_tags"]).toSet(),
                )
            },
        )
    }

    private fun strings(e: JsonElement?): List<String> = e?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
}
