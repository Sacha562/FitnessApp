package dev.fitnessapp.algo.pipeline

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.estimation.ExerciseFacts
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.rcm.Rcm
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.estimation.rircal.RirCalState
import dev.fitnessapp.algo.estimation.sse.SideKey
import dev.fitnessapp.algo.estimation.sse.Sse
import dev.fitnessapp.algo.estimation.sse.SseState
import dev.fitnessapp.algo.estimation.sse.Trend
import dev.fitnessapp.algo.estimation.stall.FailExposure
import dev.fitnessapp.algo.estimation.stall.Stall
import dev.fitnessapp.algo.prescription.PrescriptionJson
import dev.fitnessapp.algo.prescription.reason.Reason
import dev.fitnessapp.algo.prescription.reason.ReasonSemantics
import dev.fitnessapp.algo.prescription.rx.ItemScheme
import dev.fitnessapp.algo.prescription.rx.RepPref
import dev.fitnessapp.algo.prescription.rx.Rx
import dev.fitnessapp.algo.prescription.rx.RxEstimate
import dev.fitnessapp.algo.prescription.rx.RxFlags
import dev.fitnessapp.algo.prescription.rx.RxInput
import dev.fitnessapp.algo.prescription.rx.RxLast
import dev.fitnessapp.algo.prescription.rx.RxMode
import dev.fitnessapp.algo.prescription.rx.RxOutput
import dev.fitnessapp.algo.prescription.rx.SchemeContext
import dev.fitnessapp.algo.prescription.rx.SchemeKind
import dev.fitnessapp.algo.prescription.rx.Schemes
import dev.fitnessapp.algo.prescription.warmup.Warmup
import dev.fitnessapp.algo.prescription.warmup.WarmupInput
import dev.fitnessapp.algo.prescription.warmup.WarmupPlan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What RX-1 knows of an earlier `rx.prescription` of the same exercise and side (B9.2). */
data class PreviousRx(val mode: RxMode, val targets: List<Int>?, val ext: Int, val probe: Boolean, val deload: Boolean)

/**
 * One earlier exposure of the exercise and side: its working sets in order and the prescription it was logged
 * against (null when there was none, for example a session from before M3.4).
 */
data class Exposure(val sessionId: String, val date: LocalDate, val sets: List<LoggedSet>, val rx: PreviousRx?)

/**
 * Everything the prescription of one session exercise and side reads (B9.2), gathered from the stored facts by the
 * caller. [exposures] are the valid and invalid earlier exposures, newest first; [sse] is the stored SSE-1 state
 * (predicted to [at] here); [loadables] the equipment's system loads; [item] the scheme of a `delegate:rx` challenge
 * item (D49). [warm] and [firstForGroup] are WARMUP-1's group context in today's session; [warmupLoadables] the
 * loadables on the warm-up grid (D106, null = no grid) and [warmupMaxSets] the user's warm-up count (D107, null = auto).
 */
data class RxRequest(
    val exerciseId: String,
    val side: String,
    val facts: ExerciseFacts?,
    val isolation: Boolean,
    val highRisk: Boolean,
    val bodyweightOnly: Boolean,
    val prefs: List<RepPref>,
    val item: ItemScheme?,
    val sets: Int,
    val restS: Double,
    val loadables: List<Double>,
    val bodyweightW: Double?,
    val sse: SseState?,
    val rcmK: Double?,
    val rircal: RirCalState,
    val transferM: Double?,
    val exposures: List<Exposure>,
    val at: Instant,
    val today: LocalDate,
    val deload: Boolean = false,
    val warm: Boolean = false,
    val firstForGroup: Boolean = true,
    val warmupLoadables: List<Double>? = null,
    val warmupMaxSets: Int? = null,
)

/** One prescription: RX-1's input and output and WARMUP-1's sets (null when nothing gets a warm-up). */
data class PrescriptionResult(val input: RxInput, val output: RxOutput, val warmup: WarmupPlan?)

