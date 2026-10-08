package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadModel
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.algo.core.load.Rounding
import dev.fitnessapp.algo.core.params.ParamView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** `set_logged.prefill_source` values (UX 19.2). */
enum class PrefillSource(val code: String) {
    ACCEPTED_ADJUSTMENT("accepted_adjustment"),
    ENGINE_TARGET("engine_target"),
    LAST_SAME_INDEX("last_same_index"),
    LAST_PREV_INDEX("last_prev_index"),
    DEFAULT("default"),
}

/**
 * The values of one set card. [native] is the value in the equipment's native unit: pin in lb, added kg
 * (barbell total, belt load), dumbbell per hand, stack label; null for bodyweight. Fields that do not apply
 * to the exercise are null.
 */
data class SetValues(
    val loadMode: LoadMode,
    val native: Double? = null,
    val reps: Int? = null,
    val holdS: Double? = null,
)

/** One logged set of an earlier session of the same exercise. */
data class HistorySet(
    val sessionId: String,
    val sessionStart: Instant,
    val setIndex: Int,
    val side: String,
    val values: SetValues,
)

/** The card being filled: set index k (1-based), side (`both`, `L`, `R`), load mode of the card. */
data class SetSlot(val setIndex: Int, val side: String, val loadMode: LoadMode)

data class PrefillContext(
    val acceptedAdjustment: SetValues? = null,
    val target: SetValues? = null,
    val history: List<HistorySet> = emptyList(),
    /** Today's set k-1 of this exercise on the same side (D23), if logged. */
    val todayPrevious: SetValues? = null,
    val default: SetValues,
    /** The item has the side rule "right <= left" (C69); [leftLoggedReps] is today's left set of the pair. */
    val sideRule: Boolean = false,
    val leftLoggedReps: Int? = null,
)

data class Prefill(
    val values: SetValues,
    /** Source of the leading field (load if the card has one, else reps, else hold). */
    val source: PrefillSource,
    val sideCapApplied: Boolean,
)

/** UX 19.2 deviation: fields and a direction; mixed changes count as DOWN (safety first). */
enum class DeviationDirection(val code: String) { NONE("none"), UP("up"), DOWN("down") }

data class FieldDeviation(val field: String, val direction: DeviationDirection)

data class Deviation(val direction: DeviationDirection, val fields: List<FieldDeviation>) {
    /** The reason-chip context of UX 19.3, or null without a deviation. */
    val chipContext: ChipContext?
        get() = when (direction) {
            DeviationDirection.UP -> ChipContext.EASIER
            DeviationDirection.DOWN -> ChipContext.HARDER
            DeviationDirection.NONE -> null
        }

    companion object {
        val NONE = Deviation(DeviationDirection.NONE, emptyList())

        fun of(fields: List<FieldDeviation>): Deviation = when {
            fields.isEmpty() -> NONE
            fields.any { it.direction == DeviationDirection.DOWN } -> Deviation(DeviationDirection.DOWN, fields)
            else -> Deviation(DeviationDirection.UP, fields)
        }
    }
}

/** The target a draft is compared with: load on the [LoadPoint.scaleKg] scale, a rep range, a hold range. */
data class SetTarget(
    val load: LoadPoint? = null,
    val repsLo: Int? = null,
    val repsHi: Int? = null,
    val holdLoS: Double? = null,
    val rir: Double? = null,
)

data class SetDraft(val load: LoadPoint? = null, val reps: Int? = null, val holdS: Double? = null)

sealed class SnapResult {
    /** [changed] = the typed value was not loadable and was moved to [point]. */
    data class Snapped(val point: LoadPoint, val changed: Boolean) : SnapResult()

    /** Further than one gap outside the loadable range; [limit] is the nearest end of the range. */
    data class OutOfRange(val limit: LoadPoint, val above: Boolean) : SnapResult()
}

data class ChipLayout(val grid: Boolean, val chipWidthDp: Double)

/**
 * `ui.prefill` (UI-04, ALGORITHMS_PLANNING 19.7 with C69 and D18): prefill order, steps, snapping and the
 * deviation test. Loadable sets, neighbours and kg values come from `load`; this module only selects and
 * compares them. In M1a "last time" is the only source besides the default (D18); M2/M3 add targets.
 */
