package dev.fitnessapp.algo.challenge.fitexpr

/**
 * Compile-time error (section 3.12): `parse`, `type`, `unknown_identifier`, `unknown_function`, `arity`, or
 * `limit` for the section 3.11 limits. [start]/[end] are character offsets in the expression source.
 */
data class CompileError(val code: String, val start: Int, val end: Int, val message: String) {
    override fun toString(): String = "$code at $start: $message"
}

/**
 * Runtime error (section 3.12). [code] is the stable code (`missing:<path>`, `null:<path>`, `div_zero`,
 * `domain`, `empty_aggregate`, `list_length_mismatch`, `side_missing`, `unloadable`, `budget_exceeded`,
 * `type`); it is what audit rows store as `eval_error`.
 */
class EvalError(val code: String, detail: String? = null) : RuntimeException(if (detail == null) code else "$code: $detail") {
    val isMissingOrNull: Boolean get() = code.startsWith("missing:") || code.startsWith("null:") || code == "side_missing"

    companion object {
        fun missing(path: String) = EvalError("missing:$path")
        fun nullAt(path: String) = EvalError("null:$path")
        fun type(detail: String) = EvalError("type", detail)
    }
}

internal class ParseFailure(val error: CompileError) : RuntimeException(error.toString())
