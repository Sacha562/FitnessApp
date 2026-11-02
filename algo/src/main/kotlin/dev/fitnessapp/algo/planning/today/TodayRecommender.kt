package dev.fitnessapp.algo.planning.today

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Canon
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.weekplan.Band
import dev.fitnessapp.algo.planning.weekplan.Eval
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.Point
import dev.fitnessapp.algo.planning.weekplan.ReasonCode
import dev.fitnessapp.algo.planning.weekplan.SimSession
import dev.fitnessapp.algo.planning.weekplan.TodayOverride
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeekPlanInput
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import dev.fitnessapp.algo.targets.Targets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/** The inputs of 9.2: the planner's inputs, the time chip, and the hard rests the planner cannot see. */
data class TodayInput(
    val plan: WeekPlanInput,
    /** The selected time chip (null: the calendar budget, else the default length). */
    val chipMin: Double? = null,
    /** A safety state that forbids gym today (`SAFETY_JOINT`, `SAFETY_PROTOCOL`, C33). */
    val safetyRest: ReasonCode? = null,
    /** A challenge mode that forbids today's challenge session. */
    val challengeRest: ReasonCode? = null,
)

/** A reason with its precedence class (0.6: SAFETY 0, CHALLENGE 1, USER 2, GENERAL 3) and signed weight (9.6). */
data class Reason(val code: String, val cls: Int, val params: Map<String, String>, val weight: Double)

data class TodayOption(
    val optionId: String,
    /** The general session's type code; null for a challenge session alone. */
    val sessionType: String?,
    val label: String,
    val score: Double,
    val dJ: Double,
    val estMinutes: Double,
    val direct: Map<String, Double>,
    val credit: Map<String, Double>,
    /** The add-on family code of a challenge option, or of the best week with this option. */
    val addon: String?,
    val reasons: List<Reason>,
    /**
     * The types of the standalone general sessions after today in this option's own best week (D78): session building
     * divides the week's remaining sets over today and these, so the build matches the option.
     */
    val laterTypes: List<String> = emptyList(),
)

data class PlannedToday(val kind: String, val slot: String?, val type: String?, val start: Instant, val end: Instant, val anchorId: String, val label: String)

data class Excluded(val subject: String, val reasonCode: String, val cls: Int, val params: Map<String, String>)

data class RestAdvice(val flag: Boolean, val hard: Boolean, val reasonCode: String?, val params: Map<String, String>)

/** `TodayOptions` of 9.3. */
data class TodayOptions(
    val date: LocalDate,
    val budgetMin: Double,
    val budgetSource: String,
    val verdict: String,
    val planned: PlannedToday?,
    val options: List<TodayOption>,
    val primaryOptionId: String?,
    val restReasons: List<Reason>,
    val restAdvice: RestAdvice,
    val excluded: List<Excluded>,
    val weekPlanId: String,
    /** The week plan Today is consistent with (its day reasons and the week strip). */
    val weekPlan: WeekPlan,
)

/**
 * `today@1.0.0` TodayRecommender.v1 (ALGORITHMS_PLANNING 9): "do I train today, and what?" from the week planner's own
 * objective. The value of training type `C` today is the best weekly `J` with today pinned to `C` against the best
 * weekly `J` without a session today; a challenge day compares its add-on families the same way. The verdict (train,
 * soft rest, hard rest) is decided here; `ui.todaycard` only maps it to a card.
 */