/**
 * L6 prescription for one exercise of a session the user assembled or repeated (D49, M3.4): turns the stored facts
 * into RX-1's input (B9.2) and runs RX-1 and WARMUP-1. Pure: the same request gives the same prescription, so the
 * `rx.prescription` snapshot's inputs replay it. The scheme comes from the challenge item, the user's Sets and reps
 * preferences (M3.5) or the engine defaults, field by field (B9.3).
 */
object Prescribing {
    private val COUNTED = setOf("working", "top", "backoff", "amrap")

    fun plan(req: RxRequest, params: ParamView): PrescriptionResult {
        val input = input(req, params)
        val out = Rx.prescribe(input, params)
        return PrescriptionResult(input, out, warmup(req, input, out, params))
    }

    /**
     * The sets after a logged calibration probe (B9.8): SSE-1's update with the probe as the only observation (a cold
     * start without a state) gives the estimate RX-1 re-runs with for the remaining sets, and the probe's fatigue is
     * carried into them. AUTO-1's same-day offset arrives with M3.5; on the first set it is the same Kalman step.
     */
    fun followUp(req: RxRequest, probe: LoggedSet, params: ParamView): RxOutput? {
        val input = followUpInput(req, probe, params) ?: return null
        val out = Rx.prescribe(input, params)
        return out.copy(sets = out.sets.map { it.copy(setIndex = it.setIndex + 1) }, trace = out.trace + "rx.probe.followup")
    }

    /** RX-1's input for the sets after the probe (the estimate updated with it); null when nothing follows. */
    fun followUpInput(req: RxRequest, probe: LoggedSet, params: ParamView): RxInput? {
        val facts = req.facts ?: return null
        val curve = facts.curveClass ?: return null
        val k = req.rcmK ?: req.sse?.k ?: Rcm.k0(curve, params)
        val key = SideKey(req.exerciseId, probe.side)
        val priors = req.sse?.let { mapOf(key to it.copy(trend = Trend.predict(it.trend, req.at, params))) } ?: emptyMap()
        val prepared = Sse.prepare(listOf(probe), mapOf(req.exerciseId to facts), req.rircal, params)
        val transfer = req.transferM?.let { mapOf(req.exerciseId to it) } ?: emptyMap()
        val r = Sse.update(prepared, priors, mapOf(req.exerciseId to k), transfer, mapOf(req.exerciseId to facts), req.at, null, params)
        val st = r.next[key] ?: return null
        val base = input(req, params)
        val remaining = base.sets - 1
        if (remaining <= 0) return null
        val f1 = prepared.firstOrNull()?.fatigueF ?: 0.0
        return base.copy(
            sets = remaining, k = k, estimate = RxEstimate(st.trend.l, st.trend.pLL), transferM = null, last = null,
            f1 = f1, flags = base.flags.copy(daysSinceLast = null),
        )
    }

    fun input(req: RxRequest, params: ParamView): RxInput {
        val curve = req.facts?.curveClass
        val scheme = Schemes.resolve(
            req.prefs,
            SchemeContext(isolation = req.isolation, bodyweightOnly = req.bodyweightOnly, highRisk = req.highRisk, item = req.item, deload = req.deload),
            params,
        )
        // S is the session exercise's planned set count (B9.2: the slot's S; a challenge deload arrives already halved),
        // or the user's sets preference for an exercise the user picked (B9.3; a challenge item keeps its own S).
        val sets = if (req.item == null) scheme.setsPref ?: req.sets else req.sets
        val last = req.exposures.firstOrNull()
        val k = req.rcmK ?: req.sse?.k ?: curve?.let { Rcm.k0(it, params) }
        val estimate = req.sse?.let { Trend.predict(it.trend, req.at, params) }?.let { RxEstimate(it.l, it.pLL) }
        val failCounter = Stall.failCounter(req.exposures.map { FailExposure(valid(it, params), failed(it)) })
        val flags = RxFlags(
            deload = req.deload,
            failCounter = failCounter,
            usedDoubleThisMeso = req.exposures.firstOrNull { it.rx != null }?.rx?.mode == RxMode.DOUBLE,
            daysSinceLast = last?.let { ChronoUnit.DAYS.between(it.date, req.today) },
        )
        return RxInput(
            exerciseId = req.exerciseId, side = req.side, isolation = req.isolation, highRisk = req.highRisk,
            bodyweightOnly = req.bodyweightOnly, scheme = scheme, sets = sets, restsS = listOf(req.restS),
            // Without a curve class there is no capacity model: RX-1 repeats the last exposure (B9.10).
            loadables = if (k == null) emptyList() else req.loadables.sorted().distinct(), k = k ?: 0.0,
            estimate = estimate, transferM = if (estimate == null) req.transferM else null,
            last = last?.let { rxLast(it, req, params) }, flags = flags,
        )
    }

