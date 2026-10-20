package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.HabitSpec
import dev.fitnessapp.algo.challenge.cds.SemanticValidator
import dev.fitnessapp.algo.challenge.eca.Records
import dev.fitnessapp.algo.challenge.eca.RuleEnv
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.EvalError
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime

/** The dose of one habit occurrence after the mode's `habitScale` (ranges stay ranges: 4-5 sets -> 2-3). */
data class HabitDose(
    val sets: List<Int> = emptyList(),
    val totalS: List<BigDecimal> = emptyList(),
    val holdS: BigDecimal? = null,
    val addedKg: BigDecimal? = null,
    val perSide: Boolean = false,
)

/**
 * One row of the home habits checklist (UX 9.6). [state]: `DUE`, `DONE`, `SCALED`, `SKIPPED` or `OFF`;
 * [reason] is `skip` (with [skipIf] the definition's condition), `mode:<mode>`, `status:<status>` or `error:<code>`.
 */
data class HabitToday(
    val habitId: String,
    val time: LocalTime,
    val state: String,
    val reason: String? = null,
    val skipIf: String? = null,
    val measureToday: Boolean = false,
    val dose: HabitDose = HabitDose(),
)

data class HabitInput(
    val date: LocalDate,
    val state: EnrollmentState,
    /** `weekPlanSlots` (date -> challenge slots planned or done this week) feeds `todayHas` and `dayBefore(...)`. */
    val facts: EngineFacts,
    /** Dates of this ISO week with a general gym session (with the challenge sessions they make `gymDay`). */
    val generalGymDates: Set<LocalDate> = emptySet(),
    /** Habits already logged today. */
    val done: Set<String> = emptySet(),
)

/**
 * Home habits for a day (CHALLENGE_ENGINE 2.4.8, 6.4): which habits are due, their dose after the mode's
 * `habitScale` (rounded up to whole sets and whole seconds), muted habits (`disabledInModes`, the mode's
 * `disableHabits`, a paused or finished enrollment), `skipIf`, and the weekly measure day. Schedules:
 * `FREQ=DAILY`, `FREQ=WEEKLY;BYDAY=..` and `timesPerWeek` with preferred weekdays and `avoid`
 * (`gymDay`, `dayBefore(<slot>|<slot>)`).
 */
object HabitPlanner {
    fun today(def: CompiledDefinition, params: ParamView, input: HabitInput): Pair<List<HabitToday>, List<String>> {
        val s = input.state
        val env = RuleEnv(def, params, Records(params), s, input.date, input.facts)
        val tf = Materializer.transform(def, s, s.mode)
        val out = ArrayList<HabitToday>()
        val warnings = ArrayList<String>()
        for (h in def.doc.habits) {
            val compiled = def.habits[h.id]
            val time = LocalTime.parse(h.schedule.time)
            val scheduled = scheduledDays(h, input, warnings)
            if (input.date !in scheduled) continue
            val enabled = compiled?.enabledIf?.let { e ->
                try {
                    (e.evaluate(env.roots(), env.host) as? BoolV)?.b == true
                } catch (_: EvalError) {
                    false
                }
            } ?: true
            if (!enabled) continue
            if (s.status != EnrollmentState.ACTIVE) {
                out += HabitToday(h.id, time, "OFF", "status:${s.status}")
                continue
            }
            if (s.mode in h.disabledInModes || h.id in tf.disableHabits) {
                out += HabitToday(h.id, time, "OFF", "mode:${s.mode}")
                continue
            }
            val skip = compiled?.skipIf?.let { e ->
                try {
                    if ((e.evaluate(env.roots(), env.host) as? BoolV)?.b == true) "skip" else null
                } catch (err: EvalError) {
                    // A safety condition that cannot be evaluated skips the habit rather than prescribing it.
                    "error:${err.code}"
                }
            }
            if (skip != null) {
                out += HabitToday(h.id, time, "SKIPPED", skip, h.skipIf)
                continue
            }
            val scale = tf.habitScale[h.id]
            val dose = dose(h, compiled?.fields.orEmpty().mapValues { (_, e) -> runCatching { e.evaluate(env.roots(), env.host) }.getOrNull() }, scale?.sets, scale?.time)
            val measure = measureToday(h, compiled?.measureWeekday?.let { e -> runCatching { (e.evaluate(env.roots(), env.host) as? StrV)?.s }.getOrNull() }, input)
            val state = when {
                h.id in input.done -> "DONE"
                scale != null -> "SCALED"
                else -> "DUE"
            }
            out += HabitToday(h.id, time, state, if (scale != null) "mode:${s.mode}" else null, measureToday = measure, dose = dose)
        }
        return out to warnings.distinct()
    }

