package dev.fitnessapp.algo.challenge.fitexpr

import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry

/** A parsed and type-checked expression (section 3.10): the AST, its result type and the per-node types. */
class CompiledExpr internal constructor(
    val source: String,
    val ast: Node,
    val type: Type,
    internal val types: Array<Type?>,
    val limits: FitExprLimits,
) {
    /** Every identifier path the expression reads (`s.pinA`, `ex.oaa.reps`, ...), for audit bindings. */
    val reads: List<String> by lazy {
        val out = sortedSetOf<String>()
        fun walk(n: Node) {
            val p = n.path()
            if (p != null && (n is Node.Member || n is Node.Ident)) {
                out += p
                return
            }
            n.children().forEach(::walk)
        }
        walk(ast)
        out.toList()
    }

    /** All calls in the expression with their first argument when it is a string literal (`valid('cal')`). */
    val literalCalls: List<Pair<String, String?>> by lazy {
        val out = ArrayList<Pair<String, String?>>()
        fun walk(n: Node) {
            if (n is Node.Call) out += n.name to (n.args.firstOrNull() as? Node.StrLit)?.value
            n.children().forEach(::walk)
        }
        walk(ast)
        out
    }

    fun evaluate(roots: Map<String, Value>, host: EvalHost = EvalHost.NONE): Value = Evaluator(this, roots, host).run()

    /** Evaluates and also returns the evaluation's audit notes. */
    fun evaluateWithNotes(roots: Map<String, Value>, host: EvalHost): Pair<Value, List<String>> {
        val ev = Evaluator(this, roots, host)
        val v = ev.run()
        return v to ev.notes()
    }
}

sealed class CompileResult {
    data class Ok(val expr: CompiledExpr) : CompileResult()
    data class Err(val error: CompileError) : CompileResult()
}

/** `challenge.fitexpr@1.0.0` (C21, C22): the typed, side-effect-free rule expression language. */
object FitExpr {
    val ref: ModuleRef = ModuleRegistry.ref("challenge.fitexpr")

    fun parse(source: String, limits: FitExprLimits): Node {
        val ast = Parser(source, limits).parseAll()
        val count = countNodes(ast)
        if (count > limits.maxAstNodes) {
            throw ParseFailure(CompileError("limit", 0, source.length, "$count AST nodes; the limit is ${limits.maxAstNodes}"))
        }
        return ast
    }

    fun compile(source: String, scope: TypeScope, limits: FitExprLimits): CompileResult = try {
        val ast = parse(source, limits)
        val checker = TypeChecker(scope, maxId(ast) + 1)
        val type = checker.check(ast)
        CompileResult.Ok(CompiledExpr(source, ast, type, checker.types, limits))
    } catch (e: ParseFailure) {
        CompileResult.Err(e.error)
    }

    /** Compile or throw [IllegalArgumentException]; for engine-internal expressions and tests. */
    fun compileOrThrow(source: String, scope: TypeScope, limits: FitExprLimits): CompiledExpr =
        when (val r = compile(source, scope, limits)) {
            is CompileResult.Ok -> r.expr
            is CompileResult.Err -> throw IllegalArgumentException("'$source': ${r.error}")
        }

    /** Boolean positions reject anything but Bool (section 3.4). */
    fun requireBoolean(c: CompiledExpr): CompileError? = when (c.type) {
        Type.BoolT, Type.AnyT -> null
        is Type.ListT -> CompileError("type", 0, c.source.length, "a List<Bool> needs all() or any() here")
        else -> CompileError("type", 0, c.source.length, "expected Bool, found ${c.type}")
    }

    private fun countNodes(n: Node): Int = 1 + n.children().sumOf { countNodes(it) }

    private fun maxId(n: Node): Int = maxOf(n.id, n.children().maxOfOrNull { maxId(it) } ?: 0)
}
