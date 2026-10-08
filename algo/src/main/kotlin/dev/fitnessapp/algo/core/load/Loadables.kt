package dev.fitnessapp.algo.core.load

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.ParamView
import java.math.BigDecimal
import java.util.TreeMap

/**
 * B2.4: the exactly loadable options of one equipment record for one exercise, ascending by system load
 * (easier first) and de-duplicated within `load.epsilon_kg`. Ordinal stacks are ordered by level.
 */
object Loadables {
    data class Context(
        val exercise: ExerciseLoadAttrs,
        val bwKg: Double?,
        val equipmentId: String? = null,
        val calibrationRev: Int? = null,
        /** Dumbbell values in kg that may hang on the belt (`dumbbells_on_belt`). */
        val beltDumbbellsKg: List<Double> = emptyList(),
    )

    fun enumerate(scheme: LoadScheme, ctx: Context, params: ParamView): List<LoadPoint> {
        val raw = when (scheme) {
            is LoadScheme.Plates -> plates(scheme, ctx)
            is LoadScheme.Belt -> belt(scheme, ctx)
            is LoadScheme.Dumbbells -> dumbbells(scheme, ctx, params)
            is LoadScheme.Stack -> stack(scheme, ctx, params)
            is LoadScheme.PinAssist -> pins(scheme, ctx, params)
            LoadScheme.Bodyweight -> listOf(point(ctx, LoadMode.BODYWEIGHT, added = 0.0))
            is LoadScheme.Fixed -> listOf(
                point(ctx, LoadMode.EXTERNAL, added = Grid.kg(scheme.kg), native = scheme.kg, unit = "kg"),
            )
        }
        return canonical(raw, params)
    }

    /** Several option lists of one exercise (assisted, bodyweight, belt) on one scale (B2.7). */
    fun merge(lists: List<List<LoadPoint>>, params: ParamView): List<LoadPoint> = canonical(lists.flatten(), params)

    /** Sort easier-first with a total tie-break, then drop options within epsilon of an earlier one. */
    fun canonical(points: List<LoadPoint>, params: ParamView): List<LoadPoint> {
        val eps = params.double("load.epsilon_kg")
        val sorted = points.sortedWith(ORDER)
        val out = ArrayList<LoadPoint>(sorted.size)
        for (p in sorted) {
            val last = out.lastOrNull()
            val dup = last != null && last.ordinal == p.ordinal && if (p.ordinal) {
                last.nativeValue == p.nativeValue
            } else {
                p.scaleKg - last.scaleKg <= eps
            }
            if (!dup) out += p
        }
        return out
    }

    private val ORDER: Comparator<LoadPoint> = compareBy<LoadPoint> { if (it.ordinal) 1 else 0 }
        .thenBy { it.scaleKg }
        .thenBy { it.nativeValue ?: 0.0 }
        .thenBy { it.mode.ordinal }
        .thenBy { it.plateBreakdown.size }
        .thenBy { it.assistPinNative ?: 0.0 }

    private fun plates(s: LoadScheme.Plates, ctx: Context): List<LoadPoint> {
        val inventory = s.plates.map { LoadModel.cents(it.kg) to it.count / 2 }
        val sides = subsetSums(inventory, Int.MAX_VALUE)
        val barCents = s.barKg?.let(LoadModel::cents) ?: 0L
        return sides.keys.map { side ->
            val total = barCents + 2 * side
            val perSide = PlateBreakdown.solveCents(side, inventory)!!.map(LoadModel::fromCents)
            val added = LoadModel.fromCents(total)
            point(
                ctx, LoadMode.EXTERNAL, added = added, native = added, unit = "kg", breakdown = perSide,
                baseNeeded = s.barKg == null, baseOverride = if (s.barKg != null) 0.0 else null,
            )
        }
    }

    private fun belt(s: LoadScheme.Belt, ctx: Context): List<LoadPoint> {
        val inventory = s.plates.map { LoadModel.cents(it.kg) to it.count }
        val maxCents = LoadModel.cents(s.maxKg)
        val sums = subsetSums(inventory, s.maxItems)
        val out = ArrayList<LoadPoint>()
        for ((sum, _) in sums) {
            if (sum == 0L || sum > maxCents) continue // BW + 0 is the bodyweight mode, not a belt load
            val bd = PlateBreakdown.solveCents(sum, inventory, s.maxItems)!!.map(LoadModel::fromCents)
            val added = LoadModel.fromCents(sum)
            out += point(ctx, LoadMode.BODYWEIGHT_PLUS_EXTERNAL, added = added, native = added, unit = "kg", breakdown = bd)
        }
        if (s.dumbbellsOnBelt && s.maxItems >= 1) {
            for (d in ctx.beltDumbbellsKg.map(LoadModel::cents).distinct().sorted()) {
                for ((sum, items) in sums) {
                    if (items > s.maxItems - 1 || d + sum > maxCents) continue
                    val plates = if (sum == 0L) emptyList() else PlateBreakdown.solveCents(sum, inventory, s.maxItems - 1)!!
                    val bd = (plates + d).sortedDescending().map(LoadModel::fromCents)
                    val added = LoadModel.fromCents(d + sum)
                    out += point(ctx, LoadMode.BODYWEIGHT_PLUS_EXTERNAL, added = added, native = added, unit = "kg", breakdown = bd)
                }
            }
        }
        return out
    }

