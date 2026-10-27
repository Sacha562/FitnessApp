package dev.fitnessapp.algo.pipeline

import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.estimation.EstimationFixtures.LEG_PRESS
import dev.fitnessapp.algo.estimation.EstimationFixtures.set
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.estimation.sse.SseState
import dev.fitnessapp.algo.estimation.sse.TrendState
import dev.fitnessapp.algo.prescription.rx.ItemScheme
import dev.fitnessapp.algo.prescription.rx.RxMode
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** M3.4: the L6 pipeline from stored facts to RX-1 and WARMUP-1 (B9.2), the probe follow-up (B9.8), determinism. */
class PrescribingTest {
    private val params = TestParams.defaults()
    private val stack = (1..60).map { it * 5.0 }
    private val at = Instant.parse("2026-10-27T17:00:00Z")

    private fun request(
        sse: SseState? = null,
        exposures: List<Exposure> = emptyList(),
        sets: Int = 3,
        item: ItemScheme? = null,
        transferM: Double? = null,
        loadables: List<Double> = stack,
    ) = RxRequest(
        exerciseId = LEG_PRESS.exerciseId, side = "both", facts = LEG_PRESS, isolation = false, highRisk = false, bodyweightOnly = false,
        prefs = emptyList(), item = item, sets = sets, restS = 150.0, loadables = loadables, bodyweightW = null,
        sse = sse, rcmK = null, rircal = RirCal.initial(params), transferM = transferM, exposures = exposures,
        at = at, today = LocalDate.parse("2026-10-27"),
    )

    /** SSE-1 state of the C3.3 leg press: M = 158.67 kg, tight (posterior mean, no z_c), observed a week ago. */
    private val legPress = SseState(
        TrendState(kotlin.math.ln(158.67), 0.0, 0.0001, 0.0, 0.0, Instant.parse("2026-10-20T17:00:00Z"), 6), 45.0,
    )

    @Test
    fun `a new exercise starts with a probe that asks for a weight`() {
        val r = Prescribing.plan(request(), params.fork())
        r.output.probe shouldBe true
        r.output.trace shouldContain "rx.probe.pick_weight"
        r.output.sets.first().wSys.shouldBeNull()
        r.output.sets.first().targetReps shouldBe params.int("rx.default_hyp_r_hi")
        r.output.sets.drop(1).all { it.wSys == null && it.targetReps == null } shouldBe true
        r.warmup.shouldBeNull()
    }

    @Test
    fun `a transfer prior without a state starts without a probe`() {
        val r = Prescribing.plan(request(transferM = 150.0), params.fork())
        r.output.probe shouldBe false
        r.output.sets.first().wSys.shouldNotBeNull()
    }

    @Test
    fun `with a state the leg press gets loads from the model and a warm-up`() {
        val r = Prescribing.plan(request(sse = legPress), params.fork())
        r.output.probe shouldBe false
        (r.output.mode == RxMode.MODEL || r.output.mode == RxMode.DOUBLE) shouldBe true
        r.output.sets.size shouldBe 3
        r.output.sets.all { it.wSys != null && it.targetReps != null } shouldBe true
        r.warmup.shouldNotBeNull().sets.isNotEmpty() shouldBe true
        r.warmup!!.sets.all { it.wSys < r.output.sets.first().wSys!! } shouldBe true
    }

    @Test
    fun `the probe follow-up plans the remaining sets from the logged first set`() {
        val req = request()
        val probe = set("p1", "2026-10-27T17:05:00Z", 120.0, 10, 4.0, exercise = LEG_PRESS.exerciseId, loadMode = LoadMode.EXTERNAL, session = "today")
        val follow = Prescribing.followUp(req, probe, params.fork()).shouldNotBeNull()
        follow.sets.map { it.setIndex } shouldBe listOf(2, 3)
        follow.sets.all { it.wSys != null && it.targetReps != null } shouldBe true
        follow.trace shouldContain "rx.probe.followup"
        follow.probe shouldBe false
    }

    @Test
    fun `a gap of more than 21 days probes again from the decayed estimate`() {
        val old = Exposure(
            "s0", LocalDate.parse("2026-09-01"),
            listOf(set("a", "2026-09-01T17:00:00Z", 125.0, 10, 3.0, exercise = LEG_PRESS.exerciseId, loadMode = LoadMode.EXTERNAL, session = "s0")),
            null,
        )
        val r = Prescribing.plan(request(sse = legPress, exposures = listOf(old)), params.fork())
        r.output.probe shouldBe true
        r.output.sets.first().wSys.shouldNotBeNull()
    }

    @Test
    fun `a delegated item takes its reps and RIR from the challenge item`() {
        val r = Prescribing.plan(request(sse = legPress, item = ItemScheme(8, 8, 1.0, null), sets = 4), params.fork())
        r.output.scheme.rLo shouldBe 8
        r.output.scheme.rHi shouldBe 8
        r.output.scheme.qStar shouldBe 1.0
        r.output.scheme.sources["rep_min"] shouldBe "challenge"
        r.output.sets.size shouldBe 4
    }

    @Test
    fun `the last exposure's ladder carries into DOUBLE`() {
        val top = params.int("rx.default_hyp_r_hi")
        val sets = List(3) { i ->
            set("x$i", "2026-10-20T17:0$i:00Z", 125.0, top, 2.0, exercise = LEG_PRESS.exerciseId, loadMode = LoadMode.EXTERNAL, session = "s1", targetReps = top)
        }
        val prev = Exposure("s1", LocalDate.parse("2026-10-20"), sets, PreviousRx(RxMode.DOUBLE, List(3) { top }, 0, probe = false, deload = false))
        val r = Prescribing.plan(request(sse = legPress, exposures = listOf(prev)), params.fork())
        r.output.mode shouldBe RxMode.DOUBLE
        r.output.trace shouldContain "rx.double.advance"
        r.output.sets.first().wSys!! shouldBeGreaterThan 125.0
    }

    @Test
    fun `the same request gives the same inputs hash and outputs`() {
        val a = Prescribing.plan(request(sse = legPress), params.fork())
        val b = Prescribing.plan(request(sse = legPress), params.fork())
        Prescribing.outputs(a) shouldBe Prescribing.outputs(b)
        Prescribing.inputsHash(request(sse = legPress)) shouldBe Prescribing.inputsHash(request(sse = legPress))
        Prescribing.inputsHash(request(sse = legPress)) shouldNotBe Prescribing.inputsHash(request())
    }

    @Test
    fun `without loadables RX-1 repeats the last exposure`() {
        val r = Prescribing.plan(request(loadables = emptyList(), sse = legPress), params.fork())
        r.output.mode shouldBe RxMode.NONE
        r.warmup.shouldBeNull()
        r.output.capsApplied.shouldBeEmpty()
    }
}
