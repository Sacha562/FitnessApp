package dev.fitnessapp.algo.selection.time

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.ParamView

/**
 * One logged set as TIME-1's calibration reads it (17.4, D93). [work] describes the logged work of this row alone (one
 * side of an L/R pair is its own row, so `unilateralPair` is false) and carries the exercise's setup seconds in
 * `setupS`; its `sets` and `restS` are not read. [restBeforeS] is the measured rest before the set and [restPlannedS]
 * the rest timer's planned seconds (C55); either may be missing.
 */
data class CalibSet(
    val id: String,
    val sessionExerciseId: String,
    val completedAtMs: Long,
    val restBeforeS: Double?,
    val restPlannedS: Double?,
    val work: TimeItem,
)

/** A finished session's logged sets, in any order. */
data class CalibSession(val id: String, val startedAtMs: Long, val sets: List<CalibSet>)

/**
 * The user's calibrated pace (17.4, D93): the rest ratio and the changeover seconds after clamping and snapping, the
 * per-session observations that fed them (oldest first) and whether each value has left its default.
 */
data class TimeCalibrationResult(
    val restRatio: Double,
    val transitionS: Double,
    val restObservations: List<Double>,
    val switchObservations: List<Double>,
    val restCalibrated: Boolean,
    val transitionCalibrated: Boolean,
) {
    /** The params keys this calibration sets, only those that left their default. */
    fun overrides(): Map<String, Double> = buildMap {
        if (restCalibrated) put("time.rest_ratio", restRatio)
        if (transitionCalibrated) put("time.transition_s", transitionS)
    }
}

/**
 * TIME-1 calibration (ALGORITHMS_PLANNING 17.4, C19, D93), part of `time@1.1.0`. Per finished session it observes the
 * median rest ratio over consecutive sets of the same session exercise and the median changeover over exercise
 * switches (gap minus the first set's work and the exercise's setup); over the sessions it runs the EWMA of
 * `time.ewma_alpha` from the params default, keeps the default below `time.calib_min_exposures` sessions, and clamps
 * and snaps the result. [apply] overlays the result on the params, so every TIME-1 caller uses the user's pace.
 */
object TimeCalibration {
    /** The rest ratio of one session, or null with fewer than `time.calib_min_rests` measured rests. */
    fun restObservation(session: CalibSession, params: ParamView): Double? {
        val sets = ordered(session)
        val ratios = ArrayList<Double>()
        for (i in 1 until sets.size) {
            val a = sets[i - 1]
            val b = sets[i]
            if (a.sessionExerciseId != b.sessionExerciseId) continue
            val actual = b.restBeforeS ?: continue
            val planned = b.restPlannedS ?: continue
            if (planned <= 0.0 || actual < 0.0) continue
            ratios += actual / planned
        }
        if (ratios.size < params.int("time.calib_min_rests")) return null
        return median(ratios)
    }

    /** The changeover seconds of one session, or null with fewer than `time.calib_min_switches` switches. */
    fun switchObservation(session: CalibSession, params: ParamView): Double? {
        val sets = ordered(session)
        val seen = java.util.TreeSet<String>()
        val gaps = ArrayList<Double>()
        sets.firstOrNull()?.let { seen += it.sessionExerciseId }
        for (i in 1 until sets.size) {
            val a = sets[i - 1]
            val b = sets[i]
            if (a.sessionExerciseId == b.sessionExerciseId) continue
            // Only the first time an exercise starts: coming back to an exercise after another is not a changeover.
            if (!seen.add(b.sessionExerciseId)) continue
            val gapS = (b.completedAtMs - a.completedAtMs) / 1000.0
            gaps += gapS - TimeModel.work(b.work.copy(unilateralPair = false), params) - b.work.setupS
        }
        if (gaps.size < params.int("time.calib_min_switches")) return null
        return median(gaps)
    }

    /** The calibration over [sessions] (any order; they are taken by start time, then id) with the default [params]. */
    fun fit(sessions: List<CalibSession>, params: ParamView): TimeCalibrationResult {
        val ordered = sessions.sortedWith(compareBy<CalibSession> { it.startedAtMs }.thenBy { it.id })
        val rests = ordered.mapNotNull { restObservation(it, params) }
        val switches = ordered.mapNotNull { switchObservation(it, params) }
        val minExposures = params.int("time.calib_min_exposures")
        val restDefault = params.double("time.rest_ratio")
        val transitionDefault = params.double("time.transition_s")
        val restCalibrated = rests.size >= minExposures
        val transitionCalibrated = switches.size >= minExposures
        val restRatio = if (restCalibrated) {
            snap(ewma(restDefault, rests, params), "time.rest_ratio", params.double("time.calib_rest_ratio_step"))
        } else {
            restDefault
        }
        val transitionS = if (transitionCalibrated) {
            snap(ewma(transitionDefault, switches, params), "time.transition_s", params.double("time.calib_transition_step_s"))
        } else {
            transitionDefault
        }
        return TimeCalibrationResult(
            restRatio, transitionS, rests.map(Grid::ratio), switches.map(Grid::s),
            restCalibrated && restRatio != restDefault, transitionCalibrated && transitionS != transitionDefault,
        )
    }

    /** [params] with the calibrated values in place of the defaults; the same values (and hash) when nothing moved. */
    fun apply(params: ParamView, result: TimeCalibrationResult): ParamView = params.withNumbers(result.overrides())

    private fun ordered(session: CalibSession): List<CalibSet> =
        session.sets.sortedWith(compareBy<CalibSet> { it.completedAtMs }.thenBy { it.id })

    private fun ewma(prior: Double, observations: List<Double>, params: ParamView): Double {
        val a = params.double("time.ewma_alpha")
        var v = prior
        for (x in observations) v += a * (x - v)
        return v
    }

    /** Clamped to the range [key] declares (23.17) and rounded to the nearest multiple of [step], ties to even. */
    private fun snap(x: Double, key: String, step: Double): Double {
        val spec = TimeModel.paramSpecs.first { it.key == key }
        val lo = requireNotNull(spec.min) { "$key has no lower bound" }
        val hi = requireNotNull(spec.max) { "$key has no upper bound" }
        val clamped = x.coerceIn(lo, hi)
        return Grid.ratio(kotlin.math.round(clamped / step) * step).coerceIn(lo, hi)
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
