package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.ParamView
import java.time.LocalDate
import java.util.TreeMap

/**
 * One working set as `ui.progress` reads it (ALGORITHMS_PLANNING 19.6): the engine-logged e1RM in system and added kg
 * (C3: never recomputed here, so this type has no load or rep formula), REASON-1's estimator class, and the set's own
 * snapshot: effective load, the load as shown ([shownKg]: signed added kg for a bodyweight lift, else the entered
 * load), reps, hold seconds, assistance, bodyweight. [pain] is a pain flag or the `pain` reason.
 */
data class ProgressSet(
    val setId: String,
    val sessionId: String,
    val atMs: Long,
    val date: LocalDate,
    val side: String,
    val test: Boolean,
    val e1rmKg: Double? = null,
    val e1rmAddedKg: Double? = null,
    val estimatorClass: String? = null,
    val loadKg: Double? = null,
    val shownKg: Double? = null,
    val reps: Int? = null,
    val holdS: Double? = null,
    val assistKg: Double? = null,
    val assistPin: Double? = null,
    val bodyweightKg: Double? = null,
    val failedRep: Boolean = false,
    val pain: Boolean = false,
)

/** A logged level after a session: SSE-1's e1RM level (system kg) or HOLD-1's seconds, with the 80 % interval (C4). */
data class ProgressLevel(val sessionId: String, val atMs: Long, val side: String, val m: Double, val lo: Double, val hi: Double, val bodyweightKg: Double?)

/** How the exercise's system kg becomes added kg for the band (the points use the engine's per-set added kg). */
data class ProgressExercise(val fBw: Double, val implementBaseKg: Double)

enum class ProgressMetric(val label: String, val code: String) {
    STRENGTH("Strength", "e1rm"), REPS("Reps", "reps"), HOLDS("Holds", "hold"), ASSIST("Assist", "assist")
}

enum class ProgressAxis(val code: String) { ADDED("added"), SYSTEM("system") }

/** The kinds of personal best in the Summary's order (UX 19.7.2): e1RM, assistance, hold, reps. */
enum class BestKind(val code: String, val label: String) { E1RM("e1rm", "Strength"), ASSIST("assist", "Assistance"), HOLD("hold", "Hold"), REPS("reps", "Reps") }

/**
 * A personal best (C69): [value] against the [previous] best, with the load for a rep best and the relative [gain]. An
 * e1RM best is in system kg; [addedValue] and [addedPrevious] are the engine's added kg of the two sets (C32).
 */
data class PersonalBest(
    val kind: BestKind,
    val side: String,
    val sessionId: String,
    val setId: String,
    val date: LocalDate,
    val value: Double,
    val previous: Double,
    val loadKg: Double?,
    val gain: Double,
    val addedValue: Double? = null,
    val addedPrevious: Double? = null,
)

/** One chart point: per session and side, the value on the chosen axis and the set that produced it. */
data class ProgressPoint(
    val sessionId: String,
    val setId: String,
    val atMs: Long,
    val date: LocalDate,
    val side: String,
    val y: Double,
    val test: Boolean,
    val best: Boolean,
    val bodyweightKg: Double?,
    val set: ProgressSet,
)

data class ProgressBand(val atMs: Long, val side: String, val m: Double, val lo: Double, val hi: Double)

/** The chart of one metric: points, the logged band (none for reps and assistance), the bests and the unit. */
data class ProgressSeries(
    val metric: ProgressMetric,
    val axis: ProgressAxis,
    val unit: String,
    val signed: Boolean,
    val points: List<ProgressPoint>,
    val band: List<ProgressBand>,
    val bests: List<PersonalBest>,
)

/**
 * `ui.progress` (UI-11, ALGORITHMS_PLANNING 19.6, UX 19.7): the chart series from engine-logged values, SSE-1's band
 * without UI smoothing (C4), the axis from each set's own bodyweight snapshot (C32), and the personal bests of C69.
 * Pure: the caller passes one exercise's sets and levels; every order is by time, then session and set id.
 */
object UiProgress {
    private val ORDER = compareBy<ProgressSet> { it.atMs }.thenBy { it.sessionId }.thenBy { it.setId }

