package dev.fitnessapp.algo.golden

import dev.fitnessapp.algo.accounting.readiness.Dose
import dev.fitnessapp.algo.accounting.readiness.Readiness
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.det.Grid
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
import dev.fitnessapp.algo.planning.allocator.AllocInput
import dev.fitnessapp.algo.planning.allocator.Allocation
import dev.fitnessapp.algo.planning.allocator.Allocator
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.selection.elig.AddOnFilter
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.elig.GateInput
import dev.fitnessapp.algo.selection.elig.HardWindow
import dev.fitnessapp.algo.selection.elig.LightPullRule
import dev.fitnessapp.algo.selection.pref.Pref
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.pref.PrefValue
import dev.fitnessapp.algo.selection.select.Select
import dev.fitnessapp.algo.selection.select.SelectInput
import dev.fitnessapp.algo.selection.time.TimeModel
import dev.fitnessapp.algo.selection.time.WarmupKind
import dev.fitnessapp.algo.targets.WeeklyTargets
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.TreeMap

/**
 * Golden adapters for the M4.3 session-building modules (ALGORITHMS_PLANNING 24.2: W41_addon_thu, W41_allocation_fri,
 * W41_select_fri and the F-SEL cases of 22, regenerated on catalog 1.0.2, OI-16). An `allocator` payload carries a
 * `targets` payload and may chain `earlier` allocations whose projected Vhyp adds to `v_before`; a `select` payload
 * carries the catalog rows it uses, the ELIG-1 context and either explicit targets or an `allocate` block.
 */
internal object SessionGolden {
    fun allocator(p: JsonObject, params: ParamView): JsonObject {
        val (alloc, capacity) = allocate(p, params)
        return buildJsonObject {
            capacity?.let { put("capacity_min", JsonNumbers.int(it.toLong())) }
            put("allocation", allocationJson(alloc))
        }
    }

