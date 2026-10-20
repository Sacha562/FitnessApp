package dev.fitnessapp.algo.challenge.fitexpr

/**
 * Static checking of section 3.10: identifiers resolve against the field's scope, types are inferred
 * bottom-up under the dimension rules, a `List<Bool>` is rejected in every boolean position, and calls
 * are checked against the whitelist. The first error is reported.
 */
internal class TypeChecker(private val scope: TypeScope, nodeCount: Int) {
    val types: Array<Type?> = arrayOfNulls(nodeCount)

    fun check(n: Node): Type {
        val t = infer(n)
        types[n.id] = t
        return t
    }

    private fun fail(n: Node, code: String, msg: String): Nothing = throw ParseFailure(CompileError(code, n.start, n.end, msg))

    private fun infer(n: Node): Type = when (n) {
        is Node.NumLit -> Type.Q(n.dim)
        is Node.StrLit -> Type.StrT(setOf(n.value))
        is Node.BoolLit -> Type.BoolT
        is Node.NullLit -> Type.NullT
        is Node.Ident -> scope.root(n.name) ?: fail(n, "unknown_identifier", "unknown identifier '${n.name}'")
        is Node.Member -> member(n)
        is Node.Index -> index(n)
        is Node.Call -> call(n)
        is Node.ListLit -> list(n)
        is Node.Unary -> unary(n)
        is Node.Binary -> binary(n)
        is Node.Ternary -> ternary(n)
    }

    private fun member(n: Node.Member): Type = when (val t = check(n.target)) {
        is Type.AnyT -> Type.AnyT
        is Type.RecordT -> t.fields[n.name] ?: fail(n, "unknown_identifier", "unknown identifier '${n.path() ?: n.name}'")
        is Type.SideT -> when (n.name) {
            "L", "R" -> t.elem
            else -> fail(n, "type", "a side pair has only .L and .R")
        }
        else -> fail(n, "type", "'.${n.name}' on a value of type $t")
    }

    private fun index(n: Node.Index): Type {
        val target = check(n.target)
        val ix = check(n.index)
        return when (target) {
            is Type.AnyT -> Type.AnyT
            is Type.MapT -> {
                if (ix !is Type.StrT && ix !is Type.EnumT && ix !is Type.AnyT) fail(n.index, "type", "a map key must be a string")
                target.value
            }
            is Type.ListT -> {
                if (ix != Type.INT && ix !is Type.AnyT) fail(n.index, "type", "a list index must be an Int")
                target.elem
            }
            else -> fail(n, "type", "indexing a value of type $target")
        }
    }

    private fun list(n: Node.ListLit): Type {
        if (n.items.isEmpty()) return Type.ListT(Type.AnyT)
        var t = check(n.items.first())
        for (item in n.items.drop(1)) {
            t = join(t, check(item)) ?: fail(item, "type", "list elements of different types")
        }
        return Type.ListT(t)
    }

    private fun unary(n: Node.Unary): Type {
        val t = check(n.operand)
        return when (n.op) {
            "!" -> {
                requireBool(n.operand, t)
                Type.BoolT
            }
            else -> when {
                t is Type.AnyT -> t
                t is Type.Q -> t
                t is Type.ListT && (t.elem is Type.Q || t.elem is Type.AnyT) -> t
                else -> fail(n, "type", "unary minus on $t")
            }
        }
    }

    /** Boolean positions accept Bool (or Any); a List<Bool> needs all()/any() (section 3.4). */
    private fun requireBool(n: Node, t: Type) {
        when (t) {
            Type.BoolT, Type.AnyT -> Unit
            is Type.ListT -> fail(n, "type", "a List<Bool> needs all() or any() here")
            else -> fail(n, "type", "expected Bool, found $t")
        }
    }

    private fun binary(n: Node.Binary): Type {
        if (n.op == "&&" || n.op == "||") {
            requireBool(n.left, check(n.left))
            requireBool(n.right, check(n.right))
            return Type.BoolT
        }
        val l = check(n.left)
        val r = check(n.right)
        return when (n.op) {
            "??" -> join(l, r) ?: fail(n, "type", "'??' sides of different types ($l, $r)")
            "+", "-", "*", "/" -> broadcast(n, l, r) { a, b -> arith(n, a, b) }
            "==", "!=" -> broadcast(n, l, r) { a, b -> equality(n, a, b); Type.BoolT }
            "<", "<=", ">", ">=" -> broadcast(n, l, r) { a, b -> ordering(n, a, b); Type.BoolT }
            "in" -> membership(n, l, r)
            else -> fail(n, "parse", "unknown operator ${n.op}")
        }
    }

    /** Element-wise broadcasting of section 3.4 (`List op Scalar`, `Scalar op List`, `List op List`). */
    private fun broadcast(n: Node, l: Type, r: Type, scalar: (Type, Type) -> Type): Type = when {
        l is Type.ListT && r is Type.ListT -> Type.ListT(scalar(l.elem, r.elem))
        l is Type.ListT -> Type.ListT(scalar(l.elem, r))
        r is Type.ListT -> Type.ListT(scalar(l, r.elem))
        else -> scalar(l, r)
    }

