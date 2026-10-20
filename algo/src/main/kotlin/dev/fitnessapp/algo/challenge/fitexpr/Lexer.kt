package dev.fitnessapp.algo.challenge.fitexpr

import java.math.BigDecimal

internal enum class Tok { NUMBER, STRING, IDENT, TRUE, FALSE, NULL, IN, OP, EOF }

internal data class Token(val kind: Tok, val text: String, val start: Int, val end: Int, val unit: String? = null)

/** FitExpr lexer (section 3.2). Numbers are exact decimal text; units attach without a space. */
internal class Lexer(private val src: String, private val maxStringLength: Int) {
    private var pos = 0

    fun tokens(): List<Token> {
        val out = ArrayList<Token>()
        while (true) {
            val t = next()
            out += t
            if (t.kind == Tok.EOF) return out
        }
    }

    private fun next(): Token {
        while (pos < src.length && src[pos].isWhitespace()) pos++
        if (pos >= src.length) return Token(Tok.EOF, "", pos, pos)
        val start = pos
        val c = src[pos]
        return when {
            c in '0'..'9' -> number(start)
            c == '\'' -> string(start)
            c.isLetter() || c == '_' -> {
                while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_') && src[pos].code < 128) pos++
                val text = src.substring(start, pos)
                val kind = when (text) {
                    "true" -> Tok.TRUE
                    "false" -> Tok.FALSE
                    "null" -> Tok.NULL
                    "in" -> Tok.IN
                    else -> Tok.IDENT
                }
                Token(kind, text, start, pos)
            }
            else -> op(start)
        }
    }

    private fun number(start: Int): Token {
        while (pos < src.length && src[pos] in '0'..'9') pos++
        if (pos + 1 < src.length && src[pos] == '.' && src[pos + 1] in '0'..'9') {
            pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        val digits = src.substring(start, pos)
        var unit: String? = null
        if (pos < src.length && src[pos] == '%') {
            unit = "%"
            pos++
        } else if (pos < src.length && (src[pos].isLetter() || src[pos] == '_')) {
            val us = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            unit = src.substring(us, pos)
            if (unit !in UNITS) fail(start, pos, "unknown unit suffix '$unit'")
        }
        BigDecimal(digits) // validates the text
        return Token(Tok.NUMBER, digits, start, pos, unit)
    }

    private fun string(start: Int): Token {
        pos++
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) fail(start, pos, "unterminated string")
            val c = src[pos]
            when {
                c == '\'' -> {
                    pos++
                    break
                }
                c == '\\' -> {
                    if (pos + 1 >= src.length) fail(start, pos, "unterminated escape")
                    val e = src[pos + 1]
                    if (e != '\'' && e != '\\') fail(pos, pos + 2, "unknown escape \\$e")
                    sb.append(e)
                    pos += 2
                }
                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
        if (sb.length > maxStringLength) fail(start, pos, "string longer than $maxStringLength characters")
        return Token(Tok.STRING, sb.toString(), start, pos)
    }

    private fun op(start: Int): Token {
        for (o in OPS) {
            if (src.startsWith(o, pos)) {
                pos += o.length
                return Token(Tok.OP, o, start, pos)
            }
        }
        fail(start, start + 1, "unexpected character '${src[start]}'")
    }

    private fun fail(s: Int, e: Int, msg: String): Nothing = throw ParseFailure(CompileError("parse", s, e, msg))

    companion object {
        val UNITS = setOf("kg", "lb", "s", "min", "h", "d")

        /** Longest operators first, so `??` wins over `?` and `<=` over `<`. */
        private val OPS = listOf("??", "||", "&&", "==", "!=", "<=", ">=", "?", ":", "!", "<", ">", "+", "-", "*", "/", ".", "[", "]", "(", ")", ",")
    }
}