object TodayRecommender : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("today")

    const val TRAIN = "TRAIN"
    const val REST_SOFT = "REST_SOFT"
    const val REST_HARD = "REST_HARD"

    /** The versioned reason-code enum (0.6, F19), in the order of the params file. */
    val REASON_CODES: List<String> = listOf(
        "SAFETY_JOINT", "SAFETY_PROTOCOL", "REST_BEFORE", "PROTECT_REST", "GAP_DAY", "CHALLENGE_DUE", "NEXT_FIXED", "DELOAD_WEEK",
        "COVERED_BY_CHALLENGE", "RUN_ON_REST_DAY", "PLANNED_CAL", "MAX_VISITS", "BUDGET_FIT", "NO_SLOT", "TIME_SHORT", "VOLUME_DEFICIT",
        "RECOVERED", "HOURS_SINCE", "REENTRY", "BETTER_OTHER_DAY", "BETTER_THIS_DAY", "FREQ_BONUS", "LOW_SYSTEMIC", "RECOVERING", "SORE",
        "HEAVY_LOWER_GAP", "CONSECUTIVE", "RUN_NEAR", "TARGETS_MET", "ONLY_OPTION", "PREF_LIKED", "PAST_DAY", "NOT_NEEDED", "ADDON",
        "NOTHING_TO_TRAIN", "NO_ELIGIBLE_EXERCISE",
    )

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("today.constraint_chip_weight", "chip weight", 0.0, 1.0),
        ParamSpec.number("today.context_chip_weight", "chip weight", 0.0, 1.0),
        ParamSpec.integer("today.max_budget_min", "min", 90.0, 300.0),
        ParamSpec("today.reason_codes", ParamType.ENUM_ARRAY, "enum", enumValues = REASON_CODES),
        ParamSpec.number("today.transform_chip_weight", "chip weight", 0.0, 1.0),
        ParamSpec.number("today.ui_scale", "J per score unit", 0.5, 5.0),
    )

    private val SAFETY = setOf("SAFETY_JOINT", "SAFETY_PROTOCOL")
    private val CHALLENGE = setOf("REST_BEFORE", "PROTECT_REST", "GAP_DAY", "CHALLENGE_DUE", "NEXT_FIXED", "COVERED_BY_CHALLENGE", "RUN_ON_REST_DAY")
    private val USER = setOf("PLANNED_CAL", "MAX_VISITS", "BUDGET_FIT", "NO_SLOT", "TIME_SHORT")
    private val CONSTRAINT_ORDER = listOf("PROTECT_REST", "GAP_DAY", "RUN_NEAR", "BETTER_OTHER_DAY", "MAX_VISITS", "LOW_SYSTEMIC", "RECOVERING", "HEAVY_LOWER_GAP")
    private val CONTEXT_ORDER = listOf("PLANNED_CAL", "CHALLENGE_DUE", "NEXT_FIXED", "RECOVERED", "HOURS_SINCE", "ADDON", "BUDGET_FIT")

    /** The precedence class of a code (0.6); `DELOAD_WEEK` is CHALLENGE when synced to a challenge block (F8). */
    fun precedence(code: String, deloadSource: String? = null): Int = when {
        code in SAFETY -> 0
        code in CHALLENGE -> 1
        code == "DELOAD_WEEK" -> if (deloadSource == "challenge") 1 else 3
        code in USER -> 2
        else -> 3
    }

    fun recommend(input: TodayInput, params: ParamView): TodayOptions {
        val p = input.plan
        val today = Week.localDate(p.now, p.zone)
        val x = p.fixed.filter { (it.kind == FixedItem.KIND_CHALLENGE || it.kind == FixedItem.KIND_TEST) && it.date == today && it.end.isAfter(p.now) }
            .minWithOrNull(compareBy({ it.start }, { it.key }))
        return if (x != null) challengeDay(input, x, today, params) else generalDay(input, today, params)
    }

    // ---- A challenge session or test today (9.4, first branch) ----

    private fun challengeDay(input: TodayInput, x: FixedItem, today: LocalDate, params: ParamView): TodayOptions {
        val ctx = WeeklyPlanner.Context(input.plan, params)
        val ap = ctx.points.firstOrNull { it.kind == Point.ADDON && it.fixed === x }
        val best = TreeMap<Int, Eval>()
        val all = Collector(ctx, params)
        ctx.enumerate { e ->
            all.add(e)
            val c = ap?.let { e.choice[it.index] } ?: 0
            val b = best[c]
            if (b == null || better(ctx, e, b)) best[c] = e
        }
        val chosen = all.chosen()
        val week = all.output(chosen)
        val scale = params.double("today.ui_scale")
        val jNone = best[0]?.j ?: chosen.j
        val primaryChoice = ap?.let { chosen.choice[it.index] } ?: 0
        val options = ArrayList<TodayOption>()
        val excluded = ArrayList<Excluded>()
        val choices = ap?.options?.indices?.toList() ?: listOf(0)
        for (c in choices) {
            val fam = ap?.options?.get(c)
            val e = best[c]
            if (e == null) {
                excluded += Excluded(fam?.code ?: "NONE", failureFor(ctx, ap, c) ?: "NOTHING_TO_TRAIN", 3, emptyMap())
                continue
            }
            val s = ap?.let { pt -> e.sessions.firstOrNull { it.point.index == pt.index } }
            val dJ = Grid.ratio(e.j - jNone)
            val reasons = ArrayList<Reason>()
            volume(s, input, params)?.let { reasons += it }
            transform(s, input, params)?.let { reasons += it }
            if (s != null) reasons += Reason(
                "ADDON", 3, sortedMapOf("family" to s.type.code, "slot" to (x.slot ?: ""), "minutes" to Canon.esNumber(s.alloc.estMinutes, GridKind.S)),
                params.double("today.context_chip_weight"),
            )
            val label = if (s == null) x.label else "${x.label} + ${s.alloc.label.replaceFirstChar { it.lowercaseChar() }}"
            options += TodayOption(
                "opt:${x.slot ?: x.key}:${fam?.code ?: "none"}", null, label, Grid.ratio(0.5 + (e.j - jNone) / scale), dJ,
                Grid.s(Duration.between(x.start, x.end).toMinutes().toDouble()), s?.alloc?.direct.orEmpty(), s?.alloc?.credit.orEmpty(), fam?.code, reasons,
                laterTypes(e, today),
            )
        }
        options.sortWith(compareByDescending<TodayOption> { it.score }.thenBy { WeeklyPlanner.addOnRank(it.addon?.let { c -> SessionType.of(c) }) })
        val primaryId = "opt:${x.slot ?: x.key}:${ap?.options?.get(primaryChoice)?.code ?: "none"}"
        val hard = input.safetyRest ?: input.challengeRest
        val verdict = if (hard != null) REST_HARD else TRAIN
        val restReasons = listOfNotNull(hard?.let { Reason(it.code, precedence(it.code), it.params, params.double("today.constraint_chip_weight")) })
        val primaryFirst = options.filter { it.optionId == primaryId } + options.filter { it.optionId != primaryId }
        return TodayOptions(
            today, Duration.between(x.start, x.end).toMinutes() - input.plan.changeoverMin, "calendar", verdict,
            PlannedToday(if (x.kind == FixedItem.KIND_TEST) "test" else "challenge", x.slot, null, x.start, x.end, x.key, x.label),
            primaryFirst, primaryId.takeIf { verdict != REST_HARD }, restReasons, advice(verdict, restReasons), excluded, week.planId, week,
        )
    }

    // ---- A day without a challenge session (9.4, second branch) ----

    private fun generalDay(input: TodayInput, today: LocalDate, params: ParamView): TodayOptions {
        val p = input.plan
        val e = p.fixed.filter { it.kind == FixedItem.KIND_PLANNED_GYM && it.date == today && it.end.isAfter(p.now) }.minWithOrNull(compareBy({ it.start }, { it.key }))
        val base = WeeklyPlanner.Context(p, params)
        val dm = base.days.first { it.date == today }
        val start = e?.let { maxOf(it.start, p.now) } ?: dm.start ?: p.now
        val eventBudget = e?.let { it.budgetMin ?: (Duration.between(it.start, it.end).toMinutes() - p.changeoverMin) }
        val budget = input.chipMin ?: eventBudget ?: dm.budgetMin ?: p.defaultSessionMin
        val source = when {
            input.chipMin != null -> "chip"
            eventBudget != null -> "calendar"
            else -> "default"
        }
        val constraintW = params.double("today.constraint_chip_weight")
        val hard: ReasonCode? = input.safetyRest ?: dm.reason?.takeIf { it.code == "REST_BEFORE" }
        // D76: no free slot (busy or outside the usual times) is a soft rest; "Train anyway" scores the types from now.
        val noSlot = dm.reason?.takeIf { it.code == "NO_SLOT" && e == null && hard == null }
        val ctx = when {
            dm.status == "free" || e != null -> WeeklyPlanner.Context(p.copy(todayOverride = TodayOverride(today, start, budget)), params)
            noSlot != null -> WeeklyPlanner.Context(p.copy(todayOverride = TodayOverride(today, start, budget, anyTime = true)), params)
            else -> base
        }
        val tp = ctx.points.firstOrNull { it.date == today && it.standalone }
        val best = TreeMap<Int, Eval>()
        val all = Collector(ctx, params)
        // With no slot the week plan is the best one without a session today, as the planner's own plan has it.
        val rest = Collector(ctx, params)
        ctx.enumerate { ev ->
            all.add(ev)
            val c = tp?.let { ev.choice[it.index] } ?: 0
            if (noSlot != null && tp?.options?.get(c) == null) rest.add(ev)
            val b = best[c]
            if (b == null || better(ctx, ev, b)) best[c] = ev
        }
        val chosen = if (noSlot != null) rest.chosen() else all.chosen()
        val week = if (noSlot != null) rest.output(chosen) else all.output(chosen)
        val next = nextFixed(input, today)
        val excluded = ArrayList<Excluded>()
        excluded += notToday(ctx, today, start, budget, input)
        if (hard != null || tp == null) {
            val r = hard ?: ReasonCode("NOT_NEEDED")
            val restReasons = listOfNotNull(Reason(r.code, precedence(r.code), r.params, constraintW), next)
            val verdict = if (hard != null) REST_HARD else REST_SOFT
            SessionType.entries.forEach { t -> excluded += Excluded(t.code, r.code, precedence(r.code), r.params) }
            return TodayOptions(today, budget, source, verdict, e?.let { planned(it) }, emptyList(), null, restReasons, advice(verdict, restReasons), excluded, week.planId, week)
        }
        val scale = params.double("today.ui_scale")
        val jRest = best[tp.options.indexOf(null).takeIf { it >= 0 } ?: -1]?.j
        val options = ArrayList<TodayOption>()
        val seen = ArrayList<Pair<Map<String, Double>, String>>()
        val mergedInto = TreeMap<String, String>()
        for ((c, t) in tp.options.withIndex()) {
            if (t == null) continue
            val ev = best[c]
            if (ev == null) {
                excluded += Excluded(t.code, failureFor(ctx, tp, c) ?: "NOTHING_TO_TRAIN", 3, emptyMap())
                continue
            }
            val s = ev.sessions.first { it.point.index == tp.index }
            // 9.5: a type whose allocation equals an earlier type's is shown once, under the earlier type.
            val same = seen.firstOrNull { it.first == s.alloc.direct }
            if (same != null) { mergedInto[t.code] = same.second; continue }
            seen += s.alloc.direct to t.code
            val dJ = Grid.ratio(ev.j - (jRest ?: ev.j))
            val reasons = ArrayList<Reason>()
            volume(s, input, params)?.let { reasons += it }
            transform(s, input, params)?.let { reasons += it }
            constraint(ctx, s, today, dJ, chosen, input, params)?.let { reasons += it }
            if (e != null) reasons += Reason("PLANNED_CAL", 2, sortedMapOf("at" to e.start.toString()), params.double("today.context_chip_weight"))
            val addOn = ev.sessions.firstOrNull { !it.point.standalone }?.type?.code
            options += TodayOption(
                "opt:${t.code}", t.code, s.alloc.label, Grid.ratio(0.5 + dJ / scale), dJ, s.alloc.estMinutes, s.alloc.direct, s.alloc.credit, addOn, reasons,
                laterTypes(ev, today),
            )
        }
        val chosenToday = chosen.sessions.firstOrNull { it.point.index == tp.index }
        val argmax = options.sortedWith(compareByDescending<TodayOption> { it.score }.thenBy { SessionType.of(it.sessionType!!).ordinal }).firstOrNull()
        var verdict: String
        var primary: String?
        val restReasons = ArrayList<Reason>()
        if (noSlot != null) {
            verdict = REST_SOFT
            primary = argmax?.sessionType
            restReasons += Reason(noSlot.code, precedence(noSlot.code), noSlot.params, constraintW)
        } else if (e != null) {
            primary = e.plannedType?.code ?: chosenToday?.type?.code ?: argmax?.sessionType
            verdict = if (options.isEmpty()) REST_SOFT else TRAIN
            if (verdict == REST_SOFT) {
                val r = week.days.first { it.date == today }.restReason?.takeIf { it.code == "RECOVERING" } ?: ReasonCode("NOT_NEEDED")
                restReasons += Reason(r.code, precedence(r.code), r.params, constraintW)
            }
        } else if (chosenToday != null) {
            verdict = TRAIN
            primary = chosenToday.type.code
        } else {
            verdict = REST_SOFT
            primary = argmax?.sessionType
            val other = chosen.sessions.firstOrNull { it.point.kind == Point.FREE && it.point.date != today }
            val day = week.days.first { it.date == today }
            restReasons += when {
                other != null -> Reason(
                    "BETTER_OTHER_DAY", 3,
                    sortedMapOf("type" to other.alloc.label, "day" to other.point.date.dayOfWeek.name, "date" to other.point.date.toString(), "dJ" to Canon.esNumber(argmax?.dJ ?: 0.0, GridKind.RATIO)),
                    kotlin.math.abs(argmax?.dJ ?: 0.0),
                )
                day.restReason?.code == "MAX_VISITS" -> Reason("MAX_VISITS", 2, day.restReason.params, constraintW)
                day.restReason?.code == "RECOVERING" -> Reason("RECOVERING", 3, day.restReason.params, constraintW)
                else -> Reason("NOT_NEEDED", 3, emptyMap(), 0.0)
            }
        }
        if (verdict != TRAIN) next?.let { restReasons += it }
        // The primary option (the engine's choice) comes first; a planned event's own type is mapped to its option (or the first one).
        val primaryId = (primary?.let { mergedInto[it] ?: it })?.let { pt -> options.firstOrNull { it.sessionType == pt }?.optionId }
            ?: options.firstOrNull()?.optionId
        options.sortWith(compareByDescending<TodayOption> { it.score }.thenBy { SessionType.of(it.sessionType!!).ordinal })
        val ordered = options.filter { it.optionId == primaryId } + options.filter { it.optionId != primaryId }
        return TodayOptions(
            today, budget, source, verdict, e?.let { planned(it) }, ordered, primaryId, restReasons, advice(verdict, restReasons), excluded.sortedWith(
                compareBy<Excluded>({ it.cls }, { familyOrder(it.subject) }, { it.subject }),
            ),
            week.planId, week,
        )
    }

    /** The types of [e]'s standalone general sessions after [today], in time order (D78). */
    private fun laterTypes(e: Eval, today: LocalDate): List<String> =
        e.sessions.filter { it.point.standalone && it.point.date.isAfter(today) }.map { it.type.code }

    private fun planned(e: FixedItem) = PlannedToday("general", null, e.plannedType?.code, e.start, e.end, e.key, e.label)

    private fun advice(verdict: String, reasons: List<Reason>): RestAdvice {
        val first = reasons.firstOrNull()
        return RestAdvice(verdict != TRAIN, verdict == REST_HARD, if (verdict != TRAIN) first?.code else null, if (verdict != TRAIN) first?.params.orEmpty() else emptyMap())
    }

    /** `NEXT_FIXED` (context): the next fixed gym item after now (a challenge session, a test or a planned event). */
    private fun nextFixed(input: TodayInput, today: LocalDate): Reason? {
        val p = input.plan
        val n = p.fixed.filter { it.kind != FixedItem.KIND_DONE && it.kind != FixedItem.KIND_DONE_GENERAL && it.start.isAfter(p.now) }
            .minWithOrNull(compareBy({ it.start }, { it.key })) ?: return null
        return Reason("NEXT_FIXED", 1, sortedMapOf("slot" to (n.slot ?: n.label), "at" to n.start.toString(), "date" to n.date.toString()), 0.1)
    }

    /** The volume kind (9.6): `VOLUME_DEFICIT` for the group with the largest `W_g·ΔU_g` of the session. */
    private fun volume(s: SimSession?, input: TodayInput, params: ParamView): Reason? {
        s ?: return null
        val t = input.plan.targets
        var best: Reason? = null
        for (g in Groups.CANONICAL) {
            val v = s.alloc.vhyp[g] ?: continue
            val gt = t.groups[g] ?: continue
            val v0 = s.vBefore[g] ?: 0.0
            val w = gt.w * (Targets.utility(gt, v0 + v, t.base, params) - Targets.utility(gt, v0, t.base, params))
            if (best == null || w > best.weight + 1e-12) best = Reason(
                "VOLUME_DEFICIT", 3,
                sortedMapOf("group" to g, "done" to Canon.esNumber(Grid.ratio(v0), GridKind.RATIO), "after" to Canon.esNumber(Grid.ratio(v0 + v), GridKind.RATIO),
                    "target" to Canon.esNumber(gt.targetEffective, GridKind.RATIO)),
                Grid.ratio(w),
            )
        }
        return best?.takeIf { it.weight > 0.0 }
    }

    /**
     * The transform kind (9.6): `DELOAD_WEEK`, else `REENTRY` for the trained group in re-entry with the most direct
     * sets, ties by group id (D71).
     */
    private fun transform(s: SimSession?, input: TodayInput, params: ParamView): Reason? {
        val t = input.plan.targets
        val w = params.double("today.transform_chip_weight")
        if (t.deload.active) return Reason("DELOAD_WEEK", precedence("DELOAD_WEEK", t.deload.source), sortedMapOf("source" to t.deload.source), w)
        val (g, re) = s?.alloc?.direct.orEmpty().entries
            .mapNotNull { (g, v) -> t.groups[g]?.reentry?.takeIf { it.active && v > 0.0 }?.let { Triple(g, v, it) } }
            .sortedWith(compareBy({ -it.second }, { it.first }))
            .firstOrNull()?.let { it.first to it.third } ?: return null
        return Reason(
            "REENTRY", 3,
            sortedMapOf("group" to g, "family" to Groups.familyOf(g).lowercase(), "sessions_remaining" to re.sessionsRemaining.toString()), w,
        )
    }

    /** The constraint kind (9.6): the largest of the windows, the gap day, a run, a better day and low readiness. */
    private fun constraint(ctx: WeeklyPlanner.Context, s: SimSession, today: LocalDate, dJ: Double, chosen: Eval, input: TodayInput, params: ParamView): Reason? {
        val w = params.double("today.constraint_chip_weight")
        val out = ArrayList<Reason>()
        ctx.hardWindows(s.start, s.budgetMin).filter { it.scope != "all_gym" }.minWithOrNull(compareBy({ it.to }, { it.id }))?.let { h ->
            out += Reason("PROTECT_REST", 1, sortedMapOf("family" to "PULL", "groups" to h.groups.joinToString(","), "slot" to h.slot, "when" to h.to.toString(), "window_id" to h.id), w)
        }
        if (ctx.gapDays[today] != null) out += Reason("GAP_DAY", 1, sortedMapOf("slot" to adjacentSlot(input, today), "rule_id" to (input.plan.gapRule?.let { "gap" } ?: "")), w)
        if (s.runPenalty > 0.0) out += Reason("RUN_NEAR", 3, emptyMap(), s.runPenalty)
        val other = chosen.sessions.firstOrNull { it.point.kind == Point.FREE && it.point.date != today }
        if (dJ < 0.0 && other != null) out += Reason("BETTER_OTHER_DAY", 3, sortedMapOf("type" to other.alloc.label, "day" to other.point.date.dayOfWeek.name), kotlin.math.abs(dJ))
        if (s.readiness.g < params.double("weekplan.g_low")) out += Reason("LOW_SYSTEMIC", 3, sortedMapOf("pct" to Groups.roundInt(s.readiness.g * 100.0).toString()), params.double("weekplan.lowg_w"))
        return out.minWithOrNull(compareByDescending<Reason> { kotlin.math.abs(it.weight) }.thenBy { CONSTRAINT_ORDER.indexOf(it.code) })
    }

    /** The challenge slot next to a gap day: the next one, else the previous one. */
    private fun adjacentSlot(input: TodayInput, today: LocalDate): String {
        val c = input.plan.fixed.filter { it.slot != null && kotlin.math.abs(Week.calendarDays(today, it.date)) <= 1 }
        return (c.filter { it.date.isAfter(today) }.minByOrNull { it.start } ?: c.maxByOrNull { it.start })?.slot ?: ""
    }

    /** `excluded[]` lines for families restricted today (9.6): the hard windows' groups and the gap day's light pull. */
    private fun notToday(ctx: WeeklyPlanner.Context, today: LocalDate, start: Instant, budget: Double, input: TodayInput): List<Excluded> {
        val out = ArrayList<Excluded>()
        for (h in ctx.hardWindows(start, budget).filter { it.scope != "all_gym" }.sortedWith(compareBy({ it.to }, { it.id }))) {
            val groups = h.groups.filter { input.plan.targets.groups[it]?.status != "skip" }
            val fams = groups.map { Groups.familyOf(it) }.distinct()
            fams.forEach { f ->
                out += Excluded(f, "PROTECT_REST", 1, sortedMapOf("family" to f, "groups" to groups.filter { Groups.familyOf(it) == f }.joinToString(","), "slot" to h.slot, "when" to h.to.toString(), "window_id" to h.id))
            }
        }
        if (ctx.gapDays[today] != null) out += Excluded("upper_back", "GAP_DAY", 1, sortedMapOf("slot" to adjacentSlot(input, today), "rule_id" to "gap"))
        return out
    }

    private fun familyOrder(subject: String): Int {
        val fams = listOf("PUSH", "PULL", "LEGS", "CORE")
        val f = fams.indexOf(subject)
        if (f >= 0) return f
        return runCatching { SessionType.of(subject).ordinal + 10 }.getOrDefault(30)
    }

    /** Why [choice] at [point] never gave a feasible week: the minimal plan (nothing else chosen) as a witness. */
    private fun failureFor(ctx: WeeklyPlanner.Context, point: Point?, choice: Int): String? {
        point ?: return null
        val c = IntArray(ctx.points.size)
        c[point.index] = choice
        return if (ctx.simulate(c) == null) ctx.failure?.first else null
    }

    private fun better(ctx: WeeklyPlanner.Context, a: Eval, b: Eval): Boolean {
        val ja = WeeklyPlanner.round6(a.j)
        val jb = WeeklyPlanner.round6(b.j)
        return if (ja != jb) ja > jb else ctx.tieKey.compare(a, b) < 0
    }

    /** The band and tie key of 8.6 over every evaluated plan, as `weekplan` keeps them. */
    private class Collector(val ctx: WeeklyPlanner.Context, params: ParamView) {
        private val band = params.double("weekplan.tie_band")
        private var star: Eval? = null
        private val inBand = ArrayList<Eval>()
        private var n = 0

        fun add(e: Eval) {
            n++
            val s = star
            if (s == null || WeeklyPlanner.round6(e.j) > WeeklyPlanner.round6(s.j)) star = e
            val j = WeeklyPlanner.round6(star!!.j)
            inBand.removeAll { WeeklyPlanner.round6(it.j) < j - band - 1e-9 }
            if (WeeklyPlanner.round6(e.j) >= j - band - 1e-9) inBand += e
        }

        fun chosen(): Eval = inBand.sortedWith(ctx.tieKey).first()

        fun output(chosen: Eval): WeekPlan {
            val sorted = inBand.sortedWith(ctx.tieKey)
            val used = if (sorted.size < 2) "only" else ctx.tieKeyUsed(sorted[0], sorted[1])
            return ctx.output(chosen, Band(Grid.ratio(star!!.j), band, sorted.size, used), listOf(chosen), n)
        }
    }
}
