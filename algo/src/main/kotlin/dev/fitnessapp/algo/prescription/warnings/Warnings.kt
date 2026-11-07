package dev.fitnessapp.algo.prescription.warnings

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.TreeMap

enum class Severity(val code: String, val rank: Int) {
    INFO("info", 1), WARN("warn", 2), STRONG("strong", 3), SAFETY("safety", 4);

    companion object {
        fun of(code: String): Severity = entries.first { it.code == code }
    }
}

/**
 * One WarningEngine violation (B15.6): the review keys `{rule_id, severity, entity, magnitude, evidence_grade,
 * enforced}` plus the numbers the copy states, the suggested alternative (a `field:value` code or null) and citations.
 */
data class Warning(
    val ruleId: String,
    val severity: Severity,
    val entity: String,
    val magnitude: Double,
    val evidenceGrade: String,
    val enforced: Boolean,
    val numbers: TreeMap<String, Double> = TreeMap(),
    val suggestion: String? = null,
    val citations: List<String> = emptyList(),
)

/**
 * A resolved scheme as WE-1 checks it (B15.2 WE-S1 and WE-P rules): the effective values with [qStarAsked] the RIR
 * target before the safety clamp, [restS] the planned rest, [capAtLoad] the fresh capacity at the prescribed load,
 * [repsPerStep] the coarse-step figure, [rangeExhausted] the DOUBLE extension used up, [bwOverExposures] consecutive
 * bodyweight exposures with `cap - q* > rx.bw_r_hi`.
 */
data class SchemeFacts(
    val rLo: Int,
    val rHi: Int,
    val qStar: Double,
    val qStarAsked: Double = qStar,
    val setsPref: Int? = null,
    val restS: Double? = null,
    val strength: Boolean = false,
    val compound: Boolean = true,
    val highRisk: Boolean = false,
    val capAtLoad: Double? = null,
    val repsPerStep: Double? = null,
    val rangeExhausted: Boolean = false,
    val bwOverExposures: Int = 0,
)

/**
 * The challenge hard constraints of one added exercise (WE-S4, C11, C12, C50): [restDayBefore] the session's day is
 * blocked for every gym session (the day before B or TEST); [hardWindows] the ids of the hard protected windows the
 * session is in with what they forbid; [gapDay] a challenge gap day with its forbidden session tags; [sessionTags]
 * the tags the session carries with the exercise; [pullFamily] the exercise credits a pull-family group;
 * [lightPull] it passes the C50 light-pull filter.
 */
data class ConstraintFacts(
    val restDayBefore: Boolean = false,
    val hardWindows: List<WindowHit> = emptyList(),
    val gapDay: Boolean = false,
    val gapForbiddenTags: List<String> = emptyList(),
    val sessionTags: Set<String> = emptySet(),
    val groups: Map<String, Double> = emptyMap(),
    val regions: List<String> = emptyList(),
    val pullFamily: Boolean = false,
    val lightPull: Boolean = true,
)

/** A hard protected window the session falls in: what it forbids (`all_gym` scope forbids everything). */
data class WindowHit(val id: String, val groups: List<String>, val regions: List<String>, val sessionTags: List<String>, val allGym: Boolean)

/**
 * WE-1, the one WarningEngine (ALGORITHMS.md B15, C14, D10): evaluates evidence and safety rules into ranked,
 * de-duplicated violations. Only WE-S1 to WE-S5 are SAFETY; for engine outputs they are `enforced` (the prescription
 * was clamped), for user actions they are notices that never block logging (a logged RIR 0 raises none, D96). Display is UI-10's (`ui.warndisplay`).
 * Rules that read weekly targets, the time model or the week plan (WE-V2, V4-V7, WE-P8, WE-L1) arrive with M4.
 */
