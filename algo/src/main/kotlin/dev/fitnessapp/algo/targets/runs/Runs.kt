package dev.fitnessapp.algo.targets.runs

import dev.fitnessapp.algo.accounting.readiness.Dose
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/** A run (6.2): from a calendar event classified as a run, or logged. [intensity] is easy, tempo, intervals or race. */
data class Run(val id: String, val start: Instant, val end: Instant, val minutes: Double, val intensity: String, val long: Boolean = false, val source: String = "calendar")

/** A challenge rest day before a slot whose `restDayBefore.runs` policy is `warn` (C11). */
data class RunRestDay(val date: LocalDate, val slot: String)

data class RunPenalty(val value: Double, val reasons: List<String>)

data class RunWarning(val code: String, val runId: String, val slot: String)

/**
 * `runs@1.0.0` RunInterference.v1 (ALGORITHMS_PLANNING 6, F6): a run is leg and systemic fatigue with no hypertrophy
 * credit, a timing penalty for candidate sessions that give legs sets, and a warning (never a block) when it falls on
 * a challenge rest day whose policy is `warn`. Upper-body sessions get no run penalty.
 */
object Runs : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("runs")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("runs.hard_run_h", "h", 24.0, 72.0),
        ParamSpec.number("runs.hard_run_pen", "J", 0.0, 2.0),
        ParamSpec.table("runs.intensity", "multiplier (by run intensity)", 0.5, 3.0),
        ParamSpec.number("runs.long_factor", "multiplier", 1.0, 2.0),
        ParamSpec.table("runs.per_min", "fatigue units per min (by group)", 0.0, 0.2),
        ParamSpec.table("runs.priority_mult", "multiplier (by running priority)", 0.0, 3.0),
        ParamSpec.number("runs.run_after_pen", "J", 0.0, 2.0),
        ParamSpec.number("runs.run_before_pen", "J", 0.0, 2.0),
        ParamSpec.number("runs.sys_per_40min", "systemic units per 40 min", 0.2, 1.5),
        ParamSpec.integer("runs.window_h", "h", 4.0, 24.0),
    )

    private val HARD = setOf("tempo", "intervals", "race")

    /** The readiness dose of a run at its end (6.4): leg fatigue per minute and a systemic load per 40 minutes. */
    fun dose(run: Run, params: ParamView): Dose {
        val inten = requireNotNull(params.table("runs.intensity")[run.intensity]) { "run intensity ${run.intensity}" }
        // RUN_LONG_MINUTES is the classifier's key (1.4 and 6.4 share the one threshold).
        val long = run.long || run.minutes > params.double("calendar.classify.run_long_minutes")
        val f = inten * (if (long) params.double("runs.long_factor") else 1.0)
        val fat = TreeMap<String, Double>()
        params.table("runs.per_min").forEach { (g, perMin) -> fat[g] = Grid.ratio(perMin * run.minutes * f) }
        val sys = params.double("runs.sys_per_40min") * (run.minutes / 40.0) * inten
        return Dose("run:${run.id}", run.end, fat, emptyMap(), Grid.ratio(sys))
    }

    /** The penalty of a candidate session [start, end] that gives [legFs] fractional sets to legs groups (6.4). */
    fun penalty(start: Instant, end: Instant, legFs: Double, runs: List<Run>, priority: String, params: ParamView): RunPenalty {
        if (legFs <= 0.0) return RunPenalty(0.0, emptyList())
        val m = requireNotNull(params.table("runs.priority_mult")[priority]) { "running priority $priority" }
        val w = params.double("runs.window_h")
        val hardH = params.double("runs.hard_run_h")
        var p = 0.0
        val reasons = ArrayList<String>()
        for (r in runs.sortedWith(compareBy({ it.start }, { it.id }))) {
            if (r.end <= start && Week.elapsedHours(r.end, start) <= w) {
                p += params.double("runs.run_before_pen") * m
                reasons += "RUN_NEAR"
            }
            if (r.start >= end && Week.elapsedHours(end, r.start) <= w) {
                p += params.double("runs.run_after_pen") * m
                reasons += "RUN_NEAR"
            }
            val hard = r.intensity in HARD || r.long
            if (priority != "low" && hard && r.start > end && Week.elapsedHours(end, r.start) <= hardH) {
                p += params.double("runs.hard_run_pen") * m
                reasons += "RUN_NEAR"
            }
        }
        return RunPenalty(Grid.ratio(p), reasons.distinct())
    }

    /** `RUN_ON_REST_DAY` for each run on a challenge rest day with the `warn` policy (C11). */
    fun restDayWarnings(runs: List<Run>, restDays: List<RunRestDay>, zone: ZoneId): List<RunWarning> =
        runs.sortedWith(compareBy({ it.start }, { it.id })).flatMap { r ->
            restDays.filter { it.date == Week.localDate(r.start, zone) }.sortedBy { it.slot }.map { RunWarning("RUN_ON_REST_DAY", r.id, it.slot) }
        }
}
