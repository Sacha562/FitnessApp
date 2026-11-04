package dev.fitnessapp.data.engine

import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.dao.FinishedSetRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** SSE-1's per-set output (B6.5, C3): the engine e1RM in system kg, the added kg and the REASON-1 estimator class. */
data class SetE1rm(val e1rmKg: Double, val e1rmAddedKg: Double?, val estimatorClass: String)

/**
 * A logged level after one session: SSE-1's e1RM level in system kg or HOLD-1's seconds, each with its 80 % interval,
 * and SSE-1's slope `g_per_week` (ln per week). [bodyweightKg] is the session's snapshot.
 */
data class LevelRow(
    val sessionId: String,
    val startedAtMs: Long,
    val exerciseId: String,
    val side: String,
    val m: Double,
    val lo: Double,
    val hi: Double,
    val gPerWeek: Double?,
    val bodyweightKg: Double?,
)

/** Everything You → Progress, the Summary's bests and the mesocycle start read (D86): sets and engine-logged values. */
data class ProgressFacts(
    val sets: List<FinishedSetRow>,
    val e1rm: Map<String, SetE1rm>,
    val sse: List<LevelRow>,
    val hold: List<LevelRow>,
)

/**
 * Reads the finished sessions' sets with the engine-logged estimates for `ui.progress` (ALGORITHMS_PLANNING 19.6). The
 * values are the newest `estimation.sse` and `estimation.hold` snapshots of each session; nothing is recomputed (C3).
 */
class ProgressRepository(private val db: FitnessDatabase) {
    suspend fun facts(): ProgressFacts {
        val sets = db.setLogs().finishedSets()
        val e1rm = HashMap<String, SetE1rm>()
        val sse = ArrayList<LevelRow>()
        for (r in newestPerSession(db.recommendations().sessionSnapshots("estimation.sse"))) {
            val o = Json.parseToJsonElement(r.outputs).jsonObject
            o["sets"]?.jsonArray?.forEach { e ->
                val x = e.jsonObject
                val kg = x.num("e1rm_kg") ?: return@forEach
                e1rm[x.str("set_id")] = SetE1rm(kg, x.num("e1rm_added_kg"), x.str("estimator_class"))
            }
            o.getValue("states").jsonArray.forEach { e ->
                val st = e.jsonObject
                sse += LevelRow(
                    r.session_id, r.started_at, st.str("exercise_id"), st.str("side"), st.num("m_kg")!!, st.num("ci80_lo_kg")!!,
                    st.num("ci80_hi_kg")!!, st.num("g_per_week"), r.bodyweight_kg?.let { Scaled.toDouble(it, 2) },
                )
            }
        }
        val hold = ArrayList<LevelRow>()
        for (r in newestPerSession(db.recommendations().sessionSnapshots("estimation.hold"))) {
            Json.parseToJsonElement(r.outputs).jsonObject["states"]?.jsonArray?.forEach { e ->
                val st = e.jsonObject
                val t = st.num("t_s") ?: return@forEach
                hold += LevelRow(
                    r.session_id, r.started_at, st.str("exercise_id"), st.str("side"), t, st.num("ci80_lo_s") ?: t, st.num("ci80_hi_s") ?: t,
                    st.num("g_per_week"), r.bodyweight_kg?.let { Scaled.toDouble(it, 2) },
                )
            }
        }
        return ProgressFacts(sets, e1rm, sse, hold)
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.num(k: String): Double? = this[k]?.jsonPrimitive?.content?.takeIf { it != "null" }?.toDouble()
}
