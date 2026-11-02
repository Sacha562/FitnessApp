package dev.fitnessapp.algo.targets

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.core.trace.TraceStep
import java.time.LocalDate
import java.util.TreeMap

/** Ramp state of an improve group, carried from week close to week close (5.4.1). */
data class RampState(val t: Double, val tBase: Double, val tLastTolerated: Double)

data class TargetsInput(
    val isoWeek: String,
    val goals: Map<String, GroupGoal>,
    /** `profiles.training_age`; null (onboarding skipped) uses `targets.default_training_age` (C64). */
    val trainingAge: String?,
    val age: Int?,
    /** Groups owned by an active challenge (D6); empty for a paused or no enrollment. */
    val ownedGroups: List<String>,
    val contractActive: Boolean,
    /** The contract's `deloadWeek` (F8, C13). */
    val challengeDeloadWeek: Boolean,
    val committed: Map<String, Double>,
    /** Done Vhyp this ISO week, per group (the week account). */
    val done: Map<String, Double>,
    /** Local dates of the sessions that trained each group (Vhyp at least `setcredit.trained_min_credit`), any order. */
    val trainedDates: Map<String, List<LocalDate>>,
    val today: LocalDate,
    val ramp: Map<String, RampState> = emptyMap(),
    /** The previous week close flagged this week for a reactive deload ([Targets.closeWeek]). */
    val reactiveDeload: Boolean = false,
    val reactiveTriggers: List<String> = emptyList(),
    /** The user tapped "Keep normal week" on the reactive deload banner. */
    val keepNormalWeek: Boolean = false,
    val deloadSyncWithChallenge: Boolean = true,
    val mesocycleWeek: Int = 1,
    /** Maintain groups: the id of a qualifying hard set this week (C14 intensity check), computed by the caller. */
    val maintainIntensitySet: Map<String, String> = emptyMap(),
)

data class Transform(val setFactor: Double, val rirOffset: Int)

data class Reentry(val active: Boolean, val sessionsRemaining: Int)

data class GroupTargets(
    val goal: String,
    val focus: Boolean,
    val status: String,
    val t: Double,
    val targetEffective: Double,
    val mrv: Double,
    val capS: Double,
    val minTouch: Double,
    val freqTarget: Int,
    val w: Double,
    val coveredByChallenge: Boolean,
    val metByChallenge: Boolean,
    val conflictNote: String?,
    val reentry: Reentry,
    /** The combined deload and re-entry transform of this group (C51), null when neither applies. */
    val transform: Transform?,
    val intensityCheck: Boolean?,
    val intensitySetId: String?,
)

data class Deload(val active: Boolean, val source: String, val setFactor: Double, val rirOffset: Int, val triggers: List<String>)

data class WeeklyTargets(
    val isoWeek: String,
    val status: String,
    val base: Double,
    val trainingAge: String,
    val mesocycleWeek: Int,
    val groups: Map<String, GroupTargets>,
    val deload: Deload,
    val reentryTransform: Transform,
    val combineRule: String,
    val trace: List<TraceStep>,
)

/** What the week close reads per improve group: e1RM changes of valid exposures and this week's soreness answers (5.4.1). */
data class RampSignals(val e1rmDeltas: List<Double> = emptyList(), val soreness: List<String> = emptyList())

/** The reactive-deload signals of 5.4.2, evaluated at the week close for the next week. */
data class ReactiveSignals(
    /** Per mesocycle anchor (ROT-1), its e1RM changes per exposure, oldest first. */
    val anchorDeltas: List<List<Double>> = emptyList(),
    /** G at the gym session starts, oldest first. */
    val gAtStarts: List<Double> = emptyList(),
    /** Per gym session, oldest first: a `feeling_bad` or `too_tired` reason, or a `worse` readiness answer (C57). */
    val badSessions: List<Boolean> = emptyList(),
    val stallDeloadRecommended: Boolean = false,
)

