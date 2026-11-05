package dev.fitnessapp.data.engine

import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.time.CalibSession
import dev.fitnessapp.algo.selection.time.CalibSet
import dev.fitnessapp.algo.selection.time.TimeCalibration
import dev.fitnessapp.algo.selection.time.TimeCalibrationResult
import dev.fitnessapp.algo.selection.time.TimeItem
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.db.Scaled

/**
 * TIME-1's calibration inputs on the phone (ALGORITHMS_PLANNING 17.4, D93): every finished gym session with its logged
 * sets, each set's measured and planned rest (C55) and its logged work with the exercise's setup seconds. The result
 * is a pure function of these facts ([TimeCalibration.fit]) and is rebuilt whenever they change.
 */
class TimeCalibrationFacts(
    private val db: FitnessDatabase,
    private val catalog: CatalogRepository,
) {
    suspend fun sessions(): List<CalibSession> =
        db.sessions().finishedChronological().filter { it.kind == "gym" }.map { s ->
            CalibSession(s.id, s.started_at!!, db.setLogs().bySession(s.id).map(::set))
        }

    suspend fun fit(defaults: ParamView): TimeCalibrationResult = TimeCalibration.fit(sessions(), defaults.fork())

    private fun set(r: SetLogEntity): CalibSet {
        val ex = catalog.byId(r.exercise_id)
        val hold = ex?.contraction == "isometric" || ex?.contraction == "hang"
        val work = TimeItem(
            key = r.session_exercise_id, sets = 1, restS = 0.0, setupS = ex?.setupS?.toDouble() ?: 0.0,
            reps = if (hold) null else (r.reps ?: 0).toDouble(),
            eccS = if (ex?.contraction == "eccentric_only") r.ecc_s_actual?.let { Scaled.toDouble(it, 1) } else null,
            holdS = if (hold) r.duration_s?.let { Scaled.toDouble(it, 1) } ?: 0.0 else null,
        )
        return CalibSet(
            r.id, r.session_exercise_id, r.completed_at,
            r.rest_before_s?.let { Scaled.toDouble(it, 1) }, r.rest_timer_planned_s?.toDouble(), work,
        )
    }
}
