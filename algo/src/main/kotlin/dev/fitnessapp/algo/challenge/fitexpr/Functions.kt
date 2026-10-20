package dev.fitnessapp.algo.challenge.fitexpr

import dev.fitnessapp.algo.core.clock.Week
import java.math.BigDecimal
import java.math.RoundingMode

/** Raised by a function's typer; the checker turns it into a `type` compile error. */
internal class FnTypeError(message: String) : RuntimeException(message)

/**
 * One whitelisted function (section 3.8). [typer] computes the result type from the argument types (or
 * throws [FnTypeError]); [eval] computes the value from evaluated arguments. `defined` is a special form
 * handled by the evaluator and has no [eval].
 */
internal class FnSpec(
    val name: String,
    val arity: IntRange,
    val typer: (List<Type>, List<Node>) -> Type,
    val eval: ((Evaluator, Node.Call, List<Value>) -> Value)?,
)

internal object Functions {
    private fun bad(msg: String): Nothing = throw FnTypeError(msg)

    private fun isAny(t: Type) = t is Type.AnyT

    private fun q(t: Type, what: String): Dim? = when (t) {
        is Type.AnyT -> null
        is Type.Q -> t.dim
        else -> bad("$what must be a number, found $t")
    }

    private fun expectDim(t: Type, what: String, vararg dims: Dim) {
        val d = q(t, what) ?: return
        if (d !in dims) bad("$what must be ${dims.joinToString(" or ")}, found $t")
    }

    private fun expectStr(t: Type, what: String, allowed: Set<String>? = null) {
        when (t) {
            is Type.AnyT -> Unit
            is Type.StrT -> if (allowed != null && t.literals != null && !allowed.containsAll(t.literals)) {
                bad("$what must be one of ${allowed.sorted()}")
            }
            is Type.EnumT -> if (allowed != null && !allowed.containsAll(t.values)) bad("$what must be one of ${allowed.sorted()}")
            else -> bad("$what must be a string, found $t")
        }
    }

    private fun expectDate(t: Type, what: String) {
        if (t != Type.DateT && !isAny(t) && t != Type.NullT) bad("$what must be a Date, found $t")
    }

    private fun joinDims(types: List<Type>): Type {
        if (types.any(::isAny)) return Type.AnyT
        var d: Dim? = null
        for (t in types) {
            val td = (t as? Type.Q)?.dim ?: bad("expected numbers, found $t")
            d = if (d == null) td else DimRules.join(d, td) ?: bad("arguments of different dimensions")
        }
        return Type.Q(d!!)
    }

    /** `min`/`max`: n-ary, a list, or a side pair. */
    private val minMaxTyper: (List<Type>, List<Node>) -> Type = { args, _ ->
        if (args.size == 1) {
            when (val a = args[0]) {
                is Type.AnyT -> Type.AnyT
                is Type.ListT -> if (a.elem is Type.Q || isAny(a.elem)) a.elem else bad("a list of numbers is required")
                is Type.SideT -> if (a.elem is Type.Q || isAny(a.elem)) a.elem else bad("a side pair of numbers is required")
                else -> bad("one argument must be a list or a side pair")
            }
        } else {
            joinDims(args)
        }
    }

    private val modes = setOf("down", "up", "nearest")

