package dev.fitnessapp.algo.challenge.fitexpr

import java.math.BigDecimal

/**
 * FitExpr syntax tree (CHALLENGE_ENGINE 3.2). Every node carries an [id] that is unique within its
 * expression (an index into the type table of a compiled expression) and its source span `[start, end)`.
 */
sealed class Node {
    abstract val id: Int
    abstract val start: Int
    abstract val end: Int

    data class NumLit(override val id: Int, override val start: Int, override val end: Int, val value: BigDecimal, val dim: Dim, val text: String) : Node()
    data class StrLit(override val id: Int, override val start: Int, override val end: Int, val value: String) : Node()
    data class BoolLit(override val id: Int, override val start: Int, override val end: Int, val value: Boolean) : Node()
    data class NullLit(override val id: Int, override val start: Int, override val end: Int) : Node()
    data class Ident(override val id: Int, override val start: Int, override val end: Int, val name: String) : Node()
    data class Member(override val id: Int, override val start: Int, override val end: Int, val target: Node, val name: String) : Node()
    data class Index(override val id: Int, override val start: Int, override val end: Int, val target: Node, val index: Node) : Node()
    data class Call(override val id: Int, override val start: Int, override val end: Int, val name: String, val args: List<Node>) : Node()
    data class ListLit(override val id: Int, override val start: Int, override val end: Int, val items: List<Node>) : Node()
    data class Unary(override val id: Int, override val start: Int, override val end: Int, val op: String, val operand: Node) : Node()
    data class Binary(override val id: Int, override val start: Int, override val end: Int, val op: String, val left: Node, val right: Node) : Node()
    data class Ternary(override val id: Int, override val start: Int, override val end: Int, val cond: Node, val then: Node, val otherwise: Node) : Node()

    /** Direct children in source order. */
    fun children(): List<Node> = when (this) {
        is NumLit, is StrLit, is BoolLit, is NullLit, is Ident -> emptyList()
        is Member -> listOf(target)
        is Index -> listOf(target, index)
        is Call -> args
        is ListLit -> items
        is Unary -> listOf(operand)
        is Binary -> listOf(left, right)
        is Ternary -> listOf(cond, then, otherwise)
    }

    /** The dotted path of an identifier chain (`ex.oaa.reps`), or null when the node is not one. */
    fun path(): String? = when (this) {
        is Ident -> name
        is Member -> target.path()?.let { "$it.$name" }
        else -> null
    }
}

/** Numeric dimensions of section 3.3. [INT] is the integer subtype of [NUM]. */
enum class Dim(val unit: String) { INT(""), NUM(""), MASS("kg"), PIN("lb"), DURATION("s"), DAYS("d") }