    /**
     * The home mini-session of a habit (UX 9.6, C34): one item from the prescription with today's [habit] dose, so the
     * sets are logged like a challenge item (hold chips for holds, the item's flags) and `habit.completed` carries
     * per-set rows under the prescription key. Null for a habit without an exercise (a checklist) or when the habit is
     * not due today. Ranges prescribe their lower end (4-5 sets -> 4 rows); the rows carry no target where the
     * prescription has none (the hang's total time is shown as a note).
     */
    fun plan(def: CompiledDefinition, params: ParamView, input: HabitInput, habit: HabitToday, catalog: CatalogDoc?): SessionPlan? {
        if (habit.state != "DUE" && habit.state != "SCALED" && habit.state != "DONE") return null
        val h = def.doc.habits.firstOrNull { it.id == habit.habitId } ?: return null
        val p = h.prescription
        val exKey = (p["exercise"] as? JsonPrimitive)?.contentOrNull ?: return null
        val key = (p["key"] as? JsonPrimitive)?.contentOrNull ?: h.id
        val env = RuleEnv(def, params, Records(params), input.state, input.date, input.facts)
        val ex = ExerciseResolver(def, catalog, env.effectiveParams).resolve(exKey) ?: return null
        val dose = habit.dose
        val logMode = when (ex.logAs) {
            "hold" -> LogMode.HOLD_CHIPS
            "eccentric" -> LogMode.ECC_SECONDS
            else -> LogMode.RIR_CHIPS
        }
        val sides = if (dose.perSide) (p["sideOrder"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.ifEmpty { null } ?: listOf("L", "R") else listOf("both")
        val rest = (p["rest_s"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val rows = ArrayList<PlannedRow>()
        for (i in 1..(dose.sets.firstOrNull() ?: 1)) for (side in sides) {
            rows += PlannedRow(
                side = side, setKind = "working", setIndex = i, pairIndex = if (dose.perSide) i else null,
                targetHoldS = dose.holdS, addedKg = dose.addedKg?.takeIf { it.signum() > 0 }, restS = rest, logMode = logMode,
            )
        }
        val notes = (p["rules"] as? JsonArray)?.mapNotNull { r ->
            (r as? kotlinx.serialization.json.JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }?.toSortedMap()
        }.orEmpty()
        val item = PlannedItem(
            key = key, exerciseKey = ex.key, catalogSlug = ex.catalogSlug, name = h.name, role = "main", status = "planned", rows = rows,
            flagsOffered = ex.flagsOffered,
            stopSetOn = (p["stopSetOn"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            notes = notes, progressionMode = "none",
            inSessionRuleIds = def.inSessionRules.filter { r ->
                (r.spec.item == key || r.spec.item == "*") && (r.spec.tag != "P" || r.spec.proposal?.let { input.state.approved(it) } == true)
            }.map { it.id },
        )
        return SessionPlan(
            slot = HOME_SLOT, templateId = "habit:${h.id}", phase = input.state.phase, mode = input.state.mode, date = input.date, kind = "home",
            bookedMin = 0, budgetMin = 0, items = listOf(item), estMin = null, addOnCapacityMin = null, addOnOffered = false, warnings = emptyList(),
            snapshot = PlanSnapshot(def.jcsSha256, EngineVersion.ENGINE_VERSION, params.hash, catalog?.catalogVersion, input.state.stateRev),
        )
    }

    /** The plan slot of a home mini-session: not a challenge slot (`workout_sessions.challenge_slot` stays null). */
    const val HOME_SLOT = "HOME"

    /** The days of [input]'s ISO week on which [h] is scheduled. */
    fun scheduledDays(h: HabitSpec, input: HabitInput, warnings: MutableList<String> = ArrayList()): List<LocalDate> {
        val monday = Week.isoWeekStart(input.date)
        val week = (0..6).map { monday.plusDays(it.toLong()) }
        val rrule = h.schedule.rrule
        if (rrule != null) {
            val parts = rrule.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            return when (parts["FREQ"]) {
                "DAILY" -> week
                "WEEKLY" -> {
                    val days = parts["BYDAY"].orEmpty().split(',').filter { it.isNotEmpty() }
                    week.filter { d -> RRULE_DAYS[d.dayOfWeek.value - 1] in days }
                }
                else -> {
                    warnings += "habit_rrule_unsupported:${h.id}"
                    emptyList()
                }
            }
        }
        val n = h.schedule.timesPerWeek ?: return emptyList()
        val gym = week.filter { it in input.generalGymDates || input.facts.weekPlanSlots[it].orEmpty().isNotEmpty() }.toSet()
        fun avoided(d: LocalDate): Boolean = h.schedule.avoid.any { a ->
            when {
                a == "gymDay" -> d in gym
                a.startsWith("dayBefore(") && a.endsWith(")") -> {
                    val slots = a.removePrefix("dayBefore(").removeSuffix(")").split('|')
                    input.facts.weekPlanSlots[d.plusDays(1)].orEmpty().any { it in slots }
                }
                else -> {
                    warnings += "habit_avoid_unknown:${h.id}:$a"
                    false
                }
            }
        }
        val chosen = sortedSetOf<LocalDate>()
        // Each preferred weekday, or the nearest free day of the week (later first on a tie).
        for (code in h.schedule.preferredWeekdays) {
            if (chosen.size >= n) break
            val idx = SemanticValidator.WEEKDAYS.indexOf(code)
            if (idx < 0) continue
            val pick = OFFSETS.map { idx + it }.filter { it in 0..6 }.map { week[it] }.firstOrNull { it !in chosen && !avoided(it) }
            pick?.let { chosen += it }
        }
        for (d in week) {
            if (chosen.size >= n) break
            if (d !in chosen && !avoided(d)) chosen += d
        }
        if (chosen.size < n) warnings += "habit_underscheduled:${h.id}"
        return chosen.toList()
    }

    /** 0, +1, -1, +2, -2, ... within one week. */
    private val OFFSETS: List<Int> = listOf(0) + (1..6).flatMap { listOf(it, -it) }

    private val RRULE_DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /**
     * The weekly measure: on the definition's weekday, or the first later day of the week without a gym
     * session when that day has one (P26). `ASK` (P26 declined) leaves it to the user's toggle.
     */
    private fun measureToday(h: HabitSpec, weekday: String?, input: HabitInput): Boolean {
        if (h.measure == null || weekday == null) return false
        val idx = SemanticValidator.WEEKDAYS.indexOf(weekday)
        if (idx < 0) return false
        val monday = Week.isoWeekStart(input.date)
        val gymDay = { d: LocalDate -> d in input.generalGymDates || input.facts.weekPlanSlots[d].orEmpty().isNotEmpty() }
        val day = (idx..6).map { monday.plusDays(it.toLong()) }.firstOrNull { !gymDay(it) } ?: return false
        return day == input.date
    }

    private fun dose(h: HabitSpec, exprs: Map<String, Value?>, setsFactor: Double?, timeFactor: Double?): HabitDose {
        val p = h.prescription
        fun numbers(key: String): List<BigDecimal> = when (val e = p[key]) {
            is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull() }
            is JsonPrimitive -> listOfNotNull(e.contentOrNull?.toBigDecimalOrNull())
            else -> emptyList()
        }
        fun up(x: BigDecimal, f: Double?): BigDecimal = if (f == null) x else x.multiply(Dec.ofParam(f)).setScale(0, RoundingMode.CEILING)
        val hold = (exprs["hold_s"] as? QV)?.v ?: numbers("hold_s").firstOrNull()
        val added = (exprs["added"] as? QV)?.v ?: numbers("added").firstOrNull()
        return HabitDose(
            sets = numbers("sets").map { up(it, setsFactor).toInt() },
            totalS = numbers("total_s").map { Dec.norm(up(it, timeFactor)) },
            holdS = hold?.let { Dec.norm(up(it, timeFactor)) },
            addedKg = added?.let { Dec.norm(it) },
            perSide = (p["perSide"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
}
