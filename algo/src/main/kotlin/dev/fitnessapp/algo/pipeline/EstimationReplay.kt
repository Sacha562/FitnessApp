package dev.fitnessapp.algo.pipeline

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.estimation.EstimationJson
import dev.fitnessapp.algo.estimation.ExerciseFacts
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.hold.Hold
import dev.fitnessapp.algo.estimation.hold.HoldFacts
import dev.fitnessapp.algo.estimation.hold.HoldSet
import dev.fitnessapp.algo.estimation.hold.HoldState
import dev.fitnessapp.algo.estimation.rcm.Rcm
import dev.fitnessapp.algo.estimation.rcm.RcmFit
import dev.fitnessapp.algo.estimation.rcm.RcmState
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.estimation.rircal.RirCalState
import dev.fitnessapp.algo.estimation.rircal.RirCalUpdate
import dev.fitnessapp.algo.estimation.sse.PreparedSet
import dev.fitnessapp.algo.estimation.sse.SideKey
import dev.fitnessapp.algo.estimation.sse.Sse
import dev.fitnessapp.algo.estimation.sse.SseState
import dev.fitnessapp.algo.estimation.sse.TrendState
import dev.fitnessapp.algo.prescription.reason.ReasonSemantics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/** One finished session as the L8 replay reads it: facts only, the dynamic [sets] and the timed [holds]. */
data class FinishedSession(
    val id: String,
    val start: Instant,
    val localDate: LocalDate,
    val bwKg: Double?,
    val sets: List<LoggedSet>,
    val holds: List<HoldSet>,
)

/** A catalog transfer ratio between two exercise ids: `M(to) = ratio * M(from)` (B6.4 cold start). */
data class Transfer(val fromExerciseId: String, val toExerciseId: String, val ratio: Double)

/** One `recommendations` row to store (the C24 envelope without the device columns). */
data class EstimationSnapshot(
    val kind: String,
    val module: ModuleRef,
    val scopeKey: String,
    val sessionId: String,
    val scopeDate: LocalDate,
    val setLogId: String?,
    val inputs: JsonObject,
    val outputs: JsonObject,
    val trace: JsonElement,
) {
    val inputsHash: String get() = Jcs.sha256Hex(inputs)
}

/** One `exercise_model_state` row (DATA_MODEL 9.8.2): a cache, rebuilt from the facts by [EstimationReplay]. */
data class ModelStateRow(
    val model: String,
    val module: ModuleRef,
    val exerciseId: String?,
    val side: String,
    val classKey: String?,
    val state: JsonObject,
    val nObservations: Int,
    val asOf: Instant,
    val asOfSessionId: String,
) {
    val stateHash: String get() = Jcs.sha256Hex(state)
}

data class ReplayResult(val snapshots: List<EstimationSnapshot>, val states: List<ModelStateRow>)

/**
 * The L8 estimation layer (ALGORITHMS.md A12) replayed over every finished session in order: per session RCM-1
 * fits the exercises it contains, SSE-1 and HOLD-1 update with the new `k`, and RIRCAL-1's updates take effect
 * from the next session (B4.2). Pure and deterministic: the same facts give the same snapshots and states, so the
 * caller can rebuild the caches at any time and store only the rows it does not have yet.
 */
object EstimationReplay {
    private val DYNAMIC_KINDS = setOf("working", "top", "backoff", "amrap", "calibration", "test")

