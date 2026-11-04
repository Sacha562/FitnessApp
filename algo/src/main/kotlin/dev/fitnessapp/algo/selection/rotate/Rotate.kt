package dev.fitnessapp.algo.selection.rotate

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.pref.PrefValue
import dev.fitnessapp.algo.selection.select.Select
import dev.fitnessapp.algo.selection.select.SelectInput
import dev.fitnessapp.algo.selection.substitute.SubInput
import dev.fitnessapp.algo.selection.substitute.Substitute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.TreeMap
import java.util.TreeSet
import kotlin.math.ceil

/** The challenge's block as the mesocycle clock reads it (C31): the block, the week inside it and the block's length. */
data class ChallengeBlock(val blockIndex: Int, val blockWeek: Int, val blockWeeks: Int)

/**
 * Where today falls (16.1, D86 (1)): mesocycle [n] started on [start] (a Monday) and lasts [weeks]; today is in [week].
 * [isNew] when no stored mesocycle covers [start] yet, so the start has to run. [source] is `challenge_block` or
 * `calendar`.
 */
data class MesocycleClock(
    val n: Int,
    val start: LocalDate,
    val week: Int,
    val weeks: Int,
    val source: String,
    val blockIndex: Int?,
    val isNew: Boolean,
)

/**
 * One exposure of an exercise: a session with working sets of it. [bestE1rmKg] is the session's best engine-logged
 * e1RM in system kg (C3; null when no set gave one, such as holds); [repsAtLoad] the best reps at each effective load
 * (kg on the 0.01 grid) of that session.
 */
data class Exposure(val date: LocalDate, val sessionId: String, val bestE1rmKg: Double?, val repsAtLoad: List<LoadReps>)

data class LoadReps(val loadKg: Double, val reps: Int)

/** An exercise's exposures up to the mesocycle start and SSE-1's logged slope `g_per_week` there (null without one). */
data class ExerciseHistory(val slug: String, val exposures: List<Exposure>, val gPerWeek: Double?)

/** A main lift: its family, why it is one (`kept`, `sel1`, `user`) and its count of consecutive stalled mesocycles. */
data class Anchor(val family: String, val slug: String, val reason: String, val stalledMesocycles: Int)

/** A previous anchor that is not kept, with its reason (`stalled_mesocycles`, `never`, `not_eligible`). */
data class DroppedAnchor(val family: String, val slug: String, val reason: String)

/** An accessory rotated out, its replacement (null when SUB-1 has none) and the reason (`stalled`, `disliked`, both). */
data class RotatedPair(val family: String, val from: String, val to: String?, val reason: String, val keep: Double)

/**
 * A mesocycle's selection state (DATA_MODEL 10.2 `rotate.mesocycle`): the anchors per family in family order, the keep
 * scores of the previous mesocycle's accessories, the kept accessories, the rotated pairs and the dropped anchors.
 * [rev] counts the user's anchor changes (0 at the start).
 */
data class Mesocycle(
    val n: Int,
    val start: LocalDate,
    val weeks: Int,
    val source: String,
    val blockIndex: Int?,
    val rev: Int,
    val anchors: List<Anchor>,
    val keepScores: Map<String, Double>,
    val kept: List<String>,
    val rotated: List<RotatedPair>,
    val dropped: List<DroppedAnchor>,
) {
    val rotatedOut: Set<String> get() = rotated.map { it.from }.toSortedSet()
}

/**
 * The inputs of a mesocycle start (16.1). [familyRuns] holds SEL-1's family-alone input per family (D86 (2): a
 * standalone session of the family's type with nothing done this week, `opp` 2 and full readiness; the caller leaves
 * out a family the challenge owns); [pool] is ELIG-1's pool on the first day; [used] the exercises logged as working
 * sets in general blocks of the previous mesocycle; [history] the exposures of every exercise up to the start, with
 * [previousFrom] the first day of the previous mesocycle (exposures from it on belong to it).
 */
data class RotateInput(
    val clock: MesocycleClock,
    val previous: Mesocycle?,
    val familyRuns: Map<String, SelectInput>,
    val pool: List<CatalogExercise>,
    val prefs: Map<String, PrefValue>,
    val jointFlags: Map<String, Int> = emptyMap(),
    val used: Set<String>,
    val history: Map<String, ExerciseHistory>,
    val previousFrom: LocalDate,
)