object UiPrefill {
    const val MODULE_ID = "ui.prefill"

    // RIR chip row layout (UX 6.2): display constants of InteractionConfig, not params.
    private const val ROW_PADDING_DP = 12.0
    private const val CHIP_GAP_DP = 6.0
    private const val MIN_CHIP_DP = 52.0
    private const val GRID_FONT_SCALE = 1.5

    fun prefill(slot: SetSlot, ctx: PrefillContext): UiResult<Prefill> {
        val history = lastExposure(slot, ctx.history)
        val same = history.firstOrNull { it.setIndex == slot.setIndex }?.values
        val prev = history.filter { it.setIndex < slot.setIndex }.maxByOrNull { it.setIndex }?.values
        // D23: a set changed today (it differs from last time's set at its index) carries forward.
        val lastAtPrevIndex = history.firstOrNull { it.setIndex == slot.setIndex - 1 }?.values
        val todayChanged = ctx.todayPrevious?.takeIf { it.loadMode == slot.loadMode && it != lastAtPrevIndex }

        val chain: List<Pair<PrefillSource, SetValues?>> = listOf(
            PrefillSource.ACCEPTED_ADJUSTMENT to ctx.acceptedAdjustment?.takeIf { it.loadMode == slot.loadMode },
            PrefillSource.ENGINE_TARGET to ctx.target?.takeIf { it.loadMode == slot.loadMode },
            PrefillSource.LAST_PREV_INDEX to todayChanged,
            PrefillSource.LAST_SAME_INDEX to same,
            PrefillSource.LAST_PREV_INDEX to prev,
            PrefillSource.DEFAULT to ctx.default,
        )
        fun <T> pick(f: (SetValues) -> T?): Pair<T?, PrefillSource> {
            for ((src, v) in chain) {
                val x = v?.let(f)
                if (x != null) return x to src
            }
            return null to PrefillSource.DEFAULT
        }
        val (native, nativeSrc) = pick { it.native }
        val (reps, repsSrc) = pick { it.reps }
        val (hold, holdSrc) = pick { it.holdS }

        var cappedReps = reps
        var capApplied = false
        if (ctx.sideRule && slot.side == "R" && reps != null && ctx.leftLoggedReps != null && reps > ctx.leftLoggedReps) {
            cappedReps = ctx.leftLoggedReps
            capApplied = true
        }
        val source = when {
            native != null -> nativeSrc
            reps != null -> repsSrc
            hold != null -> holdSrc
            else -> PrefillSource.DEFAULT
        }
        val value = Prefill(SetValues(slot.loadMode, native, cappedReps, hold), source, capApplied)
        val input = buildJsonObject {
            put("slot", buildJsonObject {
                put("set_index", JsonNumbers.int(slot.setIndex.toLong()))
                put("side", slot.side)
                put("load_mode", slot.loadMode.code)
            })
            put("accepted_adjustment", json(ctx.acceptedAdjustment))
            put("target", json(ctx.target))
            put("last", buildJsonArray { history.forEach { add(json(it.values)) } })
            put("today_previous", json(ctx.todayPrevious))
            put("default", json(ctx.default))
            put("side_rule", ctx.sideRule)
            put("left_logged_reps", ctx.leftLoggedReps?.let { JsonNumbers.int(it.toLong()) } ?: JsonNull)
        }
        return UiResult.of(value, MODULE_ID, input)
    }

    /**
     * The sets of the latest earlier session that has sets of the same exercise in the same load mode and on
     * the same side; history never crosses load modes (C69).
     */
    fun lastExposure(slot: SetSlot, history: List<HistorySet>): List<HistorySet> {
        val eligible = history.filter { it.values.loadMode == slot.loadMode && it.side == slot.side }
        val last = eligible.maxWithOrNull(compareBy<HistorySet> { it.sessionStart }.thenBy { it.sessionId }) ?: return emptyList()
        return eligible.filter { it.sessionId == last.sessionId }.sortedBy { it.setIndex }
    }

    // ---- steps ----

