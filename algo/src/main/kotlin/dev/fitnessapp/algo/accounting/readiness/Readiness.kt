package dev.fitnessapp.algo.accounting.readiness

import dev.fitnessapp.algo.accounting.setcredit.SetCreditOut
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.time.Instant
import java.util.TreeMap

/** One readiness dose (4.2): a logged set at its time, a planned session at its end, or a run at its end. [id] breaks ties of equal [t]. */
data class Dose(val id: String, val t: Instant, val fat: Map<String, Double>, val tissue: Map<String, Double> = emptyMap(), val sys: Double = 0.0) {
    companion object {
        fun of(credit: SetCreditOut, t: Instant): Dose = Dose(credit.setId, t, credit.fat, credit.tissue, credit.sys)
    }
}

/** The latest pre-session answers (F15). [soreness] is group -> `never_sore` | `healed` | `still_sore`; [readiness] `worse` | `normal` | `better`. */
data class CheckIn(
    val ts: Instant,
    val readiness: String? = null,
    val soreness: Map<String, String> = emptyMap(),
    val elbowScore: Int = 0,
    val sleepHours: Double? = null,
)

/** Readiness(t) of 4.3; `tissueR` also carries the composite `elbow`. */
data class ReadinessOut(
    val t: Instant,
    val r: Map<String, Double>,
    val f: Map<String, Double>,
    val tissueR: Map<String, Double>,
    val g: Double,
    val s: Double,
    val modifiersApplied: List<String>,
) {
    fun label(group: String): String = label(r.getValue(group))

    companion object {
        /** `label(R)` (4.3): fresh at 0.8 and above, recovering from 0.5, fatigued below. */
        fun label(r: Double): String = when {
            r >= 0.8 -> "fresh"
            r >= 0.5 -> "recovering"
            else -> "fatigued"
        }
    }
}

/**
 * `readiness@1.0.0` MuscleReadiness.v1 (ALGORITHMS_PLANNING 4, C5): the only fatigue model of the app. Fatigue per
 * group decays with the group's time constant (times the user's `kappa` of its class); tissues and the systemic
 * channel decay with their own. Elapsed time is always computed from Instants, so a DST night is 25 h (C31). Doses
 * are summed in time order (then id), groups in canonical order, so the output does not depend on input order.
 */