/** What one build takes from the mesocycle (D86 (3)): S0 anchors, the K set and the exercises out of automatic builds. */
data class MesocycleSelection(val anchors: List<String>, val consistency: Set<String>, val rotatedOut: Set<String>)

/**
 * ROT-1 (ALGORITHMS_PLANNING 16, F12, C31, D86): main lifts locked for a mesocycle that follows the challenge block,
 * and accessories that rotate only for a reason (stalled, disliked), at most a third of a family's accessories per
 * mesocycle, each replaced by SUB-1's best candidate with a bonus for a region the family did not train. Pure and
 * deterministic: every list is sorted by slug or family order, ties break by slug.
 */
object Rotation : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("rotate")

    /** The families of 16.1 in their order (PULL is empty while the challenge owns its groups). */
    val FAMILIES: List<String> = listOf("LEGS", "PUSH", "PULL", "CORE")

    const val SOURCE_CHALLENGE = "challenge_block"
    const val SOURCE_CALENDAR = "calendar"

    /** A region counts as trained at this stimulus (D86 (2)). */
    private const val REGION_MIN = 0.5

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("rotate.anchors_per_family", "exercises", 1.0, 3.0),
        ParamSpec.integer("rotate.mesocycle_weeks", "weeks", 4.0, 8.0),
        ParamSpec.number("rotate.progress_slope", "ln per week", 0.0, 0.02),
        ParamSpec.number("rotate.region_gain", "score", 0.0, 1.0),
        ParamSpec.number("rotate.rotate_fraction", "share of accessories", 0.0, 0.5),
        ParamSpec.integer("rotate.stall_exposures", "exposures", 2.0, 6.0),
    )

    /**
     * The mesocycle of [today] (16.1, D86 (1)): with a challenge, the Monday of the ISO week holding block week 1;
     * otherwise `mesocycle_weeks` after the previous start (this week's Monday for the first one).
     */
    fun clock(today: LocalDate, challenge: ChallengeBlock?, previous: Mesocycle?, params: ParamView): MesocycleClock {
        val monday = Week.isoWeekStart(today)
        if (challenge != null) {
            val start = monday.minusWeeks((challenge.blockWeek - 1).coerceAtLeast(0).toLong())
            val same = previous != null && previous.start == start
            val n = if (same) previous!!.n else (previous?.n ?: 0) + 1
            return MesocycleClock(n, start, challenge.blockWeek, challenge.blockWeeks, SOURCE_CHALLENGE, challenge.blockIndex, isNew = !same)
        }
        val weeks = params.int("rotate.mesocycle_weeks")
        if (previous == null || previous.start.isAfter(monday)) {
            return MesocycleClock((previous?.n ?: 0) + 1, monday, 1, weeks, SOURCE_CALENDAR, null, isNew = true)
        }
        val elapsed = (Week.calendarDays(previous.start, monday) / 7).toInt()
        // A challenge mesocycle that ended (the challenge stopped) runs on in calendar weeks from its own start.
        val length = previous.weeks.coerceAtLeast(1)
        if (elapsed < length) return MesocycleClock(previous.n, previous.start, elapsed + 1, length, previous.source, previous.blockIndex, isNew = false)
        val k = (elapsed - length) / weeks
        val start = previous.start.plusWeeks(length.toLong() + k.toLong() * weeks)
        return MesocycleClock(previous.n + 1, start, (Week.calendarDays(start, monday) / 7).toInt() + 1, weeks, SOURCE_CALENDAR, null, isNew = true)
    }

    /** The start of mesocycle [RotateInput.clock] (16.1 with the readings of D86 (2)). */
    fun start(input: RotateInput, catalog: Catalog, params: ParamView): Mesocycle {
        val perFamily = params.int("rotate.anchors_per_family")
        val stallN = params.int("rotate.stall_exposures")
        val poolBySlug = input.pool.associateBy { it.slug }
        fun familyOf(slug: String): String? = (poolBySlug[slug] ?: catalog.exercise(slug))?.let { Elig.primaryGroup(it, catalog) }?.let { Groups.familyOf(it) }
        fun never(slug: String) = input.prefs[slug]?.tier == PrefTier.NEVER
        fun stalled(slug: String) = input.history[slug]?.let { stalled(it.exposures, stallN) } ?: false

        // Anchors: the previous ones unless stalled in 2 consecutive mesocycles, `never` or out of the pool.
        val anchors = ArrayList<Anchor>()
        val dropped = ArrayList<DroppedAnchor>()
        for (family in FAMILIES) {
            val kept = ArrayList<Anchor>()
            for (a in input.previous?.anchors.orEmpty().filter { it.family == family }) {
                val count = if (stalled(a.slug)) a.stalledMesocycles + 1 else 0
                when {
                    never(a.slug) -> dropped += DroppedAnchor(family, a.slug, "never")
                    a.slug !in poolBySlug -> dropped += DroppedAnchor(family, a.slug, "not_eligible")
                    count >= 2 -> dropped += DroppedAnchor(family, a.slug, "stalled_mesocycles")
                    else -> kept += Anchor(family, a.slug, "kept", count)
                }
            }
            val run = input.familyRuns[family]
            if (kept.size < perFamily && run != null) {
                val keptSlugs = kept.map { it.slug }
                val out = dropped.map { it.slug }.toSet()
                val r = Select.select(run.copy(pool = run.pool.filter { it.slug !in out }, anchors = keptSlugs, history = emptySet()), catalog, params)
                r.exercises
                    .filter { x -> x.slug !in keptSlugs && poolBySlug[x.slug]?.mechanics == "compound" && familyOf(x.slug) == family }
                    .sortedWith(compareByDescending<dev.fitnessapp.algo.selection.select.SelectedExercise> { it.b }.thenBy { it.slug })
                    .take(perFamily - kept.size)
                    .forEach { kept += Anchor(family, it.slug, "sel1", 0) }
            }
            anchors += kept
        }
        val anchorSlugs = anchors.map { it.slug }.toSet()
        val previousAnchors = input.previous?.anchors.orEmpty().map { it.slug }.toSet()

        // Accessories of the previous mesocycle, their keep scores and the rotation per family.
        val accessories = input.used.filter { it !in anchorSlugs && it !in previousAnchors }.sorted()
        val keep = TreeMap<String, Double>()
        for (slug in accessories) keep[slug] = keepScore(slug, input, params)
        val rotated = ArrayList<RotatedPair>()
        val taken = TreeSet<String>().apply { addAll(anchorSlugs) }
        val fraction = params.double("rotate.rotate_fraction")
        val byFamily = accessories.groupBy { familyOf(it) }
        for (family in FAMILIES) {
            val acc = byFamily[family].orEmpty()
            if (acc.isEmpty()) continue
            val negative = acc.filter { keep.getValue(it) < 0.0 }
            val n = minOf(negative.size, ceil(acc.size * fraction - 1e-9).toInt())
            val out = negative.sortedWith(compareBy<String> { keep.getValue(it) }.thenBy { it }).take(n)
            taken += acc.filter { it !in out }
            val trained = (acc + input.previous?.anchors.orEmpty().filter { it.family == family }.map { it.slug })
                .mapNotNull { poolBySlug[it] ?: catalog.exercise(it) }
                .flatMap { e -> e.stimulus.filterValues { it >= REGION_MIN }.keys }.toSortedSet()
            for (slug in out) {
                val to = replacement(slug, family, input, catalog, params, taken + out, trained, ::familyOf)
                to?.let { taken += it }
                rotated += RotatedPair(family, slug, to, reason(slug, input, params), keep.getValue(slug))
            }
        }
        val rotatedOut = rotated.map { it.from }.toSet()
        return Mesocycle(
            input.clock.n, input.clock.start, input.clock.weeks, input.clock.source, input.clock.blockIndex, 0,
            anchors, keep, accessories.filter { it !in rotatedOut }, rotated, dropped,
        )
    }

    /** The user's change of a main lift (16.2 "user overrides of anchors"): [to] takes [from]'s place, next revision. */
    fun override(m: Mesocycle, family: String, from: String, to: String): Mesocycle {
        require(m.anchors.any { it.family == family && it.slug == from }) { "$from is not a $family anchor" }
        val anchors = m.anchors.filterNot { it.family == family && it.slug == to }
            .map { if (it.family == family && it.slug == from) Anchor(family, to, "user", 0) else it }
        return m.copy(rev = m.rev + 1, anchors = anchors)
    }

    /**
     * What a build takes from [m] (D86 (3)): the anchors in S0 for a standalone session (none for an add-on), the K set
     * (anchors, kept accessories, replacements and [usedSinceStart]) and the rotated-out exercises.
     */
    fun selection(m: Mesocycle?, usedSinceStart: Set<String>, addOn: Boolean): MesocycleSelection {
        if (m == null) return MesocycleSelection(emptyList(), usedSinceStart.toSortedSet(), emptySet())
        val out = m.rotatedOut
        val anchors = m.anchors.map { it.slug }
        val k = TreeSet<String>().apply {
            addAll(anchors); addAll(m.kept); addAll(m.rotated.mapNotNull { it.to }); addAll(usedSinceStart)
            removeAll(out)
        }
        return MesocycleSelection(if (addOn) emptyList() else anchors.sorted(), k, out)
    }

    /**
     * ROT-1's stall reading (D86 (2)): the last [n] exposures gave neither an e1RM best (above every earlier session
     * best) nor a rep best (more reps than ever before at a load used before). Needs more than [n] exposures.
     */
    fun stalled(exposures: List<Exposure>, n: Int): Boolean {
        val sorted = exposures.sortedWith(compareBy<Exposure> { it.date }.thenBy { it.sessionId })
        if (sorted.size <= n) return false
        var bestE1rm: Double? = null
        val bestReps = TreeMap<Double, Int>()
        val pr = sorted.map { x ->
            val e1rmBest = x.bestE1rmKg?.let { v -> bestE1rm == null || v > bestE1rm!! } ?: false
            val repBest = x.repsAtLoad.any { lr -> bestReps[Grid.kg(lr.loadKg)]?.let { lr.reps > it } ?: false }
            x.bestE1rmKg?.let { v -> if (bestE1rm == null || v > bestE1rm!!) bestE1rm = v }
            x.repsAtLoad.forEach { lr -> val k = Grid.kg(lr.loadKg); if ((bestReps[k] ?: -1) < lr.reps) bestReps[k] = lr.reps }
            e1rmBest || repBest
        }
        return pr.takeLast(n).none { it }
    }

    /** `keep(e)` of 16.1 without the "bored" term (D86 (2)). */
    fun keepScore(slug: String, input: RotateInput, params: ParamView): Double {
        val h = input.history[slug]
        var k = 0.0
        val slope = params.double("rotate.progress_slope")
        if ((h?.gPerWeek ?: Double.NEGATIVE_INFINITY) >= slope || repsRising(h, input.previousFrom)) k += 1.0
        if (h != null && stalled(h.exposures, params.int("rotate.stall_exposures"))) k -= 1.0
        when (input.prefs[slug]?.tier) {
            PrefTier.NEVER, PrefTier.DISLIKE -> k -= 1.0
            PrefTier.LIKE, PrefTier.FAVORITE -> k += 0.5
            else -> {}
        }
        return Grid.ratio(k)
    }

    /** The last exposure of the previous mesocycle has more reps than its first at a load both used. */
    private fun repsRising(h: ExerciseHistory?, from: LocalDate): Boolean {
        val xs = h?.exposures.orEmpty().filter { !it.date.isBefore(from) }.sortedWith(compareBy<Exposure> { it.date }.thenBy { it.sessionId })
        if (xs.size < 2) return false
        val first = xs.first().repsAtLoad.associate { Grid.kg(it.loadKg) to it.reps }
        return xs.last().repsAtLoad.any { lr -> first[Grid.kg(lr.loadKg)]?.let { lr.reps > it } ?: false }
    }

    private fun reason(slug: String, input: RotateInput, params: ParamView): String {
        val stalled = input.history[slug]?.let { stalled(it.exposures, params.int("rotate.stall_exposures")) } ?: false
        val disliked = input.prefs[slug]?.tier.let { it == PrefTier.DISLIKE || it == PrefTier.NEVER }
        return when {
            stalled && disliked -> "stalled_disliked"
            stalled -> "stalled"
            disliked -> "disliked"
            else -> "low_keep"
        }
    }

    /** SUB-1's best candidate from [slug] in [family] plus `region_gain` for a region the family did not train. */
    private fun replacement(
        slug: String,
        family: String,
        input: RotateInput,
        catalog: Catalog,
        params: ParamView,
        taken: Set<String>,
        trained: Set<String>,
        familyOf: (String) -> String?,
    ): String? {
        val from = catalog.exercise(slug) ?: return null
        val candidates = input.pool.filter { it.slug !in taken && familyOf(it.slug) == family }
        val gain = params.double("rotate.region_gain")
        return Substitute.scored(SubInput(from, candidates, prefs = input.prefs, jointFlags = input.jointFlags), catalog, params)
            .filter { it.tier != PrefTier.DISLIKE }
            .map { c ->
                val e = catalog.exercise(c.slug)!!
                val newRegion = e.stimulus.any { (r, v) -> v >= REGION_MIN && r !in trained }
                c.slug to Grid.ratio(c.score + if (newRegion) gain else 0.0)
            }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .firstOrNull()?.first
    }

    /** The `rotate.mesocycle` outputs (DATA_MODEL 10.2 keys plus the state the next start reads back). */
    fun json(m: Mesocycle, ids: (String) -> String = { it }): JsonObject = buildJsonObject {
        put("mesocycle", JsonNumbers.int(m.n.toLong()))
        put("start", m.start.toString())
        put("weeks", JsonNumbers.int(m.weeks.toLong()))
        put("source", m.source)
        m.blockIndex?.let { put("block_index", JsonNumbers.int(it.toLong())) }
        put("rev", JsonNumbers.int(m.rev.toLong()))
        put("anchors", buildJsonArray {
            m.anchors.forEach { a ->
                add(buildJsonObject {
                    put("family", a.family); put("exercise_id", ids(a.slug)); put("reason", a.reason)
                    put("stalled_mesocycles", JsonNumbers.int(a.stalledMesocycles.toLong()))
                })
            }
        })
        put("keep_scores", buildJsonObject { m.keepScores.toSortedMap().forEach { (k, v) -> put(ids(k), JsonNumbers.grid(v, GridKind.RATIO)) } })
        put("kept", buildJsonArray { m.kept.forEach { add(JsonPrimitive(ids(it))) } })
        put("rotated", buildJsonArray {
            m.rotated.forEach { r ->
                add(buildJsonObject {
                    put("family", r.family); put("from_exercise_id", ids(r.from)); r.to?.let { put("to_exercise_id", ids(it)) }
                    put("reason", r.reason); put("keep", JsonNumbers.grid(r.keep, GridKind.RATIO))
                })
            }
        })
        put("dropped_anchors", buildJsonArray {
            m.dropped.forEach { d -> add(buildJsonObject { put("family", d.family); put("exercise_id", ids(d.slug)); put("reason", d.reason) }) }
        })
    }

    /** [json] read back; [slugs] maps a stored exercise id to its slug (null drops the entry). */
    fun parse(o: JsonObject, slugs: (String) -> String? = { it }): Mesocycle {
        fun s(x: JsonObject, k: String) = x.getValue(k).jsonPrimitive.content
        return Mesocycle(
            n = o.getValue("mesocycle").jsonPrimitive.int,
            start = LocalDate.parse(s(o, "start")),
            weeks = o.getValue("weeks").jsonPrimitive.int,
            source = s(o, "source"),
            blockIndex = o["block_index"]?.jsonPrimitive?.int,
            rev = o["rev"]?.jsonPrimitive?.int ?: 0,
            anchors = o.getValue("anchors").jsonArray.mapNotNull { e ->
                val a = e.jsonObject
                slugs(s(a, "exercise_id"))?.let { Anchor(s(a, "family"), it, s(a, "reason"), a["stalled_mesocycles"]?.jsonPrimitive?.int ?: 0) }
            },
            keepScores = TreeMap<String, Double>().apply {
                o["keep_scores"]?.jsonObject?.forEach { (k, v) -> slugs(k)?.let { put(it, v.jsonPrimitive.content.toDouble()) } }
            },
            kept = o["kept"]?.jsonArray?.mapNotNull { slugs(it.jsonPrimitive.content) }.orEmpty(),
            rotated = o.getValue("rotated").jsonArray.mapNotNull { e ->
                val r = e.jsonObject
                slugs(s(r, "from_exercise_id"))?.let {
                    RotatedPair(s(r, "family"), it, r["to_exercise_id"]?.jsonPrimitive?.content?.let(slugs), s(r, "reason"), r["keep"]?.jsonPrimitive?.content?.toDouble() ?: 0.0)
                }
            },
            dropped = o["dropped_anchors"]?.jsonArray?.mapNotNull { e ->
                val d = e.jsonObject
                slugs(s(d, "exercise_id"))?.let { DroppedAnchor(s(d, "family"), it, s(d, "reason")) }
            }.orEmpty(),
        )
    }
}