    /** One stepper tap on a load: the neighbouring loadable option; [harder] = +1 on the system-load scale. */
    fun stepLoad(points: List<LoadPoint>, current: LoadPoint, harder: Boolean, params: ParamView): LoadPoint {
        if (current.ordinal) {
            val i = points.indexOfFirst { it.nativeValue == current.nativeValue }
            val j = if (harder) i + 1 else i - 1
            return points.getOrNull(j) ?: current
        }
        return (if (harder) Rounding.nextHarder(points, current.scaleKg, params) else Rounding.nextEasier(points, current.scaleKg, params))
            ?: current
    }

    fun stepReps(reps: Int, up: Boolean): Int = if (up) reps + 1 else maxOf(0, reps - 1)

    /** Hold step: 0.5 s below a 10 s target, otherwise 1 s (C69); the attempt fields use the same step. */
    fun holdStep(targetS: Double?, params: ParamView): Double =
        if (targetS != null && targetS < params.double("ui.prefill.hold_fine_below_s")) params.double("ui.prefill.hold_step_fine_s")
        else params.double("ui.prefill.hold_step_s")

    fun stepHold(holdS: Double, up: Boolean, targetS: Double?, params: ParamView): Double {
        val step = BigDecimal(holdStep(targetS, params))
        val v = BigDecimal(holdS).setScale(1, RoundingMode.HALF_EVEN)
        val next = if (up) v.add(step) else v.subtract(step).max(BigDecimal.ZERO)
        return next.setScale(1, RoundingMode.HALF_EVEN).toDouble()
    }

    /**
     * UX 19.9 result prefill: the timer value on the S grid; a hold that reached its target logs the target,
     * else the value down to the step. In max mode (no target) the step follows [stepBasisS], the previous
     * best (GF-UX-15).
     */
    fun holdResult(measuredS: Double, targetS: Double?, params: ParamView, stepBasisS: Double? = targetS): Double {
        if (targetS != null && measuredS >= targetS) return targetS
        val step = BigDecimal(holdStep(stepBasisS, params))
        val timer = BigDecimal(measuredS).setScale(1, RoundingMode.HALF_EVEN)
        val n = timer.divide(step, 0, RoundingMode.FLOOR)
        return n.multiply(step).setScale(1, RoundingMode.HALF_EVEN).toDouble()
    }

    /** Long press (UX 6.1): step multiplier for the [repeat]-th auto-repeat (0 = the press itself). */
    fun longPressMultiplier(repeat: Int, params: ParamView): Int =
        if (repeat > params.int("ui.prefill.accel_after")) params.int("ui.prefill.accel_factor") else 1

    // ---- keypad ----

    /**
     * Snaps a typed native value to the nearest loadable option (ties to the easier one, C2). A value more
     * than one option gap beyond either end of the range is out of range ("Not loadable here").
     */
    fun snap(typed: Double, scheme: LoadScheme, implementCount: Int, points: List<LoadPoint>, params: ParamView): SnapResult {
        require(points.isNotEmpty()) { "no loadable options" }
        if (points.first().ordinal) {
            val nearest = points.minWithOrNull(compareBy<LoadPoint> { kotlin.math.abs((it.nativeValue ?: 0.0) - typed) }.thenBy { it.nativeValue ?: 0.0 })!!
            return SnapResult.Snapped(nearest, nearest.nativeValue != typed)
        }
        val scale = LoadModel.scaleOfNative(scheme, typed, implementCount, params)!!
        val gap = maxGap(points)
        val eps = params.double("load.epsilon_kg")
        if (scale > points.last().scaleKg + gap + eps) return SnapResult.OutOfRange(points.last(), true)
        if (scale < points.first().scaleKg - gap - eps) return SnapResult.OutOfRange(points.first(), false)
        val p = Rounding.nearest(points, scale, params)
        return SnapResult.Snapped(p, kotlin.math.abs(p.scaleKg - scale) > eps)
    }

