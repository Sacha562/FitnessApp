package dev.fitnessapp.algo.prescription.autoreg

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.JsonNumbers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `autoreg.suggestion` outputs (ALGORITHMS.md B13.6; review keys of D5): the trigger, deltas, proposal and hysteresis numbers. */
object AutoregJson {
    fun result(r: AutoregResult): JsonObject = buildJsonObject {
        put("trigger_rule", r.trigger?.let { JsonPrimitive(it) } ?: JsonNull)
        put("deltas", JsonArray(r.deltas.map { d -> d?.let { rep(it) } ?: JsonNull }))
        put("delta_ex", JsonNumbers.grid(Grid.ratio(r.deltaEx), GridKind.RATIO))
        put("kind", r.kind?.code?.let { JsonPrimitive(it) } ?: JsonNull)
        put("stop_reason", r.stopReason?.let { JsonPrimitive(it) } ?: JsonNull)
        put("proposed", buildJsonObject {
            put("load_kg", r.wNew?.let { kg(it) } ?: JsonNull)
            put("sets", buildJsonArray {
                r.proposed.forEach { s ->
                    add(buildJsonObject {
                        put("set_index", JsonNumbers.int(s.setIndex.toLong()))
                        put("side", s.side)
                        put("target_reps", s.targetReps?.let { JsonNumbers.int(it.toLong()) } ?: JsonNull)
                        put("effective_load_kg", s.wSys?.let { kg(it) } ?: JsonNull)
                        put("rir_pred", s.rirPred?.let { rep(it) } ?: JsonNull)
                    })
                }
            })
        })
        put("w_cur", r.wCur?.let { kg(it) } ?: JsonNull)
        put("rir_next_cur", r.rirNextCur?.let { rep(it) } ?: JsonNull)
        put("rir_next_new", r.rirNextNew?.let { rep(it) } ?: JsonNull)
        put("hysteresis", r.hysteresis?.let { h ->
            buildJsonObject {
                put("q_star", rep(h.qStar))
                put("rir_next_cur", rep(h.rirNextCur))
                put("deviation", rep(h.deviation))
                put("safety_drop", h.safetyDrop)
                put("load_change_allowed", h.loadChangeAllowed)
                put("rir_next_obs", h.rirNextObs?.let { rep(it) } ?: JsonNull)
            }
        } ?: JsonNull)
        put("card", r.card)
        put("trace", JsonArray(r.trace.map { JsonPrimitive(it) }))
    }

    private fun rep(x: Double): JsonElement = JsonNumbers.grid(Grid.rep(x), GridKind.REP)
    private fun kg(x: Double): JsonElement = JsonNumbers.grid(Grid.kg(x), GridKind.KG)
}
