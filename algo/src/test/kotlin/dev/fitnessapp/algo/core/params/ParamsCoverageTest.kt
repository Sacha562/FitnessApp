package dev.fitnessapp.algo.core.params

import dev.fitnessapp.algo.challenge.fitexpr.FitExprLimits
import dev.fitnessapp.algo.core.bodyweight.Bodyweight
import dev.fitnessapp.algo.core.load.LoadModel
import dev.fitnessapp.algo.prescription.rest.RestPolicy
import dev.fitnessapp.algo.prescription.warmup.WarmupRest
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
    /** Modules whose whole key slice is declared. WarmupRest is partial (rest keys only) until WARMUP-1. */
    private val complete: Map<String, DeclaresParams> = mapOf(
        "load" to LoadModel, "bodyweight" to Bodyweight, "rest" to RestPolicy,
        "challenge.fitexpr" to FitExprLimits,
    )
    private val all: List<DeclaresParams> = complete.values.toList() + WarmupRest

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
