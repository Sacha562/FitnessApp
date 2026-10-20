package dev.fitnessapp.algo.challenge.fitexpr

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.NormalQuantile
import dev.fitnessapp.algo.core.load.ExerciseLoadAttrs
import dev.fitnessapp.algo.core.load.Laterality
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadModel
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.algo.core.load.Loadables
import dev.fitnessapp.algo.core.load.Rounding
import dev.fitnessapp.algo.core.params.ParamView
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.SortedMap

/** The C2 calibration record of an assisted machine, in the machine's native unit. */
data class MachineCalibration(
    val unit: String,
    val step: BigDecimal,
    val min: BigDecimal,
    val max: BigDecimal?,
    val offsetKg: BigDecimal,
    val ratio: BigDecimal,
    val rev: Int,
) {
    fun scheme(): LoadScheme.PinAssist = LoadScheme.PinAssist(
        unit, Dec.toDouble(step), Dec.toDouble(min), max?.let(Dec::toDouble), Dec.toDouble(offsetKg), Dec.toDouble(ratio),
    )
}

/** RCM-1 output for one definition exercise key, captured before the session's own estimator update. */
data class UserKFact(val catalogId: String, val k: BigDecimal, val sdLnk: BigDecimal, val fitRunId: String?, val informativeWindows: Int)

/** Achievable added loads of a named load profile (for example `belt`), ascending, kg grid. */
data class LoadProfile(val achievableKg: List<BigDecimal>, val rev: Int)

/**
 * The engine-fact snapshot of section 3.9: everything another module knows that a definition expression may
 * read, frozen into the event payload so that a replay never asks the live module again.
 */
data class EngineFacts(
    val capturedAt: Instant? = null,
    val bwKg: BigDecimal? = null,
    val userK: SortedMap<String, UserKFact> = sortedMapOf(),
    /** Body-region code -> date of the last pain report there. */
    val painLastDate: SortedMap<String, LocalDate> = sortedMapOf(),
    val has: List<String> = emptyList(),
    val machines: SortedMap<String, MachineCalibration> = sortedMapOf(),
    val loadProfiles: SortedMap<String, LoadProfile> = sortedMapOf(),
    val weekPlanSlots: SortedMap<LocalDate, List<String>> = sortedMapOf(),
)

/** Exact decimal helpers at the double boundary (C22: values from double modules are grid-rounded first). */
object Dec {
    private val PARAM_MC = MathContext(15, RoundingMode.HALF_EVEN)

    /** A params value (decimal text parsed to the nearest double) back to its decimal text. */
    fun ofParam(x: Double): BigDecimal = norm(BigDecimal(x).round(PARAM_MC))

    /** Without trailing zeros and never in exponent form (`40`, not `4E+1`). */
    fun norm(x: BigDecimal): BigDecimal {
        val s = x.stripTrailingZeros()
        return if (s.scale() < 0) s.setScale(0) else s
    }

    fun kg(x: Double): BigDecimal = Grid.decimal(x, GridKind.KG)

    fun ratio(x: Double): BigDecimal = Grid.decimal(x, GridKind.RATIO)

    /** Correctly rounded on every runtime (decimal text to double), unlike `BigDecimal.doubleValue`. */
    fun toDouble(x: BigDecimal): Double = x.toPlainString().toDouble()
}

/**
 * The LOAD-1 and RCM-1 parts of the function whitelist (section 3.8), computed from [EngineFacts] only.
 * `roundLoad` and `roundPin` delegate to [Rounding] (OI-A06); FitExpr never rounds loads itself.
 */
class FactsMath(private val params: ParamView, private val facts: EngineFacts, private val machineId: String?) {
    private val lbKg: BigDecimal = Dec.ofParam(params.double("load.lb_kg"))

    fun lbKg(): BigDecimal = lbKg

    private fun machine(): MachineCalibration {
        val id = machineId ?: throw EvalError.missing("engine_facts.machines")
        return facts.machines[id] ?: throw EvalError.missing("engine_facts.machines.$id")
    }