data class WeekCloseInput(
    val closing: WeeklyTargets,
    /** The ramp state the closing week was computed with ([TargetsInput.ramp]). */
    val closingRamp: Map<String, RampState>,
    val signals: Map<String, RampSignals>,
    val reactive: ReactiveSignals,
    val nextMesocycleWeek: Int,
    /** Next week is a planned (challenge) deload week. */
    val nextIsChallengeDeload: Boolean,
)

data class RampTrace(val group: String, val p: Int?, val s: Int?, val delta: Int, val capHit: Boolean, val applied: Boolean)

data class WeekCloseOut(val ramp: Map<String, RampState>, val reactiveDeloadNext: Boolean, val triggers: List<String>, val rampTrace: List<RampTrace>)

/**
 * `targets@1.0.0` WeeklyTargets.v1 + DeloadPolicy + re-entry (ALGORITHMS_PLANNING 5, C13, C14, C27, C51, D6, F8):
 * the user's goals, training age, challenge contract and history become weekly volume targets per group, the
 * per-session caps, the planner's value weights and this week's session transforms. [utility] is the `U` that the
 * allocator, planner and recommender maximise. The week-close evaluations ([closeWeek]: the ramp and the reactive
 * deload triggers) run on the challenge's `week.closed` tick.
 */