    val byName: Map<String, FnSpec> = listOf(
        FnSpec("min", 1..8, minMaxTyper) { ev, call, a -> ev.extreme(call, a, max = false) },
        FnSpec("max", 1..8, minMaxTyper) { ev, call, a -> ev.extreme(call, a, max = true) },
        FnSpec("clamp", 3..3, { a, _ -> joinDims(a) }) { ev, call, a ->
            val x = ev.q(a[0], call.args[0])
            val lo = ev.q(a[1], call.args[1])
            val hi = ev.q(a[2], call.args[2])
            val dim = ev.joinRuntime(call, listOf(x, lo, hi))
            if (lo.v > hi.v) {
                ev.note("clamp_inverted")
                return@FnSpec QV(dim, hi.v)
            }
            QV(dim, x.v.max(lo.v).min(hi.v))
        },
        FnSpec("abs", 1..1, { a, _ -> q(a[0], "the argument"); a[0] }) { ev, call, a ->
            val x = ev.q(a[0], call.args[0])
            QV(x.dim, x.v.abs())
        },
        FnSpec("floor", 1..2, ::stepTyper) { ev, call, a -> ev.toStep(call, a, RoundingMode.FLOOR) },
        FnSpec("ceil", 1..2, ::stepTyper) { ev, call, a -> ev.toStep(call, a, RoundingMode.CEILING) },
        FnSpec("round", 1..2, ::stepTyper) { ev, call, a -> ev.toStep(call, a, RoundingMode.HALF_EVEN) },
        FnSpec("mod", 2..2, { a, _ ->
            expectDim(a[0], "the dividend", Dim.INT)
            expectDim(a[1], "the divisor", Dim.INT)
            Type.INT
        }) { ev, call, a ->
            val x = ev.q(a[0], call.args[0])
            val y = ev.q(a[1], call.args[1])
            if (y.v.signum() == 0) throw EvalError("div_zero")
            var r = x.v.remainder(y.v.abs())
            if (r.signum() < 0) r = r.add(y.v.abs())
            QV(Dim.INT, r)
        },
        FnSpec("all", 1..1, ::boolListTyper) { ev, call, a -> BoolV.of(ev.bools(call, a[0]).all { it }) },
        FnSpec("any", 1..1, ::boolListTyper) { ev, call, a -> BoolV.of(ev.bools(call, a[0]).any { it }) },
        FnSpec("count", 1..1, ::listTyperInt) { ev, call, a -> Values.int(ev.list(call, a[0]).count { it != NullV }) },
        FnSpec("len", 1..1, ::listTyperInt) { ev, call, a -> Values.int(ev.list(call, a[0]).size) },
        FnSpec("sum", 1..1, { a, _ -> numericElem(a[0]) }) { ev, call, a -> ev.sum(call, a[0]) },
        FnSpec("avg", 1..1, { a, _ ->
            when (val t = numericElem(a[0])) {
                Type.INT -> Type.NUM
                else -> t
            }
        }) { ev, call, a -> ev.avg(call, a[0]) },
        FnSpec("first", 1..1, ::elemTyper) { ev, call, a -> ev.nonNull(call, a[0]).firstOrNull() ?: throw EvalError("empty_aggregate") },
        FnSpec("last", 1..1, ::elemTyper) { ev, call, a -> ev.nonNull(call, a[0]).lastOrNull() ?: throw EvalError("empty_aggregate") },
        FnSpec("defined", 1..1, { _, _ -> Type.BoolT }, null),
        FnSpec("assistKg", 1..1, { a, _ -> expectDim(a[0], "the pin", Dim.PIN); Type.MASS }) { ev, call, a ->
            QV(Dim.MASS, ev.host.assistKg(ev.q(a[0], call.args[0]).v))
        },
        FnSpec("oneArmLoad", 1..1, { a, _ -> expectDim(a[0], "the pin", Dim.PIN); Type.MASS }) { ev, call, a ->
            QV(Dim.MASS, ev.host.oneArmLoad(ev.q(a[0], call.args[0]).v))
        },
        FnSpec("lbToKg", 1..1, { a, _ -> expectDim(a[0], "the pin", Dim.PIN); Type.MASS }) { ev, call, a ->
            QV(Dim.MASS, ev.q(a[0], call.args[0]).v.multiply(ev.host.lbKg(), ev.limits.mc))
        },
        FnSpec("kgToLb", 1..1, { a, _ -> expectDim(a[0], "the mass", Dim.MASS); Type.NUM }) { ev, call, a ->
            QV(Dim.NUM, ev.q(a[0], call.args[0]).v.divide(ev.host.lbKg(), ev.limits.mc))
        },
        FnSpec("roundLoad", 3..3, { a, _ ->
            expectDim(a[0], "the mass", Dim.MASS)
            expectStr(a[1], "the load profile")
            expectStr(a[2], "the mode", modes)
            Type.MASS
        }) { ev, call, a ->
            QV(Dim.MASS, ev.host.roundLoad(ev.q(a[0], call.args[0]).v, ev.str(a[1], call.args[1]), ev.str(a[2], call.args[2])))
        },
        FnSpec("roundPin", 2..2, { a, _ ->
            expectDim(a[0], "the pin", Dim.PIN, Dim.INT, Dim.NUM)
            expectStr(a[1], "the mode", modes)
            Type.PIN
        }) { ev, call, a ->
            QV(Dim.PIN, ev.host.roundPin(ev.q(a[0], call.args[0]).v, ev.str(a[1], call.args[1])))
        },
        FnSpec("e1rmTotal", 3..3, ::curveTyper) { ev, call, a ->
            val (t, reps, k) = ev.curveArgs(call, a)
            QV(Dim.MASS, t.multiply(BigDecimal.ONE.add(reps.subtract(BigDecimal.ONE).divide(k, ev.limits.mc)), ev.limits.mc))
        },
        FnSpec("loadForReps", 3..3, ::curveTyper) { ev, call, a ->
            val (t, reps, k) = ev.curveArgs(call, a)
            QV(Dim.MASS, t.divide(BigDecimal.ONE.add(reps.subtract(BigDecimal.ONE).divide(k, ev.limits.mc)), ev.limits.mc))
        },
        FnSpec("kgPerRepAt", 3..3, ::curveTyper) { ev, call, a ->
            val (t, reps, k) = ev.curveArgs(call, a)
            QV(Dim.MASS, t.divide(k.add(reps).subtract(BigDecimal.ONE), ev.limits.mc))
        },
        FnSpec("userK", 1..1, { a, _ -> expectStr(a[0], "the exercise key"); Type.NUM }) { ev, call, a ->
            QV(Dim.NUM, ev.host.userK(ev.str(a[0], call.args[0])))
        },
        FnSpec("userKQuantile", 2..2, { a, _ ->
            expectStr(a[0], "the exercise key")
            expectDim(a[1], "the quantile", Dim.NUM)
            Type.NUM
        }) { ev, call, a ->
            val qv = ev.q(a[1], call.args[1]).v
            if (qv.signum() <= 0 || qv >= BigDecimal.ONE) throw EvalError("domain", "quantile outside (0, 1)")
            QV(Dim.NUM, ev.host.userKQuantile(ev.str(a[0], call.args[0]), qv))
        },
        FnSpec("has", 1..1, ::strToBool) { ev, call, a -> BoolV.of(ev.host.has(ev.str(a[0], call.args[0]))) },
        FnSpec("todayHas", 1..1, ::strToBool) { ev, call, a -> BoolV.of(ev.host.todayHas(ev.str(a[0], call.args[0]))) },
        FnSpec("valid", 1..1, ::strToBool) { ev, call, a -> BoolV.of(ev.host.valid(ev.str(a[0], call.args[0]))) },
        FnSpec("reasonOn", 1..1, { a, _ -> expectStr(a[0], "the item key"); Type.STR }) { ev, call, a ->
            ev.host.reasonOn(ev.str(a[0], call.args[0]))?.let { StrV(it) } ?: NullV
        },
        FnSpec("validInBlock", 1..1, { a, _ -> expectStr(a[0], "the item key"); Type.INT }) { ev, call, a ->
            Values.int(ev.host.validInBlock(ev.str(a[0], call.args[0])))
        },
        FnSpec("sessionsDone", 1..1, { a, _ -> expectDim(a[0], "the ratio", Dim.NUM, Dim.INT); Type.INT }) { ev, call, a ->
            Values.int(ev.host.sessionsDone(ev.q(a[0], call.args[0]).v))
        },
        FnSpec("daysSince", 1..1, { a, _ -> expectDate(a[0], "the date"); Type.DAYS }) { ev, call, a ->
            val d = ev.date(a[0], call.args[0])
            Values.days(Week.calendarDays(d, ev.host.today()))
        },
        FnSpec("daysSincePain", 1..1, { a, _ -> expectStr(a[0], "the body region"); Type.DAYS }) { ev, call, a ->
            Values.days(ev.host.daysSincePain(ev.str(a[0], call.args[0])))
        },
        FnSpec("isoWeekOf", 1..1, { a, _ -> expectDate(a[0], "the date"); Type.STR }) { ev, call, a ->
            StrV(Week.isoWeek(ev.date(a[0], call.args[0])))
        },
        FnSpec("isoWeekday", 1..1, { a, _ -> expectDate(a[0], "the date"); Type.INT }) { ev, call, a ->
            Values.int(ev.date(a[0], call.args[0]).dayOfWeek.value)
        },
        FnSpec("nextFixedTestDate", 1..1, { a, _ -> expectDate(a[0], "the date"); Type.DateT }) { ev, call, a ->
            ev.host.nextFixedTestDate(ev.date(a[0], call.args[0]))?.let { DateV(it) } ?: NullV
        },
        FnSpec("phaseGatePasses", 1..1, { a, _ -> expectDim(a[0], "the phase", Dim.INT); Type.BoolT }) { ev, call, a ->
            BoolV.of(ev.host.phaseGatePasses(ev.q(a[0], call.args[0]).v.intValueExact()))
        },
    ).associateBy { it.name }