    /** The last exposure as RX-1 reads it: the top load, the reps and `q_true` per set, and the ladder it followed. */
    private fun rxLast(e: Exposure, req: RxRequest, params: ParamView): RxLast? {
        val sets = e.sets.filter { it.setKind in COUNTED && it.wSys != null }
        if (sets.isEmpty()) return null
        val curve = req.facts?.curveClass
        val q = sets.map { s -> RirCal.qTrue(s, ReasonSemantics.forSet(s, params), curve, req.rircal, params)?.qTrue }
        val prev = e.rx
        return RxLast(
            wLast = sets.maxOf { it.wSys!! }, reps = sets.map { it.reps }, qTrue = q, valid = valid(e, params),
            probe = prev?.probe ?: false, targets = prev?.targets ?: sets.mapNotNull { it.targetReps }.takeIf { it.size == sets.size },
            ext = prev?.ext ?: 0, failed = failed(e),
        )
    }

    private fun valid(e: Exposure, params: ParamView): Boolean =
        e.rx?.deload != true && e.sets.none { Reason.row(it.reasonCode).invalidatesExposure } && e.sets.any { it.setKind in COUNTED }

    private fun failed(e: Exposure): Boolean = e.sets.any { it.setKind in COUNTED && it.failedRep }

    private fun warmup(req: RxRequest, input: RxInput, out: RxOutput, params: ParamView): WarmupPlan? {
        val w = out.sets.firstOrNull()?.wSys ?: return null
        if (out.mode == RxMode.NONE || input.loadables.isEmpty()) return null
        val m = input.estimate?.let { Det.exp(it.lPred) } ?: input.transferM
        val plan = Warmup.plan(
            WarmupInput(
                wWork = w, m = m, k = input.k, isolation = req.isolation, loadables = input.loadables, bodyweightW = req.bodyweightW,
                warm = req.warm, firstForGroup = req.firstForGroup,
                gridLoadables = req.warmupLoadables?.sorted()?.distinct(), maxSets = req.warmupMaxSets,
                rStar = if (input.scheme.kind == SchemeKind.FIXED) input.scheme.rStar else null,
            ),
            params,
        )
        return plan.takeIf { it.sets.isNotEmpty() }
    }

    // ------------------------------------------------------------------------------------------------ snapshot JSON

