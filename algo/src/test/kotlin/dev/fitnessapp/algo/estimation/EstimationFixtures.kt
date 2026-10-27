package dev.fitnessapp.algo.estimation

import dev.fitnessapp.algo.core.load.LoadMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Builders for the estimator tests (ALGORITHMS.md D1 context: the weighted pull-up of 2026-W41). */
object EstimationFixtures {
    val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

    val WPU = ExerciseFacts(
        exerciseId = "weighted_pullup",
        curveClass = CurveClass.UPPER_COMPOUND,
        fBw = 1.0,
        implementBaseKg = 0.0,
        groupCoeffs = sortedMapOf("lats" to 1.0, "biceps" to 0.5, "upper_back" to 0.75),
    )
    val LEG_PRESS = ExerciseFacts(
        exerciseId = "leg_press_45",
        curveClass = CurveClass.LOWER_MACHINE_COMPOUND,
        fBw = 0.0,
        implementBaseKg = 0.0,
        groupCoeffs = sortedMapOf("quads" to 1.0, "glutes" to 0.75),
    )
    val FACTS = sortedMapOf(WPU.exerciseId to WPU, LEG_PRESS.exerciseId to LEG_PRESS)

    fun set(
        id: String,
        at: String,
        wSys: Double?,
        reps: Int,
        rir: Double?,
        exercise: String = WPU.exerciseId,
        side: String = "both",
        setKind: String = "working",
        contraction: String = "dynamic",
        loadMode: LoadMode = LoadMode.BODYWEIGHT_PLUS_EXTERNAL,
        restBeforeS: Double? = null,
        reasonCode: String? = null,
        lowerBound: Boolean = false,
        failedRep: Boolean = false,
        fingerAssisted: Boolean = false,
        session: String = "s1",
        targetReps: Int? = null,
    ): LoggedSet {
        val instant = Instant.parse(at)
        return LoggedSet(
            id = id,
            sessionId = session,
            exerciseId = exercise,
            side = side,
            performedAt = instant,
            localDate = LocalDate.ofInstant(instant, ZONE),
            setKind = setKind,
            contraction = contraction,
            loadMode = loadMode,
            wSys = wSys,
            reps = reps,
            rirReported = rir,
            rirLowerBound = lowerBound,
            failedRep = failedRep,
            fingerAssisted = fingerAssisted,
            reasonCode = reasonCode,
            targetReps = targetReps,
            restBeforeS = restBeforeS,
        )
    }
}
