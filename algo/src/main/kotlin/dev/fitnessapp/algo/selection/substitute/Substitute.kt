package dev.fitnessapp.algo.selection.substitute

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.injury.Injury
import dev.fitnessapp.algo.selection.order.Order
import dev.fitnessapp.algo.selection.order.OrderInput
import dev.fitnessapp.algo.selection.pref.Pref
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.pref.PrefValue

/**
 * One exercise of the session in its current order, as SUB-1's reorder reads it. [exercise] and [sets] feed ORD-1;
 * [pinnedRank] is the challenge block's position (99 for general work).
 */
data class SubSessionItem(
    val id: String,
    val slug: String,
    val notDone: Boolean,
    val pinned: Boolean,
    val stations: List<String>,
    val exercise: CatalogExercise? = null,
    val sets: Int = 3,
    val pinnedRank: Int = 99,
)

/** A ranked substitute with the terms of its score (15.1), for the Swap sheet, the Why sheet and the log. */
data class SubCandidate(
    val slug: String,
    val score: Double,
    val similarity: Double,
    val pattern: Double,
    val pref: Double,
    val setup: Double,
    val inj: Double,
    val tier: PrefTier,
)

/**
 * The inputs of one ranking. [pool] is ELIG-1's pool under the session's constraints; [inSession] the slugs already in
 * the session; [occupiedStations] the stations marked occupied in this session (empty for the exercise detail's
 * Alternatives, which rank without an occupied context); [busyStations] the stations busy at this weekday and hour
 * (REASON-1's `occupied` rule), whose candidates rank after the others (D62).
 */
data class SubInput(
    val from: CatalogExercise,
    val pool: List<CatalogExercise>,
    val inSession: Set<String> = emptySet(),
    val occupiedStations: Set<String> = emptySet(),
    val prefs: Map<String, PrefValue> = emptyMap(),
    val jointFlags: Map<String, Int> = emptyMap(),
    val busyStations: Set<String> = emptySet(),
)

/**
 * `substitute@1.1.0` SUB-1 (ALGORITHMS_PLANNING 15, D49, D62): when an exercise is occupied, first "Do {k} now, then
 * come back" with k the first remaining exercise in ORD-1 order that is not pinned and on another station; then up to
 * three substitutes by `W_SIM·sim + W_PATTERN·pm + W_PREF·pn + W_SETUP·sp − W_INJ·inj`, ties by slug, those on a busy
 * station after the others. Disliked candidates appear only when no other candidate reaches `SIM_MIN`; `never` is
 * never offered. The start load of the chosen one is RX-1's (its own history, else the transfer prior, else the
 * calibration probe).
 */