    /** The `rx.prescription` snapshot inputs (B9.2): every fact the request carried, on the canonical grids. */
    fun inputs(req: RxRequest): JsonObject = buildJsonObject {
        put("exercise_id", req.exerciseId)
        put("side", req.side)
        put("isolation", req.isolation)
        put("high_risk", req.highRisk)
        put("bodyweight_only", req.bodyweightOnly)
        put("item", req.item?.let { i ->
            buildJsonObject {
                put("rep_min", JsonNumbers.int(i.repMin.toLong()))
                put("rep_max", JsonNumbers.int(i.repMax.toLong()))
                put("rir_target", i.rirTarget?.let { rep(it) } ?: JsonNull)
                put("sets", i.sets?.let { JsonNumbers.int(it.toLong()) } ?: JsonNull)
            }
        } ?: JsonNull)
        put("prefs", buildJsonArray {
            req.prefs.forEach { p ->
                add(buildJsonObject {
                    put("scope", p.scope)
                    put("sets_pref", p.setsPref?.let { JsonNumbers.int(it.toLong()) } ?: JsonNull)
                    put("rep_min", p.repMin?.let { JsonNumbers.int(it.toLong()) } ?: JsonNull)
                    put("rep_max", p.repMax?.let { JsonNumbers.int(it.toLong()) } ?: JsonNull)
                    put("rir_target", p.rirTarget?.let { rep(it) } ?: JsonNull)
                    put("rep_scheme", p.repScheme?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("rest_pref_s", p.restPrefS?.let { JsonNumbers.grid(Grid.s(it), GridKind.S) } ?: JsonNull)
                    put("rest_pref_mult", p.restPrefMult?.let { JsonNumbers.grid(Grid.ratio(it), GridKind.RATIO) } ?: JsonNull)
                })
            }
        })
        put("sets", JsonNumbers.int(req.sets.toLong()))
        put("rest_s", JsonNumbers.grid(Grid.s(req.restS), GridKind.S))
        put("loadables", JsonArray(req.loadables.sorted().distinct().map { kg(it) }))
        put("bodyweight_w", req.bodyweightW?.let { kg(it) } ?: JsonNull)
        put("sse", req.sse?.let { s ->
            buildJsonObject {
                put("l", stat(s.trend.l))
                put("g", stat(s.trend.g))
                put("p_ll", stat(s.trend.pLL))
                put("p_lg", stat(s.trend.pLg))
                put("p_gg", stat(s.trend.pgg))
                put("t_last", s.trend.tLast.toString())
                put("n_obs", JsonNumbers.int(s.trend.nObs.toLong()))
                put("k", stat(s.k))
            }
        } ?: JsonNull)
        put("rcm_k", req.rcmK?.let { stat(it) } ?: JsonNull)
        put("rircal_b", stat(req.rircal.b))
        put("transfer_m", req.transferM?.let { kg(it) } ?: JsonNull)
        put("exposures", buildJsonArray {
            req.exposures.forEach { e ->
                add(buildJsonObject {
                    put("session_id", e.sessionId)
                    put("date", e.date.toString())
                    put("set_ids", JsonArray(e.sets.map { JsonPrimitive(it.id) }))
                    put("rx", e.rx?.let { p ->
                        buildJsonObject {
                            put("mode", p.mode.code)
                            put("targets", p.targets?.let { t -> JsonArray(t.map { JsonNumbers.int(it.toLong()) }) } ?: JsonNull)
                            put("ext", JsonNumbers.int(p.ext.toLong()))
                            put("probe", p.probe)
                            put("deload", p.deload)
                        }
                    } ?: JsonNull)
                })
            }
        })
        put("at", req.at.toString())
        put("today", req.today.toString())
        put("deload", req.deload)
        put("warm", req.warm)
        put("first_for_group", req.firstForGroup)
        // D106: only when present, so the inputs of exercises without a grid or a count keep their hash.
        req.warmupLoadables?.let { w -> put("warmup_loadables", JsonArray(w.sorted().distinct().map { kg(it) })) }
        req.warmupMaxSets?.let { put("warmup_max_sets", JsonNumbers.int(it.toLong())) }
    }

    fun inputsHash(req: RxRequest): String = Jcs.sha256Hex(inputs(req))

    /** `rx.prescription` outputs with the warm-up sets the card shows first (B9.15, B10). */
    fun outputs(r: PrescriptionResult): JsonObject = outputs(r.output, r.warmup)

    fun outputs(out: RxOutput, warmup: WarmupPlan?): JsonObject {
        val base = PrescriptionJson.rx(out)
        return JsonObject(base + ("warmup" to (warmup?.let { PrescriptionJson.warmup(it) } ?: JsonNull)))
    }

    fun trace(out: RxOutput): JsonElement = PrescriptionJson.rxTrace(out)

    private fun stat(x: Double) = JsonNumbers.grid(Grid.stat(x), GridKind.STAT)
    private fun rep(x: Double) = JsonNumbers.grid(Grid.rep(x), GridKind.REP)
    private fun kg(x: Double) = JsonNumbers.grid(Grid.kg(x), GridKind.KG)
}