object Targets : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("targets")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("targets.age_60", "years", 60.0, 60.0),
        ParamSpec.table("targets.base_by_age", "Vhyp sets/week (by training age)", 6.0, 20.0),
        ParamSpec.table("targets.cap_s", "FS per session (by goal)", 4.0, 15.0),
        ParamSpec("targets.combine_rule", ParamType.ENUM, "enum", enumValues = listOf("min_factor_max_offset")),
        ParamSpec("targets.default_training_age", ParamType.ENUM, "enum", enumValues = listOf("novice", "intermediate", "advanced")),
        ParamSpec.integer("targets.deload_rir_offset", "RIR", 1.0, 3.0),
        ParamSpec.number("targets.deload_set_factor", "ratio", 0.3, 0.7),
        ParamSpec.integer("targets.focus_bonus", "Vhyp sets/week", 0.0, 8.0),
        ParamSpec.table("targets.freq_target", "sessions/week (by goal)", 1.0, 3.0),
        ParamSpec.integer("targets.maintain_divisor", "dimensionless", 2.0, 9.0),
        ParamSpec.number("targets.maintain_load_frac", "ratio of 8-week working load", 0.7, 0.95),
        ParamSpec.integer("targets.maintain_min", "Vhyp sets/week", 1.0, 6.0),
        ParamSpec.integer("targets.maintain_min_60", "Vhyp sets/week", 3.0, 10.0),
        ParamSpec.number("targets.maintain_over_slope", "utility per set over T", 0.0, 0.2),
        ParamSpec.table("targets.min_touch", "FS per session (by goal)", 1.0, 4.0),
        ParamSpec.table("targets.mrv", "Vhyp sets/week (by group)", 10.0, 35.0),
        ParamSpec.number("targets.over_mrv_slope", "utility per set over MRV", 0.0, 2.0),
        ParamSpec.table("targets.perf_thresholds", "relative e1RM change", -0.1, 0.1),
        ParamSpec.number("targets.ramp_cap_frac", "ratio of T", 0.1, 0.3),
        ParamSpec.integer("targets.ramp_start_week", "mesocycle week", 1.0, 3.0),
        ParamSpec.table("targets.ramp_table", "sets/week delta (by rule)", -3.0, 3.0),
        ParamSpec.table("targets.reactive", "mixed (counts, ratio, G)", 0.0, 10.0),
        ParamSpec.integer("targets.reentry_gap_days", "days", 14.0, 35.0),
        ParamSpec.integer("targets.reentry_rir_offset", "RIR", 0.0, 2.0),
        ParamSpec.integer("targets.reentry_sessions", "sessions", 1.0, 4.0),
        ParamSpec.number("targets.reentry_set_factor", "ratio", 0.4, 0.8),
        ParamSpec.table("targets.soreness_scores", "score (by soreness answer)", 0.0, 5.0),
        ParamSpec.table("targets.special_improve", "Vhyp sets/week (by group)", 2.0, 12.0),
        ParamSpec.table("targets.w", "utility weight (by goal)", 0.0, 3.0),
    )

    /** The goal-derived `T` before any ramp (5.4), and the base the utility uses as `REF`. */
    fun baseTarget(g: String, goal: GroupGoal, base: Double, age: Int?, params: ParamView): Double = when (goal.goal) {
        "improve" -> minOf(
            (params.table("targets.special_improve")[g] ?: base) + (if (goal.focus) params.double("targets.focus_bonus") else 0.0),
            params.table("targets.mrv").getValue(g),
        )
        "maintain" -> if (age != null && age >= params.double("targets.age_60")) {
            maxOf(params.double("targets.maintain_min_60"), Groups.roundInt(base / 2.0).toDouble())
        } else {
            maxOf(params.double("targets.maintain_min"), Groups.roundInt(base / params.double("targets.maintain_divisor")).toDouble())
        }
        else -> 0.0
    }

    fun compute(input: TargetsInput, params: ParamView): WeeklyTargets {
        val trace = ArrayList<TraceStep>()
        val age = input.trainingAge ?: params.string("targets.default_training_age")
        val base = requireNotNull(params.table("targets.base_by_age")[age]) { "training age $age" }
        val deload = deloadPolicy(input, params)
        val reentryT = Transform(params.double("targets.reentry_set_factor"), params.int("targets.reentry_rir_offset"))
        val combine = params.string("targets.combine_rule")
        if (input.goals.isEmpty()) {
            return WeeklyTargets(input.isoWeek, "setup_needed", base, age, input.mesocycleWeek, emptyMap(), deload, reentryT, combine, listOf(TraceStep("targets.setup_needed")))
        }
        val mrvT = params.table("targets.mrv")
        val capS = params.table("targets.cap_s")
        val minTouch = params.table("targets.min_touch")
        val freq = params.table("targets.freq_target")
        val w = params.table("targets.w")
        val out = linkedMapOf<String, GroupTargets>()
        for (g in Groups.CANONICAL) {
            val goal = input.goals[g] ?: GroupGoal("skip")
            val mrv = mrvT.getValue(g)
            val reentry = reentry(input.trainedDates[g].orEmpty(), input.today, params)
            if (g in input.ownedGroups) {
                val conflict = if (goal.goal == "skip") "Your challenge trains this group while it is active" else null
                out[g] = GroupTargets(
                    goal.goal, goal.focus, "covered", 0.0, 0.0, mrv, 0.0, 0.0, 0, 0.0, coveredByChallenge = true, metByChallenge = false,
                    conflictNote = conflict, reentry = reentry, transform = null, intensityCheck = null, intensitySetId = null,
                )
                continue
            }
            val tBase = baseTarget(g, goal, base, input.age, params)
            var t = tBase
            if (goal.goal == "improve") {
                val st = input.ramp[g]
                if (st != null && st.tBase == tBase) t = st.t else if (st != null) trace += TraceStep("targets.ramp.reset", g)
            }
            val (gc, gw, gm, gf) = when (goal.goal) {
                "improve" -> Quad(capS.getValue("improve"), w.getValue(if (goal.focus) "focus" else "improve"), minTouch.getValue("improve"), freq.getValue("improve"))
                "maintain" -> Quad(
                    capS.getValue("maintain"), w.getValue("maintain"), minTouch.getValue("maintain"),
                    if (input.age != null && input.age >= params.double("targets.age_60")) freq.getValue("maintain_60") else freq.getValue("maintain"),
                )
                else -> Quad(0.0, 0.0, 0.0, 0.0)
            }
            val active = goal.goal != "skip"
            val met = active && (input.done[g] ?: 0.0) + (input.committed[g] ?: 0.0) >= t
            val eff = if (deload.active) Groups.roundHalf(t * deload.setFactor) else t
            val transform = when {
                !active -> null
                deload.active && reentry.active -> Transform(minOf(deload.setFactor, reentryT.setFactor), maxOf(deload.rirOffset, reentryT.rirOffset))
                deload.active -> Transform(deload.setFactor, deload.rirOffset)
                reentry.active -> reentryT
                else -> null
            }
            val maint = goal.goal == "maintain"
            out[g] = GroupTargets(
                goal.goal, goal.focus, goal.goal, t, eff, mrv, gc, gm, gf.toInt(), gw, coveredByChallenge = false, metByChallenge = met,
                conflictNote = null, reentry = if (active) reentry else Reentry(false, 0), transform = transform,
                intensityCheck = if (maint) input.maintainIntensitySet.containsKey(g) else null, intensitySetId = if (maint) input.maintainIntensitySet[g] else null,
            )
        }
        return WeeklyTargets(input.isoWeek, "ok", base, age, input.mesocycleWeek, out, deload, reentryT, combine, trace)
    }

    private data class Quad(val a: Double, val b: Double, val c: Double, val d: Double)

    /** DeloadPolicy (5.4.2): the challenge's deload week when synced (F8), else a reactive deload the user did not decline. */
    fun deloadPolicy(input: TargetsInput, params: ParamView): Deload {
        val f = params.double("targets.deload_set_factor")
        val o = params.int("targets.deload_rir_offset")
        return when {
            input.contractActive && input.deloadSyncWithChallenge && input.challengeDeloadWeek -> Deload(true, "challenge", f, o, emptyList())
            input.reactiveDeload && !input.keepNormalWeek -> Deload(true, "reactive", f, o, input.reactiveTriggers)
            else -> Deload(false, "none", 1.0, 0, emptyList())
        }
    }

    /**
     * Re-entry (5.4.3, C27): a group whose last training is more than `targets.reentry_gap_days` ago (or that the app
     * has never seen trained) gets the re-entry transform for its next `targets.reentry_sessions` sessions. The sessions
     * after the most recent gap (the start of the history counts as one) are counted from [trainedDates].
     */
    fun reentry(trainedDates: List<LocalDate>, today: LocalDate, params: ParamView): Reentry {
        val gap = params.double("targets.reentry_gap_days")
        val n = params.int("targets.reentry_sessions")
        val dates = trainedDates.filter { !it.isAfter(today) }.sorted()
        if (dates.isEmpty() || Week.calendarDays(dates.last(), today) > gap) return Reentry(true, n)
        var afterGap = 1
        for (i in dates.indices.drop(1)) afterGap = if (Week.calendarDays(dates[i - 1], dates[i]) > gap) 1 else afterGap + 1
        val remaining = maxOf(0, n - afterGap)
        return Reentry(remaining > 0, remaining)
    }

    /** `U_g(V)` of 5.4 with `REF` = [base]; 0 for skip and covered groups. */
    fun utility(gt: GroupTargets, v: Double, base: Double, params: ParamView): Double = Utility(params).of(gt, v, base)

    /** [utility] with its slopes read once, for the inner loops of the allocator and the week planner. */
    class Utility(params: ParamView) {
        private val overMrv = params.double("targets.over_mrv_slope")
        private val maintainOver = params.double("targets.maintain_over_slope")

        fun of(gt: GroupTargets, v: Double, base: Double): Double = when (gt.status) {
            "improve" -> Det.sqrt(minOf(v, gt.mrv) / base) - overMrv * maxOf(0.0, v - gt.mrv) / base
            "maintain" -> Det.sqrt(minOf(v, gt.t) / base) + maintainOver * maxOf(0.0, v - gt.t) / base
            else -> 0.0
        }
    }

    /** The perf score of one e1RM change (5.4.1): 1 up to 4 by `targets.perf_thresholds`. */
    fun perfScore(delta: Double, params: ParamView): Int {
        val th = params.table("targets.perf_thresholds")
        return when {
            delta >= th.getValue("p1") -> 1
            delta >= th.getValue("p2") -> 2
            delta >= th.getValue("p3") -> 3
            else -> 4
        }
    }

    /** The ramp delta of the RP table (5.4.1) before the cap; null scores are missing. */
    fun rampDelta(p: Int?, s: Int?, params: ParamView): Int {
        val t = params.table("targets.ramp_table")
        return when {
            p == 4 -> t.getValue("p4").toInt()
            s == 4 || p == 3 -> t.getValue("s4_or_p3").toInt()
            p == null || s == null -> t.getValue("missing").toInt()
            s == 1 && p == 1 -> t.getValue("s1_and_p1").toInt()
            p <= 2 && s <= 2 -> t.getValue("p_le2_and_s_le2").toInt()
            else -> t.getValue("missing").toInt()
        }
    }

    /** The week close (5.4.1 ramp and 5.4.2 reactive triggers) for the next week. */
    fun closeWeek(input: WeekCloseInput, params: ParamView): WeekCloseOut {
        val closing = input.closing
        val closingDeload = closing.deload.active
        val start = params.int("targets.ramp_start_week")
        val capFrac = params.double("targets.ramp_cap_frac")
        val sore = params.table("targets.soreness_scores")
        val ramp = TreeMap<String, RampState>()
        val traces = ArrayList<RampTrace>()
        for ((g, gt) in closing.groups) {
            if (gt.status != "improve") continue
            val tBase = baseTarget(g, GroupGoal(gt.goal, gt.focus), closing.base, null, params)
            val sig = input.signals[g] ?: RampSignals()
            val p = sig.e1rmDeltas.takeIf { it.isNotEmpty() }?.let { d -> Groups.roundInt(d.map { perfScore(it, params).toDouble() }.average()) }
            val s = sig.soreness.mapNotNull { sore[it] }.maxOrNull()?.toInt()
            // T_lastTolerated: T of the last non-deload week whose P was not 4.
            val prevTol = if (!closingDeload && p != 4) gt.t else input.closingRamp[g]?.takeIf { it.tBase == tBase }?.tLastTolerated ?: tBase
            if (input.nextMesocycleWeek == 1) {
                val tNext = Groups.roundInt(0.5 * (prevTol + tBase)).toDouble().coerceIn(tBase, gt.mrv)
                ramp[g] = RampState(tNext, tBase, prevTol)
                traces += RampTrace(g, p, s, 0, false, false)
                continue
            }
            val applies = input.nextMesocycleWeek >= start && !input.nextIsChallengeDeload && !closingDeload
            var delta = rampDelta(p, s, params)
            val cap = maxOf(1, kotlin.math.floor(capFrac * gt.t).toInt())
            val capHit = delta > cap
            if (capHit) delta = cap
            val tNext = if (applies) (gt.t + delta).coerceIn(tBase - 2.0, gt.mrv) else gt.t
            ramp[g] = RampState(tNext, tBase, prevTol)
            traces += RampTrace(g, p, s, delta, capHit, applies)
        }
        val triggers = if (input.nextIsChallengeDeload) emptyList() else reactiveTriggers(input.reactive, params)
        return WeekCloseOut(ramp, triggers.isNotEmpty(), triggers, traces)
    }

    /** The reactive triggers R1-R4 of 5.4.2 that fire. */
    fun reactiveTriggers(sig: ReactiveSignals, params: ParamView): List<String> {
        val r = params.table("targets.reactive")
        val out = ArrayList<String>()
        val n = r.getValue("consecutive_exposures").toInt()
        val drop = r.getValue("e1rm_drop")
        if (sig.anchorDeltas.count { d -> d.size >= n && d.takeLast(n).all { it <= -drop } } >= r.getValue("key_exercises").toInt()) out += "R1"
        val gn = r.getValue("g_sessions").toInt()
        if (sig.gAtStarts.size >= gn && sig.gAtStarts.takeLast(gn).all { it < r.getValue("g") }) out += "R2"
        val bn = r.getValue("feeling_bad_sessions").toInt()
        if (sig.badSessions.size >= bn && sig.badSessions.takeLast(bn).all { it }) out += "R3"
        if (sig.stallDeloadRecommended) out += "R4"
        return out
    }
}