object Substitute : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("substitute")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("substitute.max_defers", "deferrals", 1.0, 4.0),
        ParamSpec.number("substitute.region_weight", "vector weight", 0.0, 1.0),
        ParamSpec.table("substitute.setup_fast_s", "s (by setup speed class)", 0.0, 600.0),
        ParamSpec.number("substitute.sim_min", "cosine", 0.4, 0.9),
        ParamSpec.number("substitute.w_inj", "score weight", 0.0, 1.0),
        ParamSpec.number("substitute.w_pattern", "score weight", 0.0, 1.0),
        ParamSpec.number("substitute.w_pref", "score weight", 0.0, 1.0),
        ParamSpec.number("substitute.w_setup", "score weight", 0.0, 1.0),
        ParamSpec.number("substitute.w_sim", "score weight", 0.0, 1.0),
    )

    /** The Swap sheet's three candidates (15.1). */
    fun rank(input: SubInput, catalog: Catalog, params: ParamView, limit: Int = 3): List<SubCandidate> {
        val all = scored(input, catalog, params)
        val liked = all.filter { it.tier != PrefTier.DISLIKE }
        val shown = if (liked.isNotEmpty()) liked else all
        return shown.take(limit)
    }

    /**
     * The exercise detail's Alternatives (UX 13.3): the same ranking without an occupied context, up to [limit],
     * disliked ones after the others so they still show with their label.
     */
    fun alternatives(input: SubInput, catalog: Catalog, params: ParamView, limit: Int = 5): List<SubCandidate> {
        val all = scored(input.copy(occupiedStations = emptySet()), catalog, params)
        return (all.filter { it.tier != PrefTier.DISLIKE } + all.filter { it.tier == PrefTier.DISLIKE }).take(limit)
    }

    /** Every candidate at or above `SIM_MIN`, sorted by (score desc, slug asc). */
    fun scored(input: SubInput, catalog: Catalog, params: ParamView): List<SubCandidate> {
        val e = input.from
        val simMin = params.double("substitute.sim_min")
        val wSim = params.double("substitute.w_sim")
        val wPattern = params.double("substitute.w_pattern")
        val wPref = params.double("substitute.w_pref")
        val wSetup = params.double("substitute.w_setup")
        val wInj = params.double("substitute.w_inj")
        val fast = params.table("substitute.setup_fast_s")
        val lo = Det.ln(Pref.mult(PrefTier.DISLIKE, params))
        val hi = Det.ln(Pref.mult(PrefTier.FAVORITE, params))
        val out = ArrayList<SubCandidate>()
        for (c in input.pool) {
            if (c.slug == e.slug || c.slug in input.inSession) continue
            if (c.requiresStation.any { it in input.occupiedStations }) continue
            val pv = input.prefs[c.slug]
            val tier = pv?.tier ?: PrefTier.NEUTRAL
            if (tier == PrefTier.NEVER) continue
            val sim = catalog.similarity(e, c)
            if (sim < simMin) continue
            val pm = when {
                c.pattern == e.pattern -> 1.0
                c.family == e.family -> 0.5
                else -> 0.0
            }
            val m = pv?.m ?: Pref.mult(PrefTier.NEUTRAL, params)
            val pn = if (m <= 0.0) 0.0 else Grid.ratio(((Det.ln(m) - lo) / (hi - lo)).coerceIn(0.0, 1.0))
            val sp = when {
                c.setupS <= fast.getValue("fast") -> 1.0
                c.setupS <= fast.getValue("medium") -> 0.5
                else -> 0.0
            }
            val inj = Injury.subTerm(c, input.jointFlags)
            val score = Grid.ratio(wSim * sim + wPattern * pm + wPref * pn + wSetup * sp - wInj * inj)
            out += SubCandidate(c.slug, score, sim, pm, pn, sp, inj, tier)
        }
        val busy = input.pool.filter { c -> c.requiresStation.any { it in input.busyStations } }.map { it.slug }.toSet()
        return out.sortedWith(compareBy<SubCandidate> { it.slug in busy }.thenByDescending { it.score }.thenBy { it.slug })
    }

    /**
     * "Do {k} now, then come back to {e}" (15.1, D62): the remaining exercises other than [fromId] in ORD-1 order
     * ([improve] the improve groups, [stations] the station keys), and k the first that is not pinned and on another
     * station; null when there is none. Items without a catalog row keep their session position after the ordered ones.
     */
    fun doNow(
        order: List<SubSessionItem>,
        fromId: String,
        improve: Set<String>,
        stations: Set<String>?,
        catalog: Catalog,
        params: ParamView,
    ): SubSessionItem? {
        val from = order.firstOrNull { it.id == fromId } ?: return null
        val rest = order.filter { it.notDone && it.id != fromId }
        val known = rest.filter { it.exercise != null }
        val queue = known.groupBy { it.slug }.mapValues { ArrayDeque(it.value) }
        val ordered = Order.order(known.map { OrderInput(it.exercise!!, it.sets, it.pinnedRank) }, improve, stations, catalog, params)
            .map { queue.getValue(it.slug).removeFirst() } + rest.filter { it.exercise == null }
        return ordered.firstOrNull { k -> !k.pinned && (from.stations.isEmpty() || k.stations.none { it in from.stations }) }
    }
}