    private fun arith(n: Node.Binary, a: Type, b: Type): Type {
        if (a is Type.AnyT || b is Type.AnyT) return Type.AnyT
        if (a is Type.NullT || b is Type.NullT) fail(n, "type", "arithmetic on null")
        if (a == Type.DateT || b == Type.DateT) {
            return when {
                a == Type.DateT && b == Type.DAYS && (n.op == "+" || n.op == "-") -> Type.DateT
                a == Type.DAYS && b == Type.DateT && n.op == "+" -> Type.DateT
                a == Type.DateT && b == Type.DateT && n.op == "-" -> Type.DAYS
                else -> fail(n, "type", "'${n.op}' between $a and $b")
            }
        }
        if (a !is Type.Q || b !is Type.Q) fail(n, "type", "'${n.op}' between $a and $b")
        val d = DimRules.arith(n.op, a.dim, b.dim) ?: fail(n, "type", "'${n.op}' between $a and $b")
        return Type.Q(d)
    }

    private fun equality(n: Node, a: Type, b: Type) {
        if (a is Type.NullT || b is Type.NullT || a is Type.AnyT || b is Type.AnyT) return
        comparisonProblem(a, b)?.let { fail(n, "type", it) }
    }

    private fun ordering(n: Node, a: Type, b: Type) {
        if (a is Type.AnyT || b is Type.AnyT) return
        val ok = (a is Type.Q && b is Type.Q && DimRules.comparable(a.dim, b.dim)) || (a == Type.DateT && b == Type.DateT)
        if (!ok) fail(n, "type", "cannot order $a and $b")
    }

    /** Null when `a == b` is well typed; otherwise the reason (unknown enum literals included). */
    private fun comparisonProblem(a: Type, b: Type): String? = when {
        a is Type.Q && b is Type.Q -> if (DimRules.comparable(a.dim, b.dim)) null else "cannot compare $a with $b"
        a is Type.EnumT && b is Type.StrT ->
            if (b.literals == null || a.values.containsAll(b.literals)) null
            else "unknown enum literal ${(b.literals - a.values.toSet()).sorted()}; declared values ${a.values}"
        a is Type.StrT && b is Type.EnumT -> comparisonProblem(b, a)
        a is Type.EnumT && b is Type.EnumT -> null
        a is Type.StrT && b is Type.StrT -> null
        a == b -> null
        else -> "cannot compare $a with $b"
    }

    private fun membership(n: Node.Binary, l: Type, r: Type): Type {
        if (r is Type.AnyT) return if (l is Type.ListT) Type.ListT(Type.BoolT) else Type.BoolT
        if (r !is Type.ListT) fail(n.right, "type", "the right side of 'in' must be a list")
        val elem = if (l is Type.ListT) l.elem else l
        if (elem !is Type.AnyT && r.elem !is Type.AnyT) {
            comparisonProblem(elem, r.elem)?.let { fail(n, "type", "'in': $it") }
        }
        return if (l is Type.ListT) Type.ListT(Type.BoolT) else Type.BoolT
    }

    private fun ternary(n: Node.Ternary): Type {
        requireBool(n.cond, check(n.cond))
        val a = check(n.then)
        val b = check(n.otherwise)
        return join(a, b) ?: fail(n, "type", "the branches have different types ($a, $b)")
    }

    private fun call(n: Node.Call): Type {
        val fn = Functions.byName[n.name] ?: fail(n, "unknown_function", "unknown function '${n.name}'")
        if (!scope.allowsFunction(n.name)) fail(n, "unknown_function", "'${n.name}' is not available here")
        if (n.args.size !in fn.arity) fail(n, "arity", "'${n.name}' takes ${fn.arity.first}..${fn.arity.last} arguments, got ${n.args.size}")
        val args = n.args.map { check(it) }
        return try {
            fn.typer(args, n.args)
        } catch (e: FnTypeError) {
            fail(n, "type", "'${n.name}': ${e.message}")
        }
    }

    companion object {
        /** The common type of two branches (`?:`, `??`, list literals), or null. */
        fun join(a: Type, b: Type): Type? = when {
            a == b -> a
            a is Type.AnyT || b is Type.AnyT -> Type.AnyT
            a is Type.NullT -> b
            b is Type.NullT -> a
            a is Type.Q && b is Type.Q -> DimRules.join(a.dim, b.dim)?.let { Type.Q(it) }
            a is Type.StrT && b is Type.StrT -> Type.StrT(if (a.literals != null && b.literals != null) a.literals + b.literals else null)
            a is Type.EnumT && b is Type.StrT -> if (b.literals != null && a.values.containsAll(b.literals)) a else Type.STR
            a is Type.StrT && b is Type.EnumT -> join(b, a)
            a is Type.EnumT && b is Type.EnumT -> Type.STR
            a is Type.ListT && b is Type.ListT -> join(a.elem, b.elem)?.let { Type.ListT(it) }
            else -> null
        }
    }
}

/** The identifier scope of one expression field (section 3.7). */
interface TypeScope {
    fun root(name: String): Type?
    fun allowsFunction(name: String): Boolean = true

    companion object {
        fun of(roots: Map<String, Type>, functions: Set<String>? = null): TypeScope = object : TypeScope {
            override fun root(name: String): Type? = roots[name]
            override fun allowsFunction(name: String): Boolean = functions == null || name in functions
        }
    }
}
