package dev.fitnessapp.sim.sessions

import dev.fitnessapp.algo.accounting.readiness.Readiness
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.pipeline.BuildRequest
import dev.fitnessapp.algo.pipeline.Building
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.selection.catalog.Taxonomy
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.pref.Pref
import dev.fitnessapp.algo.selection.pref.PrefRow
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.time.WarmupKind
import dev.fitnessapp.algo.targets.GroupGoal
import dev.fitnessapp.algo.targets.Targets
import dev.fitnessapp.algo.targets.TargetsInput
import dev.fitnessapp.sim.challenge.SimResources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * `sim sessions <persona.json> [params file]`: the New session sheet's builds for a persona at the start of a week with
 * nothing done (full readiness, no committed volume), through the same Targets, ELIG-1, allocator and SEL-1 path the
 * phone runs (SessionBuild). A persona holds `training_age`, `goals` ({group: goal}), optional `focus`, `prefs`
 * ({slug: tier}), `stations` (available gym equipment), `budget_min`, `types` (session type codes), optional
 * `later_types` (the week plan's later sessions, for `opp_g`), `anchors` (mesocycle main lifts) and `date`.
 * Used for algorithm reviews (CLAUDE.md "Algorithm reviews"): before/after a params or code change.
 */
object SessionsCli {
    private val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

    fun run(args: List<String>): Int {
        val persona = Json.parseToJsonElement(File(args.getOrElse(0) { error("usage: sessions <persona.json> [params]") }).readText()).jsonObject
        val params = args.getOrNull(1)?.let { ParamView.fromParamsFile(File(it).readText()) }
            ?: ParamView.fromParamsFile(SimResources.text(LATEST_PARAMS))
        print(render(persona, params))
        return 0
    }

    const val LATEST_PARAMS: String = "params/default-params-1.0.7.json"

    fun render(persona: JsonObject, params: ParamView): String {
        val taxonomy = Taxonomy.parse(SimResources.text("reference/muscle_groups.json"), SimResources.text("reference/muscle_regions.json"))
        val catalog = Catalog(CatalogDoc.parse(SimResources.text(SimResources.CATALOG_FILE)), taxonomy, regionWeight = params.double("substitute.region_weight"))
        val equipment = Json.parseToJsonElement(SimResources.text("reference/equipment_types.json")).jsonObject.getValue("rows").jsonArray
        val stationKeys = equipment.map { it.jsonObject }.filter { it["category"]?.jsonPrimitive?.content == "station" }
            .map { it.getValue("key").jsonPrimitive.content }.toSortedSet()

        fun strings(key: String) = persona[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val date = LocalDate.parse(persona["date"]?.jsonPrimitive?.content ?: "2026-11-09")
        val at = Week.atLocal(date, LocalTime.of(18, 0), ZONE)
        val focus = strings("focus").toSet()
        val goals = persona.getValue("goals").jsonObject.mapValues { (g, v) -> GroupGoal(v.jsonPrimitive.content, g in focus) }
        val tiers = persona["prefs"]?.jsonObject?.mapValues { PrefTier.of(it.value.jsonPrimitive.content) }.orEmpty()
        val prefs = tiers.toSortedMap().mapValues { (_, t) -> Pref.evaluate(PrefRow(t), emptyList(), date, params) }
        val targets = Targets.compute(
            TargetsInput(
                isoWeek = Week.isoWeek(date), goals = goals, trainingAge = persona["training_age"]?.jsonPrimitive?.content, age = null,
                ownedGroups = emptyList(), contractActive = false, challengeDeloadWeek = false, committed = emptyMap(), done = emptyMap(),
                trainedDates = emptyMap(), today = date,
            ),
            params,
        )
        val readiness = Readiness.evaluate(emptyList(), at, null, null, params).r
        val statuses = targets.groups
        val elig = EligContext(
            availableStations = strings("stations").toSortedSet(), prefs = prefs,
            skipGroups = statuses.filterValues { it.status == "skip" }.keys,
            goalGroups = statuses.filterValues { (it.status == "improve" || it.status == "maintain") && !it.coveredByChallenge }.keys,
        )
        val later = strings("later_types").map { SessionType.of(it) }
        val anchors = strings("anchors").sorted()
        val budget = persona["budget_min"]?.jsonPrimitive?.content?.toDouble() ?: 60.0
        val out = StringBuilder()
        out.append("params ${params.hash.take(12)}  date $date  budget $budget min  anchors ${anchors.ifEmpty { listOf("none") }}\n")
        for (code in strings("types")) {
            val type = SessionType.of(code)
            val req = BuildRequest(
                type = type, start = at, budgetMin = budget, warmup = WarmupKind.GENERAL, targets = targets,
                vBefore = Groups.CANONICAL.associateWith { 0.0 }, opp = opp(type, later), readiness = readiness, elig = elig,
                prefM = prefs.mapValues { it.value.m }, stations = stationKeys, anchors = anchors, history = anchors.toSet(),
            )
            val r = Building.build(req, catalog, params)
            out.append("\n${type.code} - ${r.label.ifEmpty { "(empty)" }} ~${Math.round(r.estMin)} min${r.empty?.let { "  EMPTY $it" } ?: ""}\n")
            for (x in r.exercises) {
                val tier = tiers[x.slug]?.code ?: "-"
                val name = catalog.exercise(x.slug)?.nameEn ?: x.slug
                out.append(String.format("  %-34s %d sets  [%s]\n", name, x.sets, tier))
            }
        }
        return out.toString()
    }

    /** SessionBuild.opp: this session and the later ones whose type trains `g` (core groups ride on every type but CORE). */
    private fun opp(type: SessionType, later: List<SessionType>): Map<String, Int> {
        fun members(t: SessionType): Set<String> =
            (t.families.flatMap { Groups.FAMILIES.getValue(it) } + if (t != SessionType.CORE) Groups.FAMILIES.getValue("CORE") else emptyList()).toSet()
        val sessions = listOf(type) + later
        return Groups.CANONICAL.associateWith { g -> sessions.count { g in members(it) }.coerceAtLeast(1) }
    }
}
