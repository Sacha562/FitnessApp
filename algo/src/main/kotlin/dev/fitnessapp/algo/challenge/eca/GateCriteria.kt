package dev.fitnessapp.algo.challenge.eca

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.I18n
import dev.fitnessapp.algo.challenge.cds.Scopes
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.CompileResult
import dev.fitnessapp.algo.challenge.fitexpr.CompiledExpr
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.EvalError
import dev.fitnessapp.algo.challenge.fitexpr.FitExpr
import dev.fitnessapp.algo.challenge.fitexpr.FitExprLimits
import dev.fitnessapp.algo.challenge.fitexpr.Node
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.RecordV
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.params.ParamView
import java.math.BigDecimal

/** One exit-gate criterion for the dashboard (CHALLENGE_ENGINE 10.1, UX 19.7.3 `GateCriterion`). */
data class GateCriterion(
    val phaseId: Int,
    val index: Int,
    val label: I18n?,
    val source: String,
    /** The result on the latest valid test, on that test day's bodyweight (C62); null without a test. */
    val met: Boolean?,
    /** The criterion's comparison side that depends on bodyweight, as an expression source, if it has one. */
    val thresholdSource: String?,
)

/**
 * Gate progress (10.1): `met` is evaluated once on the test-day weigh-in (`t.bw`, C62) and never
 * re-evaluated later; [thresholdAt] evaluates the threshold side of a comparison at another bodyweight, so
 * the dashboard can show the forward threshold at BW(t).
 */
object GateCriteria {
    fun criteria(def: CompiledDefinition, params: ParamView, state: EnrollmentState, facts: EngineFacts, phaseId: Int = state.phase): List<GateCriterion> {
        val gate = def.gates[phaseId] ?: return emptyList()
        val env = RuleEnv(def, params, Records(params), state, state.lastTickDate, facts)
        val test = state.lastValidTest
        return gate.criteria.mapIndexed { i, c ->
            val met = when {
                gate.source == "anyValidObservation" -> evaluate(env, c, "m", RecordV("m", state.phaseObservations))
                test == null -> null
                else -> evaluate(env, c, "t", RecordV("t", test.metrics))
            }
            GateCriterion(phaseId, i, gate.labels.getOrNull(i), gate.source, met, thresholdSide(c.ast, c.source))
        }
    }

    private fun evaluate(env: RuleEnv, c: CompiledExpr, root: String, rec: RecordV): Boolean? = try {
        (c.evaluate(env.roots() + (root to rec), env.host) as? BoolV)?.b
    } catch (_: EvalError) {
        null
    }

    /** The right side of a top-level ordering comparison whose expression reads `t.bw`. */
    private fun thresholdSide(n: Node, source: String): String? {
        val b = n as? Node.Binary ?: return null
        if (b.op !in setOf(">=", ">", "<=", "<")) return null
        val rhs = source.substring(b.right.start, b.right.end)
        return rhs.takeIf { it.contains("t.bw") }
    }

    /** The threshold of criterion [index] of [phaseId] at [bwKg], on the kg grid; null if it has none. */
    fun thresholdAt(def: CompiledDefinition, params: ParamView, state: EnrollmentState, facts: EngineFacts, phaseId: Int, index: Int, bwKg: BigDecimal): BigDecimal? {
        val crit = criteria(def, params, state, facts, phaseId).getOrNull(index) ?: return null
        val src = crit.thresholdSource ?: return null
        val inputs = Scopes.Inputs(
            def.params.mapValues { it.value.type }, def.stateVarByName.mapValues { it.value.type },
            def.metrics.mapValues { it.value.type }, emptyMap(), emptyList(), emptyList(),
        )
        val compiled = when (val r = FitExpr.compile(src, Scopes.forTest(inputs), FitExprLimits.from(params))) {
            is CompileResult.Ok -> r.expr
            is CompileResult.Err -> return null
        }
        if (compiled.type != Type.MASS) return null
        val env = RuleEnv(def, params, Records(params), state, state.lastTickDate, facts)
        return try {
            val v = compiled.evaluate(env.roots() + ("t" to RecordV("t", mapOf("bw" to QV(Dim.MASS, bwKg)))), env.host) as? QV
            v?.let { Dec.norm(Grid.decimal(it.v, GridKind.KG)) }
        } catch (_: EvalError) {
            null
        }
    }
}