    fun assistKg(pin: BigDecimal): BigDecimal = Dec.kg(LoadModel.assistKg(Dec.toDouble(pin), machine().scheme(), params))

    fun oneArmLoad(pin: BigDecimal): BigDecimal {
        val bw = facts.bwKg ?: throw EvalError.missing("bw")
        return Dec.kg(LoadModel.oneArmLoad(1.0, Dec.toDouble(bw), Dec.toDouble(pin), machine().scheme(), params))
    }

    fun roundLoad(m: BigDecimal, profile: String, mode: String): BigDecimal {
        val p = facts.loadProfiles[profile] ?: throw EvalError.missing("engine_facts.load_profiles.$profile")
        if (p.achievableKg.isEmpty()) throw EvalError("unloadable", "load profile $profile is empty")
        val options = Loadables.canonical(p.achievableKg.map { profilePoint(Dec.toDouble(it)) }, params)
        val target = Dec.toDouble(m)
        val chosen = when (mode) {
            "down" -> Rounding.easierOrEqual(options, target, params)
            "up" -> Rounding.harderOrEqual(options, target, params)
            "nearest" -> Rounding.nearest(options, target, params)
            else -> throw EvalError.type("roundLoad mode $mode")
        }
        return Dec.norm(Dec.kg(chosen.addedKg))
    }

    /** Pins map to system load first, so `up` (more assistance) is LOAD-1's easierOrEqual (C2). */
    fun roundPin(x: BigDecimal, mode: String): BigDecimal {
        val cal = machine().scheme()
        val ctx = Loadables.Context(PIN_ATTRS, bwKg = null)
        val options = Loadables.enumerate(cal, ctx, params)
        if (options.isEmpty()) throw EvalError("unloadable", "machine without pins")
        val u = requireNotNull(LoadModel.unitKg(cal.unit, params))
        val target = -(Dec.toDouble(x) * u * cal.ratio + cal.offsetKg)
        val chosen = when (mode) {
            "up" -> Rounding.easierOrEqual(options, target, params)
            "down" -> Rounding.harderOrEqual(options, target, params)
            "nearest" -> Rounding.nearest(options, target, params)
            else -> throw EvalError.type("roundPin mode $mode")
        }
        return Dec.norm(Dec.kg(requireNotNull(chosen.assistPinNative)))
    }

    fun userK(key: String): BigDecimal = (facts.userK[key] ?: throw EvalError.missing("engine_facts.user_k.$key")).k

    /** `exp(ln k + z(q) x sd_lnk)` clamped to the RCM-1 grid bounds, ratio grid. */
    fun userKQuantile(key: String, q: BigDecimal): BigDecimal {
        val f = facts.userK[key] ?: throw EvalError.missing("engine_facts.user_k.$key")
        val z = NormalQuantile.z(Dec.toDouble(q))
        val k = Det.exp(Det.ln(Dec.toDouble(f.k)) + z * Dec.toDouble(f.sdLnk))
        val clamped = k.coerceIn(params.double("rcm.k_min"), params.double("rcm.k_max"))
        return Dec.norm(Dec.ratio(clamped))
    }

    private fun profilePoint(kg: Double): LoadPoint = LoadPoint(
        mode = LoadMode.BODYWEIGHT_PLUS_EXTERNAL, nativeValue = kg, nativeUnit = "kg", implementCount = null,
        perImplementKg = null, addedKg = Grid.kg(kg), implementBaseKg = 0.0, assistPinNative = null, assistKg = null,
        assistOrdinal = null, bwKg = null, fBw = 1.0, wSys = null, plateBreakdown = emptyList(), equipmentId = null,
        calibrationRev = null,
    )

    private companion object {
        val PIN_ATTRS = ExerciseLoadAttrs(1.0, null, Laterality.UNILATERAL, listOf(LoadMode.ASSISTED_MACHINE))
    }
}
