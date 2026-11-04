package dev.fitnessapp.algo.golden

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.golden.PlanningGolden.d
import dev.fitnessapp.algo.golden.PlanningGolden.numbers
import dev.fitnessapp.algo.golden.PlanningGolden.str
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.elig.GateInput
import dev.fitnessapp.algo.selection.pref.Pref
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.pref.PrefValue
import dev.fitnessapp.algo.selection.rotate.ChallengeBlock
import dev.fitnessapp.algo.selection.rotate.ExerciseHistory
import dev.fitnessapp.algo.selection.rotate.Exposure
import dev.fitnessapp.algo.selection.rotate.LoadReps
import dev.fitnessapp.algo.selection.rotate.Mesocycle
import dev.fitnessapp.algo.selection.rotate.RotateInput
import dev.fitnessapp.algo.selection.rotate.Rotation
import dev.fitnessapp.algo.selection.select.SelectInput
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.TreeMap

/**
 * The ROT-1 golden (ALGORITHMS_PLANNING 16.3, D86 (7)): a chain of mesocycle starts on one catalog, each reading the
 * previous one's state. A payload carries the catalog rows, the preferences, SEL-1's family runs (explicit targets) and
 * the `starts`, each with its day, the challenge block, the exercises used in the previous mesocycle and the exposures.
 */
internal object RotateGolden {
    fun rotate(p: JsonObject, params: ParamView): JsonObject {
        val catalog = PlanningGolden.catalog(p)
        val tiers = p["prefs"]?.jsonObject?.mapValues { PrefTier.of(it.value.jsonPrimitive.content) }.orEmpty()
        val prefs = tiers.mapValues { (_, t) -> PrefValue(Pref.mult(t, params), t, hidden = false, z = 0.0, explicit = true, prompt = false) }
        val ctx = EligContext(prefs = prefs)
        val elig = Elig.pool(catalog, ctx, params)
        val stations = p["stations"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSortedSet()
        val runs = TreeMap<String, SelectInput>()
        p.getValue("family_runs").jsonObject.forEach { (family, r) ->
            val o = r.jsonObject
            val target = numbers(o["target"])
            val goals = Groups.CANONICAL.associateWith { g -> o.getValue("goals").jsonObject[g]?.jsonPrimitive?.content ?: "maintain" }
            val gate = Elig.gate(elig, catalog, ctx, GateInput(target, goals), params)
            runs[family] = SelectInput(
                pool = gate.pool, target = target, goals = goals, capS = emptyMap(), budgetMin = d(o.getValue("budget_min")),
                prefM = prefs.mapValues { it.value.m }, unlocked = gate.unlocked.associate { it.slug to it.group }, stations = stations,
            )
        }
        var previous: Mesocycle? = null
        val starts = buildJsonArray {
            p.getValue("starts").jsonArray.forEach { s ->
                val o = s.jsonObject
                val today = LocalDate.parse(str(o, "today"))
                val challenge = o["challenge"]?.jsonObject?.let { c ->
                    ChallengeBlock(c.getValue("block_index").jsonPrimitive.int, c.getValue("block_week").jsonPrimitive.int, c.getValue("block_weeks").jsonPrimitive.int)
                }
                val clock = Rotation.clock(today, challenge, previous, params)
                val history = o["history"]?.jsonObject?.entries?.associate { (slug, h) -> slug to history(slug, h.jsonObject) }.orEmpty()
                val m = Rotation.start(
                    RotateInput(
                        clock = clock, previous = previous, familyRuns = runs, pool = elig.pool, prefs = prefs,
                        used = o.getValue("used").jsonArray.map { it.jsonPrimitive.content }.toSet(), history = history,
                        previousFrom = previous?.start ?: clock.start.minusWeeks(params.int("rotate.mesocycle_weeks").toLong()),
                    ),
                    catalog, params,
                )
                add(buildJsonObject {
                    put("clock", buildJsonObject {
                        put("n", JsonNumbers.int(clock.n.toLong())); put("start", clock.start.toString())
                        put("week", JsonNumbers.int(clock.week.toLong())); put("is_new", clock.isNew)
                    })
                    put("mesocycle", Rotation.json(m))
                    put("selection", Rotation.selection(m, emptySet(), addOn = false).let { sel ->
                        buildJsonObject {
                            put("anchors", strings(sel.anchors)); put("consistency", strings(sel.consistency.toList()))
                            put("rotated_out", strings(sel.rotatedOut.toList()))
                        }
                    })
                })
                previous = m
            }
        }
        return buildJsonObject { put("starts", starts) }
    }

    private fun history(slug: String, o: JsonObject): ExerciseHistory = ExerciseHistory(
        slug,
        o.getValue("exposures").jsonArray.map { e ->
            val x = e.jsonObject
            Exposure(
                LocalDate.parse(str(x, "date")), str(x, "session_id"), x["e1rm_kg"]?.let { d(it) },
                x["reps_at_load"]?.jsonArray?.map { lr -> lr.jsonArray.let { LoadReps(d(it[0]), it[1].jsonPrimitive.int) } }.orEmpty(),
            )
        },
        o["g_per_week"]?.let { d(it) },
    )

    private fun strings(xs: List<String>): JsonElement = buildJsonArray { xs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
}
