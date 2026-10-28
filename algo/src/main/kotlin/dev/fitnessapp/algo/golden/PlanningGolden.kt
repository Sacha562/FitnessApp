package dev.fitnessapp.algo.golden

import dev.fitnessapp.algo.accounting.readiness.Dose
import dev.fitnessapp.algo.accounting.readiness.Readiness
import dev.fitnessapp.algo.accounting.setcredit.CreditSet
import dev.fitnessapp.algo.accounting.setcredit.CreditedSet
import dev.fitnessapp.algo.accounting.setcredit.SetCredit
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.catalog.MuscleGroup
import dev.fitnessapp.algo.selection.catalog.MuscleRegion
import dev.fitnessapp.algo.selection.catalog.Taxonomy
import dev.fitnessapp.algo.targets.GroupGoal
import dev.fitnessapp.algo.targets.Targets
import dev.fitnessapp.algo.targets.TargetsInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Golden adapters for the M4.1 planning modules (ALGORITHMS_PLANNING 24.2: W41_accounting, W41_readiness, W41_targets,
 * DST_W43_readiness). A fixture carries the catalog rows it uses and a region -> group map, so it runs without the
 * bundled catalog (the on-device self-test passes params only).
 */
internal object PlanningGolden {
    private val json = Json { ignoreUnknownKeys = false }

    /** Week account of `sets` in [week_start, now] with `committed_sets` grouped by session (SetCredit, 3.4). */
    fun setcredit(p: JsonObject, params: ParamView): JsonObject {
        val zone = ZoneId.of(str(p, "zone"))
        val catalog = catalog(p)
        val now = Instant.parse(str(p, "now"))
        val done = credited(p, "sets", catalog, zone, params)
        val committed = credited(p, "committed_sets", catalog, zone, params)
        val bySession = committed.groupBy { it.set.sessionId }.toSortedMap().values.map { s -> SetCredit.sessionVhyp(s.map { it.credit }) }
        val from = LocalDate.parse(str(p, "week_start")).atStartOfDay(zone).toInstant()
        val acc = SetCredit.account(done, bySession, emptyList(), from, now, zone, params)
        return buildJsonObject {
            put("sets", buildJsonArray {
                (done + committed).forEach { c ->
                    add(buildJsonObject {
                        put("set_id", c.set.id)
                        put("q", ratio(c.credit.qUsed))
                        put("q_source", c.credit.qSource)
                        put("vhyp", groups(c.credit.vhyp))
                        put("fat", groups(c.credit.fat))
                        put("sys", ratio(c.credit.sys))
                    })
                }
            })
            put("done", buildJsonObject {
                acc.done.forEach { (g, d) ->
                    put(g, buildJsonObject { put("fs", ratio(d.fs)); put("vhyp", ratio(d.vhyp)); put("vstr", ratio(d.vstr)); put("fat", ratio(d.fat)) })
                }
            })
            put("committed", groups(acc.committed))
            put("before_general", groups(SetCredit.sum(listOf(acc.done.mapValues { it.value.vhyp }, acc.committed))))
            put("sessions_training", buildJsonObject { acc.sessionsTraining.forEach { (g, n) -> put(g, JsonNumbers.int(n.toLong())) } })
            put("days_since_trained", buildJsonObject { acc.daysSinceTrained.forEach { (g, n) -> put(g, n?.let { JsonNumbers.int(it) } ?: JsonNull) } })
            put("missing_rir_share", ratio(acc.missingRirShare))
        }
    }

    /** Readiness at each of `at` from the doses of `sets` and `committed_sets` (at their times) plus inline `doses`. */
    fun readiness(p: JsonObject, params: ParamView): JsonObject {
        val zone = ZoneId.of(str(p, "zone"))
        val doses = ArrayList<Dose>()
        if (p.containsKey("sets")) {
            val catalog = catalog(p)
            (credited(p, "sets", catalog, zone, params) + credited(p, "committed_sets", catalog, zone, params))
                .forEach { doses += Dose.of(it.credit, it.set.ts) }
        }
        p["doses"]?.jsonArray?.forEach { e ->
            val o = e.jsonObject
            doses += Dose(str(o, "id"), Instant.parse(str(o, "t")), numbers(o["fat"]), numbers(o["tissue"]), o["sys"]?.let { d(it) } ?: 0.0)
        }
        return buildJsonObject {
            put("at", buildJsonArray {
                p.getValue("at").jsonArray.forEach { e ->
                    val t = Instant.parse(e.jsonPrimitive.content)
                    val r = Readiness.evaluate(doses, t, null, null, params)
                    add(buildJsonObject {
                        put("t", t.toString())
                        put("r", groups(r.r.filterValues { it < 1.0 }))
                        put("tissue_r", groups(r.tissueR.filterValues { it < 1.0 }))
                        put("g", ratio(r.g))
                    })
                }
            })
        }
    }

