package dev.fitnessapp.algo.challenge.plan

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.HardConstraintSpec
import dev.fitnessapp.algo.challenge.cds.SemanticValidator
import dev.fitnessapp.algo.challenge.eca.Records
import dev.fitnessapp.algo.challenge.eca.RuleEnv
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.EvalError
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class Interval(val start: Instant, val end: Instant) {
    fun overlaps(o: Interval): Boolean = start.isBefore(o.end) && o.start.isBefore(end)
}

/** A gym session the planner must respect: a planned general session, or the last gym session of last week. */
data class GymAnchor(val date: LocalDate, val interval: Interval?, val tags: List<String>)

/** A challenge slot fixed by a matched calendar event (C20): the planner never moves it. */
data class SlotAnchor(val slot: String, val interval: Interval, val source: String, val eventRef: String?, val slotTypeMismatch: Boolean = false)

/** A challenge session already done this week, identified by its template slot (C35). */
data class DoneSession(val slot: String, val date: LocalDate, val interval: Interval?)

/** Availability windows; defaults come from the `challenge.plan.*` params (profile-editable). */
data class Availability(val weekdayStartMin: Int, val weekdayLatestStartMin: Int, val weekendOpenMin: Int, val weekendCloseMin: Int)

data class PlanInput(
    val now: Instant,
    val zone: ZoneId,
    val state: EnrollmentState,
    val facts: EngineFacts,
    /** Monday of the ISO week to plan. */
    val weekStart: LocalDate,
    val done: List<DoneSession> = emptyList(),
    val matched: List<SlotAnchor> = emptyList(),
    val previousLastGym: GymAnchor? = null,
    val general: List<GymAnchor> = emptyList(),
    val runs: List<Interval> = emptyList(),
    val busy: List<Interval> = emptyList(),
    val availability: Availability? = null,
    val projected: Boolean = false,
)

