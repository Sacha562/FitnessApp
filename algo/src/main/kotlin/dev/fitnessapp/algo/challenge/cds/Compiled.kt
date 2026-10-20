package dev.fitnessapp.algo.challenge.cds

import dev.fitnessapp.algo.challenge.fitexpr.CompiledExpr
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.Value

/** One finding of a validation stage (2.6): the check id (`S1` for structural, `V01`-`V18`), a JSON pointer, the text. */
data class ValidationError(val id: String, val pointer: String, val message: String, val warning: Boolean = false) {
    override fun toString(): String = (if (warning) "warn " else "") + "$id $pointer: $message"
}

/** The rule stages of section 5.3 in their total order. */
enum class Stage(val code: String) {
    SAFETY("safety"), DATA("data"), STRUCTURAL("structural"), PROGRESSION("progression");

    companion object {
        fun of(code: String): Stage? = entries.firstOrNull { it.code == code }
    }
}

/** The fixed modes of v1 (DATA_MODEL E26). */
object Modes {
    const val NORMAL = "normal"
    const val DELOAD = "deload"
    const val TEST_PENDING = "test_pending"
    const val EXTRA_DELOAD = "extra_deload"
    const val INJURY = "injury"
    val ALL: List<String> = listOf(NORMAL, DELOAD, TEST_PENDING, EXTRA_DELOAD, INJURY)

    /** C47: progression is frozen in every mode but normal. */
    fun frozen(mode: String): Boolean = mode != NORMAL
}

enum class LockKind(val code: String) {
    NO_INCREASE("noIncrease"), NO_DECREASE("noDecrease"), FREEZE("freeze");

    companion object {
        fun of(code: String): LockKind? = entries.firstOrNull { it.code == code }
    }
}

/** Rule actions of section 5.2; conditional actions are resolved on the frozen context. */
sealed class Action {
    data class Set(val variable: String, val to: CompiledExpr) : Action()
    data class Adjust(val variable: String, val by: CompiledExpr, val roundProfile: String?, val roundMode: String?) : Action()
    data class Lock(val variables: List<String>, val kind: LockKind) : Action()
    data class SetMode(val mode: String) : Action()
    data class Notify(val kind: String, val msg: String, val once: String) : Action()
    data class RestoreSnapshot(val block: CompiledExpr) : Action()
    data object AdvancePhase : Action()
    data object AdvanceWeek : Action()
    data object RepeatWeek : Action()
    data class StartNewBlock(val firstWeekMode: CompiledExpr?) : Action()
    data object RestartBlock : Action()
    data class ScheduleTest(val how: String) : Action()
    data class PostponeTest(val how: String) : Action()
    data class If(val condition: CompiledExpr, val then: List<Action>, val otherwise: List<Action>) : Action()
}

/** In-session actions of section 6.10. */
sealed class InSessionAction {
    data class Suggest(
        val field: String,
        val scope: String,
        val op: String,
        val value: CompiledExpr,
        val roundProfile: String?,
        val roundMode: String?,
        val msg: String,
    ) : InSessionAction()

    data class Notify(val kind: String, val msg: String) : InSessionAction()
    data class CapTarget(val scope: String, val field: String, val value: CompiledExpr) : InSessionAction()
}

class CompiledRule(
    val spec: RuleSpec,
    val stage: Stage,
    val condition: CompiledExpr,
    val lets: List<Pair<String, CompiledExpr>>,
    val actions: List<Action>,
) {
    val id: String get() = spec.id

    /** The proposal the rule belongs to (through `proposal`, `supersededBy` or the first of `uses`), C48. */
    val proposalId: String? get() = spec.proposal ?: spec.supersededBy ?: spec.uses.firstOrNull()

    /** Audit `rule_tag`: `A+P` for an A rule that uses a P definition (5.4). */
    val auditTag: String get() = if (spec.tag == "A" && spec.uses.isNotEmpty()) "A+P" else spec.tag
}

class CompiledInSessionRule(val spec: InSessionRuleSpec, val condition: CompiledExpr, val actions: List<InSessionAction>) {
    val id: String get() = spec.id
}

/** A definition parameter with its type and default value. */
data class ParamInfo(val name: String, val decl: ParamDecl, val type: Type, val default: Value)

/** A state variable: declared type, compiled `init`, bounds. */
data class StateVarInfo(
    val spec: StateVarSpec,
    val type: Type,
    val init: CompiledExpr?,
    val min: Value?,
    val max: Value?,
) {
    val name: String get() = spec.name
}