    /** The keypad chips: nearest loadable at or below and at or above the typed value. */
    fun keypadChips(typed: Double, scheme: LoadScheme, implementCount: Int, points: List<LoadPoint>, params: ParamView): Pair<LoadPoint?, LoadPoint?> {
        val scale = LoadModel.scaleOfNative(scheme, typed, implementCount, params) ?: return null to null
        return Rounding.bracket(points, scale, params)
    }

    private fun maxGap(points: List<LoadPoint>): Double =
        points.zipWithNext().maxOfOrNull { (a, b) -> b.scaleKg - a.scaleKg } ?: 0.0

    // ---- deviation ----

    /**
     * ALGORITHMS_PLANNING 19.7: load at least half a step from the target, reps outside the target range,
     * hold below `hold_short_frac` x the target (ranges judged against their lower bound). [loadStepKg] is the
     * gap to the neighbouring loadable option at the target.
     */
    fun deviation(draft: SetDraft, target: SetTarget, loadStepKg: Double?, params: ParamView, sideRuleLeftReps: Int? = null): Deviation {
        val fields = ArrayList<FieldDeviation>()
        val tl = target.load
        val dl = draft.load
        if (tl != null && dl != null && !tl.ordinal && !dl.ordinal && loadStepKg != null) {
            val d = dl.scaleKg - tl.scaleKg
            if (kotlin.math.abs(d) >= 0.5 * loadStepKg) fields += FieldDeviation("load", if (d > 0) DeviationDirection.UP else DeviationDirection.DOWN)
        } else if (tl != null && dl != null && tl.ordinal && dl.ordinal && tl.nativeValue != dl.nativeValue) {
            fields += FieldDeviation("load", if ((dl.nativeValue ?: 0.0) > (tl.nativeValue ?: 0.0)) DeviationDirection.UP else DeviationDirection.DOWN)
        }
        val reps = draft.reps
        if (reps != null) {
            if (target.repsLo != null && reps < target.repsLo) fields += FieldDeviation("reps", DeviationDirection.DOWN)
            else if (target.repsHi != null && reps > target.repsHi) fields += FieldDeviation("reps", DeviationDirection.UP)
        }
        val hold = draft.holdS
        if (hold != null && target.holdLoS != null && hold < params.double("ui.prefill.hold_short_frac") * target.holdLoS) {
            fields += FieldDeviation("hold", DeviationDirection.DOWN)
        }
        if (sideRuleLeftReps != null && reps != null && reps > sideRuleLeftReps) {
            fields += FieldDeviation("side_rule", DeviationDirection.UP)
        }
        return Deviation.of(fields)
    }

    /** Post-log RIR deviation (C69): at least `post_rir_delta` from the target RIR. */
    fun postRirDeviation(loggedRir: Double, targetRir: Double?, params: ParamView): Deviation {
        if (targetRir == null) return Deviation.NONE
        val delta = params.double("ui.prefill.post_rir_delta")
        return when {
            loggedRir - targetRir >= delta -> Deviation.of(listOf(FieldDeviation("rir", DeviationDirection.UP)))
            targetRir - loggedRir >= delta -> Deviation.of(listOf(FieldDeviation("rir", DeviationDirection.DOWN)))
            else -> Deviation.NONE
        }
    }

    /** RIR chip row (UX 6.2): six chips in a row, or a 2 x 3 grid when chips get narrow or text is large. */
    fun rirChipLayout(widthDp: Double, fontScale: Double): ChipLayout {
        val w = (widthDp - 2 * ROW_PADDING_DP - 5 * CHIP_GAP_DP) / 6
        val width = BigDecimal(w).setScale(1, RoundingMode.HALF_EVEN).toDouble()
        return ChipLayout(grid = w < MIN_CHIP_DP || fontScale >= GRID_FONT_SCALE, chipWidthDp = width)
    }

    private fun json(v: SetValues?): JsonElement = if (v == null) JsonNull else buildJsonObject {
        put("load_mode", v.loadMode.code)
        put("native", v.native?.let { JsonNumbers.grid(it, GridKind.KG) } ?: JsonNull)
        put("reps", v.reps?.let { JsonNumbers.int(it.toLong()) } ?: JsonNull)
        put("hold_s", v.holdS?.let { JsonNumbers.grid(it, GridKind.S) } ?: JsonNull)
    }
}
