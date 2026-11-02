package dev.fitnessapp.app.planning

import dev.fitnessapp.algo.accounting.readiness.Dose
import dev.fitnessapp.algo.challenge.contract.ChallengeContract
import dev.fitnessapp.algo.challenge.contract.ContractBuilder
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.plan.Interval
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.planning.SessionTagRule
import dev.fitnessapp.algo.planning.allocator.AddOnSlot
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.today.TodayInput
import dev.fitnessapp.algo.planning.today.TodayOptions
import dev.fitnessapp.algo.planning.today.TodayRecommender
import dev.fitnessapp.algo.planning.weekplan.AddOnFamily
import dev.fitnessapp.algo.planning.weekplan.AddOnSpec
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.GroupAvailability
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeekPlanInput
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import dev.fitnessapp.algo.selection.elig.AddOnFilter
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.elig.HardWindow
import dev.fitnessapp.algo.selection.elig.LightPullRule
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.TreeMap

/**
 * The week planner and the Today recommender as the phone runs them (M4.5, D70): the inputs of ALGORITHMS_PLANNING 8.2
 * from the classified calendar, the challenge's week plan and contract, the logged sessions, this week's volume and the
 * profile. Next week (8.8) starts from this week's committed doses and the chosen plan's general sessions, with its own
 * challenge placement, targets and an empty account.
 */
object WeekPlans {
    /** The add-on families the planner weighs (8.6: none < LEGS < PUSH < CORE). */
    private val ADD_ON_TYPES = listOf(SessionType.LEGS, SessionType.PUSH, SessionType.CORE)

    /** One planner run: its input, the plan, and the facts the UI reads beside it. */
    class Run(val input: WeekPlanInput, val plan: WeekPlan, val contract: ChallengeContract?, val volume: WeekVolume)

    suspend fun thisWeek(c: AppContainer, now: Instant = c.clock.now()): Run = withContext(Dispatchers.Default) {
        val wv = WeekVolume.compute(c, now)
        val input = input(c, now, 0, wv, wv.doses).let { it.copy(previous = previous(c, it.isoWeek)) }
        val plan = WeeklyPlanner.plan(input, c.params.fork())
        remember(c, plan)
        Run(input, plan, contract(c, now, 0), wv)
    }

    /** Next ISO week's provisional plan (8.8): general items are only shown, never written (Plan it is M4.6). */
    suspend fun nextWeek(c: AppContainer, current: Run, now: Instant = c.clock.now()): Run = withContext(Dispatchers.Default) {
        val nextMonday = Week.isoWeekStart(Week.localDate(now, PlanningFacts.ZONE)).plusWeeks(1)
        val wv = WeekVolume.compute(c, Week.atLocal(nextMonday, LocalTime.of(0, 1), PlanningFacts.ZONE))
        val carried = current.volume.doses.filter { it.t.isAfter(now) } + current.plan.planDoses
        val input = input(c, now, 1, wv, wv.doses + carried).copy(provisional = true)
        Run(input, WeeklyPlanner.plan(input, c.params.fork()), contract(c, now, 1), wv)
    }

    /** Today's options (9) at the chosen time chip ([chipMin] null: the calendar budget or the default length). */
    suspend fun today(c: AppContainer, week: Run, chipMin: Double?): TodayOptions = withContext(Dispatchers.Default) {
        TodayRecommender.recommend(TodayInput(week.input, chipMin = chipMin), c.params.fork())
    }

    private suspend fun contract(c: AppContainer, now: Instant, weekOffset: Int): ChallengeContract? {
        val e = c.challenges.open().firstOrNull() ?: return null
        val f = c.challenges.folded(e.id)
        if (f.state.status != EnrollmentState.ACTIVE) return null
        val (_, plan) = ChallengeWeek.plan(c, f, c.challengeFacts.facts(f.def, f.state), now, weekOffset)
        return ContractBuilder.build(f.def, c.params, f.state, plan, c.profiles.get()?.changeover_min ?: AppContainer.CHANGEOVER_MIN)
    }

    /** The day keys of this week's last plan (8.6 stability term); kept on the device per ISO week. */
    private fun previous(c: AppContainer, isoWeek: String): Map<LocalDate, String> =
        c.settings.weekPlanKeys(isoWeek)?.let { text ->
            runCatching { Json.parseToJsonElement(text).jsonObject.entries.associate { LocalDate.parse(it.key) to it.value.jsonPrimitive.content } }.getOrNull()
        }.orEmpty()