    fun run(
        sessions: List<FinishedSession>,
        facts: Map<String, ExerciseFacts>,
        holdFacts: Map<String, HoldFacts>,
        transfers: List<Transfer>,
        params: ParamView,
    ): ReplayResult {
        val snapshots = ArrayList<EstimationSnapshot>()
        val sse = TreeMap<SideKey, SseState>()
        val sseAsOf = TreeMap<SideKey, Pair<Instant, String>>()
        val rcm = TreeMap<String, RcmFit>()
        val rcmAsOf = TreeMap<String, Pair<Instant, String>>()
        val hold = TreeMap<SideKey, HoldState>()
        val holdAsOf = TreeMap<SideKey, Pair<Instant, String>>()
        var rircal = RirCal.initial(params)
        var rircalAsOf: Pair<Instant, String>? = null
        val history = TreeMap<String, MutableList<PreparedSet>>()
        val lastExposure = TreeMap<String, List<LoggedSet>>()

        for (s in sessions) {
            val dynamic = s.sets.filter { it.contraction == "dynamic" && it.setKind != "warmup" }
            val prepared = Sse.prepare(s.sets, facts, rircal, params)
            prepared.filter { it.skipCode == null }.forEach { history.getOrPut(it.set.exerciseId) { ArrayList() } += it }

            // RCM-1 for every exercise of the session with a curve class and at least one usable set.
            val kBy = TreeMap<String, Double>()
            for (ex in prepared.filter { it.skipCode == null }.map { it.set.exerciseId }.distinct().sorted()) {
                val curve = facts[ex]?.curveClass ?: continue
                val prev = rcm[ex]?.state
                val own = history.getValue(ex)
                var fit = Rcm.fit(own, s.localDate, curve, prev, null, params)
                var pooled: Double? = null
                if (fit.m == 0) {
                    val others = history.filterKeys { it != ex && facts[it]?.curveClass == curve }
                    pooled = Rcm.pooledClassK(others, s.localDate, curve, params)
                    if (pooled != null) fit = Rcm.fit(own, s.localDate, curve, prev, pooled, params)
                }
                rcm[ex] = fit
                rcmAsOf[ex] = s.start to s.id
                snapshots += EstimationSnapshot(
                    "estimation.rcm", Rcm.ref, "exercise:$ex", s.id, s.localDate, null,
                    rcmInputs(ex, s.localDate, curve.code, prev, pooled, own, params), EstimationJson.rcm(fit), JsonArray(emptyList()),
                )
                kBy[ex] = fit.state.kUsed
            }
            // A dynamic exercise without a fit yet (no usable set this time) keeps its state's k; else the class prior.
            for (ex in dynamic.map { it.exerciseId }.distinct()) {
                if (kBy.containsKey(ex)) continue
                val k = rcm[ex]?.state?.kUsed ?: facts[ex]?.curveClass?.let { Rcm.k0(it, params) }
                if (k != null) kBy[ex] = k
            }

            if (dynamic.isNotEmpty()) {
                val transferM = TreeMap<String, Double>()
                for (ex in dynamic.map { it.exerciseId }.distinct().sorted()) {
                    if (sse.keys.any { it.exerciseId == ex }) continue
                    val t = transfers.filter { it.toExerciseId == ex }.sortedBy { it.fromExerciseId }
                        .firstOrNull { tr -> sse.keys.any { it.exerciseId == tr.fromExerciseId } } ?: continue
                    val from = sse.entries.first { it.key.exerciseId == t.fromExerciseId }.value
                    transferM[ex] = t.ratio * Det.exp(from.trend.l)
                }
                val priors = TreeMap(sse)
                val r = Sse.update(prepared, priors, kBy, transferM, facts, s.start, s.bwKg, params)
                r.next.forEach { (key, st) ->
                    if (priors[key] != st) {
                        sse[key] = st
                        sseAsOf[key] = s.start to s.id
                    }
                }
                snapshots += EstimationSnapshot(
                    "estimation.sse", Sse.ref, "session:${s.id}", s.id, s.localDate, null,
                    sseInputs(s, rircal, priors, kBy, transferM),
                    EstimationJson.sse(r), EstimationJson.sseTrace(r),
                )
            }

            if (s.holds.isNotEmpty()) {
                val priors = TreeMap(hold)
                val r = Hold.update(s.holds, holdFacts, priors, s.start, params)
                r.next.forEach { (key, st) ->
                    if (priors[key] != st) {
                        hold[key] = st
                        holdAsOf[key] = s.start to s.id
                    }
                }
                snapshots += EstimationSnapshot(
                    "estimation.hold", Hold.ref, "session:${s.id}", s.id, s.localDate, null,
                    holdInputs(s, priors), EstimationJson.hold(r), buildJsonObject { put("steps", EstimationJson.steps(r.trace)) },
                )
            }

            // RIRCAL-1 (B4.3-B4.5): explicit checks, then one implicit update per exercise and side, then the hook.
            val updates = ArrayList<Pair<RirCalUpdate, String?>>()
            for (c in dynamic.filter { it.setKind == "calibration" && it.calibExtraReps != null && it.rirReported != null }
                .sortedWith(compareBy<LoggedSet> { it.performedAt }.thenBy { it.id })) {
                val u = RirCal.explicit(rircal, facts[c.exerciseId]?.curveClass, c.rirReported!!, c.calibExtraReps!!, s.localDate, params)
                rircal = u.state
                updates += u to c.id
            }
            val working = dynamic.filter { it.setKind in DYNAMIC_KINDS && it.setKind != "calibration" }
            for ((key, group) in working.groupBy { SideKey(it.exerciseId, it.side) }.toSortedMap()) {
                val ordered = group.sortedWith(compareBy<LoggedSet> { it.performedAt }.thenBy { it.id })
                val f1 = prepared.firstOrNull { it.set.id == ordered.first().id }?.fatigueF ?: 0.0
                val u = RirCal.implicit(rircal, facts[key.exerciseId]?.curveClass, ordered.map { it to ReasonSemantics.forSet(it, params) }, f1, params)
                if (u != null) {
                    rircal = u.state
                    updates += u to null
                }
            }
            for (ex in working.map { it.exerciseId }.distinct().sorted()) {
                val now = working.filter { it.exerciseId == ex }
                val before = lastExposure[ex]
                if (before != null) {
                    val u = RirCal.reasonHook(rircal, facts[ex]?.curveClass, listOf(before, now), params)
                    if (u != null) {
                        rircal = u.state
                        updates += u to null
                    }
                }
                lastExposure[ex] = now
            }
            for ((u, setId) in updates) {
                snapshots += EstimationSnapshot(
                    "estimation.rircal", RirCal.ref, "user", s.id, s.localDate, setId,
                    buildJsonObject {
                        put("session_id", s.id)
                        put("set_id", setId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("type", u.type)
                        put("b_before", stat(u.bBefore))
                        put("s_q_before", stat(u.sQBefore))
                    },
                    EstimationJson.rircal(u), JsonArray(emptyList()),
                )
                rircalAsOf = s.start to s.id
            }
        }

        val states = ArrayList<ModelStateRow>()
        sse.forEach { (key, st) ->
            val (at, sid) = sseAsOf.getValue(key)
            states += ModelStateRow("sse", Sse.ref, key.exerciseId, key.side, null, sseState(st), st.trend.nObs, at, sid)
        }
        rcm.forEach { (ex, fit) ->
            val (at, sid) = rcmAsOf.getValue(ex)
            states += ModelStateRow("rcm", Rcm.ref, ex, "both", null, rcmState(fit), fit.nSets, at, sid)
        }
        hold.forEach { (key, st) ->
            val (at, sid) = holdAsOf.getValue(key)
            states += ModelStateRow("hold", Hold.ref, key.exerciseId, key.side, null, holdState(st), st.trend.nObs, at, sid)
        }
        rircalAsOf?.let { (at, sid) ->
            states += ModelStateRow("rircal", RirCal.ref, null, "both", "user", rircalUser(rircal), rircal.n, at, sid)
            rircal.classes.forEach { (code, c) ->
                val state = buildJsonObject {
                    put("b_c", stat(c.bC))
                    put("n_c", JsonNumbers.int(c.nC.toLong()))
                    put("last_check_date", c.lastCheckDate?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                }
                states += ModelStateRow("rircal", RirCal.ref, null, "both", code, state, c.nC, at, sid)
            }
        }
        return ReplayResult(snapshots, states)
    }

    /** The `k_used`, `sd_lnk` and informative windows of an `rcm` state row (read back by the challenge facts). */
    fun rcmState(fit: RcmFit): JsonObject = buildJsonObject {
        put("k_used", stat(fit.state.kUsed))
        put("sd_lnk", stat(fit.state.sdLnk))
        put("m", JsonNumbers.int(fit.m.toLong()))
    }

    private fun sseState(st: SseState): JsonObject = buildJsonObject {
        trend(st.trend)
        put("k", stat(st.k))
    }

    private fun holdState(st: HoldState): JsonObject = buildJsonObject {
        trend(st.trend)
        put("w_ref_kg", st.wRef?.let { JsonNumbers.grid(it, GridKind.KG) } ?: JsonNull)
        put("segment_kg", st.segmentKg?.let { JsonNumbers.grid(it, GridKind.KG) } ?: JsonNull)
    }

    private fun rircalUser(st: RirCalState): JsonObject = buildJsonObject {
        put("b", stat(st.b))
        put("s_q", stat(st.sQ))
        put("n", JsonNumbers.int(st.n.toLong()))
        put("last_check_week", st.lastCheckWeek?.let { JsonPrimitive(it) } ?: JsonNull)
        put("checks_in_last_check_week", JsonNumbers.int(st.checksInLastCheckWeek.toLong()))
    }

    private fun JsonObjectBuilder.trend(t: TrendState) {
        put("l", stat(t.l))
        put("g", stat(t.g))
        put("p_ll", stat(t.pLL))
        put("p_lg", stat(t.pLg))
        put("p_gg", stat(t.pgg))
        put("t_last", t.tLast.toString())
        put("n_obs", JsonNumbers.int(t.nObs.toLong()))
    }

    private fun sseInputs(
        s: FinishedSession,
        rircal: RirCalState,
        priors: Map<SideKey, SseState>,
        kBy: Map<String, Double>,
        transferM: Map<String, Double>,
    ): JsonObject = buildJsonObject {
        put("session_id", s.id)
        put("session_start", s.start.toString())
        put("bw_kg", s.bwKg?.let { JsonNumbers.grid(it, GridKind.KG) } ?: JsonNull)
        put("rircal", rircalUser(rircal))
        put("priors", buildJsonArray {
            priors.forEach { (key, st) ->
                add(buildJsonObject {
                    put("exercise_id", key.exerciseId)
                    put("side", key.side)
                    trend(st.trend)
                    put("k", stat(st.k))
                })
            }
        })
        put("k_by_exercise", buildJsonObject { kBy.toSortedMap().forEach { (ex, k) -> put(ex, stat(k)) } })
        put("transfer_m", buildJsonObject { transferM.toSortedMap().forEach { (ex, m) -> put(ex, JsonNumbers.grid(Grid.kg(m), GridKind.KG)) } })
        put("sets", buildJsonArray { s.sets.sortedBy { it.id }.forEach { add(setJson(it)) } })
    }

    private fun holdInputs(s: FinishedSession, priors: Map<SideKey, HoldState>): JsonObject = buildJsonObject {
        put("session_id", s.id)
        put("session_start", s.start.toString())
        put("priors", buildJsonArray {
            priors.forEach { (key, st) ->
                add(buildJsonObject {
                    put("exercise_id", key.exerciseId)
                    put("side", key.side)
                    trend(st.trend)
                })
            }
        })
        put("holds", buildJsonArray {
            s.holds.sortedBy { it.id }.forEach { h ->
                add(buildJsonObject {
                    put("id", h.id)
                    put("exercise_id", h.exerciseId)
                    put("side", h.side)
                    put("performed_at", h.performedAt.toString())
                    put("set_kind", h.setKind)
                    put("w_sys", h.wSys?.let { kg(it) } ?: JsonNull)
                    put("external_kg", kg(h.externalKg))
                    put("t_done_s", JsonNumbers.grid(Grid.s(h.tDoneS), GridKind.S))
                    put("reserve_s", JsonNumbers.grid(Grid.s(h.reserveS), GridKind.S))
                    put("reserve_lower_bound", h.reserveLowerBound)
                    put("torso_rotation", h.torsoRotation)
                    put("reason_code", h.reasonCode?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("round_id", h.roundId?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        })
    }

    private fun rcmInputs(
        ex: String,
        fitDate: LocalDate,
        curve: String,
        prev: RcmState?,
        pooled: Double?,
        own: List<PreparedSet>,
        params: ParamView,
    ): JsonObject = buildJsonObject {
        val lookback = params.int("rcm.lookback_days").toLong()
        put("exercise_id", ex)
        put("fit_date", fitDate.toString())
        put("curve_class", curve)
        put("prev", prev?.let { buildJsonObject { put("k_used", stat(it.kUsed)); put("sd_lnk", stat(it.sdLnk)) } } ?: JsonNull)
        put("pooled_k", pooled?.let { stat(it) } ?: JsonNull)
        put("sets", buildJsonArray {
            own.filter { !it.set.localDate.isAfter(fitDate) && !it.set.localDate.isBefore(fitDate.minusDays(lookback)) }
                .sortedBy { it.set.id }
                .forEach { p ->
                    add(buildJsonObject {
                        put("set_id", p.set.id)
                        put("local_date", p.set.localDate.toString())
                        put("set_kind", p.set.setKind)
                        put("w_sys", p.set.wSys?.let { kg(it) } ?: JsonNull)
                        put("n_eff", rep(p.q!!.nEff))
                        put("q_true", rep(p.q.qTrue))
                        put("sigma_q", stat(p.q.sigmaQ))
                        put("failure", p.q.failure)
                        put("fatigue_f", JsonNumbers.grid(Grid.ratio(p.fatigueF), GridKind.RATIO))
                        put("estimator_class", p.estimatorClass.kind.code)
                        put("rir", p.set.rirReported?.let { rep(it) } ?: JsonNull)
                    })
                }
        })
    }

    private fun setJson(s: LoggedSet): JsonObject = buildJsonObject {
        put("id", s.id)
        put("exercise_id", s.exerciseId)
        put("side", s.side)
        put("performed_at", s.performedAt.toString())
        put("set_kind", s.setKind)
        put("contraction", s.contraction)
        put("load_mode", s.loadMode.code)
        put("w_sys", s.wSys?.let { kg(it) } ?: JsonNull)
        put("reps", JsonNumbers.int(s.reps.toLong()))
        put("rir", s.rirReported?.let { rep(it) } ?: JsonNull)
        put("rir_lower_bound", s.rirLowerBound)
        put("failed_rep", s.failedRep)
        put("reason_code", s.reasonCode?.let { JsonPrimitive(it) } ?: JsonNull)
        put("calib_extra_reps", s.calibExtraReps?.let { JsonNumbers.int(it.toLong()) } ?: JsonNull)
        put("target_reps", s.targetReps?.let { JsonNumbers.int(it.toLong()) } ?: JsonNull)
        put("q_star", s.qStar?.let { rep(it) } ?: JsonNull)
        put("rest_before_s", s.restBeforeS?.let { JsonNumbers.grid(Grid.s(it), GridKind.S) } ?: JsonNull)
        put("planned_rest_before_s", s.plannedRestBeforeS?.let { JsonNumbers.grid(Grid.s(it), GridKind.S) } ?: JsonNull)
    }

    private fun stat(x: Double) = JsonNumbers.grid(Grid.stat(x), GridKind.STAT)
    private fun rep(x: Double) = JsonNumbers.grid(Grid.rep(x), GridKind.REP)
    private fun kg(x: Double) = JsonNumbers.grid(Grid.kg(x), GridKind.KG)
}
