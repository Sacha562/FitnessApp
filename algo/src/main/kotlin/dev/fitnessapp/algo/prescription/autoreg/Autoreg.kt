package dev.fitnessapp.algo.prescription.autoreg

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.estimation.fatigue.Fatigue
import dev.fitnessapp.algo.estimation.rcm.RepCurve
import dev.fitnessapp.algo.estimation.sse.PreparedSet
import dev.fitnessapp.algo.estimation.sse.Sse
import dev.fitnessapp.algo.prescription.rx.Rx
import dev.fitnessapp.algo.prescription.rx.RxEstimate
import dev.fitnessapp.algo.prescription.rx.RxFlags
import dev.fitnessapp.algo.prescription.rx.RxInput
import dev.fitnessapp.algo.prescription.rx.RxMode
import dev.fitnessapp.algo.prescription.rx.RxOutput
import dev.fitnessapp.algo.prescription.rx.RxSet
import dev.fitnessapp.algo.prescription.rx.SchemeKind

enum class SuggestionKind(val code: String) { LOAD("load"), REPS("reps"), STOP("stop") }

/**
 * What AUTO-1 reads after a logged working set of one exercise and side (B13). [rx] and [plan] are the prescription the
 * sets were logged against (its input and output; after a probe, the follow-up). [logged] are this exercise and side's
 * working sets so far, in order, as SSE-1 prepared them (B6.3: `q_true`, `F`, `n_fresh`). [deltaStart] is
 * `delta_today` when the exercise started; [cardsShown] the cards shown for it this session; [techniqueEvents] the
 * `technique_breakdown` and `torso_rotation` reasons logged in it; [ruleItem] a RULE challenge item (shadow only, B13.5).
 */
data class AutoregInput(
    val rx: RxInput,
    val plan: RxOutput,
    val logged: List<PreparedSet>,
    val deltaStart: Double = 0.0,
    val cardsShown: Int = 0,
    val techniqueEvents: Int = 0,
    val ruleItem: Boolean = false,
)

/** The numbers behind a load decision (B13.3): the next set's predicted RIR at the current load against `q*`. */
data class Hysteresis(val qStar: Double, val rirNextCur: Double, val deviation: Double, val safetyDrop: Boolean, val loadChangeAllowed: Boolean)

/**
 * `autoreg.suggestion` (B13.6 outputs). [deltas] are `Delta_j` per logged set (null without a RIR); [trigger] the UI-06
 * rule that fired (`first_set`, `consecutive`, `reps_short`, `reason`) or null; [kind] null is a Keep; [proposed] the
 * remaining sets with their original set indexes. [card] = shown to the user (never for RULE items).
 */
data class AutoregResult(
    val deltaEx: Double,
    val deltas: List<Double?>,
    val trigger: String?,
    val kind: SuggestionKind?,
    val stopReason: String?,
    val proposed: List<RxSet>,
    val wCur: Double?,
    val wNew: Double?,
    val rirNextCur: Double?,
    val rirNextNew: Double?,
    val hysteresis: Hysteresis?,
    val card: Boolean,
    val trace: List<String>,
)

/**
 * AUTO-1 with the UI-06 trigger (ALGORITHMS.md B13, C16, C57): after each logged working set, the same-day readiness
 * offset `delta_ex` (a Kalman step of the set's e1RM residual against SSE-1's level), the trigger, and, when it fires,
 * a proposal for the remaining sets from RX-1 re-run at `M_use * exp(delta_ex)` behind the hysteresis and the bounds.
 * Never applied by itself; at most `autoreg.max_suggestions_per_exercise` cards per exercise and session (D10, C16).
 */