    private fun stepTyper(a: List<Type>, @Suppress("UNUSED_PARAMETER") n: List<Node>): Type {
        val d = q(a[0], "the value") ?: return Type.AnyT
        if (a.size == 2) {
            val s = q(a[1], "the step")
            if (s != null && DimRules.join(d, s) == null) bad("the step must have the value's dimension")
        }
        return a[0]
    }

    private fun boolListTyper(a: List<Type>, @Suppress("UNUSED_PARAMETER") n: List<Node>): Type {
        when (val t = a[0]) {
            is Type.AnyT -> Unit
            is Type.ListT -> if (t.elem != Type.BoolT && !isAny(t.elem)) bad("a List<Bool> is required, found $t")
            else -> bad("a List<Bool> is required, found $t")
        }
        return Type.BoolT
    }

    private fun listTyperInt(a: List<Type>, @Suppress("UNUSED_PARAMETER") n: List<Node>): Type {
        if (a[0] !is Type.ListT && !isAny(a[0])) bad("a list is required, found ${a[0]}")
        return Type.INT
    }

    private fun elemTyper(a: List<Type>, @Suppress("UNUSED_PARAMETER") n: List<Node>): Type = when (val t = a[0]) {
        is Type.AnyT -> Type.AnyT
        is Type.ListT -> t.elem
        else -> bad("a list is required, found $t")
    }

    private fun numericElem(t: Type): Type = when (t) {
        is Type.AnyT -> Type.AnyT
        is Type.ListT -> if (t.elem is Type.Q || isAny(t.elem)) t.elem else bad("a list of numbers is required")
        else -> bad("a list is required, found $t")
    }

    private fun curveTyper(a: List<Type>, @Suppress("UNUSED_PARAMETER") n: List<Node>): Type {
        expectDim(a[0], "the total load", Dim.MASS)
        expectDim(a[1], "the reps", Dim.INT)
        expectDim(a[2], "k", Dim.NUM, Dim.INT)
        return Type.MASS
    }

    private fun strToBool(a: List<Type>, @Suppress("UNUSED_PARAMETER") n: List<Node>): Type {
        expectStr(a[0], "the argument")
        return Type.BoolT
    }
}