    /** The metric tabs that apply (UX 15.2): only those with data. */
    fun metrics(sets: List<ProgressSet>): List<ProgressMetric> = buildList {
        if (sets.any { it.e1rmKg != null }) add(ProgressMetric.STRENGTH)
        if (sets.any { it.reps != null && it.loadKg != null && it.holdS == null }) add(ProgressMetric.REPS)
        if (sets.any { it.holdS != null }) add(ProgressMetric.HOLDS)
        if (sets.any { it.assistKg != null }) add(ProgressMetric.ASSIST)
    }

    /**
     * Every personal best of one exercise in time order (19.6, C69): an e1RM best above the previous maximum by
     * `pr_min_gain`; a rep best with more reps than ever at a load used before (0.01 kg grid); a hold best by at least
     * `hold_pr_min_gain_s` per side; an assistance best with less assistance (a lower pin) for a clean rep. None before
     * `pr_min_history` earlier sessions of the metric, none from a set with pain, none from an excluded set.
     */
    fun bests(sets: List<ProgressSet>, params: ParamView): List<PersonalBest> {
        val minGain = params.double("ui.progress.pr_min_gain")
        val holdGain = params.double("ui.progress.hold_pr_min_gain_s")
        val minHistory = params.int("ui.progress.pr_min_history")
        val out = ArrayList<PersonalBest>()
        for ((side, bySide) in sets.filter { !it.pain }.groupBy { it.side }.toSortedMap()) {
            val sessions = bySide.sortedWith(ORDER).groupBy { it.sessionId }.values.sortedWith(compareBy({ it.first().atMs }, { it.first().sessionId }))
            var e1rmMax: Double? = null; var e1rmN = 0; var e1rmSet: ProgressSet? = null
            var holdMax: Double? = null; var holdN = 0
            var assistMin: ProgressSet? = null; var assistN = 0
            val repsAt = TreeMap<Double, Int>(); var repsN = 0
            for (xs in sessions) {
                xs.filter { it.e1rmKg != null && it.estimatorClass != "excluded" }.maxWithOrNull(compareBy<ProgressSet> { it.e1rmKg }.thenByDescending { it.setId })?.let { b ->
                    val v = b.e1rmKg!!
                    val prev = e1rmMax
                    if (prev != null && e1rmN >= minHistory && v > prev * (1.0 + minGain)) {
                        out += best(BestKind.E1RM, side, b, v, prev, null, v / prev - 1.0).copy(addedValue = b.e1rmAddedKg, addedPrevious = e1rmSet?.e1rmAddedKg)
                    }
                    if (prev == null || v > prev) { e1rmMax = v; e1rmSet = b }
                    e1rmN++
                }
                xs.filter { it.holdS != null }.maxWithOrNull(compareBy<ProgressSet> { it.holdS }.thenByDescending { it.setId })?.let { b ->
                    val v = b.holdS!!
                    val prev = holdMax
                    if (prev != null && holdN >= minHistory && v >= prev + holdGain - 1e-9) out += best(BestKind.HOLD, side, b, v, prev, null, if (prev > 0) v / prev - 1.0 else 1.0)
                    holdMax = maxOf(prev ?: v, v); holdN++
                }
                xs.filter { it.assistKg != null && (it.reps ?: 0) >= 1 && !it.failedRep }
                    .minWithOrNull(compareBy<ProgressSet> { it.assistKg }.thenBy { it.setId })?.let { b ->
                        val prev = assistMin
                        val less = prev != null && when {
                            b.assistPin != null && prev.assistPin != null -> b.assistPin < prev.assistPin
                            else -> b.assistKg!! < prev.assistKg!! - 0.005
                        }
                        if (less && assistN >= minHistory) out += best(BestKind.ASSIST, side, b, b.assistKg!!, prev!!.assistKg!!, null, if (prev.assistKg!! > 0) 1.0 - b.assistKg / prev.assistKg else 0.0)
                        if (prev == null || b.assistKg!! < prev.assistKg!!) assistMin = b
                        assistN++
                    }
                val withReps = xs.filter { it.reps != null && it.loadKg != null && it.holdS == null }
                if (withReps.isNotEmpty()) {
                    val bestAt = TreeMap<Double, ProgressSet>()
                    withReps.forEach { s -> val k = Grid.kg(s.loadKg!!); if ((bestAt[k]?.reps ?: -1) < s.reps!!) bestAt[k] = s }
                    if (repsN >= minHistory) {
                        bestAt.mapNotNull { (k, s) -> repsAt[k]?.takeIf { s.reps!! > it }?.let { prev -> Triple(k, s, prev) } }
                            .maxWithOrNull(compareBy<Triple<Double, ProgressSet, Int>> { it.second.reps!!.toDouble() / it.third }.thenBy { it.first })
                            ?.let { (k, s, prev) -> out += best(BestKind.REPS, side, s, s.reps!!.toDouble(), prev.toDouble(), k, s.reps.toDouble() / prev - 1.0) }
                    }
                    bestAt.forEach { (k, s) -> if ((repsAt[k] ?: -1) < s.reps!!) repsAt[k] = s.reps }
                    repsN++
                }
            }
        }
        return out.sortedWith(compareBy<PersonalBest> { it.date }.thenBy { it.sessionId }.thenBy { it.kind.ordinal }.thenBy { it.side })
    }