object Autoreg : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("autoreg")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("autoreg.consec_delta", "RIR", 0.5, 3.0),
        ParamSpec.number("autoreg.delta_max", "ratio", 0.0, 0.15),
        ParamSpec.number("autoreg.delta_min", "ratio", -0.2, 0.0),
        ParamSpec.number("autoreg.fatigue_stop_floor", "ratio", 0.6, 0.95),
        ParamSpec.number("autoreg.first_set_delta", "RIR", 1.0, 4.0),
        ParamSpec.number("autoreg.hysteresis_rir", "RIR", 0.5, 3.0),
        ParamSpec.integer("autoreg.max_suggestions_per_exercise", "cards", 1.0, 3.0),
        ParamSpec.number("autoreg.raise_max_rel", "ratio", 0.01, 0.1),
        ParamSpec.integer("autoreg.raise_max_steps_strength", "steps", 1.0, 3.0),
        ParamSpec.integer("autoreg.reps_short", "reps", 1.0, 5.0),
        ParamSpec.number("autoreg.safety_drop_margin", "RIR", 0.5, 2.0),
        ParamSpec.integer("autoreg.stop_consec_short", "sets", 1.0, 4.0),
        ParamSpec.integer("autoreg.technique_stop_events", "events", 1.0, 4.0),
        ParamSpec.number("autoreg.transfer", "dimensionless", 0.0, 1.0),
        ParamSpec.integer("autoreg.transfer_min_agree", "exercises", 1.0, 4.0),
        ParamSpec("autoreg.trigger_basis", ParamType.ENUM, "enum", enumValues = listOf("model", "raw_vs_aim")),
    )

    private val WORKING = setOf("working", "top", "backoff", "amrap")

    /**
     * `delta_today` for an exercise not yet started (B13.2): `autoreg.transfer` times the mean `delta_ex` of the
     * finished exercises when at least `autoreg.transfer_min_agree` of them agree in sign (one suffices after a
     * `feeling_good` reason), else 0.
     */
    fun deltaToday(finished: List<Double>, feelingGood: Boolean, params: ParamView): Double {
        val need = if (feelingGood) 1 else params.int("autoreg.transfer_min_agree")
        if (finished.size < need) return 0.0
        val sameSign = finished.all { it > 0.0 } || finished.all { it < 0.0 }
        if (!sameSign) return 0.0
        return Grid.ratio(params.double("autoreg.transfer") * finished.sum() / finished.size)
    }

    fun evaluate(i: AutoregInput, params: ParamView): AutoregResult {
        val trace = ArrayList<String>()
        val k = i.rx.k
        val kc = Fatigue.constants(params)
        val lPred = i.rx.estimate?.lPred ?: i.rx.transferM?.let { Det.ln(it) }
        val pLL = i.rx.estimate?.pLL ?: params.double("sse.transfer_sd").let { it * it }
        val mUse = i.plan.mUse
        val sdDay = params.double("sse.sd_day")
        val raw = params.string("autoreg.trigger_basis") == "raw_vs_aim"

        // B13.2: delta_ex after every working set, shown or not; Delta_j against the model-predicted RIR (C57).
        var delta = i.deltaStart
        val deltas = ArrayList<Double?>()
        var fRx = i.rx.f1
        for ((j, p) in i.logged.withIndex()) {
            val w = p.set.wSys
            val q = p.q?.qTrue
            val capRx = if (w != null && mUse != null && k > 0.0) RepCurve.cap(w, mUse, k) * (1.0 - fRx) else null
            val rirPred = capRx?.let { it - p.set.reps }
            deltas += when {
                raw -> p.set.rirReported?.let { it - i.plan.scheme.qStar }
                q != null && rirPred != null -> Grid.rep(q - rirPred)
                else -> null
            }
            if (capRx != null) fRx = Fatigue.step(fRx, p.set.reps.toDouble(), capRx, plannedRest(i, j + 1), kc)
            if (p.skipCode == null && lPred != null && w != null && k > 0.0) {
                val nFresh = p.nFresh!!
                val eps = Sse.z(w, nFresh, k) - lPred
                val sd = Sse.obsSd(nFresh, p.q!!.sigmaQ, p.fatigueF, k, params)
                val s2 = sd * sd * p.estimatorClass.m + sdDay * sdDay
                val ks = pLL / (pLL + s2)
                delta = (delta + ks * (eps - delta)).coerceIn(params.double("autoreg.delta_min"), params.double("autoreg.delta_max"))
            }
        }
        val deltaEx = Grid.ratio(delta)
        val keep = AutoregResult(deltaEx, deltas, null, null, null, emptyList(), null, null, null, null, null, false, trace)

        val last = i.logged.lastOrNull() ?: return keep
        val j = i.logged.size
        val remaining = i.plan.sets.filter { it.setIndex > j }
        val probeSet = j == 1 && "rx.probe.followup" in i.plan.trace
        val eligible = (i.plan.mode == RxMode.MODEL || i.plan.mode == RxMode.DOUBLE) && !i.plan.probe && !probeSet &&
            last.set.setKind in WORKING && last.set.reasonCode != "pain" && remaining.isNotEmpty() &&
            i.cardsShown < params.int("autoreg.max_suggestions_per_exercise") && mUse != null
        if (!eligible) return keep.copy(trace = trace + "autoreg.ineligible")

        val s = i.plan.scheme
        val target = i.plan.sets.firstOrNull { it.setIndex == j }?.targetReps
        val dj = deltas.last()
        val dPrev = deltas.getOrNull(j - 2)
        val trigger = when {
            j == 1 && dj != null && kotlin.math.abs(dj) >= params.double("autoreg.first_set_delta") -> "first_set"
            j >= 2 && dj != null && dPrev != null && kotlin.math.sign(dj) == kotlin.math.sign(dPrev) && dj != 0.0 &&
                kotlin.math.abs(dj) >= params.double("autoreg.consec_delta") && kotlin.math.abs(dPrev) >= params.double("autoreg.consec_delta") -> "consecutive"
            target != null && last.set.reps <= target - params.int("autoreg.reps_short") -> "reps_short"
            last.set.reasonCode == "too_easy" || last.set.reasonCode == "too_hard" -> "reason"
            else -> null
        }

        // The next set from the actual fatigue state (B7.3 recursion on the observed n_eff).
        val fNext = last.q?.let { Fatigue.step(last.fatigueF, last.repsDone, it.nEff, plannedRest(i, j), kc) } ?: fRx
        val mNew = mUse!! * Det.exp(deltaEx)
        val next = remaining.first()
        val wCur = next.wSys ?: last.set.wSys ?: return keep.copy(trigger = trigger)
        val wPlan = i.plan.sets.firstNotNullOfOrNull { it.wSys } ?: wCur
        val targets = remaining.map { it.targetReps ?: s.rHi }
        val rirCur = predicted(wCur, mNew, k, targets, fNext, remaining, kc)

        // B13.4: stop advice (one card, counts toward the limit).
        val shortAtFailure = i.logged.takeLast(params.int("autoreg.stop_consec_short")).let { tail ->
            tail.size == params.int("autoreg.stop_consec_short") && tail.all { it.set.reps < s.rLo && it.set.rirReported == 0.0 }
        }
        val qMin = maxOf(0.0, s.qStar - 1.0)
        val floor = params.double("autoreg.fatigue_stop_floor") * wPlan
        val fatigueStop = i.rx.loadables.filter { it >= floor - EPS }.none { w ->
            val cap = RepCurve.cap(w, mNew, k) * (1.0 - fNext)
            Rx.roundHalfDown(cap - qMin) >= s.rLo
        }
        val stop = when {
            fatigueStop -> "fatigue"
            shortAtFailure -> "short_at_failure"
            i.techniqueEvents >= params.int("autoreg.technique_stop_events") -> "technique"
            else -> null
        }
        if (stop != null) {
            trace += "autoreg.stop.$stop"
            return AutoregResult(deltaEx, deltas, trigger, SuggestionKind.STOP, stop, emptyList(), wCur, null, Grid.rep(rirCur.first()), null,
                null, !i.ruleItem, trace)
        }
        if (trigger == null) return keep.copy(rirNextCur = Grid.rep(rirCur.first()))
        trace += "autoreg.trigger.$trigger"

        // B13.3: re-run RX-1 for the remaining sets at M_use * exp(delta_ex) and the actual F state.
        val rerunInput = i.rx.copy(
            sets = remaining.size, f1 = fNext, estimate = RxEstimate(Det.ln(mNew), 0.0), transferM = null, last = null,
            flags = RxFlags(), restsS = remaining.map { it.restS ?: i.rx.restsS.last() },
        )
        val rerun = Rx.modelMode(rerunInput, params)
        val dev = rirCur.first() - s.qStar
        val safety = rirCur.first() < s.qStar - params.double("autoreg.safety_drop_margin")
        val allowed = safety || kotlin.math.abs(dev) >= params.double("autoreg.hysteresis_rir")
        val hyst = Hysteresis(s.qStar, Grid.rep(rirCur.first()), Grid.rep(dev), safety, allowed)
        val strength = s.kind == SchemeKind.FIXED || s.strength
        var wNew = wCur
        if (allowed) {
            val wRerun = rerun.sets.first().wSys!!
            var hi = wPlan * (1.0 + params.double("autoreg.delta_max"))
            hi = minOf(hi, if (strength) harder(i.rx.loadables, wCur, params.int("autoreg.raise_max_steps_strength")) ?: wCur else wCur * (1.0 + params.double("autoreg.raise_max_rel")))
            val lo = if (safety) Double.NEGATIVE_INFINITY else wPlan * (1.0 + params.double("autoreg.delta_min"))
            wNew = when {
                wRerun > wCur + EPS -> i.rx.loadables.filter { it > wCur + EPS && it <= minOf(wRerun, hi) + EPS }.maxOrNull() ?: wCur
                wRerun < wCur - EPS -> i.rx.loadables.filter { it < wCur - EPS && it >= maxOf(wRerun, lo) - EPS }.minOrNull() ?: wCur
                else -> wCur
            }
            if (wNew == wCur) trace += "autoreg.load.bounded"
        } else {
            trace += "autoreg.hysteresis.keep_load"
        }
        if (kotlin.math.abs(wNew - wCur) > EPS) {
            val atNew = Rx.modelMode(rerunInput.copy(loadables = listOf(wNew)), params)
            val sets = remaining.mapIndexed { n, r -> r.copy(targetReps = atNew.sets[n].targetReps, wSys = Grid.kg(wNew), rirPred = atNew.sets[n].rirPred) }
            val rirNew = predicted(wNew, mNew, k, sets.map { it.targetReps ?: s.rHi }, fNext, remaining, kc)
            trace += if (wNew > wCur) "autoreg.load.up" else if (safety) "autoreg.load.safety_drop" else "autoreg.load.down"
            return AutoregResult(deltaEx, deltas, trigger, SuggestionKind.LOAD, null, sets.withPred(rirNew), wCur, Grid.kg(wNew),
                Grid.rep(rirCur.first()), Grid.rep(rirNew.first()), hyst, !i.ruleItem, trace)
        }
        if (s.kind != SchemeKind.RANGE) return keep.copy(trigger = trigger, rirNextCur = Grid.rep(rirCur.first()), hysteresis = hyst, trace = trace + "autoreg.keep")

        // RANGE without a load change: reps +/-1 inside the range when that moves the predicted RIR toward q* by >= 0.5.
        val reps = targets.toMutableList()
        var changed = false
        for (n in reps.indices) {
            val base = predicted(wCur, mNew, k, reps, fNext, remaining, kc)[n]
            var best = reps[n]
            var bestGain = 0.0
            for (r in listOf(reps[n] + 1, reps[n] - 1)) {
                if (r < s.rLo || r > s.rHi) continue
                val trial = reps.toMutableList().also { it[n] = r }
                val rir = predicted(wCur, mNew, k, trial, fNext, remaining, kc)[n]
                val gain = kotlin.math.abs(base - s.qStar) - kotlin.math.abs(rir - s.qStar)
                if (gain >= 0.5 - 1e-9 && gain > bestGain + 1e-9) {
                    best = r
                    bestGain = gain
                }
            }
            if (best != reps[n]) {
                reps[n] = best
                changed = true
            }
        }
        if (!changed) return keep.copy(trigger = trigger, rirNextCur = Grid.rep(rirCur.first()), hysteresis = hyst, trace = trace + "autoreg.keep")
        val rirNew = predicted(wCur, mNew, k, reps, fNext, remaining, kc)
        trace += "autoreg.reps"
        val sets = remaining.mapIndexed { n, r -> r.copy(targetReps = reps[n], wSys = Grid.kg(wCur)) }
        return AutoregResult(deltaEx, deltas, trigger, SuggestionKind.REPS, null, sets.withPred(rirNew), wCur, Grid.kg(wCur),
            Grid.rep(rirCur.first()), Grid.rep(rirNew.first()), hyst, !i.ruleItem, trace)
    }

    /** Predicted RIR per remaining set at load [w] with the prescription recursion from [f1] (B7.2). */
    private fun predicted(w: Double, m: Double, k: Double, reps: List<Int>, f1: Double, sets: List<RxSet>, kc: Fatigue.Constants): List<Double> {
        val capFresh = RepCurve.cap(w, m, k)
        val out = ArrayList<Double>()
        var f = f1
        reps.forEachIndexed { n, r ->
            val cap = capFresh * (1.0 - f)
            out += cap - r
            if (n < reps.size - 1) f = Fatigue.step(f, r.toDouble(), cap, sets[n].restS ?: 0.0, kc)
        }
        return out
    }

    private fun List<RxSet>.withPred(rir: List<Double>): List<RxSet> = mapIndexed { n, s -> s.copy(rirPred = Grid.rep(rir[n])) }

    /** The planned rest after set [setIndex] (1-based) of this exercise and side. */
    private fun plannedRest(i: AutoregInput, setIndex: Int): Double =
        i.plan.sets.firstOrNull { it.setIndex == setIndex }?.restS ?: i.rx.restsS.getOrElse(setIndex - 1) { i.rx.restsS.last() }

    /** The loadable [steps] steps above [w] (B13.3: strength raises at most `nextHarder`). */
    private fun harder(loadables: List<Double>, w: Double, steps: Int): Double? =
        loadables.filter { it > w + EPS }.sorted().getOrNull(steps - 1) ?: loadables.filter { it > w + EPS }.maxOrNull()

    private const val EPS = 0.005
}