data class MetricInfo(val spec: MetricSpec, val type: Type, val derived: CompiledExpr?) {
    val id: String get() = spec.id
    val sided: Boolean get() = spec.sides == "LR"
}

data class DerivedInfo(val spec: DerivedSpec, val type: Type, val expr: CompiledExpr) {
    val name: String get() = spec.name
}

/** A numeric or keyword item field: a literal, a `[min, max]` range, an expression, or a keyword (`ramp`, `none`). */
sealed class FieldValue {
    data class Lit(val value: Value) : FieldValue()
    data class Range(val lo: Value, val hi: Value) : FieldValue()
    data class Expr(val expr: CompiledExpr) : FieldValue()
    data class Keyword(val word: String) : FieldValue()
}

/** How an item names its exercise (2.4.7). */
sealed class ExerciseChoice {
    data class Fixed(val key: String) : ExerciseChoice()
    data class AnyOf(val keys: List<String>, val pick: String) : ExerciseChoice()
    data class Expr(val expr: CompiledExpr) : ExerciseChoice()
}

/** An item of a resolved template with every expression field compiled. */
class CompiledItem(
    val spec: ItemSpec,
    val exercise: ExerciseChoice?,
    val fields: Map<String, FieldValue>,
    val skipIf: CompiledExpr?,
    val enabledIf: CompiledExpr?,
    val position: FieldValue?,
    /** Variant value -> compiled fields of that variant (merged at materialization, 6.3 step 7). */
    val variants: Map<String, Map<String, FieldValue>>,
) {
    val key: String get() = spec.key
}

/** A template after inheritance (6.2): its own or inherited header fields and the resolved items. */
class ResolvedTemplate(
    val id: String,
    val slot: String,
    val name: I18n,
    val kind: String,
    val bookedMin: Int,
    val tags: List<String>,
    val requiresReadiness: List<String>,
    val tag: String?,
    val proposal: String?,
    val validity: CompiledExpr?,
    val items: List<CompiledItem>,
)

/** One mode with its inheritance resolved for both decisions of its proposal (2.4.6). */
data class ModeInfo(val name: String, val spec: ModeSpec, val setsExpr: CompiledExpr?, val lightBilateralSets: CompiledExpr?)

data class GateInfo(val phaseId: Int, val source: String, val criteria: List<CompiledExpr>, val labels: List<I18n>)

class CompiledDefinition(
    val doc: DefinitionDoc,
    val jcsSha256: String,
    val params: Map<String, ParamInfo>,
    val stateVars: List<StateVarInfo>,
    val metrics: Map<String, MetricInfo>,
    val derived: List<DerivedInfo>,
    val templates: Map<String, ResolvedTemplate>,
    val rules: List<CompiledRule>,
    val inSessionRules: List<CompiledInSessionRule>,
    val gates: Map<Int, GateInfo>,
    val modes: Map<String, ModeInfo>,
    val blockWeeks: CompiledExpr,
    val weekCounts: CompiledExpr,
    val postponeIf: CompiledExpr,
    val hardLimits: Map<String, CompiledExpr>,
    val habitExprs: Map<String, Pair<CompiledExpr?, CompiledExpr?>>,
) {
    val slug: String get() = doc.id
    val version: String get() = doc.version
    val stateVarByName: Map<String, StateVarInfo> = stateVars.associateBy { it.name }
    val snapshotVars: List<String> get() = doc.state.snapshotVars

    /** The assisted machine bound by `equipment.required[].binding = machineCalibration`. */
    val machineId: String? = doc.equipment.required.firstOrNull { it.binding == "machineCalibration" }?.id

    /** Every `week.ex.<key>` item the week rules read, so the fold keeps only those rows (4.7). */
    val weekItemKeys: List<String> = rules.flatMap { r ->
        (listOf(r.condition) + r.lets.map { it.second }).flatMap { it.reads } +
            r.actions.flatMap { reads(it) }
    }.filter { it.startsWith("week.ex.") }.map { it.removePrefix("week.ex.").substringBefore('.') }.distinct().sorted()

    private fun reads(a: Action): List<String> = when (a) {
        is Action.Set -> a.to.reads
        is Action.Adjust -> a.by.reads
        is Action.If -> a.condition.reads + a.then.flatMap(::reads) + a.otherwise.flatMap(::reads)
        else -> emptyList()
    }
}
