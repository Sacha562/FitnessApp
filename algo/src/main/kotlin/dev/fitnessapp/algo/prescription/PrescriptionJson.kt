package dev.fitnessapp.algo.prescription

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.prescription.rest.RestAdapt
import dev.fitnessapp.algo.prescription.rest.RestDecision
import dev.fitnessapp.algo.prescription.rx.RxOutput
import dev.fitnessapp.algo.prescription.warmup.WarmupPlan
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `outputs` of the prescription snapshots with the DATA_MODEL 10.2 keys first (ALGORITHMS.md B9.15, B10, B11.4):
 * `rx.prescription`, `warmup.sets`, `rest.adapt`. Every number goes through its grid's canonical text.
 */
object PrescriptionJson {
    fun rx(o: RxOutput): JsonObject = buildJsonObject {
        put("mode", o.mode.code)
        put("sets", buildJsonArray {
            o.sets.forEach { s ->
                add(buildJsonObject {
                    put("set_index", JsonNumbers.int(s.setIndex.toLong()))
                    put("side", s.side)
                    int("target_reps", s.targetReps)
                    num("target_seconds", s.targetSeconds, GridKind.S)
                    num("rir", s.rir, GridKind.REP)
                    num("effective_load_kg", s.wSys, GridKind.KG)
                })
            }
        })
        put("caps_applied", buildJsonArray {
            o.capsApplied.forEach { c ->
                add(buildJsonObject {
                    put("type", c.type)
                    num("limit_w", c.limitW, GridKind.KG)
                    num("unclamped_w", c.unclampedW, GridKind.KG)
                })
            }
        })
        put("rir_pred", buildJsonArray { o.sets.forEach { add(it.rirPred?.let { v -> JsonNumbers.grid(Grid.rep(v), GridKind.REP) } ?: JsonNull) } })
        put("rest_s", buildJsonArray { o.sets.forEach { add(it.restS?.let { v -> JsonNumbers.grid(Grid.s(v), GridKind.S) } ?: JsonNull) } })
        put("scheme", buildJsonObject {
            put("kind", o.scheme.kind.code)
            put("r_lo", JsonNumbers.int(o.scheme.rLo.toLong()))
            put("r_hi", JsonNumbers.int(o.scheme.rHi.toLong()))
            put("r_star", JsonNumbers.int(o.scheme.rStar.toLong()))
            num("q_star", o.scheme.qStar, GridKind.REP)
            put("sources", buildJsonObject { o.scheme.sources.forEach { (k, v) -> put(k, v) } })
        })
        put("double", o.double?.let { d ->
            buildJsonObject {
                put("targets", buildJsonArray { d.targets.forEach { add(JsonNumbers.int(it.toLong())) } })
                put("ext", JsonNumbers.int(d.ext.toLong()))
            }
        } ?: JsonNull)
        put("probe", o.probe)
        put("fail_counter", JsonNumbers.int(o.failCounter.toLong()))
        put("variant_suggestion", o.variantSuggestion?.let { JsonPrimitive(it) } ?: JsonNull)
        put("shadow", o.shadow?.let { rx(it) } ?: JsonNull)
    }

    /** The RX-1 trace (B9.15): the `M_use` parts, the coarse-step figure, the candidate table and the step codes. */
    fun rxTrace(o: RxOutput): JsonObject = buildJsonObject {
        num("m_use", o.mUse, GridKind.KG)
        num("z_c", o.zC, GridKind.STAT)
        num("reps_per_step", o.repsPerStep, GridKind.REP)
        put("candidates", buildJsonArray {
            o.candidates.forEach { c ->
                add(buildJsonObject {
                    num("w", c.w, GridKind.KG)
                    num("cap_fresh", c.capFresh, GridKind.REP)
                    put("reps", buildJsonArray { c.reps.forEach { add(JsonNumbers.int(it.toLong())) } })
                    put("rir", buildJsonArray { c.rir.forEach { add(JsonNumbers.grid(Grid.rep(it), GridKind.REP)) } })
                    num("j", c.j, GridKind.STAT)
                    put("feasible", c.feasible?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        })
        put("steps", buildJsonArray { o.trace.forEach { add(JsonPrimitive(it)) } })
    }

    fun warmup(p: WarmupPlan): JsonObject = buildJsonObject {
        put("tier", p.tier.code)
        num("p_w", p.pW, GridKind.RATIO)
        put("sets", buildJsonArray {
            p.sets.forEach { s ->
                add(buildJsonObject {
                    num("w_sys", s.wSys, GridKind.KG)
                    put("bodyweight", s.bodyweight)
                    put("reps", JsonNumbers.int(s.reps.toLong()))
                    num("rest_s", s.restS, GridKind.S)
                })
            }
        })
    }

    fun rest(d: RestDecision): JsonObject = buildJsonObject {
        num("timer_s", d.timerS, GridKind.S)
        num("between_sides_s", d.betweenSidesS, GridKind.S)
        num("base_s", d.baseS, GridKind.S)
        put("source", d.source.code)
        put("steps", buildJsonArray { d.steps.forEach { add(JsonPrimitive(it)) } })
    }

    fun restAdapt(a: RestAdapt): JsonObject = buildJsonObject {
        put("rest_class", a.restClass.code)
        num("base_old_s", a.baseOldS, GridKind.S)
        num("base_new_s", a.baseNewS, GridKind.S)
        num("early_share", a.earlyShare, GridKind.RATIO)
        num("resid_mean", a.residMean, GridKind.REP)
        put("n_sets", JsonNumbers.int(a.nSets.toLong()))
    }

    private fun JsonObjectBuilder.num(key: String, x: Double?, kind: GridKind) {
        put(key, x?.let { JsonNumbers.grid(Grid.round(it, kind), kind) } ?: JsonNull)
    }

    private fun JsonObjectBuilder.int(key: String, x: Int?) {
        put(key, x?.let { JsonNumbers.int(it.toLong()) } ?: JsonNull)
    }
}
