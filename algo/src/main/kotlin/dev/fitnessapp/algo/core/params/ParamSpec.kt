package dev.fitnessapp.algo.core.params

/** Value types of ALGORITHMS.md A8.2. */
enum class ParamType { NUMBER, INTEGER, BOOLEAN, ENUM, NUMBER_ARRAY, ENUM_ARRAY, NUMBER_TABLE, REGEX }

/**
 * One key a module declares (A8.2, P15). [min]/[max] apply element-wise to arrays and tables. The bounds
 * here are the validation bounds of the bundled file; the file's own metadata is documentation only.
 */
data class ParamSpec(
    val key: String,
    val type: ParamType,
    val unit: String,
    val min: Double? = null,
    val max: Double? = null,
    val enumValues: List<String> = emptyList(),
) {
    companion object {
        fun number(key: String, unit: String, min: Double, max: Double) = ParamSpec(key, ParamType.NUMBER, unit, min, max)
        fun integer(key: String, unit: String, min: Double, max: Double) = ParamSpec(key, ParamType.INTEGER, unit, min, max)
        fun table(key: String, unit: String, min: Double, max: Double) = ParamSpec(key, ParamType.NUMBER_TABLE, unit, min, max)
    }
}

/** A module that owns params keys: the declaration that [ParamView] and [ParamsValidator] check. */
interface DeclaresParams {
    val paramSpecs: List<ParamSpec>
}
