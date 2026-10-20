package dev.fitnessapp.algo.challenge.fitexpr

import java.math.BigDecimal

/**
 * Recursive-descent parser for the section 3.2 grammar (lowest precedence first). `!` binds looser than a
 * comparison, comparisons do not chain, and a call is only allowed on a bare function name.
 */
internal class Parser(src: String, private val limits: FitExprLimits) {
    private val toks = Lexer(src, limits.maxStringLength).tokens()
    private var i = 0
    private var nextId = 0
    private var depth = 0

    fun parseAll(): Node {
        val n = expr()
        val t = peek()
        if (t.kind != Tok.EOF) fail(t, "unexpected '${t.text}'")
        return n
    }

    private fun id(): Int = nextId++

    private fun peek(): Token = toks[i]

    private fun isOp(text: String): Boolean = peek().kind == Tok.OP && peek().text == text

    private fun take(): Token = toks[i++]

    private fun expectOp(text: String): Token {
        val t = peek()
        if (t.kind != Tok.OP || t.text != text) fail(t, "expected '$text'")
        return take()
    }

    private fun fail(t: Token, msg: String): Nothing =
        throw ParseFailure(CompileError("parse", t.start, maxOf(t.end, t.start + 1), msg))

    private inline fun <T> nested(start: Token, body: () -> T): T {
        depth++
        if (depth > limits.maxDepth) throw ParseFailure(CompileError("limit", start.start, start.end, "expression nested deeper than ${limits.maxDepth}"))
        try {
            return body()
        } finally {
            depth--
        }
    }

    private fun expr(): Node = nested(peek()) { ternary() }

    private fun ternary(): Node {
        val c = coalesce()
        if (!isOp("?")) return c
        take()
        val a = expr()
        expectOp(":")
        val b = expr()
        return Node.Ternary(id(), c.start, b.end, c, a, b)
    }

    private fun coalesce(): Node {
        var l = or()
        while (isOp("??")) {
            take()
            val r = or()
            l = Node.Binary(id(), l.start, r.end, "??", l, r)
        }
        return l
    }

    private fun or(): Node {
        var l = and()
        while (isOp("||")) {
            take()
            val r = and()
            l = Node.Binary(id(), l.start, r.end, "||", l, r)
        }
        return l
    }

    private fun and(): Node {
        var l = not()
        while (isOp("&&")) {
            take()
            val r = not()
            l = Node.Binary(id(), l.start, r.end, "&&", l, r)
        }
        return l
    }

    private fun not(): Node {
        if (isOp("!")) {
            val t = take()
            val operand = nested(t) { not() }
            return Node.Unary(id(), t.start, operand.end, "!", operand)
        }
        return comparison()
    }

    private fun comparisonOp(): String? {
        val t = peek()
        if (t.kind == Tok.IN) return "in"
        if (t.kind == Tok.OP && t.text in CMP) return t.text
        return null
    }

    private fun comparison(): Node {
        val l = additive()
        val op = comparisonOp() ?: return l
        take()
        val r = additive()
        if (comparisonOp() != null) fail(peek(), "comparisons do not chain; use &&")
        return Node.Binary(id(), l.start, r.end, op, l, r)
    }

    private fun additive(): Node {
        var l = multiplicative()
        while (isOp("+") || isOp("-")) {
            val op = take().text
            val r = multiplicative()
            l = Node.Binary(id(), l.start, r.end, op, l, r)
        }
        return l
    }

    private fun multiplicative(): Node {
        var l = unary()
        while (isOp("*") || isOp("/")) {
            val op = take().text
            val r = unary()
            l = Node.Binary(id(), l.start, r.end, op, l, r)
        }
        return l
    }

    private fun unary(): Node {
        if (isOp("-")) {
            val t = take()
            val operand = nested(t) { unary() }
            return Node.Unary(id(), t.start, operand.end, "-", operand)
        }
        return postfix()
    }