    private fun best(kind: BestKind, side: String, s: ProgressSet, v: Double, prev: Double, load: Double?, gain: Double) =
        PersonalBest(kind, side, s.sessionId, s.setId, s.date, v, prev, load, Grid.ratio(gain))

    /**
     * The chart of [metric] (19.6): per session and side one point (the best set), on [axis] for strength ("added"
     * takes the engine's per-set added kg, so a later bodyweight change does not move old points); the band is the
     * logged level with its 80 % interval, shifted with that session's bodyweight snapshot for added kg.
     */
    fun series(
        metric: ProgressMetric,
        sets: List<ProgressSet>,
        levels: List<ProgressLevel>,
        axis: ProgressAxis,
        exercise: ProgressExercise,
        params: ParamView,
    ): ProgressSeries {
        val bests = bests(sets, params).filter { it.kind.code == metric.code }
        val bestKeys = bests.map { it.sessionId to it.side }.toSet()
        val points = ArrayList<ProgressPoint>()
        for ((side, bySide) in sets.groupBy { it.side }.toSortedMap()) {
            for (xs in bySide.sortedWith(ORDER).groupBy { it.sessionId }.values) {
                val pick: ProgressSet? = when (metric) {
                    ProgressMetric.STRENGTH -> xs.filter { it.e1rmKg != null }.maxWithOrNull(compareBy<ProgressSet> { it.e1rmKg }.thenByDescending { it.setId })
                    ProgressMetric.REPS -> xs.filter { it.reps != null && it.loadKg != null && it.holdS == null }
                        .maxWithOrNull(compareBy<ProgressSet> { it.reps }.thenBy { it.loadKg }.thenByDescending { it.setId })
                    ProgressMetric.HOLDS -> xs.filter { it.holdS != null }.maxWithOrNull(compareBy<ProgressSet> { it.holdS }.thenByDescending { it.setId })
                    ProgressMetric.ASSIST -> xs.filter { it.assistKg != null && (it.reps ?: 0) >= 1 && !it.failedRep }
                        .minWithOrNull(compareBy<ProgressSet> { it.assistKg }.thenBy { it.setId })
                }
                pick ?: continue
                val y = when (metric) {
                    ProgressMetric.STRENGTH -> if (axis == ProgressAxis.SYSTEM) pick.e1rmKg!! else pick.e1rmAddedKg ?: (pick.e1rmKg!! - exercise.implementBaseKg)
                    ProgressMetric.REPS -> pick.reps!!.toDouble()
                    ProgressMetric.HOLDS -> pick.holdS!!
                    ProgressMetric.ASSIST -> pick.assistKg!!
                }
                points += ProgressPoint(pick.sessionId, pick.setId, pick.atMs, pick.date, side, Grid.kg(y), pick.test, (pick.sessionId to side) in bestKeys, pick.bodyweightKg, pick)
            }
        }
        val band = when (metric) {
            ProgressMetric.STRENGTH -> levels.mapNotNull { l ->
                if (axis == ProgressAxis.SYSTEM) return@mapNotNull ProgressBand(l.atMs, l.side, l.m, l.lo, l.hi)
                val shift = if (exercise.fBw == 0.0) exercise.implementBaseKg else l.bodyweightKg?.let { exercise.fBw * it + exercise.implementBaseKg } ?: return@mapNotNull null
                ProgressBand(l.atMs, l.side, Grid.kg(l.m - shift), Grid.kg(l.lo - shift), Grid.kg(l.hi - shift))
            }
            ProgressMetric.HOLDS -> levels.map { ProgressBand(it.atMs, it.side, it.m, it.lo, it.hi) }
            else -> emptyList()
        }.sortedWith(compareBy<ProgressBand> { it.atMs }.thenBy { it.side })
        val unit = when (metric) {
            ProgressMetric.STRENGTH, ProgressMetric.ASSIST -> "kg"
            ProgressMetric.REPS -> "reps"
            ProgressMetric.HOLDS -> "s"
        }
        val signed = metric == ProgressMetric.STRENGTH && axis == ProgressAxis.ADDED && exercise.fBw > 0.0
        return ProgressSeries(metric, axis, unit, signed, points.sortedWith(compareBy<ProgressPoint> { it.atMs }.thenBy { it.side }), band, bests)
    }