object Readiness : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("readiness")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("readiness.checkin_valid_h", "h", 4.0, 24.0),
        ParamSpec.number("readiness.elbow_check_factor", "per elbow score point", 0.0, 0.25),
        ParamSpec.table("readiness.f_ref", "fatigue units (by group)", 4.0, 12.0),
        ParamSpec.table("readiness.kappa_default", "multiplier of tau (by class)", 0.7, 1.5),
        ParamSpec.number("readiness.r_exclude", "R", 0.3, 0.7),
        ParamSpec.number("readiness.r_full", "R", 0.6, 0.95),
        ParamSpec.number("readiness.r_target_improve", "R", 0.7, 0.95),
        ParamSpec.number("readiness.r_target_maintain", "R", 0.6, 0.9),
        ParamSpec.number("readiness.readiness_worse_g", "multiplier of G", 0.7, 1.0),
        ParamSpec.number("readiness.sleep_short_g", "multiplier of G", 0.7, 1.0),
        ParamSpec.integer("readiness.sleep_short_h", "h", 4.0, 7.0),
        ParamSpec.number("readiness.soreness_factor", "per soreness point", 0.0, 0.25),
        ParamSpec.table("readiness.soreness_points", "points (by soreness answer)", 0.0, 5.0),
        ParamSpec.number("readiness.sys_scale", "per systemic unit", 0.2, 1.0),
        ParamSpec.integer("readiness.sys_tau_h", "h", 24.0, 96.0),
        ParamSpec.table("readiness.tau_h", "h (by group)", 12.0, 60.0),
        ParamSpec.integer("readiness.tissue_ref", "fatigue units", 3.0, 12.0),
        ParamSpec.integer("readiness.tissue_tau_h", "h", 24.0, 96.0),
        ParamSpec.integer("readiness.window_h", "h", 168.0, 336.0),
    )

    /** The kappa class of a group: `t` + its time constant in hours (t22, t26, t30, t34). */
    fun classOf(group: String, params: ParamView): String = "t" + params.table("readiness.tau_h").getValue(group).toInt()

    private fun tau(group: String, kappa: Map<String, Double>?, params: ParamView): Double {
        val base = params.table("readiness.tau_h").getValue(group)
        val cls = "t" + base.toInt()
        return base * (kappa?.get(cls) ?: params.table("readiness.kappa_default").getValue(cls))
    }

    private fun window(doses: List<Dose>, t: Instant, params: ParamView): List<Pair<Double, Dose>> {
        val w = params.double("readiness.window_h")
        return doses.sortedWith(compareBy({ it.t }, { it.id }))
            .map { Week.elapsedHours(it.t, t) to it }
            .filter { (h, _) -> h >= 0.0 && h <= w }
    }

    /** Fatigue of [group] at [t] without check-in modifiers. */
    fun fatigue(group: String, doses: List<Dose>, t: Instant, kappa: Map<String, Double>?, params: ParamView): Double {
        val tau = tau(group, kappa, params)
        return window(doses, t, params).sumOf { (h, d) -> (d.fat[group] ?: 0.0) * Det.exp(-h / tau) }
    }

    /** Readiness(t) with the check-in modifiers (4.4); [kappa] is the user's approved per-class multipliers, null for the defaults. */
    fun evaluate(doses: List<Dose>, t: Instant, kappa: Map<String, Double>?, checkIn: CheckIn?, params: ParamView): ReadinessOut {
        val fRef = params.table("readiness.f_ref")
        val win = window(doses, t, params)
        val r = TreeMap<String, Double>()
        val f = TreeMap<String, Double>()
        for (g in Groups.CANONICAL) {
            val tau = tau(g, kappa, params)
            val fg = win.sumOf { (h, d) -> (d.fat[g] ?: 0.0) * Det.exp(-h / tau) }
            f[g] = fg
            r[g] = (1.0 - fg / fRef.getValue(g)).coerceIn(0.0, 1.0)
        }
        val tTau = params.double("readiness.tissue_tau_h")
        val tRef = params.double("readiness.tissue_ref")
        val rt = TreeMap<String, Double>()
        for (k in Groups.TISSUES) {
            val ft = win.sumOf { (h, d) -> (d.tissue[k] ?: 0.0) * Det.exp(-h / tTau) }
            rt[k] = (1.0 - ft / tRef).coerceIn(0.0, 1.0)
        }
        val sTau = params.double("readiness.sys_tau_h")
        val s = win.sumOf { (h, d) -> d.sys * Det.exp(-h / sTau) }
        var g = (1.0 - params.double("readiness.sys_scale") * s).coerceIn(0.0, 1.0)

        val mods = ArrayList<String>()
        val valid = checkIn?.takeIf { c -> Week.elapsedHours(c.ts, t).let { it >= 0.0 && it <= params.double("readiness.checkin_valid_h") } }
        if (valid != null) {
            val factor = params.double("readiness.soreness_factor")
            val points = params.table("readiness.soreness_points")
            for (grp in Groups.CANONICAL) {
                val ans = valid.soreness[grp] ?: continue
                r[grp] = r.getValue(grp) * (1.0 - factor * requireNotNull(points[ans]) { "soreness answer $ans" })
                mods += "soreness:$grp"
            }
            if (valid.readiness == "worse") {
                g *= params.double("readiness.readiness_worse_g")
                mods += "readiness_worse"
            }
            if (valid.sleepHours != null && valid.sleepHours < params.double("readiness.sleep_short_h")) {
                g *= params.double("readiness.sleep_short_g")
                mods += "sleep_short"
            }
            if (valid.elbowScore > 0) {
                val m = 1.0 - params.double("readiness.elbow_check_factor") * valid.elbowScore
                rt["elbow_medial"] = rt.getValue("elbow_medial") * m
                rt["elbow_lateral"] = rt.getValue("elbow_lateral") * m
                mods += "elbow_check"
            }
        }
        rt["elbow"] = minOf(rt.getValue("elbow_medial"), rt.getValue("elbow_lateral"))
        fun grid(m: Map<String, Double>) = m.mapValuesTo(TreeMap()) { Grid.ratio(it.value) }
        return ReadinessOut(t, grid(r), grid(f), grid(rt), Grid.ratio(g), Grid.ratio(s), mods)
    }

    /** `nextReady(g, rTarget)` (4.4): the earliest instant R[g] reaches [rTarget] with no new dose (exact: one time constant per group). */
    fun nextReady(group: String, rTarget: Double, doses: List<Dose>, now: Instant, kappa: Map<String, Double>?, params: ParamView): Instant {
        val f = fatigue(group, doses, now, kappa, params)
        val thr = params.table("readiness.f_ref").getValue(group) * (1.0 - rTarget)
        if (f <= thr) return now
        val hours = tau(group, kappa, params) * Det.ln(f / thr)
        return now.plusMillis(kotlin.math.ceil(hours * 3_600_000.0).toLong())
    }
}
