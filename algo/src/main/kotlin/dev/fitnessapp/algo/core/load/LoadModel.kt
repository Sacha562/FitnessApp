package dev.fitnessapp.algo.core.load

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * LOAD-1 (ALGORITHMS.md B2): the one place that puts every exercise on the system-load scale.
 *
 * `w_sys = f_bw * BW + implement_base_kg + added_kg - assist_kg` (KG grid), undefined (null) for
 * `assisted_ordinal`, when BW is needed but missing, and when the implement base is needed but unknown.
 */
object LoadModel : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("load")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("load.belt_max_items_default", "plates", 1.0, 8.0),
        ParamSpec.number("load.belt_max_kg_default", "kg", 20.0, 100.0),
        ParamSpec.table("load.default_plate_inventory", "kg, count", 0.0, 20.0),
        ParamSpec.number("load.epsilon_kg", "kg", 0.001, 0.01),
        ParamSpec.number("load.lb_kg", "kg/lb", 0.45359237, 0.45359237),
        ParamSpec.number("load.pin_max_unknown_native", "lb", 50.0, 300.0),
        ParamSpec.number("load.review_f_bw_pull_dip", "ratio", 0.9, 1.0),
    )

    /** kg per native unit; `level` only with a calibrated `level_kg` (C43), else null (ordinal). */
    fun unitKg(unit: String, params: ParamView, levelKg: Double? = null): Double? = when (unit) {
        "kg" -> 1.0
        "lb" -> params.double("load.lb_kg")
        "level" -> levelKg
        else -> throw IllegalArgumentException("unit $unit has no kg scale")
    }

    /** C2: `assistKg(pin) = pin * unit_kg * ratio + offset_kg` (KG grid). */
    fun assistKg(pinNative: Double, cal: LoadScheme.PinAssist, params: ParamView): Double {
        val u = requireNotNull(unitKg(cal.unit, params)) { "pin unit ${cal.unit}" }
        return Grid.kg(pinNative * u * cal.ratio + cal.offsetKg)
    }

    /**
     * The B2.2 identity. [implementBaseKg] null means unknown; [baseNeeded] says whether the scheme needs
     * it (plates without a bar: Smith and plate-loaded machines). Returns null when `w_sys` is undefined.
     */
    fun systemLoad(
        mode: LoadMode,
        fBw: Double,
        bwKg: Double?,
        implementBaseKg: Double?,
        baseNeeded: Boolean,
        addedKg: Double,
        assistKg: Double,
    ): Double? {
        if (mode == LoadMode.ASSISTED_ORDINAL) return null
        if (fBw > 0 && bwKg == null) return null
        if (baseNeeded && implementBaseKg == null) return null
        val w = fBw * (bwKg ?: 0.0) + (implementBaseKg ?: 0.0) + addedKg - assistKg
        return Grid.kg(w)
    }

    /** FitExpr `oneArmLoad(pin)`: `f_bw * BW - assistKg(pin)` for the one-arm family (f_bw 1.00). */
    fun oneArmLoad(fBw: Double, bwKg: Double, pinNative: Double, cal: LoadScheme.PinAssist, params: ParamView): Double =
        Grid.kg(fBw * bwKg - assistKg(pinNative, cal, params))

    /**
     * The [LoadPoint.scaleKg] of a typed native value, for snapping keypad input (UI-04) with [Rounding].
     * [implementCount] applies to dumbbells. Returns null for ordinal stacks (snap on the level instead).
     */
    fun scaleOfNative(scheme: LoadScheme, native: Double, implementCount: Int, params: ParamView): Double? = when (scheme) {
        is LoadScheme.PinAssist -> -assistKg(native, scheme, params)
        is LoadScheme.Dumbbells -> Grid.kg(implementCount * native * unitKg(scheme.unit, params)!!)
        is LoadScheme.Stack -> unitKg(scheme.unit, params, scheme.levelKg)?.let { Grid.kg(native * it * scheme.ratio) }
        is LoadScheme.Plates, is LoadScheme.Belt, is LoadScheme.Fixed -> Grid.kg(native)
        LoadScheme.Bodyweight -> 0.0
    }

    // ---- exact decimal helpers (hundredths), shared by Loadables and PlateBreakdown ----

    internal fun cents(x: Double): Long = Grid.decimal(x, GridKind.KG).movePointRight(2).longValueExact()

    internal fun fromCents(c: Long): Double = BigDecimal(c).movePointLeft(2).toDouble()

    /** Native values are stored as numeric(7,2): exact two-decimal arithmetic for stack and pin steps. */
    internal fun dec2(x: Double): BigDecimal = BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN)
}
