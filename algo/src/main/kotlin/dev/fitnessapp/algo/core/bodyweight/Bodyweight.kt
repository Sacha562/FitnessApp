package dev.fitnessapp.algo.core.bodyweight

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/** One `metric_observations` row with `metric_code = 'bw'` (not deleted). [localDate] is in the profile zone. */
data class BwObservation(
    val id: String,
    val observedAt: Instant,
    val localDate: LocalDate,
    val valueKg: Double,
    val sessionId: String? = null,
)

/** `workout_sessions.bodyweight_source` values. */
enum class BwSource(val code: String) { TEST_WEIGHIN("test_weighin"), EWMA("ewma"), FALLBACK("fallback"), NONE("none") }

data class BwSnapshot(
    val valueKg: Double?,
    val source: BwSource,
    val stale: Boolean,
    val lastObservationDate: LocalDate?,
)

/** The session facts BW-1 reads. [firstSetAt] null = no set logged yet. */
data class BwSession(
    val id: String,
    val kind: String,
    val start: Instant,
    val localDate: LocalDate,
    val firstSetAt: Instant? = null,
)

/**
 * BW-1 (ALGORITHMS.md B3, C32): one bodyweight function. Smoothed (EWMA over day means) for prescriptions
 * and estimation, the raw test-day weigh-in for gates, and a staleness flag for the weigh-in prompt.
 */
object Bodyweight : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("bodyweight")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("bodyweight.ewma_alpha", "dimensionless", 0.05, 1.0),
        ParamSpec.number("bodyweight.max_kg", "kg", 300.0, 300.0),
        ParamSpec.number("bodyweight.min_kg", "kg", 20.0, 20.0),
        ParamSpec.integer("bodyweight.stale_days", "days", 3.0, 60.0),
    )

    /** `BW_ewma(t)`: day means of the valid observations at or before [at], smoothed; null without data. */
    fun ewma(observations: List<BwObservation>, at: Instant, params: ParamView): Double? {
        val days = dayMeans(valid(observations, params).filter { !it.observedAt.isAfter(at) })
        if (days.isEmpty()) return null
        val alpha = params.double("bodyweight.ewma_alpha")
        var e = days.firstEntry().value
        for (x in days.values.drop(1)) e += alpha * (x - e)
        return Grid.kg(e)
    }

    /** The session snapshot (B3): constant within the session, stored on the session and every set. */
    fun sessionSnapshot(
        session: BwSession,
        observations: List<BwObservation>,
        fallbackKg: Double?,
        params: ParamView,
    ): BwSnapshot {
        val obs = valid(observations, params)
        val upToStart = obs.filter { !it.observedAt.isAfter(session.start) }
        val last = obs.filter { it.sessionId == session.id || !it.observedAt.isAfter(session.start) }
            .maxByOrNull { it.observedAt }?.localDate
        val staleDays = params.int("bodyweight.stale_days")
        val stale = last == null || Week.calendarDays(last, session.localDate) > staleDays

        if (session.kind == "test") {
            val weighIn = obs.filter { it.sessionId == session.id && (session.firstSetAt == null || it.observedAt.isBefore(session.firstSetAt)) }
                .maxWithOrNull(compareBy<BwObservation> { it.observedAt }.thenBy { it.id })
            if (weighIn != null) return BwSnapshot(Grid.kg(weighIn.valueKg), BwSource.TEST_WEIGHIN, false, weighIn.localDate)
        }
        if (upToStart.isNotEmpty()) return BwSnapshot(ewma(obs, session.start, params), BwSource.EWMA, stale, last)
        if (fallbackKg != null) return BwSnapshot(Grid.kg(fallbackKg), BwSource.FALLBACK, stale, last)
        return BwSnapshot(null, BwSource.NONE, stale, last)
    }

    /** `BW_gate`: the raw test weigh-in, or null (the challenge then marks the gate input missing). */
    fun gate(session: BwSession, observations: List<BwObservation>, params: ParamView): Double? {
        val snap = sessionSnapshot(session, observations, null, params)
        return if (snap.source == BwSource.TEST_WEIGHIN) snap.valueKg else null
    }

    private fun valid(observations: List<BwObservation>, params: ParamView): List<BwObservation> {
        val min = params.double("bodyweight.min_kg")
        val max = params.double("bodyweight.max_kg")
        return observations.filter { it.valueKg in min..max }
            .sortedWith(compareBy<BwObservation> { it.observedAt }.thenBy { it.id })
    }

    /** Two weigh-ins on one day count once (their mean). */
    private fun dayMeans(obs: List<BwObservation>): TreeMap<LocalDate, Double> {
        val sums = TreeMap<LocalDate, Pair<Double, Int>>()
        for (o in obs) {
            val (s, n) = sums[o.localDate] ?: (0.0 to 0)
            sums[o.localDate] = (s + o.valueKg) to (n + 1)
        }
        return TreeMap(sums.mapValues { (_, v) -> v.first / v.second })
    }
}
