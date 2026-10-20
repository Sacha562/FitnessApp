package dev.fitnessapp.algo.challenge.fitexpr

import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import java.math.MathContext
import java.math.RoundingMode

/**
 * Static types of section 3.3. [StrT.literals] carries the possible values of string literals (null =
 * unknown), so enum comparisons and enum assignments can be checked. [RecordT] types the identifier scopes
 * (`p`, `s`, `ex.<key>`, ...); [AnyT] switches checking off for values whose type is only known at run time
 * (`onboard.*`).
 */
sealed class Type {
    data class Q(val dim: Dim) : Type() {
        override fun toString(): String = dim.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    data object DateT : Type()
    data object BoolT : Type()
    data class StrT(val literals: Set<String>? = null) : Type()
    data class EnumT(val values: List<String>) : Type()
    data class ListT(val elem: Type) : Type()
    data class SideT(val elem: Type) : Type()
    data class MapT(val value: Type) : Type()
    data class RecordT(val name: String, val fields: Map<String, Type>) : Type()
    data object NullT : Type()
    data object AnyT : Type()

    companion object {
        val INT = Q(Dim.INT)
        val NUM = Q(Dim.NUM)
        val MASS = Q(Dim.MASS)
        val PIN = Q(Dim.PIN)
        val DURATION = Q(Dim.DURATION)
        val DAYS = Q(Dim.DAYS)
        val STR = StrT(null)
    }
}

/**
 * Engineering limits of section 3.11 (params `challenge.fitexpr.*`). [mc] is the C22 decimal context.
 */
data class FitExprLimits(
    val maxAstNodes: Int,
    val maxDepth: Int,
    val maxListLength: Int,
    val maxStringLength: Int,
    val maxCallArgs: Int,
    val maxEvalSteps: Int,
    val mathContextDigits: Int,
) {
    val mc: MathContext = MathContext(mathContextDigits, RoundingMode.HALF_EVEN)

    companion object : DeclaresParams {
        override val paramSpecs: List<ParamSpec> = listOf(
            ParamSpec.integer("challenge.fitexpr.math_context_digits", "significant digits", 34.0, 34.0),
            ParamSpec.integer("challenge.fitexpr.max_ast_nodes", "AST nodes per expression", 512.0, 512.0),
            ParamSpec.integer("challenge.fitexpr.max_call_args", "arguments", 8.0, 8.0),
            ParamSpec.integer("challenge.fitexpr.max_depth", "levels", 32.0, 32.0),
            ParamSpec.integer("challenge.fitexpr.max_eval_steps", "node visits per evaluation", 10000.0, 10000.0),
            ParamSpec.integer("challenge.fitexpr.max_list_length", "elements", 1000.0, 1000.0),
            ParamSpec.integer("challenge.fitexpr.max_string_length", "characters", 256.0, 256.0),
        )

        fun from(params: ParamView): FitExprLimits = FitExprLimits(
            maxAstNodes = params.int("challenge.fitexpr.max_ast_nodes"),
            maxDepth = params.int("challenge.fitexpr.max_depth"),
            maxListLength = params.int("challenge.fitexpr.max_list_length"),
            maxStringLength = params.int("challenge.fitexpr.max_string_length"),
            maxCallArgs = params.int("challenge.fitexpr.max_call_args"),
            maxEvalSteps = params.int("challenge.fitexpr.max_eval_steps"),
            mathContextDigits = params.int("challenge.fitexpr.math_context_digits"),
        )
    }
}

/** The dimension algebra of section 3.3, shared by the type checker and the evaluator. */
object DimRules {
    /** Result dimension of `a op b` for `+ - * /`, or null when the combination is a type error. */
    fun arith(op: String, a: Dim, b: Dim, rightIsIntegral: Boolean = b == Dim.INT, leftIsIntegral: Boolean = a == Dim.INT): Dim? {
        fun scalarish(d: Dim) = d == Dim.INT || d == Dim.NUM
        return when (op) {
            "+", "-" -> when {
                a == Dim.INT && b == Dim.INT -> Dim.INT
                scalarish(a) && scalarish(b) -> Dim.NUM
                a == b -> a
                else -> null
            }
            "*" -> when {
                a == Dim.INT && b == Dim.INT -> Dim.INT
                scalarish(a) && scalarish(b) -> Dim.NUM
                (a == Dim.MASS || a == Dim.DURATION) && scalarish(b) -> a
                scalarish(a) && (b == Dim.MASS || b == Dim.DURATION) -> b
                a == Dim.PIN && rightIsIntegral -> Dim.PIN
                b == Dim.PIN && leftIsIntegral -> Dim.PIN
                else -> null
            }
            "/" -> when {
                scalarish(a) && scalarish(b) -> Dim.NUM
                (a == Dim.MASS || a == Dim.DURATION) && scalarish(b) -> a
                a == b && (a == Dim.MASS || a == Dim.DURATION) -> Dim.NUM
                else -> null
            }
            else -> null
        }
    }

    /** Whether two dimensions may be compared (an Int compares with a Num). */
    fun comparable(a: Dim, b: Dim): Boolean =
        a == b || ((a == Dim.INT || a == Dim.NUM) && (b == Dim.INT || b == Dim.NUM))

    /** The common dimension of n-ary numeric functions (`min`, `max`, `clamp`), or null. */
    fun join(a: Dim, b: Dim): Dim? = when {
        a == b -> a
        (a == Dim.INT || a == Dim.NUM) && (b == Dim.INT || b == Dim.NUM) -> Dim.NUM
        else -> null
    }
}
