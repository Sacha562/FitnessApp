package dev.fitnessapp.app.timer

import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.repo.LogSetCommand
import dev.fitnessapp.data.repo.PlannedValues
import dev.fitnessapp.data.repo.SessionRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Logs the warm-up item of an interval that ended (ANDROID.md 12.3, UX 11.7.6, D33) as its Done tap would: a warm-up
 * row with the seconds the interval ran, no load and no rest columns (`rest_end_reason = none`).
 */
object IntervalLog {
    suspend fun log(db: FitnessDatabase, sessions: SessionRepository, d: RestEffect.IntervalDone) {
        val t = d.timer
        val seId = t.session_exercise_id ?: return
        val setIndex = t.set_index ?: return
        if (db.sessions().get(t.session_id)?.status != "in_progress") return
        if (db.sessionExercises().get(seId)?.status !in setOf("pending", "active", "deferred")) return
        // Already logged by hand, or by an earlier delivery of the same end: nothing to add.
        if (db.setLogs().bySession(t.session_id).any { it.session_exercise_id == seId && it.set_index == setIndex && it.side == t.side }) return
        sessions.logSet(
            LogSetCommand(
                sessionId = t.session_id, sessionExerciseId = seId, exerciseId = t.exercise_id, setIndex = setIndex, side = t.side,
                setKind = "warmup", load = null, reps = null, durationS = d.durationS, rir = null, rirLowerBound = false,
                holdReserveS = null, holdReserveLowerBound = false, flags = emptyList(), deviationReason = null,
                planned = PlannedValues(durationS = t.prescribed_s.toDouble(), source = "engine"),
                rest = null, startedAtMs = t.start_wall_ms, completedAtMs = d.completedAtWall,
                telemetry = mapOf(
                    "prefill_source" to JsonPrimitive("engine_target"), "side_cap_applied" to JsonPrimitive(false),
                    "deviation_direction" to JsonPrimitive("none"), "deviation_fields" to JsonArray(emptyList()),
                    "input_method" to JsonPrimitive("none"), "taps" to JsonPrimitive(0), "snapped" to JsonPrimitive(false),
                    "log_control" to JsonPrimitive(d.control), "ms_from_card_shown" to JsonPrimitive(d.completedAtWall - t.start_wall_ms),
                ),
            ),
        )
    }
}