    /** WeeklyTargets (5) for a preset or explicit goals. */
    fun targets(p: JsonObject, params: ParamView): JsonObject {
        val goals = p.getValue("goals").jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            GroupGoal(str(o, "goal"), o["focus"]?.jsonPrimitive?.content == "true")
        }
        val input = TargetsInput(
            isoWeek = str(p, "iso_week"), goals = goals, trainingAge = p["training_age"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            age = null, ownedGroups = p.getValue("owned").jsonArray.map { it.jsonPrimitive.content }, contractActive = p["contract_active"]?.jsonPrimitive?.content == "true",
            challengeDeloadWeek = p["deload_week"]?.jsonPrimitive?.content == "true", committed = numbers(p["committed"]), done = numbers(p["done"]),
            trainedDates = emptyMap(), today = LocalDate.parse(str(p, "today")),
        )
        val t = Targets.compute(input, params)
        return buildJsonObject {
            put("status", t.status)
            put("base", ratio(t.base))
            put("deload", buildJsonObject {
                put("active", t.deload.active); put("source", t.deload.source)
                put("set_factor", ratio(t.deload.setFactor)); put("rir_offset", JsonNumbers.int(t.deload.rirOffset.toLong()))
            })
            put("groups", buildJsonObject {
                t.groups.forEach { (g, gt) ->
                    put(g, buildJsonObject {
                        put("status", gt.status); put("t", ratio(gt.t)); put("target_effective", ratio(gt.targetEffective)); put("w", ratio(gt.w))
                        put("cap_s", ratio(gt.capS)); put("min_touch", ratio(gt.minTouch)); put("met_by_challenge", gt.metByChallenge)
                        put("reentry", gt.reentry.active)
                        gt.transform?.let { tr -> put("transform", buildJsonObject { put("set_factor", ratio(tr.setFactor)); put("rir_offset", JsonNumbers.int(tr.rirOffset.toLong())) }) }
                    })
                }
            })
            put("general_target_sum", ratio(t.groups.values.sumOf { it.t }))
        }
    }

    private fun credited(p: JsonObject, key: String, catalog: Catalog, zone: ZoneId, params: ParamView): List<CreditedSet> {
        val last = p["last_done"]?.jsonObject?.mapValues { LocalDate.parse(it.value.jsonPrimitive.content) }.orEmpty()
        val sets = p[key]?.jsonArray?.map { e ->
            val o = e.jsonObject
            CreditSet(
                id = str(o, "id"), sessionId = str(o, "session"), exerciseSlug = str(o, "exercise"), side = str(o, "side"),
                setKind = o["set_kind"]?.jsonPrimitive?.content ?: "working", completed = true, ts = Instant.parse(str(o, "ts")),
                durationS = o["duration_s"]?.let { d(it) }, felt = o["felt"]?.jsonPrimitive?.content, rirReported = o["rir"]?.let { d(it) },
                plannedRir = o["planned_rir"]?.let { d(it) },
            )
        }.orEmpty().sortedWith(compareBy({ it.ts }, { it.id }))
        // Novelty history: the fixture's last date before the week, then each earlier day of the same exercise.
        val seen = java.util.TreeMap<String, LocalDate>()
        return sets.map { s ->
            val day = Week.localDate(s.ts, zone)
            val prior = listOfNotNull(last[s.exerciseSlug], seen[s.exerciseSlug]?.takeIf { it < day }).maxOrNull()
            if ((seen[s.exerciseSlug] ?: LocalDate.MIN) < day) seen[s.exerciseSlug] = day
            CreditedSet(s, SetCredit.credit(s, requireNotNull(catalog.exercise(s.exerciseSlug)) { "fixture lacks ${s.exerciseSlug}" }, catalog, prior, zone, params))
        }
    }

    private fun catalog(p: JsonObject): Catalog {
        val regions = p.getValue("regions").jsonObject.entries.sortedBy { it.key }
        val taxonomy = Taxonomy(
            Groups.CANONICAL.mapIndexed { i, g -> MuscleGroup(g, Groups.familyOf(g).lowercase(), g, sort = i) },
            regions.mapIndexed { i, (r, g) -> MuscleRegion(r, g.jsonPrimitive.content, labelEn = r, sort = i) },
        )
        val rows = p.getValue("exercises").jsonArray.map { json.decodeFromJsonElement(CatalogExercise.serializer(), it) }
        return Catalog(CatalogDoc("fixture", rows, emptyList()), taxonomy)
    }

    private fun groups(m: Map<String, Double>) = buildJsonObject {
        Groups.CANONICAL.forEach { g -> m[g]?.let { put(g, ratio(it)) } }
        m.keys.filter { it !in Groups.CANONICAL }.sorted().forEach { put(it, ratio(m.getValue(it))) }
    }

    private fun numbers(e: JsonElement?): Map<String, Double> = e?.jsonObject?.mapValues { d(it.value) }.orEmpty()
    private fun d(e: JsonElement): Double = e.jsonPrimitive.content.toDouble()
    private fun str(o: JsonObject, k: String): String = o.getValue(k).jsonPrimitive.content
    private fun ratio(x: Double) = JsonNumbers.grid(Grid.ratio(x), GridKind.RATIO)
}