    private fun remember(c: AppContainer, plan: WeekPlan) {
        val keys = WeeklyPlanner.dayKeys(plan)
        c.settings.setWeekPlanKeys(plan.isoWeek, kotlinx.serialization.json.JsonObject(keys.entries.associate { it.key.toString() to kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
    }

    private suspend fun input(c: AppContainer, now: Instant, weekOffset: Int, wv: WeekVolume, doses: List<Dose>): WeekPlanInput {
        val zone = PlanningFacts.ZONE
        val monday = Week.isoWeekStart(Week.localDate(now, zone)).plusWeeks(weekOffset.toLong())
        val sunday = monday.plusDays(6)
        val profile = c.profiles.get()
        val changeover = profile?.changeover_min ?: AppContainer.CHANGEOVER_MIN
        val params = c.params.fork()
        val prefs = c.selection.prefs(Week.localDate(now, zone))
        val stations = c.selection.stations()
        val fixed = ArrayList<FixedItem>()
        var contract: ChallengeContract? = null

        // The challenge's sessions of the week (calendar-matched or suggested), with the add-on after C and A (C34, C49).
        val e = c.challenges.open().firstOrNull()
        val f = e?.let { c.challenges.folded(it.id) }?.takeIf { it.state.status == EnrollmentState.ACTIVE }
        if (f != null) {
            val (_, plan) = ChallengeWeek.plan(c, f, c.challengeFacts.facts(f.def, f.state), now, weekOffset)
            contract = ContractBuilder.build(f.def, c.params, f.state, plan, changeover)
            val testSlot = f.def.doc.structure.block.testSlot
            for (s in plan.sessions) {
                val kind = when {
                    s.source == "done" -> FixedItem.KIND_DONE
                    s.slot == testSlot -> FixedItem.KIND_TEST
                    else -> FixedItem.KIND_CHALLENGE
                }
                var addOn: AddOnSpec? = null
                val slot = contract.reservedSessions.firstOrNull { it.slot == s.slot && it.date == s.date }?.addOnSlot
                if (kind == FixedItem.KIND_CHALLENGE && slot != null && s.interval.end.isAfter(now)) {
                    val booked = Duration.between(s.interval.start, s.interval.end).toMinutes().toInt()
                    val sp = runCatching { c.challengeSessions.materialize(f.enrollment.id, s.slot, s.date, booked) }.getOrNull()
                    val cap = sp?.addOnCapacityMin
                    if (sp != null && sp.addOnOffered && cap != null) {
                        val start = s.interval.start.plusMillis(((sp.estMin?.toDouble() ?: 0.0) * 60_000.0).toLong())
                        val elig = eligContext(c, contract, prefs, stations, gapDay = false, gapTags = emptyList(), hard = emptyList())
                        val fams = ADD_ON_TYPES.map { t ->
                            val groups = t.families.flatMap { Groups.FAMILIES.getValue(it) }
                            val ctx = elig.copy(addOn = AddOnFilter(slot.allowPatterns, slot.maxGripDemand, slot.forbidGroups, groups))
                            val allowed = Elig.groupAvailability(c.catalogModel, ctx, params).filterValues { it }.keys
                            AddOnFamily(t, AddOnSlot(t.code, allowed, slot.forbidGroups.toSet(), slot.maxSets?.toDouble(), slot.minRir))
                        }.filter { it.slot.allowedGroups.isNotEmpty() }
                        addOn = AddOnSpec(start, cap.toDouble(), fams, slot.forbidSessionTags.toSet())
                    }
                }
                fixed += FixedItem(
                    key = "${s.slot}:${s.date}", kind = kind, date = s.date, start = s.interval.start, end = s.interval.end,
                    label = if (s.slot == testSlot) "Test day" else "Session ${s.slot}", slot = s.slot, tags = s.tags, addOn = addOn,
                )
            }
        }

        // Logged general sessions of the week (and one in progress): H6's heavy-lower FS and the gap rule's tags; the
        // previous week's for H6 and H1 across the week boundary (D74).
        val tagRules = contract?.sessionTagRules?.let { SessionTagRule.parse(it) }.orEmpty()
        val credited = PlanningFacts.credited(c, now)
        fun generalOnly(s: WorkoutSessionEntity) = s.kind == "gym" && s.challenge_slot == null && s.habit_id == null && s.started_at != null && s.deleted_at == null
        fun item(s: WorkoutSessionEntity): FixedItem {
            val fs = TreeMap<String, Double>()
            credited.filter { it.set.sessionId == s.id }.forEach { cs -> cs.credit.fs.forEach { (g, v) -> fs[g] = (fs[g] ?: 0.0) + v } }
            return FixedItem(
                key = s.id, kind = FixedItem.KIND_DONE_GENERAL, date = LocalDate.parse(s.local_date), start = Instant.ofEpochMilli(s.started_at!!),
                end = s.finished_at?.let { Instant.ofEpochMilli(it) } ?: now, label = "Gym session",
                heavyLowerFs = listOf("quads", "hamstrings", "glutes").sumOf { fs[it] ?: 0.0 }, tags = tagRules.filter { it.reached(fs) }.map { it.tag },
            )
        }
        val general = c.db.sessions().finishedInIsoWeek(Week.isoWeek(monday)).filter(::generalOnly) +
            listOfNotNull(c.sessionRepository.observeActiveOnce()?.takeIf { generalOnly(it) && it.local_date >= monday.toString() && it.local_date <= sunday.toString() })
        general.forEach { fixed += item(it) }
        val priorGeneral = c.db.sessions().finishedInIsoWeek(Week.isoWeek(monday.minusWeeks(1))).filter(::generalOnly).map(::item)

        // The user's own planned gym events (C49: the event is training time; its length minus the changeover is the budget).
        val anchors = c.calendar.snapshot.value.anchors.filter { !it.localDate.isBefore(monday) && !it.localDate.isAfter(sunday) }
        val doneDates = fixed.filter { it.kind == FixedItem.KIND_DONE_GENERAL }.map { it.date }.toSet()
        anchors.filter { it.kind == AnchorKind.PLANNED_GYM && it.localDate !in doneDates }.forEach { a ->
            fixed += FixedItem(
                key = a.instanceKey, kind = FixedItem.KIND_PLANNED_GYM, date = a.localDate, start = a.beginUtc, end = a.endUtc, label = "Planned gym",
                plannedType = a.gymType?.let { runCatching { SessionType.of(it) }.getOrNull() },
                budgetMin = (Duration.between(a.beginUtc, a.endUtc).toMinutes() - changeover).toDouble(),
            )
        }

        val gym = c.equipment.defaultGym()
        val windows = TreeMap<LocalDate, List<Interval>>()
        // The usual training times, or the default evenings and weekend days while none are saved (D75); a day the user
        // switched off has no window. The gym's opening hours narrow them.
        val user = TrainingWindows.effective(profile?.availability)
        val open = days(gym?.opening_hours)
        for (i in 0L..6L) {
            val d = monday.plusDays(i)
            val key = DAYS[i.toInt()]
            val w = user[key]?.let { intersect(it, open[key]) }
            windows[d] = listOfNotNull(w?.let { (a, b) -> Interval(Week.atLocal(d, a, zone), Week.atLocal(d, b, zone)) })
        }
        val gapDays = contract?.blockedDays.orEmpty()
        val avail = TreeMap<String, Map<String, Boolean>>()
        val availability = GroupAvailability { gapDay, gapTags, hardIds ->
            avail.getOrPut("$gapDay|${gapTags.joinToString(",")}|${hardIds.joinToString(",")}") {
                val hard = contract?.protectedWindows.orEmpty().filter { it.id in hardIds }.distinctBy { it.id }
                Elig.groupAvailability(c.catalogModel, eligContext(c, contract, prefs, stations, gapDay, gapTags, hard.map { HardWindow(it.id, it.groups, it.regions) }), params)
            }
        }
        return WeekPlanInput(
            isoWeek = Week.isoWeek(monday), monday = monday, zone = zone, now = now, windows = windows, priorGeneral = priorGeneral,
            busy = anchors.filter { it.kind == AnchorKind.BUSY }.map { Interval(it.beginUtc, it.endUtc) },
            fixed = fixed, blocked = gapDays, gapRule = contract?.gapRule, protectedWindows = contract?.protectedWindows.orEmpty(),
            ownedGroups = contract?.ownsMuscles.orEmpty().toSet(), forbiddenSessionTags = contract?.forbiddenSessionTags.orEmpty().toSet(),
            tagRules = tagRules, runs = wv.runs, runningPriority = profile?.running_priority ?: "low", targets = wv.targets,
            done = wv.account.done.mapValues { it.value.vhyp }, committed = wv.account.committed, sessionsTraining = wv.account.sessionsTraining,
            doses = doses, maxVisits = profile?.max_gym_visits_per_week ?: 4, defaultSessionMin = (profile?.default_session_min ?: 75).toDouble(),
            changeoverMin = changeover.toDouble(), availability = availability,
        )
    }

    /** ELIG-1's context for a general session (12.2), as the New session sheet builds it (ChallengeConstraints, D53). */
    private fun eligContext(
        c: AppContainer, contract: ChallengeContract?, prefs: Map<String, dev.fitnessapp.algo.selection.pref.PrefValue>, stations: Set<String>?,
        gapDay: Boolean, gapTags: List<String>, hard: List<HardWindow>,
    ): EligContext {
        if (contract == null) return EligContext(availableStations = stations, prefs = prefs)
        val p = c.params.fork()
        return EligContext(
            availableStations = stations, prefs = prefs, jointFlags = contract.jointFlags, forbidExerciseTags = contract.forbiddenExerciseTags,
            gapDay = gapDay, forbiddenSessionTags = gapTags, hardWindows = hard,
            lightPull = LightPullRule(p.int("challenge.contract.gap_day_pull_max_grip_demand"), p.double("challenge.contract.gap_day_pull_biceps_coef_below")),
        )
    }

    private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** `{day: [[from, to]]}` (DATA_MODEL 9.3.1) as one window per weekday; `{}` gives none. */
    private fun days(json: String?): Map<String, Pair<LocalTime, LocalTime>> = runCatching {
        Json.parseToJsonElement(json ?: "{}").jsonObject.mapNotNull { (d, v) ->
            val w = v.jsonArray.firstOrNull()?.jsonArray ?: return@mapNotNull null
            d to (LocalTime.parse(w[0].jsonPrimitive.content) to LocalTime.parse(w[1].jsonPrimitive.content))
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun intersect(w: Pair<LocalTime, LocalTime>, open: Pair<LocalTime, LocalTime>?): Pair<LocalTime, LocalTime>? {
        if (open == null) return w
        val a = maxOf(w.first, open.first)
        val b = minOf(w.second, open.second)
        return if (a < b) a to b else null
    }
}
