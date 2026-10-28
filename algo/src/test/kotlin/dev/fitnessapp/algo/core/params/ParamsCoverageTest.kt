package dev.fitnessapp.algo.core.params

import dev.fitnessapp.algo.challenge.calendar.SlotMatcher
import dev.fitnessapp.algo.challenge.contract.ContractBuilder
import dev.fitnessapp.algo.challenge.eca.RuleEngine
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.fitexpr.FitExprLimits
import dev.fitnessapp.algo.challenge.plan.WeekPlanner
import dev.fitnessapp.algo.core.bodyweight.Bodyweight
import dev.fitnessapp.algo.core.calendar.CalendarClassifier
import dev.fitnessapp.algo.core.load.LoadModel
import dev.fitnessapp.algo.estimation.fatigue.Fatigue
import dev.fitnessapp.algo.estimation.hold.Hold
import dev.fitnessapp.algo.estimation.rcm.Rcm
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.estimation.sse.Sse
import dev.fitnessapp.algo.estimation.stall.Stall
import dev.fitnessapp.algo.prescription.reason.Reason
import dev.fitnessapp.algo.prescription.rx.Rx
import dev.fitnessapp.algo.prescription.warmup.Warmup
import dev.fitnessapp.algo.prescription.rest.RestPolicy
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * ALGORITHMS.md A11 `ParamsCoverageTest` for the modules implemented so far: the defaults validate against
 * their specs, the spec bounds equal the file's documented bounds, every key of a fully implemented module
 * is declared, and no key is declared by two modules. Grows with every module.
 */
class ParamsCoverageTest {
    /** Modules whose whole key slice is declared (Warmup includes WarmupRest's keys, Reason ReasonSemantics'). */
    private val complete: Map<String, DeclaresParams> = mapOf(
        "load" to LoadModel, "bodyweight" to Bodyweight, "rest" to RestPolicy,
        "challenge.fitexpr" to FitExprLimits, "challenge.enrollment" to EnrollmentFold, "challenge.eca" to RuleEngine,
        "challenge.plan" to WeekPlanner, "challenge.contract" to ContractBuilder,
        "calendar.classify" to CalendarClassifier, "challenge.calendar" to SlotMatcher,
        "fatigue" to Fatigue, "rcm" to Rcm, "rircal" to RirCal, "sse" to Sse, "hold" to Hold,
        "reason" to Reason, "rx" to Rx, "warmup" to Warmup, "stall" to Stall,
        "autoreg" to dev.fitnessapp.algo.prescription.autoreg.Autoreg, "warnings" to dev.fitnessapp.algo.prescription.warnings.Warnings,
        "pref" to dev.fitnessapp.algo.selection.pref.Pref, "injury" to dev.fitnessapp.algo.selection.injury.Injury,
        "elig" to dev.fitnessapp.algo.selection.elig.Elig, "substitute" to dev.fitnessapp.algo.selection.substitute.Substitute,
    )
    private val all: List<DeclaresParams> = complete.values.toList()

    private val params = Json.parseToJsonElement(TestParams.text).jsonObject.getValue("params").jsonObject
    private val values = ParamsHashes.values(params)

    @Test
    fun `defaults validate against the declared specs`() {
        ParamsValidator.problems(values, all.flatMap { it.paramSpecs }) shouldBe emptyList()
    }

    @Test
    fun `spec bounds equal the documented bounds`() {
        all.flatMap { it.paramSpecs }.forEach { spec ->
            val entry = params.getValue(spec.key).jsonObject
            entry["min"]?.jsonPrimitive?.content?.toDouble() shouldBe spec.min
            entry["max"]?.jsonPrimitive?.content?.toDouble() shouldBe spec.max
        }
    }

    @Test
    fun `complete modules declare their whole slice`() {
        val slices = ParamsHashes.slices(values)
        complete.forEach { (module, decl) ->
            decl.paramSpecs.map { it.key }.sorted() shouldBe slices.getValue(module).keys.sorted()
        }
    }

    @Test
    fun `reads outside the declaration are visible in the read set`() {
        val view = TestParams.defaults()
        RestPolicy.decide(dev.fitnessapp.algo.prescription.rest.RestInput(dev.fitnessapp.algo.prescription.rest.RestClass.CORE), view)
        val declared = RestPolicy.paramSpecs.map { it.key }.toSet()
        view.readKeys().filterNot { it in declared } shouldBe emptyList()
    }
}
