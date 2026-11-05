package dev.fitnessapp.algo.selection.time

import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** TIME-1 calibration (ALGORITHMS_PLANNING 17.4, D93): per-session medians, the EWMA over sessions, snap and clamp. */
class TimeCalibrationTest {
    private val params = TestParams.defaults()

    private class Builder(private val session: String) {
        val sets = ArrayList<CalibSet>()
        var t = 0L

        /** One row of exercise [ex], [afterS] seconds after the previous row, 10 reps, 60 s setup. */
        fun row(ex: String, afterS: Long, rest: Double? = null, planned: Double? = null, reps: Double = 10.0) {
            t += afterS * 1000
            sets += CalibSet("$session-${sets.size}", ex, t, rest, planned, TimeItem(ex, 1, 0.0, 60.0, reps = reps))
        }
    }

    /**
     * Exercises A, B and C with three sets each. The rests after the first set of each exercise have the ratios
     * [ratios] (planned 100 s); the switches A→B and B→C are [gapAB] and [gapBC] seconds.
     */
    private fun session(id: String, start: Long, ratios: List<Double>, gapAB: Long, gapBC: Long): CalibSession {
        val b = Builder(id)
        var i = 0
        b.row("A", 0)
        repeat(2) { b.row("A", 120, ratios[i] * 100.0, 100.0).also { i++ } }
        b.t += gapAB * 1000
        b.row("B", 0)
        repeat(2) { b.row("B", 120, ratios[i] * 100.0, 100.0).also { i++ } }
        b.t += gapBC * 1000
        b.row("C", 0)
        repeat(2) { b.row("C", 120, ratios[i] * 100.0, 100.0).also { i++ } }
        return CalibSession(id, start, b.sets)
    }

    private val typical = listOf(1.5, 1.4, 1.6, 1.3, 1.45, 1.45)

    @Test
    fun `session observations are medians of rest ratios and of changeovers minus work and setup`() {
        val s = session("s1", 0, typical, gapAB = 300, gapBC = 260)
        TimeCalibration.restObservation(s, params)!! shouldBe (1.45 plusOrMinus 1e-9)
        // 300 - 35 (10 reps x 3 s + 5 s) - 60 setup = 205; 260 - 95 = 165; median 185
        TimeCalibration.switchObservation(s, params) shouldBe 185.0
    }

    @Test
    fun `defaults stay below three counted sessions`() {
        val r = TimeCalibration.fit(listOf(session("s1", 0, typical, 300, 260), session("s2", 1, typical, 300, 260)), params)
        r.restRatio shouldBe 1.0
        r.transitionS shouldBe 60.0
        r.overrides() shouldBe emptyMap()
        TimeCalibration.apply(params, r).hash shouldBe params.hash
    }

    @Test
    fun `EWMA over sessions from the default, snapped to 0_05 and 5 s`() {
        val sessions = (1..3).map { session("s$it", it.toLong(), typical, 300, 260) }
        val r = TimeCalibration.fit(sessions.reversed(), params)
        // rest: 1 -> 1.135 -> 1.2295 -> 1.29565 -> 1.30; transition: 60 -> 97.5 -> 123.75 -> 142.125 -> 140
        r.restRatio shouldBe 1.3
        r.transitionS shouldBe 140.0
        r.restObservations shouldBe listOf(1.45, 1.45, 1.45)
        r.overrides() shouldBe mapOf("time.rest_ratio" to 1.3, "time.transition_s" to 140.0)
    }

    @Test
    fun `values are clamped to the declared range`() {
        val slow = listOf(5.0, 5.0, 5.0, 5.0, 5.0, 5.0)
        val r = TimeCalibration.fit((1..3).map { session("s$it", it.toLong(), slow, 2000, 2000) }, params)
        r.restRatio shouldBe 3.0
        r.transitionS shouldBe 300.0
    }

    @Test
    fun `a session with too few rests or switches does not count`() {
        val b = Builder("x")
        b.row("A", 0)
        b.row("A", 120, 150.0, 100.0)
        b.row("A", 120, null, null) // the second side of a pair: no rest of its own
        b.row("B", 300)
        val s = CalibSession("x", 0, b.sets)
        TimeCalibration.restObservation(s, params) shouldBe null
        TimeCalibration.switchObservation(s, params) shouldBe null
    }

    @Test
    fun `coming back to an exercise is not a changeover`() {
        val b = Builder("x")
        b.row("A", 0)
        b.row("B", 200) // 200 - 95 = 105
        b.row("A", 900) // back to A: not counted
        b.row("C", 300) // 300 - 95 = 205
        TimeCalibration.switchObservation(CalibSession("x", 0, b.sets), params) shouldBe 155.0
    }

    @Test
    fun `the overlay changes the two keys, the hash and every TIME-1 estimate`() {
        val r = TimeCalibration.fit((1..3).map { session("s$it", it.toLong(), typical, 300, 260) }, params)
        val cal = TimeCalibration.apply(params, r)
        cal.hash shouldNotBe params.hash
        cal.double("time.rest_ratio") shouldBe 1.3
        cal.double("time.transition_s") shouldBe 140.0
        cal.double("time.tempo_s") shouldBe params.double("time.tempo_s")
        val item = TimeItem("x", 3, 90.0, 30.0, reps = 10.0)
        TimeModel.exerciseSeconds(item, params) shouldBe 375.0 // 30 + 3 x (35 + 90) - 90 + 60
        TimeModel.exerciseSeconds(item, cal) shouldBe 509.0 // 30 + 3 x (35 + 117) - 117 + 140
        (TimeModel.estDose(mapOf("quads" to 3.0), false, cal) > TimeModel.estDose(mapOf("quads" to 3.0), false, params)) shouldBe true
    }
}