    fun select(p: JsonObject, params: ParamView): JsonObject {
        val catalog = PlanningGolden.catalog(p)
        val e = p["elig"]?.jsonObject ?: JsonObject(emptyMap())
        // `hidden` marks exercises whose station is taken (occupied) for the whole block.
        val hidden = strings(e["hidden"]).toSet()
        val tiers = p["prefs"]?.jsonObject?.mapValues { PrefTier.of(it.value.jsonPrimitive.content) }.orEmpty()
        val prefs = (tiers.keys + hidden).associateWith { slug ->
            val tier = tiers[slug] ?: PrefTier.NEUTRAL
            PrefValue(Pref.mult(tier, params), tier, hidden = slug in hidden, z = 0.0, explicit = slug in tiers, prompt = false)
        }
        val addOn = e["add_on"]?.jsonObject?.let { a ->
            AddOnFilter(strings(a["allow_patterns"]), a["max_grip_demand"]?.jsonPrimitive?.content?.toInt(), strings(a["forbid_groups"]), Groups.FAMILIES.getValue(str(a, "family")))
        }
        val ctx = EligContext(
            prefs = prefs, jointFlags = numbers(e["joint_flags"]).mapValues { it.value.toInt() }, gapDay = e["gap_day"]?.jsonPrimitive?.content == "true",
            forbiddenSessionTags = strings(e["forbidden_session_tags"]), skipGroups = strings(e["skip_groups"]).toSet(), addOn = addOn,
            lightPull = e["light_pull"]?.jsonArray?.let { LightPullRule(it[0].jsonPrimitive.content.toInt(), d(it[1])) },
            goalGroups = e["goal_groups"]?.let { strings(it).toSet() },
            hardWindows = e["hard_windows"]?.jsonArray?.map { w ->
                val o = w.jsonObject
                HardWindow(str(o, "id"), strings(o["groups"]), strings(o["regions"]))
            }.orEmpty(),
        )
        var target = numbers(p["target"])
        var goals = p["goals"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        var capS = numbers(p["cap_s"])
        var alloc: Allocation? = null
        var capacity: Int? = null
        p["allocate"]?.jsonObject?.let { a ->
            val (al, cap) = allocate(a, params)
            alloc = al
            capacity = cap
            val t = PlanningGolden.weeklyTargets(a.getValue("targets").jsonObject, params)
            target = al.credit
            goals = t.groups.mapValues { it.value.status }
            capS = t.groups.mapValues { it.value.capS }
        }
        val budget = p["budget"]?.jsonObject?.let { b ->
            val total = capacity?.toDouble() ?: d(b.getValue("total_min"))
            total - TimeModel.warmupSeconds(WarmupKind.valueOf(str(b, "warmup").uppercase()), params) / 60.0
        } ?: d(p.getValue("budget_min"))
        val pool = Elig.pool(catalog, ctx, params)
        val gate = Elig.gate(pool, catalog, ctx, GateInput(target, goals), params)
        val input = SelectInput(
            pool = gate.pool, target = target, goals = goals, capS = capS, budgetMin = budget, focus = strings(p["focus"]).toSet(),
            mult = p["mult"]?.let { d(it) } ?: 1.0, maxSets = p["max_sets"]?.jsonPrimitive?.content?.toInt(),
            prefM = prefs.mapValues { it.value.m }, unlocked = gate.unlocked.associate { it.slug to it.group }, regionNeeds = strings(p["region_needs"]),
            jointFlags = ctx.jointFlags, forbiddenSessionTags = ctx.forbiddenSessionTags.toSet(),
            tagRules = p["tag_rules"]?.jsonObject?.let { SessionTagRule.parse(it) }.orEmpty(), stations = strings(p["stations"]).toSet(),
        )
        val r = Select.select(input, catalog, params)
        return buildJsonObject {
            alloc?.let { put("allocation", allocationJson(it)) }
            capacity?.let { put("capacity_min", JsonNumbers.int(it.toLong())) }
            put("budget_min", s(r.budgetMin))
            put("pool_size", JsonNumbers.int(gate.pool.size.toLong()))
            put("filtered", buildJsonObject { pool.filtered.forEach { (k, v) -> put(k, JsonNumbers.int(v.toLong())) } })
            put("unlocked", buildJsonArray { gate.unlocked.forEach { u -> add(buildJsonObject { put("exercise", u.slug); put("group", u.group) }) } })
            put("coverage_gaps", buildJsonArray { gate.coverageGaps.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("exercises", buildJsonArray {
                r.exercises.forEach { x ->
                    add(buildJsonObject {
                        put("exercise", x.slug); put("sets", JsonNumbers.int(x.sets.toLong())); put("est_s", s(x.estS)); put("b", ratio(x.b))
                        x.chainedAfter?.let { put("chained_after", it) }
                        x.unlockedFor?.let { put("unlocked_for", it) }
                    })
                }
            })
            put("sets", JsonNumbers.int(r.exercises.sumOf { it.sets }.toLong()))
            put("est_min", s(r.estMin))
            put("coverage", groups(r.coverage))
            put("shortfall", groups(r.shortfall))
            put("pref_limits_volume", r.prefLimitsVolume)
            put("terms", buildJsonObject {
                put("v", ratio(r.terms.v)); put("o", ratio(r.terms.o)); put("rb", ratio(r.terms.rb)); put("k", ratio(r.terms.k))
                put("x", ratio(r.terms.x)); put("t", ratio(r.terms.t)); put("j", ratio(r.terms.j))
            })
            put("top_candidates", buildJsonArray {
                r.topCandidates.take(5).forEach { c -> add(buildJsonObject { put("exercise", c.slug); put("j", ratio(c.j)) }) }
            })
        }
    }

    /** One allocation; `earlier` allocations run first (in order) and their projected Vhyp adds to `v_before`. */
    private fun allocate(p: JsonObject, params: ParamView): Pair<Allocation, Int?> {
        val targets: WeeklyTargets = PlanningGolden.weeklyTargets(p.getValue("targets").jsonObject, params)
        val vBefore = TreeMap(numbers(p["v_before"]))
        val doses = ArrayList<Dose>()
        p["earlier"]?.jsonArray?.forEach { e ->
            val o = e.jsonObject
            val withTargets = JsonObject(o + ("targets" to p.getValue("targets")) + ("v_before" to JsonObject(vBefore.mapValues { JsonNumbers.grid(it.value, GridKind.RATIO) })))
            val earlier = allocate(withTargets, params).first
            earlier.vhyp.forEach { (g, v) -> vBefore[g] = (vBefore[g] ?: 0.0) + v }
            earlier.dose?.let { doses += it }
        }
        // `readiness` is the challenge-only readiness at the start; the earlier general doses add their decayed fatigue.
        val start = Instant.parse(str(p, "start"))
        val fRef = params.table("readiness.f_ref")
        val readiness = TreeMap(numbers(p["readiness"]))
        if (doses.isNotEmpty()) for (g in Groups.CANONICAL) {
            val f = (1.0 - (readiness[g] ?: 1.0)) * fRef.getValue(g) + Readiness.fatigue(g, doses, start, null, params)
            readiness[g] = Grid.ratio((1.0 - f / fRef.getValue(g)).coerceIn(0.0, 1.0))
        }
        val capacity = p["capacity"]?.jsonObject?.let { c ->
            TimeModel.addOnCapacity(d(c.getValue("booked_min")), d(c.getValue("challenge_block_min")), d(c.getValue("buffer_min")), params).capacityMin
        }
        val unavailable = strings(p["unavailable"]).toSet()
        val addOn = p["add_on"]?.jsonObject?.let { a ->
            AddOnSlot(
                str(a, "family"), strings(a["allowed_groups"]).toSet(), strings(a["forbid_groups"]).toSet(),
                a["max_sets"]?.let { d(it) }, a["min_rir"]?.jsonPrimitive?.content?.toInt(),
            )
        }
        val opp = numbers(p["opp"]).mapValues { it.value.toInt() }
        val input = AllocInput(
            type = SessionType.of(str(p, "type")), start = start,
            budgetMin = capacity?.toDouble() ?: d(p.getValue("budget_min")), vBefore = vBefore,
            opp = Groups.CANONICAL.associateWith { opp[it] ?: 1 }, readiness = readiness, targets = targets,
            available = Groups.CANONICAL.associateWith { it !in unavailable }, forbiddenGroups = strings(p["forbidden_groups"]).toSet(),
            forbiddenSessionTags = strings(p["forbidden_session_tags"]).toSet(),
            tagRules = p["tag_rules"]?.jsonObject?.let { SessionTagRule.parse(it) }.orEmpty(), addOn = addOn,
            doseId = p["dose_id"]?.jsonPrimitive?.content ?: "alloc",
        )
        return Allocator.allocate(input, params) to capacity
    }

    private fun allocationJson(a: Allocation) = buildJsonObject {
        put("label", a.label)
        put("feasible", a.feasible)
        a.infeasibleReason?.let { put("infeasible_reason", it) }
        put("alloc", groups(a.alloc))
        put("normal", groups(a.normal))
        put("direct", groups(a.direct))
        put("credit", groups(a.credit))
        put("rir_target", groups(a.rirTarget))
        put("est_min", s(a.estMinutes))
        put("normal_min", s(a.normalMinutes))
        put("predicted_tags", buildJsonArray { a.predictedTags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        put("dropped", buildJsonArray { a.dropped.forEach { x -> add(buildJsonObject { put("group", x.group); put("reason", x.reason) }) } })
        put("vhyp", groups(a.vhyp))
        a.dose?.let { dose ->
            put("dose", buildJsonObject {
                put("t", dose.t.toString()); put("fat", groups(dose.fat)); put("tissue", groups(dose.tissue)); put("sys", ratio(dose.sys))
            })
        }
    }

    private fun strings(e: JsonElement?): List<String> = e?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun s(x: Double) = JsonNumbers.grid(x, GridKind.S)
}
