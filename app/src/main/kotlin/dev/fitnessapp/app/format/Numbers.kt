package dev.fitnessapp.app.format

import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadPoint
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Display formatting (UX 5, D12): English, decimal point regardless of the phone locale, native unit first and
 * kilograms second, assist kg with one decimal (C54). Storage stays on the canonical grids.
 */
object Numbers {
    /** Up to [maxDecimals] decimals without trailing zeros: 22.5, 20, 1.25. */
    fun num(x: Double, maxDecimals: Int = 2): String =
        BigDecimal(x).setScale(maxDecimals, RoundingMode.HALF_EVEN).stripTrailingZeros().let {
            if (it.signum() == 0) "0" else it.toPlainString()
        }

    fun oneDecimal(x: Double): String = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toPlainString()

    fun kg(x: Double): String = num(x) + " kg"

    /** The main load line of the set card (UX 11.7). */
    fun loadMain(p: LoadPoint?, mode: LoadMode): String = when {
        mode == LoadMode.BODYWEIGHT -> "BW"
        p == null -> "—"
        mode == LoadMode.ASSISTED_MACHINE -> "Pin " + num(p.assistPinNative ?: 0.0) + " " + (p.nativeUnit ?: "lb")
        mode == LoadMode.BODYWEIGHT_PLUS_EXTERNAL -> "BW + " + kg(p.addedKg)
        p.ordinal -> "Level " + num(p.nativeValue ?: 0.0)
        p.nativeUnit == "lb" -> num(p.nativeValue ?: 0.0) + " lb"
        else -> kg(p.nativeValue ?: p.addedKg)
    }

    /** The secondary line: plates, per hand, assist kg and one-arm / effective load (UX 11.7, C54). */
    fun loadSecondary(p: LoadPoint?, mode: LoadMode, unilateral: Boolean, bwKg: Double?): String? {
        if (p == null) return null
        return when (mode) {
            LoadMode.ASSISTED_MACHINE -> {
                val assist = "≈ " + oneDecimal(p.assistKg ?: 0.0) + " kg assist"
                val w = p.wSys ?: return assist
                val pct = bwKg?.takeIf { it > 0 }?.let { " (" + BigDecimal(w / it * 100).setScale(0, RoundingMode.HALF_EVEN).toPlainString() + " % BW)" } ?: ""
                assist + " · " + (if (unilateral) "one-arm load " else "effective ") + oneDecimal(w) + " kg" + pct
            }
            LoadMode.BODYWEIGHT_PLUS_EXTERNAL ->
                if (p.plateBreakdown.isEmpty()) null else p.plateBreakdown.joinToString(" + ") { num(it) } + " on belt"
            LoadMode.EXTERNAL -> when {
                p.implementCount != null -> "per hand"
                p.plateBreakdown.isNotEmpty() -> p.plateBreakdown.joinToString(" + ") { num(it) } + " per side"
                p.nativeUnit == "lb" -> "≈ " + oneDecimal(p.addedKg) + " kg"
                else -> null
            }
            else -> null
        }
    }

    fun clock(totalS: Long): String {
        val h = totalS / 3600
        val m = (totalS % 3600) / 60
        val s = totalS % 60
        fun two(v: Long) = if (v < 10) "0$v" else v.toString()
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }
}
