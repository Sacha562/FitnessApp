package dev.fitnessapp.algo.estimation.sse

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.estimation.EstimationFixtures.FACTS
import dev.fitnessapp.algo.estimation.EstimationFixtures.WPU
import dev.fitnessapp.algo.estimation.EstimationFixtures.set
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** B6.4 drift rule (D123): two sessions in a row beyond `sse.drift_gate_sd` on the same side widen `P_LL`. */
class SseDriftTest {
    private val params = TestParams.defaults()
    private val sdDay = 0.035

    /** One face pull session of 2026-10/11 (user review, D123): the logged per-set `z` and `s` of its normal sets. */
    private data class Session(val start: String, val normal: List<Pair<Double, Double>>, val lowerBounds: List<Pair<Double, Double>> = emptyList())

    private val facePull = listOf(
        Session("2026-10-31T08:44:53.198Z", listOf(2.74078570245 to 0.0996630991884), listOf(2.69183035548 to 0.10066355348)),
        Session("2026-11-04T19:06:19.072Z", listOf(2.70858108866 to 0.0997951001519), listOf(2.67166593294 to 0.0991512118911)),
        Session("2026-11-08T19:48:53.919Z", listOf(2.78377741206 to 0.057623299328, 2.92634155419 to 0.0908895595028)),
        Session("2026-11-11T19:41:00.969Z", listOf(2.91671987035 to 0.0796221023462, 3.01537002659 to 0.100178775876)),
        Session("2026-11-15T13:43:57.329Z", listOf(2.95145087896 to 0.0852158135094, 3.17621193525 to 0.0962455666552)),
        Session("2026-11-20T09:09:21.812Z", listOf(3.08898576714 to 0.0763336408934, 3.14188263683 to 0.0892613957068)),
    )

    /** Cold start on 27 October from the session's one normal set (its "5" chip set is a lower bound below it). */
    private val coldStart = Trend.start(2.78156887571, 0.08 * 0.08, Instant.parse("2026-10-27T17:33:41.962Z"), params)

    private fun obs(xs: List<Pair<Double, Double>>) = xs.map { (z, s) -> TrendObs(z, s * s) }

    /** The face pull states after each session, as Sse.update stores them (rounded, `zn_prev` on the STAT grid). */
    private fun replay(drift: Boolean): List<Pair<TrendState, TrendUpdate>> {
        var st = coldStart
        var znPrev: Double? = null
        return facePull.map { s ->
            val at = Instant.parse(s.start)
            val upd = Trend.update(
                Trend.predict(st, at, params), obs(s.normal), obs(s.lowerBounds), sdDay, at, 0, params,
                if (drift) Drift(1.5, 25.0, znPrev) else null,
            )
            st = upd.state.rounded()
            znPrev = upd.zn?.let { Grid.stat(it) } ?: znPrev
            st to upd
        }
    }

    @Test
    fun `without the rule the replay reproduces the logged face pull states`() {
        val r = replay(drift = false)
        r.map { Grid.kg(Det.exp(it.first.l)) } shouldBe listOf(15.9, 15.66, 16.2, 16.91, 17.7, 18.54)
        r.forEach { it.second.driftLambda.shouldBeNull() }
    }

    @Test
    fun `the face pull opens up on 15 November and catches up`() {
        val r = replay(drift = true)
        r.map { it.second.zn!! }.let { zn ->
            zn[3] shouldBe (2.05 plusOrMinus 0.005)
            zn[4] shouldBe (2.72 plusOrMinus 0.005)
        }
        // Only 15 November: 11 November's predecessor (8 November, zn 0.90) was inside the gate.
        r.map { it.second.driftLambda } shouldBe listOf(null, null, null, null, 25.0, null)
        Grid.kg(Det.exp(r[3].first.l)) shouldBe 16.91
        Grid.kg(Det.exp(r[4].first.l)) shouldBe (20.50 plusOrMinus 0.005)
        Grid.kg(Det.exp(r[5].first.l)) shouldBe (21.47 plusOrMinus 0.005)
        // The widened prior no longer trips the Huber gate on 20 November (it did without the rule).
        r[5].second.huberApplied shouldBe false
        replay(drift = false)[5].second.huberApplied shouldBe true
    }

    private val prior = TrendState(Det.ln(100.0), 0.0, 0.0012, 0.0001, 0.005 * 0.005, Instant.parse("2026-11-01T18:00:00Z"), 8)
    private val at = Instant.parse("2026-11-04T18:00:00Z")
    private val surprise = listOf(TrendObs(Det.ln(120.0), 0.08 * 0.08))

    private fun step(znPrev: Double?, max: Double = 25.0) =
        Trend.update(prior, surprise, emptyList(), sdDay, at, 0, params, Drift(1.5, max, znPrev))

    @Test
    fun `one surprise alone or after one on the other side changes nothing`() {
        val plain = Trend.update(prior, surprise, emptyList(), sdDay, at, 0, params)
        (plain.zn!! > 1.5) shouldBe true
        for (prev in listOf(null, 1.2, -2.0)) {
            val u = step(prev)
            u.driftLambda.shouldBeNull()
            u.state shouldBe plain.state
        }
    }

    @Test
    fun `the widening matches the innovation and is capped`() {
        val u = step(2.0, max = 1000.0)
        val nu = Det.ln(120.0) - prior.l
        val r = sdDay * sdDay + 0.08 * 0.08
        u.driftLambda!! shouldBe ((nu * nu - r) / prior.pLL plusOrMinus 1e-12)
        step(2.0, max = 3.0).driftLambda shouldBe 3.0
        // A wider prior moves the level further toward the observation.
        (step(2.0).state.l > Trend.update(prior, surprise, emptyList(), sdDay, at, 0, params).state.l) shouldBe true
    }

    /** GF-SSE-01's session A through Sse.update, with and without the drift keys. */
    private fun sessionA(p: ParamView, znPrev: Double?): SseResult {
        val start = Instant.parse("2026-10-08T17:45:00Z")
        val sets = listOf(2.0, 1.0, 1.0, 0.0).mapIndexed { i, rir ->
            set("a$i", start.plusSeconds(300L + 180L * i).toString(), 93.20, 5, rir, restBeforeS = if (i == 0) null else 150.0)
        }
        val key = SideKey(WPU.exerciseId, "both")
        val prior = SseState(TrendState(Det.ln(109.94), 0.003, 0.0009, 0.0, 0.005 * 0.005, Instant.parse("2026-10-01T17:45:00Z"), 10), 30.63, znPrev)
        val prepared = Sse.prepare(sets, FACTS, RirCal.initial(p), p)
        return Sse.update(prepared, mapOf(key to prior), mapOf(WPU.exerciseId to 30.63), emptyMap(), FACTS, start, 70.7, p)
    }

    @Test
    fun `Sse stores zn_prev and reports the drift flag only with the drift keys`() {
        val key = SideKey(WPU.exerciseId, "both")
        val on = sessionA(params, 2.5)
        on.states.single().driftApplied shouldBe false
        on.states.single().zn!! shouldBe (0.0296 / Det.sqrt(0.002622) plusOrMinus 0.002)
        on.next.getValue(key).znPrev shouldBe on.states.single().zn
        on.states.single().mKg shouldBe 111.55

        val old = ParamView.fromParamsFile(
            requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.8.json")).readText(Charsets.UTF_8),
        )
        val off = sessionA(old, 2.5)
        off.states.single().driftApplied.shouldBeNull()
        off.states.single().zn.shouldBeNull()
        off.next.getValue(key).znPrev.shouldBeNull()
        off.states.single().mKg shouldBe 111.55
    }
}