object Warnings : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("warnings")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("warnings.l1_max_consecutive", "days", 3.0, 7.0),
        ParamSpec.integer("warnings.p1_strong_cap", "reps", 30.0, 60.0),
        ParamSpec.integer("warnings.p1_warn_cap", "reps", 20.0, 40.0),
        ParamSpec.integer("warnings.p2_strong_rir", "RIR", 4.0, 6.0),
        ParamSpec.integer("warnings.p2_warn_rir", "RIR", 3.0, 5.0),
        ParamSpec.integer("warnings.p4_reps", "reps", 8.0, 20.0),
        ParamSpec.integer("warnings.p5_strong_rlo", "reps", 8.0, 15.0),
        ParamSpec.integer("warnings.p5_warn_rlo", "reps", 4.0, 8.0),
        ParamSpec.integer("warnings.p6_compound_min_s", "s", 30.0, 90.0),
        ParamSpec.integer("warnings.p6_strength_min_s", "s", 60.0, 180.0),
        ParamSpec.integer("warnings.p7_min_width", "reps", 1.0, 4.0),
        ParamSpec.number("warnings.p8_strong_ratio", "ratio", 0.5, 0.85),
        ParamSpec.number("warnings.p8_time_tol", "ratio", 0.05, 0.3),
        ParamSpec.number("warnings.p8_warn_ratio", "ratio", 0.7, 0.95),
        ParamSpec.number("warnings.strength_tau_sets", "sets", 0.5, 4.0),
        ParamSpec.integer("warnings.v1_info_lt", "sets/week", 10.0, 10.0),
        ParamSpec.integer("warnings.v1_strong_lt", "sets/week", 3.0, 3.0),
        ParamSpec.integer("warnings.v1_warn_lt", "sets/week", 6.0, 6.0),
        ParamSpec.integer("warnings.v2_intensity_days", "days", 7.0, 28.0),
        ParamSpec.number("warnings.v2_intensity_p", "ratio", 0.7, 0.95),
        ParamSpec.integer("warnings.v2_intensity_rir", "RIR", 1.0, 5.0),
        ParamSpec.integer("warnings.v2_no_sets_days", "days", 7.0, 21.0),
        ParamSpec.integer("warnings.v3_info_gt", "sets/session", 11.0, 11.0),
        ParamSpec.integer("warnings.v3_warn_gt", "sets/session", 15.0, 15.0),
        ParamSpec.integer("warnings.v5_min_freq", "exposures/week", 1.0, 3.0),
        ParamSpec.integer("warnings.v5_min_target", "sets/week", 6.0, 20.0),
        ParamSpec.table("warnings.f_int", "dimensionless", 0.0, 1.0),
        ParamSpec.table("warnings.f_load", "dimensionless", 0.0, 60.0),
        ParamSpec.table("warnings.compare_hyp_ranges", "reps", 1.0, 40.0),
        ParamSpec("warnings.compare_q", ParamType.NUMBER_ARRAY, "RIR", 0.0, 5.0),
        ParamSpec("warnings.compare_strength_reps", ParamType.NUMBER_ARRAY, "reps", 1.0, 8.0),
    )

    // ------------------------------------------------------------------------------------------------ schemes

    /** WE-S1 (the clamp) and the scheme rules WE-P1 to P7, P9, P11 for one preference or prescription (B15.2). */
    fun scheme(entity: String, f: SchemeFacts, params: ParamView): List<Warning> {
        val out = ArrayList<Warning>()
        if (f.highRisk && f.qStarAsked < params.double("rx.min_q_high_risk")) {
            out += Warning("WE-S1", Severity.SAFETY, entity, 1.0, "expert-opinion", true, nums("q_star_asked" to f.qStarAsked, "q_star" to f.qStar),
                "q_star:" + num(params.double("rx.min_q_high_risk")), listOf("D10"))
        }
        val x = maxOf(f.capAtLoad ?: 0.0, f.rHi.toDouble())
        if (x > params.int("warnings.p1_warn_cap")) {
            val strong = x > params.int("warnings.p1_strong_cap")
            out += Warning("WE-P1", if (strong) Severity.STRONG else Severity.WARN, entity, mag((x - 30.0) / 30.0), "moderate", false,
                nums("reps" to x), "heavier_load", listOf("Lopez 2021", "Schoenfeld 2021"))
        }
        if (!f.strength && f.qStar >= params.int("warnings.p2_warn_rir")) {
            val strong = f.qStar >= params.int("warnings.p2_strong_rir")
            out += Warning("WE-P2", if (strong) Severity.STRONG else Severity.WARN, entity, mag((f.qStar - 3.0) / 2.0), "moderate", false,
                nums("q_star" to f.qStar), "q_star:2", listOf("Robinson 2024", "Refalo 2023"))
        }
        if (f.setsPref == 1) out += Warning("WE-P3", Severity.INFO, entity, 0.3, "moderate", false, nums("sets" to 1.0), "sets:2", listOf("ACSM 2026"))
        if (f.rHi > params.int("warnings.p4_reps")) {
            out += Warning("WE-P4", Severity.INFO, entity, 0.2, "moderate", false, nums("reps" to f.rHi.toDouble()), null, listOf("Halperin 2022"))
        }
        if (f.strength && f.rLo > params.int("warnings.p5_warn_rlo")) {
            val strong = f.rLo > params.int("warnings.p5_strong_rlo")
            out += Warning("WE-P5", if (strong) Severity.STRONG else Severity.WARN, entity, mag((f.rLo - 6.0) / 6.0), "moderate", false,
                nums("rep_min" to f.rLo.toDouble()), "rep_scheme:fixed_3_5", listOf("Schoenfeld 2017", "Lopez 2021"))
        }
        f.restS?.let { rest ->
            val bound = when {
                f.strength -> params.int("warnings.p6_strength_min_s").toDouble()
                f.compound -> params.int("warnings.p6_compound_min_s").toDouble()
                else -> null
            }
            if (bound != null && rest < bound) {
                out += Warning("WE-P6", Severity.WARN, entity, mag((bound - rest) / bound), if (f.strength) "moderate" else "weak", false,
                    nums("rest_s" to rest, "bound_s" to bound), "rest_s:150", listOf(if (f.strength) "Grgic 2018" else "ACSM 2026"))
            }
        }
        val narrow = f.rHi - f.rLo < params.int("warnings.p7_min_width") && (f.repsPerStep ?: 0.0) > 1.5
        if (narrow || f.rangeExhausted) {
            out += Warning("WE-P7", Severity.INFO, entity, 0.3, "engineering-fact", false,
                nums("width" to (f.rHi - f.rLo).toDouble(), "reps_per_step" to (f.repsPerStep ?: 0.0)), "widen_range", emptyList())
        }
        if (!f.highRisk && f.qStar == 0.0) out += Warning("WE-P9", Severity.INFO, entity, 0.2, "moderate", false, nums("q_star" to 0.0), "q_star:1", listOf("Refalo 2023"))
        if (f.bwOverExposures >= 2) {
            out += Warning("WE-P11", Severity.INFO, entity, 0.3, "expert-opinion", false, nums("exposures" to f.bwOverExposures.toDouble()), "harder_variant", emptyList())
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ in session

    /**
     * WE-S2: a load above the generic load-jump cap [wCap]. A manual load on a general item is a SAFETY notice; a RULE
     * load is WARN and not enforced (D13, C45); HOLD-1 items are never evaluated (C70).
     */
    fun loadAboveCap(entity: String, w: Double, wCap: Double, ruleItem: Boolean, hold: Boolean): Warning? {
        if (hold || w <= wCap + EPS) return null
        val m = Grid.ratio(minOf(1.0, (w - wCap) / wCap))
        return if (ruleItem) {
            Warning("WE-S2", Severity.WARN, entity, m, "expert-opinion", false, nums("w" to w, "w_cap" to wCap), null, listOf("D13", "C45"))
        } else {
            Warning("WE-S2", Severity.SAFETY, entity, m, "expert-opinion", false, nums("w" to w, "w_cap" to wCap), "w:" + num(wCap), listOf("C15"))
        }
    }

    /** WE-S2 enforced: an engine prescription clamped by the cap table (not the DOUBLE one-step advance, C45). */
    fun capApplied(entity: String, type: String, limitW: Double, unclampedW: Double): Warning? {
        if (type !in ENFORCED_CAPS || unclampedW <= limitW + EPS) return null
        return Warning("WE-S2", Severity.SAFETY, entity, Grid.ratio(minOf(1.0, (unclampedW - limitW) / limitW)), "expert-opinion", true,
            nums("w" to unclampedW, "w_cap" to limitW), null, listOf("C15"))
    }

    /**
     * WE-P12 (D80): a bodyweight lift that can carry the dip belt, where a working set showed more capacity at `q*`
     * ([capOverQ] = reps + reported RIR - q*) than the DOUBLE ladder's top (`r_hi + rx.double_max_extension`): only
     * added load progresses it, so the note suggests the belt (never automatic, D10).
     */
    fun bodyweightAddLoad(entity: String, capOverQ: Double?, rHi: Int, beltAllowed: Boolean, params: ParamView): Warning? {
        val top = rHi + params.int("rx.double_max_extension")
        if (!beltAllowed || capOverQ == null || capOverQ < top) return null
        return Warning("WE-P12", Severity.INFO, entity, 0.3, "expert-opinion", false, nums("cap_over_q" to capOverQ, "top" to top.toDouble()), "add_load", listOf("D80"))
    }

    /** WE-S5: on a `high_risk` exercise the next set at the current load is predicted below `rx.min_q_high_risk`. */
    fun predictedFailure(entity: String, highRisk: Boolean, rirNext: Double?, params: ParamView): Warning? {
        val min = params.double("rx.min_q_high_risk")
        if (!highRisk || rirNext == null || rirNext >= min) return null
        return Warning("WE-S5", Severity.SAFETY, entity, mag((min - rirNext) / min), "expert-opinion", false, nums("rir_next" to rirNext), "easier_load", listOf("D10"))
    }

    /** WE-S4 (C11, C12, C50): an added exercise against the active challenge's hard constraints. */
    fun constraint(entity: String, f: ConstraintFacts): List<Warning> {
        val out = ArrayList<Warning>()
        if (f.restDayBefore) {
            out += Warning("WE-S4", Severity.SAFETY, entity, 1.0, "per challenge definition", false, TreeMap(), "rule_override:rest_day_before", listOf("C11", "D5"))
        }
        for (w in f.hardWindows.sortedBy { it.id }) {
            val hit = w.allGym || w.groups.any { (f.groups[it] ?: 0.0) >= HIT_COEFF } || w.regions.any { it in f.regions } ||
                w.sessionTags.any { it in f.sessionTags }
            if (hit) out += Warning("WE-S4", Severity.SAFETY, entity, 1.0, "per challenge definition", false, TreeMap(), "rule_override:${w.id}", listOf("C12"))
        }
        if (f.gapDay) {
            val tags = f.gapForbiddenTags.filter { it in f.sessionTags }
            if (tags.isNotEmpty()) {
                out += Warning("WE-S4", Severity.SAFETY, entity, 1.0, "per challenge definition", false, TreeMap(),
                    "rule_override:gap_day:" + tags.sorted().joinToString(","), listOf("C11"))
            } else if (f.pullFamily && !f.lightPull) {
                out += Warning("WE-S4", Severity.WARN, entity, 0.5, "per challenge definition", false, TreeMap(), "gap_day_light_pull", listOf("C50"))
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ history

    /** WE-S3 advice: two pain events in one body region within 14 days. */
    fun painRepeat(region: String, events14d: Int): Warning? =
        if (events14d < 2) null else Warning("WE-S3", Severity.SAFETY, "region:$region", 1.0, "expert-opinion", false, nums("events" to events14d.toDouble()), "substitute", listOf("C33"))

    /** WE-P10: at least three `grip_failure` reasons in 28 days. */
    fun gripLimited(entity: String, events28d: Int): Warning? =
        if (events28d < 3) null else Warning("WE-P10", Severity.INFO, entity, 0.3, "expert-opinion", false, nums("events" to events28d.toDouble()), "grip_work_or_straps")

    /** WE-T1: a STALL-1 tier action that would change a user-owned field (D10: suggested, not applied). */
    fun stallAction(entity: String, suggestion: String): Warning =
        Warning("WE-T1", Severity.INFO, entity, 0.5, "expert-opinion", false, TreeMap(), suggestion, listOf("B14"))

    /** WE-T2: `feeling_bad + too_tired >= 3` in 14 days, or the global deload trigger. */
    fun recoveryCheck(badEvents14d: Int, globalTriggers: List<String>, params: ParamView): Warning? {
        if (badEvents14d < params.int("stall.global_bad_events") && globalTriggers.isEmpty()) return null
        return Warning("WE-T2", Severity.INFO, "user", 0.5, "expert-opinion", false, nums("events" to badEvents14d.toDouble()), "checklist",
            globalTriggers.sorted())
    }

    // ------------------------------------------------------------------------------------------------ volume

    /**
     * WE-V1 for an `improve` group: [x] projected weekly fractional sets over [exposures] exposures. Not raised while the
     * week's DeloadPolicy is active ([deload], D61): the halved targets are the plan, not a deficit.
     */
    fun improveWeeklyLow(group: String, x: Double, exposures: Int, deload: Boolean, params: ParamView): Warning? {
        if (deload) return null
        val severity = when {
            x < params.int("warnings.v1_strong_lt") -> Severity.STRONG
            x < params.int("warnings.v1_warn_lt") -> Severity.WARN
            x < params.int("warnings.v1_info_lt") -> Severity.INFO
            else -> return null
        }
        val xNew = x + maxOf(1, exposures)
        val gain = if (x > 0.0) Det.sqrt(xNew / x) - 1.0 else 1.0
        return Warning("WE-V1", severity, "group:$group", mag((10.0 - x) / 10.0), "moderate", false,
            nums("x" to x, "x_new" to xNew, "gain" to Grid.ratio(gain)), "sets_per_exposure:+1", listOf("Pelland 2024", "ACSM 2026"))
    }

    /** WE-V3: per session and group above about 11 fractional sets ("preliminary evidence", C29). */
    fun sessionVolumeHigh(group: String, x: Double, params: ParamView): Warning? {
        val severity = when {
            x > params.int("warnings.v3_warn_gt") -> Severity.WARN
            x > params.int("warnings.v3_info_gt") -> Severity.INFO
            else -> return null
        }
        return Warning("WE-V3", severity, "group:$group", mag((x - 11.0) / 11.0), "weak", false, nums("x" to x), "split_sessions",
            listOf("Remmert (preprint)"))
    }

    // ------------------------------------------------------------------------------------------------ ranking

    /** B15.6: severity, then magnitude descending, then rule id, then entity; one row per `(rule_id, entity)`. */
    fun rank(warnings: List<Warning>): List<Warning> = warnings
        .sortedWith(compareByDescending<Warning> { it.severity.rank }.thenByDescending { it.magnitude }.thenBy { it.ruleId }.thenBy { it.entity })
        .distinctBy { it.ruleId to it.entity }

    /** `warnings.evaluation` outputs (B15.6, D5, C58). */
    fun json(warnings: List<Warning>): JsonObject = buildJsonObject {
        put("warnings", buildJsonArray {
            rank(warnings).forEach { w ->
                add(buildJsonObject {
                    put("rule_id", w.ruleId)
                    put("severity", w.severity.code)
                    put("entity", w.entity)
                    put("magnitude", JsonNumbers.grid(w.magnitude, GridKind.RATIO))
                    put("evidence_grade", w.evidenceGrade)
                    put("enforced", w.enforced)
                    put("numbers", JsonObject(w.numbers.mapValues { (_, v) -> JsonNumbers.grid(Grid.stat(v), GridKind.STAT) }))
                    put("suggestion", w.suggestion?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("citations", JsonArray(w.citations.map { JsonPrimitive(it) }))
                })
            }
        })
    }

    private fun nums(vararg p: Pair<String, Double>) = TreeMap<String, Double>().apply { p.forEach { put(it.first, it.second) } }
    private fun mag(x: Double) = Grid.ratio(x.coerceIn(0.0, 1.0))
    private fun num(x: Double): String = Grid.decimal(x, GridKind.REP).stripTrailingZeros().toPlainString()

    private val ENFORCED_CAPS = setOf("up", "fast")

    /** A group coefficient that counts as training the group in a protected window (a hard set's credit, C44). */
    private const val HIT_COEFF = 0.5
    private const val EPS = 0.005
}