    /** The window of a range chip (UX 19.7.1): the last [weeks] weeks before the newest point; null weeks = all. */
    fun window(s: ProgressSeries, weeks: Int?): ProgressSeries {
        val latest = (s.points.map { it.atMs } + s.band.map { it.atMs }).maxOrNull() ?: return s
        if (weeks == null) return s
        val from = latest - weeks * 7L * 86_400_000L
        val keep = s.points.filter { it.atMs >= from }.map { it.sessionId }.toSet()
        return s.copy(points = s.points.filter { it.atMs >= from }, band = s.band.filter { it.atMs >= from }, bests = s.bests.filter { it.sessionId in keep })
    }

    /**
     * The Session Summary's new bests (UX 12.3, 19.7.2): those of [sessionId], ordered by relative gain, then kind, then
     * exercise and side; [max] is `ui.progress.max_prs_summary` unless "Show all".
     */
    fun sessionBests(byExercise: Map<String, List<PersonalBest>>, sessionId: String, max: Int?): List<Pair<String, PersonalBest>> {
        val all = byExercise.toSortedMap().flatMap { (ex, bs) -> bs.filter { it.sessionId == sessionId }.map { ex to it } }
            .sortedWith(compareByDescending<Pair<String, PersonalBest>> { it.second.gain }.thenBy { it.second.kind.ordinal }.thenBy { it.first }.thenBy { it.second.side })
        return if (max == null) all else all.take(max)
    }

    /**
     * The challenge gate next to the SSE-1 estimate (19.6, C4, C62): the gate metric's test value (D7 personal curve),
     * its x1.10 value, the threshold on the test day's bodyweight and the forward threshold at today's; all labelled.
     */
    data class Gate(
        val label: String,
        val value: Double?,
        val valueDate: LocalDate?,
        val m110: Double?,
        val thresholdNow: Double,
        val bwNow: Double?,
        val thresholdTest: Double?,
        val bwTest: Double?,
        val ratioText: String?,
    )

    fun gateLines(g: Gate, estimate: ProgressBand?, kg: (Double) -> String): List<String> = buildList {
        estimate?.let { add("Training estimate (SSE-1) ${kg(it.m)} (80 %: ${kg(it.lo)} to ${kg(it.hi)})") }
        if (g.value != null) {
            val date = g.valueDate?.let { " (test ${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)})" }.orEmpty()
            add("${g.label}: gate value ${kg(g.value)}$date" + (g.m110?.let { " · ×1.10 rule ${kg(it)}" } ?: ""))
        }
        val now = "target ${kg(g.thresholdNow)}" + (g.bwNow?.let { " at today's ${num(it)} kg" } ?: "")
        val test = if (g.thresholdTest != null && g.bwTest != null && Grid.kg(g.thresholdTest) != Grid.kg(g.thresholdNow)) {
            " · ${kg(g.thresholdTest)} at the test day's ${num(g.bwTest)} kg"
        } else ""
        add("Gate $now$test")
    }

    private fun num(x: Double): String = dev.fitnessapp.algo.core.det.Canon.esNumber(x, dev.fitnessapp.algo.core.det.GridKind.KG)
}
