package dev.fitnessapp.algo.challenge.fitexpr

import java.math.BigDecimal
import java.time.LocalDate
import java.util.SortedMap

/**
 * Runtime values (section 3.3). Numbers are [QV] (dimension + exact decimal); equality of numbers is by
 * `compareTo` ([same]), never `BigDecimal.equals`. [RecordV] holds the identifier scopes; a lookup of an
 * absent field raises `missing:<path>`.
 */
sealed interface Value

data class QV(val dim: Dim, val v: BigDecimal) : Value {
    override fun toString(): String = v.toPlainString() + dim.unit
}

data class DateV(val d: LocalDate) : Value {
    override fun toString(): String = d.toString()
}

data class BoolV private constructor(val b: Boolean) : Value {
    override fun toString(): String = b.toString()

    companion object {
        val TRUE = BoolV(true)
        val FALSE = BoolV(false)
        fun of(b: Boolean): BoolV = if (b) TRUE else FALSE
    }
}

data class StrV(val s: String) : Value {
    override fun toString(): String = "'$s'"
}

data class ListV(val items: List<Value>) : Value {
    override fun toString(): String = items.joinToString(", ", "[", "]")
}

/** A left/right pair; a missing side is null (`side_missing` when it is read). */
data class SideV(val l: Value?, val r: Value?) : Value {
    override fun toString(): String = "{L: $l, R: $r}"
}

data class MapV(val entries: SortedMap<String, Value>) : Value

data class RecordV(val path: String, val fields: Map<String, Value>) : Value {
    fun get(name: String): Value = fields[name] ?: throw EvalError.missing(if (path.isEmpty()) name else "$path.$name")
}

data object NullV : Value {
    override fun toString(): String = "null"
}

object Values {
    fun int(n: Long): QV = QV(Dim.INT, BigDecimal(n))
    fun int(n: Int): QV = QV(Dim.INT, BigDecimal(n))
    fun num(text: String): QV = QV(Dim.NUM, BigDecimal(text))
    fun mass(text: String): QV = QV(Dim.MASS, BigDecimal(text))
    fun pin(text: String): QV = QV(Dim.PIN, BigDecimal(text))
    fun duration(text: String): QV = QV(Dim.DURATION, BigDecimal(text))
    fun days(n: Long): QV = QV(Dim.DAYS, BigDecimal(n))
    fun str(s: String): StrV = StrV(s)
    fun bool(b: Boolean): BoolV = BoolV.of(b)

    /** Semantic equality: numbers by value (2.50 == 2.5), records and maps structurally. */
    fun same(a: Value, b: Value): Boolean = when {
        a is QV && b is QV -> a.v.compareTo(b.v) == 0 && DimRules.comparable(a.dim, b.dim)
        a is ListV && b is ListV -> a.items.size == b.items.size && a.items.indices.all { same(a.items[it], b.items[it]) }
        a is SideV && b is SideV -> sameOrNull(a.l, b.l) && sameOrNull(a.r, b.r)
        else -> a == b
    }

    private fun sameOrNull(a: Value?, b: Value?): Boolean = if (a == null || b == null) a == b else same(a, b)
}