    private fun postfix(): Node {
        val callable = peek().kind == Tok.IDENT
        var n = primary()
        while (true) {
            when {
                isOp(".") -> {
                    take()
                    val t = peek()
                    if (t.kind != Tok.IDENT) fail(t, "expected a field name after '.'")
                    take()
                    n = Node.Member(id(), n.start, t.end, n, t.text)
                }
                isOp("[") -> {
                    take()
                    val ix = expr()
                    val close = expectOp("]")
                    n = Node.Index(id(), n.start, close.end, n, ix)
                }
                isOp("(") -> {
                    val open = peek()
                    val fn = (n as? Node.Ident)?.takeIf { callable } ?: fail(open, "a call is only allowed on a function name")
                    take()
                    val args = ArrayList<Node>()
                    if (!isOp(")")) {
                        args += expr()
                        while (isOp(",")) {
                            take()
                            args += expr()
                        }
                    }
                    val close = expectOp(")")
                    if (args.size > limits.maxCallArgs) {
                        throw ParseFailure(CompileError("limit", fn.start, close.end, "more than ${limits.maxCallArgs} arguments"))
                    }
                    n = Node.Call(id(), fn.start, close.end, fn.name, args)
                }
                else -> return n
            }
        }
    }

    private fun primary(): Node {
        val t = peek()
        return when (t.kind) {
            Tok.NUMBER -> {
                take()
                number(t)
            }
            Tok.STRING -> {
                take()
                Node.StrLit(id(), t.start, t.end, t.text)
            }
            Tok.TRUE, Tok.FALSE -> {
                take()
                Node.BoolLit(id(), t.start, t.end, t.kind == Tok.TRUE)
            }
            Tok.NULL -> {
                take()
                Node.NullLit(id(), t.start, t.end)
            }
            Tok.IDENT -> {
                take()
                Node.Ident(id(), t.start, t.end, t.text)
            }
            Tok.OP -> when (t.text) {
                "(" -> {
                    take()
                    val inner = expr()
                    expectOp(")")
                    inner
                }
                "[" -> {
                    take()
                    val items = ArrayList<Node>()
                    if (!isOp("]")) {
                        items += expr()
                        while (isOp(",")) {
                            take()
                            items += expr()
                        }
                    }
                    val close = expectOp("]")
                    Node.ListLit(id(), t.start, close.end, items)
                }
                else -> fail(t, "unexpected '${t.text}'")
            }
            Tok.IN -> fail(t, "unexpected 'in'")
            Tok.EOF -> fail(t, "unexpected end of expression")
        }
    }

    private fun number(t: Token): Node.NumLit {
        val raw = BigDecimal(t.text)
        val isInt = !t.text.contains('.')
        val (value, dim) = when (t.unit) {
            null -> raw to (if (isInt) Dim.INT else Dim.NUM)
            "%" -> raw.movePointLeft(PERCENT_DIGITS) to Dim.NUM
            "kg" -> raw to Dim.MASS
            "lb" -> raw to Dim.PIN
            "s" -> raw to Dim.DURATION
            "min" -> raw.multiply(SIXTY) to Dim.DURATION
            "h" -> raw.multiply(SECONDS_PER_HOUR) to Dim.DURATION
            "d" -> {
                if (!isInt) fail(t, "days must be whole")
                raw to Dim.DAYS
            }
            else -> fail(t, "unknown unit")
        }
        return Node.NumLit(id(), t.start, t.end, value, dim, t.text + (t.unit ?: ""))
    }

    private companion object {
        val CMP = setOf("==", "!=", "<", "<=", ">", ">=")
        const val PERCENT_DIGITS = 2
        private const val SECONDS_PER_MINUTE_LONG = 60L
        private const val SECONDS_PER_HOUR_LONG = 3600L
        val SIXTY: BigDecimal = BigDecimal(SECONDS_PER_MINUTE_LONG)
        val SECONDS_PER_HOUR: BigDecimal = BigDecimal(SECONDS_PER_HOUR_LONG)
    }
}

/** Fully parenthesised rendering; `parse(print(ast))` reproduces the tree up to ids and spans. */
object Printer {
    fun print(n: Node): String = when (n) {
        is Node.NumLit -> n.text
        is Node.StrLit -> "'" + n.value.replace("\\", "\\\\").replace("'", "\\'") + "'"
        is Node.BoolLit -> n.value.toString()
        is Node.NullLit -> "null"
        is Node.Ident -> n.name
        is Node.Member -> "(" + print(n.target) + ")." + n.name
        is Node.Index -> "(" + print(n.target) + ")[" + print(n.index) + "]"
        is Node.Call -> n.name + "(" + n.args.joinToString(", ") { print(it) } + ")"
        is Node.ListLit -> "[" + n.items.joinToString(", ") { print(it) } + "]"
        is Node.Unary -> n.op + "(" + print(n.operand) + ")"
        is Node.Binary -> "(" + print(n.left) + ") " + n.op + " (" + print(n.right) + ")"
        is Node.Ternary -> "(" + print(n.cond) + ") ? (" + print(n.then) + ") : (" + print(n.otherwise) + ")"
    }
}
