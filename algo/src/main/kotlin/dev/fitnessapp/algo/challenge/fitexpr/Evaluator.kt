package dev.fitnessapp.algo.challenge.fitexpr

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Evaluates one compiled expression against a frozen context (sections 3.4-3.6). All arithmetic is
 * `BigDecimal` in the C22 context; `&&`, `||`, `?:`, `??` and `defined` short-circuit; every node visit
 * counts against `max_eval_steps`. Errors are [EvalError]s; the caller decides what an error means
 * (a false condition, a dropped rule, a kept value).
 */
class Evaluator internal constructor(
    private val expr: CompiledExpr,
    private val roots: Map<String, Value>,
    val host: EvalHost,
) {
    val limits: FitExprLimits get() = expr.limits
    private var steps = 0
    private val notes = ArrayList<String>()

    /** Audit notes raised during evaluation (for example `clamp_inverted`). */
    fun notes(): List<String> = notes.toList()

    internal fun note(code: String) {
        if (code !in notes) notes += code
    }

    fun run(): Value = eval(expr.ast)

    private fun pathOf(n: Node): String = n.path() ?: expr.source.substring(n.start, n.end)

    internal fun eval(n: Node): Value {
        if (++steps > limits.maxEvalSteps) throw EvalError("budget_exceeded", "more than ${limits.maxEvalSteps} steps")
        return when (n) {
            is Node.NumLit -> QV(n.dim, n.value)
            is Node.StrLit -> StrV(n.value)
            is Node.BoolLit -> BoolV.of(n.value)
            is Node.NullLit -> NullV
            is Node.Ident -> roots[n.name] ?: throw EvalError.missing(n.name)
            is Node.Member -> member(n)
            is Node.Index -> index(n)
            is Node.Call -> call(n)
            is Node.ListLit -> checkLength(ListV(n.items.map { eval(it) }))
            is Node.Unary -> unary(n)
            is Node.Binary -> binary(n)
            is Node.Ternary -> if (bool(eval(n.cond), n.cond)) eval(n.then) else eval(n.otherwise)
        }
    }

    private fun checkLength(v: ListV): ListV {
        if (v.items.size > limits.maxListLength) throw EvalError("budget_exceeded", "list longer than ${limits.maxListLength}")
        return v
    }

    private fun member(n: Node.Member): Value = when (val t = eval(n.target)) {
        is RecordV -> t.get(n.name)
        is SideV -> when (n.name) {
            "L" -> t.l ?: throw EvalError("side_missing", pathOf(n))
            "R" -> t.r ?: throw EvalError("side_missing", pathOf(n))
            else -> throw EvalError.type("a side pair has only .L and .R")
        }
        is NullV -> throw EvalError.nullAt(pathOf(n.target))
        else -> throw EvalError.type("'.${n.name}' on $t")
    }

    private fun index(n: Node.Index): Value {
        val t = eval(n.target)
        val ix = eval(n.index)
        return when {
            t is MapV && ix is StrV -> t.entries[ix.s] ?: throw EvalError.missing(pathOf(n.target) + "[" + ix.s + "]")
            t is ListV && ix is QV && ix.dim == Dim.INT -> {
                val k = ix.v.intValueExact()
                t.items.getOrNull(k) ?: throw EvalError.missing(pathOf(n.target) + "[" + k + "]")
            }
            t is NullV -> throw EvalError.nullAt(pathOf(n.target))
            ix is NullV -> throw EvalError.nullAt(pathOf(n.index))
            else -> throw EvalError.type("indexing $t with $ix")
        }
    }

    private fun unary(n: Node.Unary): Value {
        val v = eval(n.operand)
        return when (n.op) {
            "!" -> BoolV.of(!bool(v, n.operand))
            else -> negate(v, n.operand)
        }
    }

    private fun negate(v: Value, at: Node): Value = when (v) {
        is QV -> QV(v.dim, v.v.negate())
        is ListV -> ListV(v.items.map { negate(it, at) })
        is NullV -> throw EvalError.nullAt(pathOf(at))
        else -> throw EvalError.type("unary minus on $v")
    }

    internal fun bool(v: Value, at: Node): Boolean = when (v) {
        is BoolV -> v.b
        is NullV -> throw EvalError.nullAt(pathOf(at))
        else -> throw EvalError.type("expected Bool at ${pathOf(at)}, found $v")
    }

    private fun binary(n: Node.Binary): Value {
        when (n.op) {
            "&&" -> return if (!bool(eval(n.left), n.left)) BoolV.FALSE else BoolV.of(bool(eval(n.right), n.right))
            "||" -> return if (bool(eval(n.left), n.left)) BoolV.TRUE else BoolV.of(bool(eval(n.right), n.right))
            "??" -> {
                val l = try {
                    eval(n.left)
                } catch (e: EvalError) {
                    if (!e.isMissingOrNull) throw e
                    NullV
                }
                return if (l is NullV) eval(n.right) else l
            }
        }
        val l = eval(n.left)
        val r = eval(n.right)
        return when (n.op) {
            "in" -> membership(l, r, n)
            else -> broadcast(n, l, r) { a, b -> scalarBinary(n, a, b) }
        }
    }

    private fun broadcast(n: Node, l: Value, r: Value, f: (Value, Value) -> Value): Value = when {
        l is ListV && r is ListV -> {
            if (l.items.size != r.items.size) throw EvalError("list_length_mismatch", pathOf(n))
            checkLength(ListV(l.items.indices.map { f(l.items[it], r.items[it]) }))
        }
        l is ListV -> checkLength(ListV(l.items.map { f(it, r) }))
        r is ListV -> checkLength(ListV(r.items.map { f(l, it) }))
        else -> f(l, r)
    }

    private fun scalarBinary(n: Node.Binary, a: Value, b: Value): Value = when (n.op) {
        "==" -> BoolV.of(equal(a, b))
        "!=" -> BoolV.of(!equal(a, b))
        "<", "<=", ">", ">=" -> {
            val c = order(n, a, b)
            BoolV.of(
                when (n.op) {
                    "<" -> c < 0
                    "<=" -> c <= 0
                    ">" -> c > 0
                    else -> c >= 0
                },
            )
        }
        else -> arith(n, a, b)
    }

    private fun equal(a: Value, b: Value): Boolean {
        if (a is NullV || b is NullV) return a is NullV && b is NullV
        return Values.same(a, b)
    }

    private fun order(n: Node.Binary, a: Value, b: Value): Int = when {
        a is NullV -> throw EvalError.nullAt(pathOf(n.left))
        b is NullV -> throw EvalError.nullAt(pathOf(n.right))
        a is QV && b is QV -> {
            if (!DimRules.comparable(a.dim, b.dim)) throw EvalError.type("cannot order ${a.dim} and ${b.dim}")
            a.v.compareTo(b.v)
        }
        a is DateV && b is DateV -> a.d.compareTo(b.d)
        else -> throw EvalError.type("cannot order $a and $b")
    }

    private fun arith(n: Node.Binary, a: Value, b: Value): Value {
        if (a is NullV) throw EvalError.nullAt(pathOf(n.left))
        if (b is NullV) throw EvalError.nullAt(pathOf(n.right))
        if (a is DateV || b is DateV) {
            return when {
                a is DateV && b is QV && b.dim == Dim.DAYS && n.op == "+" -> DateV(a.d.plusDays(b.v.longValueExact()))
                a is DateV && b is QV && b.dim == Dim.DAYS && n.op == "-" -> DateV(a.d.minusDays(b.v.longValueExact()))
                a is QV && a.dim == Dim.DAYS && b is DateV && n.op == "+" -> DateV(b.d.plusDays(a.v.longValueExact()))
                a is DateV && b is DateV && n.op == "-" -> Values.days(java.time.temporal.ChronoUnit.DAYS.between(b.d, a.d))
                else -> throw EvalError.type("'${n.op}' between $a and $b")
            }
        }
        if (a !is QV || b !is QV) throw EvalError.type("'${n.op}' between $a and $b")
        val dim = DimRules.arith(n.op, a.dim, b.dim) ?: throw EvalError.type("'${n.op}' between ${a.dim} and ${b.dim}")
        val mc = limits.mc
        val v = when (n.op) {
            "+" -> a.v.add(b.v, mc)
            "-" -> a.v.subtract(b.v, mc)
            "*" -> a.v.multiply(b.v, mc)
            else -> {
                if (b.v.signum() == 0) throw EvalError("div_zero", pathOf(n))
                a.v.divide(b.v, mc)
            }
        }
        return QV(dim, v)
    }

    private fun membership(l: Value, r: Value, n: Node.Binary): Value {
        val list = when (r) {
            is ListV -> r.items
            is NullV -> throw EvalError.nullAt(pathOf(n.right))
            else -> throw EvalError.type("the right side of 'in' must be a list")
        }
        fun contains(x: Value): Boolean = x !is NullV && list.any { it !is NullV && Values.same(x, it) }
        return when (l) {
            is ListV -> checkLength(ListV(l.items.map { BoolV.of(contains(it)) }))
            else -> BoolV.of(contains(l))
        }
    }

    private fun call(n: Node.Call): Value {
        if (n.name == "defined") return defined(n.args[0])
        val fn = Functions.byName.getValue(n.name)
        val args = n.args.map { eval(it) }
        return fn.eval!!(this, n, args)
    }

    /** Section 3.5: false on missing, null or a missing side instead of raising. */
    private fun defined(arg: Node): Value {
        val v = try {
            eval(arg)
        } catch (e: EvalError) {
            if (e.isMissingOrNull) return BoolV.FALSE
            throw e
        }
        return BoolV.of(
            when (v) {
                is NullV -> false
                is SideV -> v.l != null && v.r != null && v.l !is NullV && v.r !is NullV
                else -> true
            },
        )
    }

    // ---- helpers used by the function table ----

    internal fun q(v: Value, at: Node): QV = when (v) {
        is QV -> v
        is NullV -> throw EvalError.nullAt(pathOf(at))
        else -> throw EvalError.type("expected a number at ${pathOf(at)}, found $v")
    }

    internal fun str(v: Value, at: Node): String = when (v) {
        is StrV -> v.s
        is NullV -> throw EvalError.nullAt(pathOf(at))
        else -> throw EvalError.type("expected a string at ${pathOf(at)}, found $v")
    }

    internal fun date(v: Value, at: Node): java.time.LocalDate = when (v) {
        is DateV -> v.d
        is NullV -> throw EvalError.nullAt(pathOf(at))
        else -> throw EvalError.type("expected a Date at ${pathOf(at)}, found $v")
    }

    internal fun list(call: Node.Call, v: Value): List<Value> = when (v) {
        is ListV -> v.items
        is NullV -> throw EvalError.nullAt(pathOf(call.args[0]))
        else -> throw EvalError.type("'${call.name}' needs a list")
    }

    internal fun nonNull(call: Node.Call, v: Value): List<Value> = list(call, v).filter { it != NullV }

    internal fun bools(call: Node.Call, v: Value): List<Boolean> = nonNull(call, v).map {
        (it as? BoolV)?.b ?: throw EvalError.type("'${call.name}' needs a List<Bool>")
    }

    internal fun joinRuntime(call: Node.Call, xs: List<QV>): Dim {
        var d = xs.first().dim
        for (x in xs.drop(1)) d = DimRules.join(d, x.dim) ?: throw EvalError.type("'${call.name}': mixed dimensions")
        return d
    }

    internal fun extreme(call: Node.Call, args: List<Value>, max: Boolean): Value {
        val xs: List<QV> = if (args.size == 1) {
            when (val a = args[0]) {
                is ListV -> nonNull(call, a).map { q(it, call.args[0]) }
                is SideV -> {
                    val l = a.l ?: throw EvalError("side_missing", pathOf(call.args[0]))
                    val r = a.r ?: throw EvalError("side_missing", pathOf(call.args[0]))
                    listOf(q(l, call.args[0]), q(r, call.args[0]))
                }
                else -> listOf(q(a, call.args[0]))
            }
        } else {
            args.mapIndexed { i, v -> q(v, call.args[i]) }
        }
        if (xs.isEmpty()) throw EvalError("empty_aggregate", pathOf(call))
        val dim = joinRuntime(call, xs)
        var best = xs.first()
        for (x in xs.drop(1)) {
            val c = x.v.compareTo(best.v)
            if ((max && c > 0) || (!max && c < 0)) best = x
        }
        return QV(dim, best.v)
    }

    internal fun toStep(call: Node.Call, args: List<Value>, mode: RoundingMode): Value {
        val x = q(args[0], call.args[0])
        val step = if (args.size == 2) q(args[1], call.args[1]).v else BigDecimal.ONE
        if (step.signum() <= 0) throw EvalError("domain", "step must be positive")
        val k = x.v.divide(step, limits.mc).setScale(0, mode)
        return QV(x.dim, k.multiply(step, limits.mc))
    }

    internal fun sum(call: Node.Call, v: Value): Value {
        val xs = nonNull(call, v).map { q(it, call.args[0]) }
        if (xs.isEmpty()) {
            val t = expr.types[call.id] as? Type.Q
            return QV(t?.dim ?: Dim.INT, BigDecimal.ZERO)
        }
        val dim = joinRuntime(call, xs)
        return QV(dim, xs.fold(BigDecimal.ZERO) { acc, x -> acc.add(x.v, limits.mc) })
    }

    internal fun avg(call: Node.Call, v: Value): Value {
        val xs = nonNull(call, v).map { q(it, call.args[0]) }
        if (xs.isEmpty()) throw EvalError("empty_aggregate", pathOf(call))
        val dim = joinRuntime(call, xs).let { if (it == Dim.INT) Dim.NUM else it }
        val total = xs.fold(BigDecimal.ZERO) { acc, x -> acc.add(x.v, limits.mc) }
        return QV(dim, total.divide(BigDecimal(xs.size), limits.mc))
    }

    internal fun curveArgs(call: Node.Call, args: List<Value>): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val t = q(args[0], call.args[0]).v
        val reps = q(args[1], call.args[1]).v
        val k = q(args[2], call.args[2]).v
        if (k.signum() <= 0) throw EvalError("domain", "k must be > 0")
        if (reps < BigDecimal.ONE) throw EvalError("domain", "reps must be >= 1")
        return Triple(t, reps, k)
    }
}