data class PlacedSession(
    val slot: String,
    val templateId: String?,
    val date: LocalDate,
    val interval: Interval,
    /** `done`, `calendar_marked`, `calendar_unmarked` or `suggested` (7.1); `train_now` for the trial of 7.6. */
    val source: String,
    val eventRef: String? = null,
    val slotTypeMismatch: Boolean = false,
    val constraintWarnings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

data class BlockedDay(val date: LocalDate, val scope: String, val tags: List<String>, val reason: String, val ruleId: String)

data class ChallengeWeekPlan(
    val isoWeek: String,
    val mode: String,
    val projected: Boolean,
    val sessions: List<PlacedSession>,
    val blockedDays: List<BlockedDay>,
    val partial: Boolean,
    val droppedSlots: List<String>,
    val relaxations: List<String>,
    val score: Double,
    val warnings: List<String>,
)

/**
 * `challenge.plan@1.0.0` (CHALLENGE_ENGINE 7): places this ISO week's still-required challenge sessions on
 * days and start times under the definition's hard constraints (never relaxed), honouring done sessions and
 * matched calendar events, and scores the feasible assignments (7.5). Starts on one day do not interact
 * with other days, so each slot-day takes its best start and the search enumerates days exhaustively.
 */
object WeekPlanner : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("challenge.plan")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("challenge.plan.busy_buffer_min", "min", 0.0, 90.0),
        ParamSpec.number("challenge.plan.lead_min", "min", 0.0, 240.0),
        ParamSpec.number("challenge.plan.tie_band", "score", 0.05, 0.05),
        ParamSpec.number("challenge.plan.w_after_busy", "score", 0.0, 5.0),
        ParamSpec.number("challenge.plan.w_day_distance", "score", 0.0, 5.0),
        ParamSpec.number("challenge.plan.w_gap_evenness", "score per day of gap deviation", 0.0, 3.0),
        ParamSpec.number("challenge.plan.w_pref_day", "score", 0.0, 10.0),
        ParamSpec.number("challenge.plan.w_pref_start", "score", 0.0, 5.0),
        ParamSpec.number("challenge.plan.w_run_same_day", "score", 0.0, 5.0),
        ParamSpec.integer("challenge.plan.weekday_step_min", "min", 5.0, 60.0),
        ParamSpec.number("challenge.plan.weekday_window_latest_start", "min after local midnight", 0.0, 1439.0),
        ParamSpec.number("challenge.plan.weekday_window_start", "min after local midnight", 0.0, 1439.0),
        ParamSpec.number("challenge.plan.weekend_close", "min after local midnight", 0.0, 1439.0),
        ParamSpec.number("challenge.plan.weekend_open", "min after local midnight", 0.0, 1439.0),
        ParamSpec.integer("challenge.plan.weekend_step_min", "min", 15.0, 60.0),
    )

    /** "Within 2 h after another busy block" (7.5): a fixed part of the scoring formula, not a tunable. */
    private const val AFTER_BUSY_HOURS = 2L
    private val AFTER_BUSY: Duration = Duration.ofHours(AFTER_BUSY_HOURS)

    private class Weights(p: ParamView) {
        val prefDay = p.double("challenge.plan.w_pref_day")
        val prefStart = p.double("challenge.plan.w_pref_start")
        val dayDistance = p.double("challenge.plan.w_day_distance")
        val runSameDay = p.double("challenge.plan.w_run_same_day")
        val afterBusy = p.double("challenge.plan.w_after_busy")
        val gapEvenness = p.double("challenge.plan.w_gap_evenness")
        val tieBand = p.double("challenge.plan.tie_band")
    }

    /** The slots required in [mode]'s week (7.2), with a postponed test shown as its fallback slot (C36). */
    fun requiredSlots(def: CompiledDefinition, params: ParamView, state: EnrollmentState, facts: EngineFacts, weekStart: LocalDate): List<String> {
        val base = def.doc.schedule.slotsPerWeek[state.mode].orEmpty()
        val testSlot = def.doc.structure.block.testSlot
        if (testSlot !in base) return base
        val pt = state.plannedTest
        val weekEnd = weekStart.plusWeeks(1).minusDays(1)
        val postponedHere = pt.basis == "postponed" && (pt.date == null || pt.date.isBefore(weekStart) || pt.date.isAfter(weekEnd))
        val waiting = postponedHere && postponeIf(def, params, state, facts, weekStart)
        return if (waiting) base.map { if (it == testSlot) def.doc.schedule.testDay.postponedSlotBecomes else it } else base
    }

    private fun postponeIf(def: CompiledDefinition, params: ParamView, state: EnrollmentState, facts: EngineFacts, date: LocalDate): Boolean = try {
        val env = RuleEnv(def, params, Records(params), state, date, facts)
        (def.postponeIf.evaluate(env.roots(), env.host) as? BoolV)?.b == true
    } catch (_: EvalError) {
        true
    }

    private fun tagsOf(def: CompiledDefinition, state: EnrollmentState, slot: String): List<String> =
        Materializer.templateFor(def, state, slot)?.tags.orEmpty()

    private fun minuteOfDay(t: LocalTime): Int = java.time.temporal.ChronoUnit.MINUTES.between(LocalTime.MIDNIGHT, t).toInt()

    private fun weekday(code: String): Int = SemanticValidator.WEEKDAYS.indexOf(code) + 1

    private class Session(val slot: String?, val date: LocalDate, val interval: Interval?, val tags: List<String>, val placed: Boolean, val gym: Boolean = true)

    private class Candidate(val slot: String, val date: LocalDate, val interval: Interval, val score: Double, val startScore: Double)

    fun plan(def: CompiledDefinition, params: ParamView, input: PlanInput): ChallengeWeekPlan {
        val s = input.state
        val iso = Week.isoWeek(input.weekStart)
        if (s.status != EnrollmentState.ACTIVE) {
            return ChallengeWeekPlan(iso, s.mode, input.projected, emptyList(), emptyList(), false, emptyList(), emptyList(), 0.0, emptyList())
        }
        val w = Weights(params)
        val required = requiredSlots(def, params, s, input.facts, input.weekStart)
        val doneSlots = input.done.filter { Week.isoWeek(it.date) == iso }
        val matched = input.matched.filter { Week.isoWeek(Week.localDate(it.interval.start, input.zone)) == iso && it.slot !in doneSlots.map { d -> d.slot } }
        val fixed = ArrayList<PlacedSession>()
        for (d in doneSlots) fixed += PlacedSession(d.slot, Materializer.templateFor(def, s, d.slot)?.id, d.date, d.interval ?: dayInterval(d.date, input.zone), "done", tags = tagsOf(def, s, d.slot))
        for (m in matched) {
            fixed += PlacedSession(
                m.slot, Materializer.templateFor(def, s, m.slot)?.id, Week.localDate(m.interval.start, input.zone), m.interval, m.source, m.eventRef,
                m.slotTypeMismatch, tags = tagsOf(def, s, m.slot),
            )
        }
        val remaining = required.filter { slot -> fixed.none { it.slot == slot } }
        val sequence = def.doc.schedule.order.sequence
        val ordered = remaining.sortedBy { slot -> sequence.indexOfFirst { slot in it }.let { if (it < 0) Int.MAX_VALUE else it } }
        val candidates = ordered.associateWith { slot -> candidatesFor(def, params, input, slot, w, fixed) }

        val relaxations = ArrayList<String>()
        var slots = ordered
        val dropped = ArrayList<String>()
        var strictOrder = true
        var best: List<Candidate>? = search(def, params, input, slots, candidates, fixed, strictOrder, w)
        if (best == null && def.doc.schedule.order.strictness == "relaxable") {
            strictOrder = false
            best = search(def, params, input, slots, candidates, fixed, strictOrder, w)
            if (best != null) relaxations += "order"
        }
        val priority = def.doc.schedule.slotPriority
        val protectedSlots = setOf(def.doc.structure.block.testSlot, def.doc.schedule.testDay.postponedSlotBecomes)
        while (best == null && slots.isNotEmpty()) {
            val drop = slots.filter { it !in protectedSlots }.maxByOrNull { priority.indexOf(it) } ?: break
            slots = slots - drop
            dropped += drop
            relaxations += "drop:$drop"
            best = search(def, params, input, slots, candidates, fixed, strictOrder, w)
        }
        val placed = best.orEmpty().map { c ->
            PlacedSession(c.slot, Materializer.templateFor(def, s, c.slot)?.id, c.date, c.interval, "suggested", tags = tagsOf(def, s, c.slot))
        }
        val unplaced = slots.filter { slot -> placed.none { it.slot == slot } }
        val all = (fixed + placed).sortedWith(compareBy({ it.date }, { it.interval.start }))
        val warnings = fixedViolations(def, params, input, all)
        val withWarnings = all.map { p -> p.copy(constraintWarnings = warnings.filter { it.second == p.slot }.map { it.first }) }
        val score = best?.sumOf { it.score }?.minus(w.gapEvenness * gapDeviation(all.map { it.date })) ?: 0.0
        return ChallengeWeekPlan(
            iso, s.mode, input.projected, withWarnings, blockedDays(def, input, all), dropped.isNotEmpty() || unplaced.isNotEmpty(),
            dropped + unplaced, relaxations, score, (warnings.map { it.first } + runWarnings(def, input, all)).distinct(),
        )
    }

    private fun dayInterval(d: LocalDate, zone: ZoneId): Interval =
        Interval(Week.atLocal(d, LocalTime.NOON, zone), Week.atLocal(d, LocalTime.NOON, zone))

    private fun durMin(def: CompiledDefinition, s: EnrollmentState, slot: String): Int =
        def.doc.schedule.preferred.firstOrNull { it.slot == slot }?.durMin ?: Materializer.templateFor(def, s, slot)?.bookedMin ?: 0

    /** 7.3: the best start per day for [slot] (preferred start, availability window, busy blocks, lead time). */
    private fun candidatesFor(def: CompiledDefinition, params: ParamView, input: PlanInput, slot: String, w: Weights, fixed: List<PlacedSession>): List<Candidate> {
        val s = input.state
        val pref = def.doc.schedule.preferred.firstOrNull { it.slot == slot }
        val prefDow = pref?.let { weekday(it.weekday) }
        val prefStart = pref?.let { LocalTime.parse(it.start) }
        val dur = Duration.ofMinutes(durMin(def, s, slot).toLong())
        val av = input.availability ?: Availability(
            params.int("challenge.plan.weekday_window_start"), params.int("challenge.plan.weekday_window_latest_start"),
            params.int("challenge.plan.weekend_open"), params.int("challenge.plan.weekend_close"),
        )
        val buffer = Duration.ofMinutes(params.int("challenge.plan.busy_buffer_min").toLong())
        val lead = Duration.ofMinutes(params.int("challenge.plan.lead_min").toLong())
        val today = Week.localDate(input.now, input.zone)
        val out = ArrayList<Candidate>()
        for (i in 0..6) {
            val d = input.weekStart.plusDays(i.toLong())
            if (d.isBefore(today)) continue
            // One gym visit per day (C50): not on a day that already has a challenge or general session.
            if (fixed.any { it.date == d } || input.general.any { it.date == d }) continue
            val dow = d.dayOfWeek.value
            val weekend = d.dayOfWeek >= DayOfWeek.SATURDAY
            val starts = sortedSetOf<Int>()
            if (prefDow == dow && prefStart != null) starts += minuteOfDay(prefStart)
            if (weekend) {
                val step = params.int("challenge.plan.weekend_step_min")
                var m = av.weekendOpenMin
                while (m + dur.toMinutes() <= av.weekendCloseMin) {
                    starts += m
                    m += step
                }
            } else {
                val step = params.int("challenge.plan.weekday_step_min")
                var m = av.weekdayStartMin
                while (m <= av.weekdayLatestStartMin) {
                    starts += m
                    m += step
                }
            }
            var bestStart: Candidate? = null
            for (m in starts) {
                if (weekend && (m < av.weekendOpenMin || m + dur.toMinutes() > av.weekendCloseMin)) continue
                val start = Week.atLocal(d, LocalTime.MIDNIGHT.plusMinutes(m.toLong()), input.zone)
                if (!start.isAfter(input.now.plus(lead))) continue
                val iv = Interval(start, start.plus(dur))
                val widened = Interval(start.minus(buffer), start.plus(dur).plus(buffer))
                if (input.busy.any { it.overlaps(widened) }) continue
                if (input.general.any { g -> g.interval?.overlaps(iv) == true }) continue
                var startScore = 0.0
                if (prefStart != null && m == minuteOfDay(prefStart)) startScore += w.prefStart
                if (input.busy.any { b -> !b.end.isAfter(start) && Duration.between(b.end, start) <= AFTER_BUSY }) startScore -= w.afterBusy
                if (bestStart == null || Det.cmpScore(startScore, bestStart.startScore) > 0) {
                    bestStart = Candidate(slot, d, iv, 0.0, startScore)
                }
            }
            val b = bestStart ?: continue
            var dayScore = b.startScore
            if (prefDow != null) {
                if (dow == prefDow) dayScore += w.prefDay
                val dist = kotlin.math.abs(dow - prefDow).let { minOf(it, DayOfWeek.entries.size - it) }
                dayScore -= w.dayDistance * dist
            }
            if (input.runs.any { Week.localDate(it.start, input.zone) == d }) dayScore -= w.runSameDay
            out += Candidate(slot, d, b.interval, dayScore, b.startScore)
        }
        return out
    }

    private fun search(
        def: CompiledDefinition,
        params: ParamView,
        input: PlanInput,
        slots: List<String>,
        candidates: Map<String, List<Candidate>>,
        fixed: List<PlacedSession>,
        strictOrder: Boolean,
        w: Weights,
    ): List<Candidate>? {
        val results = ArrayList<Pair<List<Candidate>, Double>>()
        val chosen = ArrayList<Candidate>()
        fun rec(i: Int) {
            if (i == slots.size) {
                if (feasible(def, params, input, fixed, chosen, strictOrder)) {
                    val dates = (fixed.map { it.date } + chosen.map { it.date })
                    val score = chosen.sumOf { it.score } - w.gapEvenness * gapDeviation(dates)
                    results += chosen.toList() to score
                }
                return
            }
            for (c in candidates[slots[i]].orEmpty()) {
                if (chosen.any { it.date == c.date }) continue
                chosen += c
                rec(i + 1)
                chosen.removeAt(chosen.size - 1)
            }
        }
        rec(0)
        if (results.isEmpty()) return if (slots.isEmpty()) emptyList() else null
        val max = results.maxOf { it.second }
        return results.filter { max - it.second <= w.tieBand + Det.TIE_EPS }
            .sortedWith(
                compareBy<Pair<List<Candidate>, Double>>({ r -> r.first.joinToString(",") { it.date.toString() } })
                    .thenBy { r -> r.first.joinToString(",") { it.interval.start.toString() } }
                    .thenBy { r -> r.first.joinToString(",") { it.slot } },
            ).first().first
    }

    /** Sum of |gap_i - mean gap| over consecutive session dates of the week (7.5). */
    private fun gapDeviation(dates: List<LocalDate>): Double {
        val gaps = dates.distinct().sorted().zipWithNext { a, b -> Week.calendarDays(a, b).toDouble() }
        if (gaps.isEmpty()) return 0.0
        val mean = gaps.sum() / gaps.size
        return gaps.sumOf { kotlin.math.abs(it - mean) }
    }

    private fun sessions(def: CompiledDefinition, input: PlanInput, fixed: List<PlacedSession>, chosen: List<Candidate>): List<Session> {
        val s = input.state
        val out = ArrayList<Session>()
        input.previousLastGym?.let { out += Session(null, it.date, it.interval, it.tags, placed = false) }
        for (g in input.general) out += Session(null, g.date, g.interval, g.tags, placed = false)
        for (f in fixed) out += Session(f.slot, f.date, f.interval, f.tags, placed = false)
        for (c in chosen) out += Session(c.slot, c.date, c.interval, tagsOf(def, s, c.slot), placed = true)
        return out
    }

    /** 7.4 on a full assignment; only pairs involving a placed candidate can make it infeasible. */
    private fun feasible(def: CompiledDefinition, params: ParamView, input: PlanInput, fixed: List<PlacedSession>, chosen: List<Candidate>, strictOrder: Boolean): Boolean =
        violations(def, params, input, sessions(def, input, fixed, chosen), strictOrder, onlyPlaced = true).isEmpty()

    private fun violations(def: CompiledDefinition, params: ParamView, input: PlanInput, all: List<Session>, strictOrder: Boolean, onlyPlaced: Boolean): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        val iso = Week.isoWeek(input.weekStart)
        val env = RuleEnv(def, params, Records(params), input.state, input.weekStart, input.facts)
        for (h in def.doc.schedule.hard) {
            when (h.type) {
                "minGapDays" -> {
                    val tagged = all.filter { it.gym && it.tags.any { t -> t in h.appliesToTags } }
                    for (i in tagged.indices) for (j in i + 1 until tagged.size) {
                        val a = tagged[i]
                        val b = tagged[j]
                        if (onlyPlaced && !a.placed && !b.placed) continue
                        if (kotlin.math.abs(Week.calendarDays(a.date, b.date)) < (h.days ?: 0) + 1) out += both(h.id, a, b)
                    }
                }
                "restDayBefore" -> for (x in all.filter { it.slot in h.slots }) {
                    val before = x.date.minusDays(1)
                    for (y in all) {
                        if (y === x || y.date != before || !y.gym) continue
                        if (h.scope == "tags" && y.tags.none { it in h.appliesToTags }) continue
                        if (onlyPlaced && !x.placed && !y.placed) continue
                        out += both(h.id, x, y)
                    }
                    if ((!onlyPlaced || x.placed) && runForbidden(h) && input.runs.any { Week.localDate(it.start, input.zone) == before }) out += h.id to x.slot
                }
                "maxPerWeek" -> {
                    val n = def.hardLimits[h.id]?.let { e ->
                        try {
                            (e.evaluate(env.roots(), env.host) as? QV)?.v?.toInt()
                        } catch (_: EvalError) {
                            null
                        }
                    } ?: continue
                    val inWeek = all.filter { Week.isoWeek(it.date) == iso && h.sessionTag in it.tags }
                    if (inWeek.size > n && (!onlyPlaced || inWeek.any { it.placed })) {
                        out += h.id to null
                        for (x in inWeek) x.slot?.let { out += h.id to it }
                    }
                }
                "slotWeekday" -> for (x in all.filter { it.slot == h.slot && (it.placed || !onlyPlaced) }) {
                    if (x.date.dayOfWeek.value != weekday(h.weekday ?: "")) out += h.id to x.slot
                }
            }
        }
        if (strictOrder) {
            val seq = def.doc.schedule.order.sequence
            val bySlot = all.filter { it.slot != null && Week.isoWeek(it.date) == iso }
            for (g in 0 until seq.size - 1) {
                val a = bySlot.filter { it.slot in seq[g] }
                val b = bySlot.filter { it.slot in seq[g + 1] }
                for (x in a) for (y in b) {
                    if (onlyPlaced && !x.placed && !y.placed) continue
                    if (!x.date.isBefore(y.date)) out += both("order", x, y)
                }
            }
        }
        // D38: a slot that goes first while its condition holds (OAP: the test while there are no loads). Only suggested
        // sessions are moved behind it; a session already done or booked earlier stays where it is.
        for ((slot, cond) in def.orderFirst) {
            val on = try {
                (cond.evaluate(env.roots(), env.host) as? BoolV)?.b == true
            } catch (_: EvalError) {
                false
            }
            if (!on) continue
            val week = all.filter { it.slot != null && Week.isoWeek(it.date) == iso }
            for (x in week.filter { it.slot == slot }) for (y in week.filter { it.slot != slot && it.placed }) {
                if (!x.date.isBefore(y.date)) out += both("first", x, y)
            }
        }
        // A fixed general session inside a hard window of a placed challenge slot (C12).
        for (pw in def.doc.integration.protectedWindows) {
            if (pw.mode != "hard") continue
            if (pw.proposal != null && !input.state.approved(pw.proposal)) continue
            for (x in all.filter { it.slot in pw.slots && (it.placed || !onlyPlaced) }) {
                val iv = x.interval ?: continue
                val window = if (pw.relation == "before") Interval(iv.start.minus(Duration.ofHours(pw.hours.toLong())), iv.start)
                else Interval(iv.end, iv.end.plus(Duration.ofHours(pw.hours.toLong())))
                for (g in input.general) {
                    val giv = g.interval ?: continue
                    val hit = pw.scope == "all_gym" || g.tags.any { it in pw.sessionTags }
                    if (hit && giv.overlaps(window)) out += pw.id to x.slot
                }
            }
        }
        return out
    }

    /** A violation between two sessions is charged to each challenge slot involved, so neither side looks clean. */
    private fun both(id: String, a: Session, b: Session): List<Pair<String, String?>> =
        listOfNotNull(a.slot, b.slot).distinct().map { id to it }.ifEmpty { listOf(id to null) }

    /** 7.4: a run the day before is a violation with `runs: forbid`, or with scope `all_training` unless `runs: warn`. */
    private fun runForbidden(h: HardConstraintSpec): Boolean =
        h.runs == "forbid" || (h.scope == "all_training" && h.runs != "warn")

    /** `restDayBefore` with `runs: warn`: a run the day before a challenge slot is reported, never a violation. */
    private fun runWarnings(def: CompiledDefinition, input: PlanInput, all: List<PlacedSession>): List<String> {
        val out = ArrayList<String>()
        for (h in def.doc.schedule.hard.filter { it.type == "restDayBefore" && it.runs == "warn" }) {
            for (x in all.filter { it.slot in h.slots }) {
                val before = x.date.minusDays(1)
                if (input.runs.any { Week.localDate(it.start, input.zone) == before }) out += "${h.id}:run_before_${x.slot}"
            }
        }
        return out
    }

    /** Hard-constraint violations among fixed sessions: reported, never moved (7.2, 9.7). */
    private fun fixedViolations(def: CompiledDefinition, params: ParamView, input: PlanInput, all: List<PlacedSession>): List<Pair<String, String?>> {
        val sessions = ArrayList<Session>()
        input.previousLastGym?.let { sessions += Session(null, it.date, it.interval, it.tags, placed = false) }
        for (g in input.general) sessions += Session(null, g.date, g.interval, g.tags, placed = false)
        for (p in all) sessions += Session(p.slot, p.date, p.interval, p.tags, placed = p.source == "suggested")
        // Suggested sessions passed the search; a violation charged to one comes from a relaxation the plan reports.
        return violations(def, params, input, sessions, def.doc.schedule.order.strictness == "strict", onlyPlaced = false)
            .filter { v -> all.none { it.source == "suggested" && it.slot == v.second } }
            .distinct()
    }

    /** Blocked days of 8.4 inside the planned ISO week. */
    fun blockedDays(def: CompiledDefinition, input: PlanInput, sessions: List<PlacedSession>): List<BlockedDay> {
        val days = (0..6).map { input.weekStart.plusDays(it.toLong()) }
        val out = sortedMapOf<LocalDate, BlockedDay>()
        val sessionDays = sessions.map { it.date }.toSet()
        for (h in def.doc.schedule.hard.filter { it.type == "restDayBefore" }) {
            for (x in sessions.filter { it.slot in h.slots }) {
                val d = x.date.minusDays(1)
                val tags = if (h.scope == "tags") h.appliesToTags else emptyList()
                if (d in days) out[d] = BlockedDay(d, h.scope ?: "all_gym", tags, "rest day before ${x.slot}", h.id)
            }
        }
        for (h in def.doc.schedule.hard.filter { it.type == "minGapDays" }) {
            val anchors = sessions.filter { it.tags.any { t -> t in h.appliesToTags } }.map { it.date } +
                listOfNotNull(input.previousLastGym?.takeIf { g -> g.tags.any { it in h.appliesToTags } }?.date)
            for (a in anchors) for (k in 1..(h.days ?: 0)) {
                for (d in listOf(a.minusDays(k.toLong()), a.plusDays(k.toLong()))) {
                    if (d !in days || d in sessionDays || out.containsKey(d)) continue
                    out[d] = BlockedDay(d, "tags", h.appliesToTags, "gap day", h.id)
                }
            }
        }
        return out.values.toList()
    }
}
