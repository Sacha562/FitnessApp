package dev.fitnessapp.data.engine

import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.dao.SseSnapshotRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** SSE-1's level of one exercise and side after one session, as logged (B6.5): system kg with its 80 % interval. */
data class E1rmPoint(
    val sessionId: String,
    val startedAtMs: Long,
    val side: String,
    val mKg: Double,
    val ci80LoKg: Double,
    val ci80HiKg: Double,
    val bodyweightKg: Double?,
)

/**
 * Reads the engine-logged estimates back for display. The UI never recomputes an e1RM (C3): every value comes from
 * the newest `estimation.sse` snapshot of each session.
 */
class EstimateRepository(private val db: FitnessDatabase) {
    fun observeE1rm(exerciseId: String): Flow<List<E1rmPoint>> = db.recommendations().observeSseSnapshots().map { rows ->
        newestPerSession(rows).flatMap { r ->
            Json.parseToJsonElement(r.outputs).jsonObject.getValue("states").jsonArray
                .map { it.jsonObject }
                .filter { it.getValue("exercise_id").jsonPrimitive.content == exerciseId }
                .map { st ->
                    E1rmPoint(
                        r.session_id, r.started_at, st.getValue("side").jsonPrimitive.content,
                        st.getValue("m_kg").jsonPrimitive.content.toDouble(),
                        st.getValue("ci80_lo_kg").jsonPrimitive.content.toDouble(),
                        st.getValue("ci80_hi_kg").jsonPrimitive.content.toDouble(),
                        r.bodyweight_kg?.let { Scaled.toDouble(it, 2) },
                    )
                }
        }
    }

}

/** One row per session: the one no other row of the session supersedes (an edited past session has several). */
internal fun newestPerSession(rows: List<SseSnapshotRow>): List<SseSnapshotRow> =
    rows.groupBy { it.session_id }.values.mapNotNull { group ->
        val superseded = group.mapNotNull { it.supersedes_id }.toSet()
        group.filter { it.id !in superseded }.maxByOrNull { it.id }
    }.sortedWith(compareBy<SseSnapshotRow> { it.started_at }.thenBy { it.session_id })
