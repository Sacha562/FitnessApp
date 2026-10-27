package dev.fitnessapp.algo.prescription.rx

import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.estimation.stall.Stall
import dev.fitnessapp.algo.estimation.stall.TierAction
import java.util.TreeMap

enum class SchemeKind(val code: String) { RANGE("range"), FIXED("fixed") }

/** One `rep_preferences` row (DATA_MODEL 9.4); [scope] is `exercise`, `muscle_group`, `mechanics`, `goal` or `global`. */
data class RepPref(
    val scope: String,
    val setsPref: Int? = null,
    val repMin: Int? = null,
    val repMax: Int? = null,
    val rirTarget: Double? = null,
    val repScheme: String? = null,
    val restPrefS: Double? = null,
)

/** A challenge item's own scheme: RULE items and `delegate:rx` items (OI-A20, D49) take their sets, reps and RIR. */
data class ItemScheme(val repMin: Int, val repMax: Int, val rirTarget: Double?, val sets: Int?)

/** What the scheme depends on besides the preferences (B9.3). */
data class SchemeContext(
    val ruleItem: Boolean = false,
    val isolation: Boolean = false,
    val maintainGoal: Boolean = false,
    val bodyweightOnly: Boolean = false,
    val highRisk: Boolean = false,
    val item: ItemScheme? = null,
    val painFollowUp: Boolean = false,
    val reentryRirAdd: Double? = null,
    val deload: Boolean = false,
    val tier: TierAction = TierAction.NONE,
    /** `evidence_mode = auto_adjust_with_warning` (E35): tier actions may change user-owned fields. */
    val autoAdjust: Boolean = false,
)

/**
 * The resolved scheme with the source of every field (`user:<scope>`, `challenge`, `engine_default`), the goal kind,
 * the safety clamps that were enforced and the evidence actions held back as suggestions (D10, C57).
 */
data class Scheme(
    val kind: SchemeKind,
    val rLo: Int,
    val rHi: Int,
    val qStar: Double,
    val setsPref: Int?,
    val restPrefS: Double?,
    val strength: Boolean,
    val sources: TreeMap<String, String>,
    val clamps: List<String>,
    val suggestions: List<String>,
) {
    val rStar: Int get() = rHi
}

/** Scheme resolution of RX-1 (ALGORITHMS.md B9.3). */
object Schemes {
    private val SCOPE_ORDER = listOf("exercise", "muscle_group", "mechanics", "goal", "global")

    fun resolve(prefs: List<RepPref>, ctx: SchemeContext, params: ParamView): Scheme {
        val ordered = prefs.sortedBy { SCOPE_ORDER.indexOf(it.scope).let { i -> if (i < 0) SCOPE_ORDER.size else i } }
        val sources = TreeMap<String, String>()
        fun <T> pick(field: String, item: T?, get: (RepPref) -> T?): T? {
            if (item != null) {
                sources[field] = "challenge"
                return item
            }
            for (p in ordered) get(p)?.let { sources[field] = "user:${p.scope}"; return it }
            return null
        }
        val exercisePref = ordered.firstOrNull { it.scope == "exercise" }
        val strength = ctx.ruleItem ||
            (exercisePref?.repScheme == "fixed" && exercisePref.repMax != null && exercisePref.repMax <= 6)

        var rLo = pick("rep_min", ctx.item?.repMin) { it.repMin }
        var rHi = pick("rep_max", ctx.item?.repMax) { it.repMax }
        val schemeText = pick("rep_scheme", ctx.item?.let { if (it.repMin == it.repMax) "fixed" else "range" }) { it.repScheme }
        var q = pick("rir_target", ctx.item?.rirTarget) { it.rirTarget }
        val sets = pick("sets_pref", ctx.item?.sets) { it.setsPref }
        val rest = pick("rest_pref_s", null) { it.restPrefS }

        if (rLo == null || rHi == null) {
            if (strength) {
                val r = params.int("rx.default_strength_r_star")
                if (rLo == null) { rLo = r; sources["rep_min"] = "engine_default" }
                if (rHi == null) { rHi = r; sources["rep_max"] = "engine_default" }
            } else {
                if (rLo == null) { rLo = params.int("rx.default_hyp_r_lo"); sources["rep_min"] = "engine_default" }
                if (rHi == null) { rHi = params.int("rx.default_hyp_r_hi"); sources["rep_max"] = "engine_default" }
            }
        }
        if (q == null) {
            q = when {
                ctx.maintainGoal -> params.double("rx.default_q_maintain")
                strength -> params.double("rx.default_q_strength")
                ctx.isolation -> params.double("rx.default_q_hyp_isolation")
                else -> params.double("rx.default_q_hyp_compound")
            }
            sources["rir_target"] = "engine_default"
        }
        if (ctx.bodyweightOnly && rHi!! > params.int("rx.bw_r_hi")) {
            rHi = params.int("rx.bw_r_hi")
            if (rLo!! > rHi) rLo = rHi
        }
        var kind = if (schemeText == "fixed" || rLo == rHi) SchemeKind.FIXED else SchemeKind.RANGE
        if (kind == SchemeKind.FIXED && schemeText == "fixed" && rLo != rHi) rLo = rHi

        val clamps = ArrayList<String>()
        val suggestions = ArrayList<String>()
        // SAFETY (B15.3, D10): no failure on high-risk lifts, whatever the source.
        if (ctx.highRisk && q!! < params.double("rx.min_q_high_risk")) {
            q = params.double("rx.min_q_high_risk")
            clamps += "rx.safety.min_q_high_risk"
        }
        // Transforms in order (B9.3): pain follow-up, re-entry (combined with a deload in B9.9, C51), stall tier.
        if (ctx.painFollowUp) q = maxOf(q!!, params.double("reason.pain_min_q"))
        if (ctx.reentryRirAdd != null && !ctx.deload) q = q!! + ctx.reentryRirAdd
        val userQ = sources["rir_target"]?.startsWith("user:") == true
        val userReps = sources["rep_min"]?.startsWith("user:") == true || sources["rep_max"]?.startsWith("user:") == true
        when (ctx.tier) {
            TierAction.TIER1 -> if (userQ && !ctx.autoAdjust) suggestions += "stall.tier1.q_add" else q = q!! + params.double("stall.tier1_q_add")
            TierAction.TIER2 -> if (userReps && !ctx.autoAdjust) suggestions += "stall.tier2.rep_shift" else {
                val (lo, hi) = Stall.tier2Range(rLo!!, rHi!!, params)
                rLo = lo
                rHi = hi
                if (rLo == rHi) kind = SchemeKind.FIXED
            }
            TierAction.NONE -> Unit
        }
        return Scheme(kind, rLo!!, rHi!!, q!!, sets, rest, strength, sources, clamps, suggestions)
    }
}