    private fun dumbbells(s: LoadScheme.Dumbbells, ctx: Context, params: ParamView): List<LoadPoint> {
        val count = if (ctx.exercise.laterality == Laterality.BILATERAL) 2 else 1
        val u = LoadModel.unitKg(s.unit, params)!!
        return s.values.map { v ->
            val per = Grid.kg(v * u)
            // added = count x the rounded per-implement mass, so the stored pair satisfies the server check
            // `added_kg = implement_count * load_per_implement_kg` exactly (DATA_MODEL 9.6.4).
            point(
                ctx, LoadMode.EXTERNAL, added = Grid.kg(count * per), native = v, unit = s.unit,
                implementCount = count, perImplementKg = per,
            )
        }
    }

    private fun stack(s: LoadScheme.Stack, ctx: Context, params: ParamView): List<LoadPoint> {
        val u = LoadModel.unitKg(s.unit, params, s.levelKg)
        val start = LoadModel.dec2(s.start)
        val step = LoadModel.dec2(s.step)
        val n = LoadModel.dec2(s.max).subtract(start).divideToIntegralValue(step).toInt()
        val out = ArrayList<LoadPoint>()
        for (i in 0..n) {
            val v = start.add(step.multiply(BigDecimal(i)))
            val variants = listOf(BigDecimal.ZERO) + s.addOnSteps.map(LoadModel::dec2)
            for (a in variants) {
                val native = v.add(a).toDouble()
                out += if (u == null) {
                    point(ctx, LoadMode.EXTERNAL, added = 0.0, native = native, unit = s.unit, ordinal = true)
                } else {
                    point(ctx, LoadMode.EXTERNAL, added = Grid.kg(native * u * s.ratio), native = native, unit = s.unit)
                }
            }
        }
        return out
    }

    private fun pins(s: LoadScheme.PinAssist, ctx: Context, params: ParamView): List<LoadPoint> {
        val max = s.max ?: params.double("load.pin_max_unknown_native")
        val min = LoadModel.dec2(s.min)
        val step = LoadModel.dec2(s.step)
        val n = LoadModel.dec2(max).subtract(min).divideToIntegralValue(step).toInt()
        return (0..n).map { i ->
            val pin = min.add(step.multiply(BigDecimal(i))).toDouble()
            val assist = LoadModel.assistKg(pin, s, params)
            point(ctx, LoadMode.ASSISTED_MACHINE, added = 0.0, assist = assist, pin = pin, native = pin, unit = s.unit)
        }
    }

    /** Achievable sums (hundredths) with at most [maxItems] items -> the minimum item count reaching each sum. */
    private fun subsetSums(inventory: List<Pair<Long, Int>>, maxItems: Int): TreeMap<Long, Int> {
        var sums = TreeMap<Long, Int>().apply { put(0L, 0) }
        for ((size, available) in inventory.sortedByDescending { it.first }) {
            if (size <= 0 || available <= 0) continue
            val next = TreeMap<Long, Int>(sums)
            for ((sum, items) in sums) {
                for (c in 1..available) {
                    if (items + c > maxItems) break
                    val s = sum + c * size
                    val prev = next[s]
                    if (prev == null || items + c < prev) next[s] = items + c
                }
            }
            sums = next
        }
        return sums
    }

    private fun point(
        ctx: Context,
        mode: LoadMode,
        added: Double,
        assist: Double? = null,
        pin: Double? = null,
        native: Double? = null,
        unit: String? = null,
        implementCount: Int? = null,
        perImplementKg: Double? = null,
        breakdown: List<Double> = emptyList(),
        baseNeeded: Boolean = false,
        baseOverride: Double? = null,
        ordinal: Boolean = false,
    ): LoadPoint {
        val ex = ctx.exercise
        val base = baseOverride ?: ex.implementBaseKg
        val fBw = ex.fBw
        val wSys = if (ordinal) null else LoadModel.systemLoad(
            mode, fBw, ctx.bwKg, base, baseNeeded, added, assist ?: 0.0,
        )
        return LoadPoint(
            mode = mode, nativeValue = native, nativeUnit = unit,
            implementCount = implementCount, perImplementKg = perImplementKg,
            addedKg = added, implementBaseKg = base ?: 0.0,
            assistPinNative = pin, assistKg = assist, assistOrdinal = null,
            bwKg = if (fBw > 0) ctx.bwKg else null, fBw = fBw, wSys = wSys,
            plateBreakdown = breakdown, equipmentId = ctx.equipmentId, calibrationRev = ctx.calibrationRev,
            ordinal = ordinal,
        )
    }
}
