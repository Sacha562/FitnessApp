package dev.fitnessapp.algo.estimation

import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.trace.TraceStep
import dev.fitnessapp.algo.estimation.fatigue.FatigueFit
import dev.fitnessapp.algo.estimation.hold.HoldResult
import dev.fitnessapp.algo.estimation.rcm.RcmFit
import dev.fitnessapp.algo.estimation.rircal.RirCalUpdate
import dev.fitnessapp.algo.estimation.sse.SseResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.SortedMap

/**
 * The `outputs` of the estimation snapshots with the DATA_MODEL 10.2 keys (ALGORITHMS.md B4.6, B5.1, B6.5, B7.5,
 * B8.4), every number through its grid's canonical text. Null values are written as JSON null.
 */
object EstimationJson {
    fun sse(r: SseResult): JsonObject = buildJsonObject {
        put("sets", buildJsonArray {
            r.sets.forEach { s ->
                add(buildJsonObject {
                    put("set_id", s.setId)
                    num("q_true", s.qTrue, GridKind.REP)
                    num("n_eff", s.nEff, GridKind.REP)
                    num("n_fresh", s.nFresh, GridKind.REP)
                    num("fatigue_f", s.fatigueF, GridKind.RATIO)
                    num("z", s.z, GridKind.STAT)
                    num("s", s.s, GridKind.STAT)
                    put("estimator_class", s.estimatorClass)
                    num("e1rm_kg", s.e1rmKg, GridKind.KG)
                    num("e1rm_added_kg", s.e1rmAddedKg, GridKind.KG)
                    num("k_used", s.kUsed, GridKind.STAT)
                    num("residual", s.residual, GridKind.STAT)
                    num("n_eff_pred", s.nEffPred, GridKind.REP)
                    num("z_pred_lo", s.zPredLo, GridKind.STAT)
                    num("z_pred_hi", s.zPredHi, GridKind.STAT)
                })
            }
        })
        put("states", buildJsonArray {
            r.states.forEach { s ->
                add(buildJsonObject {
                    put("exercise_id", s.exerciseId)
                    put("side", s.side)
                    num("m_kg", s.mKg, GridKind.KG)
                    num("ci80_lo_kg", s.ci80LoKg, GridKind.KG)
                    num("ci80_hi_kg", s.ci80HiKg, GridKind.KG)
                    num("g_per_week", s.gPerWeek, GridKind.STAT)
                    num("l_prior", s.lPrior, GridKind.STAT)
                    num("p_ll_prior", s.pLLPrior, GridKind.STAT)
                    put("huber_applied", s.huberApplied)
                    put("lb_applied", s.lbApplied)
                })
            }
        })
    }

    /** The innovation statistics of SSE-1 (B6.5: "in the trace") and the trace steps. */
    fun sseTrace(r: SseResult): JsonObject = buildJsonObject {
        put("innovations", buildJsonArray {
            r.states.forEach { s ->
                add(buildJsonObject {
                    put("exercise_id", s.exerciseId)
                    put("side", s.side)
                    num("nu", s.nu, GridKind.STAT)
                    num("s", s.innovationS, GridKind.STAT)
                    num("k0", s.k0, GridKind.STAT)
                    num("k1", s.k1, GridKind.STAT)
                })
            }
        })
        put("steps", steps(r.trace))
    }

    fun rcm(f: RcmFit): JsonObject = buildJsonObject {
        num("k_prev", f.kPrev, GridKind.STAT)
        num("k_hat", f.kHat, GridKind.STAT)
        num("k_used", f.state.kUsed, GridKind.STAT)
        num("sd_lnk", f.state.sdLnk, GridKind.STAT)
        put("m", JsonNumbers.int(f.m.toLong()))
        put("n_sets", JsonNumbers.int(f.nSets.toLong()))
        put("rate_limited", f.rateLimited)
        put("pooled", f.pooled)
        num("j_min", f.jMin, GridKind.STAT)
        num("j_k0", f.jK0, GridKind.STAT)
        put("sets", buildJsonArray {
            f.sets.forEach { s ->
                add(buildJsonObject {
                    put("set_id", s.setId)
                    num("w_sys", s.wSys, GridKind.KG)
                    num("n_eff", s.nEff, GridKind.REP)
                    num("fatigue_f", s.fatigueF, GridKind.RATIO)
                    num("n_fresh", s.nFresh, GridKind.REP)
                    put("window", JsonNumbers.int(s.window.toLong()))
                    num("residual", s.residual, GridKind.STAT)
                })
            }
        })
    }

    fun rircal(u: RirCalUpdate): JsonObject = buildJsonObject {
        num("b_before", u.bBefore, GridKind.STAT)
        num("b_after", u.bAfter, GridKind.STAT)
        num("s_q_before", u.sQBefore, GridKind.STAT)
        num("s_q_after", u.sQAfter, GridKind.STAT)
        num("e", u.e, GridKind.STAT)
        put("type", u.type)
        put("class", u.curveClass?.let { JsonPrimitive(it) } ?: JsonNull)
        put("n_c", JsonNumbers.int(u.nC.toLong()))
    }

    fun fatigue(f: FatigueFit): JsonObject = buildJsonObject {
        num("c_hat", f.cHat, GridKind.RATIO)
        num("c_used", f.cUsed, GridKind.RATIO)
        num("d_hat", f.dHat, GridKind.RATIO)
        num("d_used", f.dUsed, GridKind.RATIO)
        put("m", JsonNumbers.int(f.m.toLong()))
        put("resid_by_rest_bucket", buckets(f.residByRestBucket))
        put("resid_by_rir_bucket", buckets(f.residByRirBucket))
        put("resid_by_set_index", buckets(f.residBySetIndex))
    }

    fun hold(r: HoldResult): JsonObject = buildJsonObject {
        put("sets", buildJsonArray {
            r.sets.forEach { s ->
                add(buildJsonObject {
                    put("set_id", s.setId)
                    num("t_done", s.tDone, GridKind.S)
                    num("s_res", s.sRes, GridKind.S)
                    num("fatigue_f", s.fatigueF, GridKind.RATIO)
                    num("w_sys", s.wSys, GridKind.KG)
                    num("z", s.z, GridKind.STAT)
                    num("sigma", s.sigma, GridKind.STAT)
                    put("estimator_class", s.estimatorClass)
                })
            }
        })
        put("states", buildJsonArray {
            r.states.forEach { s ->
                add(buildJsonObject {
                    put("exercise_id", s.exerciseId)
                    put("side", s.side)
                    put("hold_class", s.holdClass)
                    num("t_s", s.tS, GridKind.S)
                    num("ci80_lo_s", s.ci80LoS, GridKind.S)
                    num("ci80_hi_s", s.ci80HiS, GridKind.S)
                    num("g_per_week", s.gPerWeek, GridKind.STAT)
                    num("beta", s.beta, GridKind.STAT)
                    num("w_ref_kg", s.wRefKg, GridKind.KG)
                })
            }
        })
    }

    fun steps(trace: List<TraceStep>): JsonArray = buildJsonArray {
        trace.forEach { t ->
            add(buildJsonObject {
                put("code", t.code)
                put("entity", t.entity?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }
    }

    private fun buckets(m: SortedMap<String, Double>): JsonElement =
        buildJsonObject { m.forEach { (k, v) -> put(k, JsonNumbers.grid(v, GridKind.REP)) } }

    private fun JsonObjectBuilder.num(key: String, x: Double?, kind: GridKind) {
        put(key, x?.let { JsonNumbers.grid(it, kind) } ?: JsonNull)
    }
}
