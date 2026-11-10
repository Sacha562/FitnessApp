package dev.fitnessapp.app.planning

import dev.fitnessapp.algo.accounting.setcredit.Coverage
import dev.fitnessapp.algo.accounting.setcredit.CreditSet
import dev.fitnessapp.algo.accounting.setcredit.SetCredit
import dev.fitnessapp.algo.accounting.setcredit.WeekAccount
import dev.fitnessapp.algo.challenge.contract.ContractBuilder
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.materialize.HabitPlanner
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.prescription.warnings.Severity
import dev.fitnessapp.algo.prescription.warnings.Warning
import dev.fitnessapp.algo.prescription.warnings.Warnings
import dev.fitnessapp.algo.targets.GroupGoal
import dev.fitnessapp.algo.targets.Targets
import dev.fitnessapp.algo.targets.TargetsInput
import dev.fitnessapp.algo.targets.WeeklyTargets
import dev.fitnessapp.algo.targets.runs.Run
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import dev.fitnessapp.app.ui.challenges.HabitDays
import dev.fitnessapp.app.uidet.UiVolumeBars
import dev.fitnessapp.app.uidet.VolumeBars
import dev.fitnessapp.app.uidet.VolumeGroup
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.TreeMap

/**
 * One row of a group's sheet this week: an exercise's done sets, or its committed challenge sessions and habits still
 * ahead ([committed]), or a planned general session of the week plan ([slug] is the plan item's key, [date] and [label]
 * name it, D113).
 */
data class Contribution(
    val slug: String,
    val vhyp: Double,
    val sets: Int,
    val committed: Boolean,
    val date: LocalDate? = null,
    val label: String? = null,
)

/** The active challenge as the Week tab names it: "Block 1 · week 5", deload or not, the groups it owns. */
data class WeekChallenge(val enrollmentId: String, val name: String, val blockIndex: Int, val blockWeek: Int, val deload: Boolean, val owns: List<String>)

/**
 * This ISO week's muscle volume (UX 10.8, M4.2): done Vhyp from the logged sets, committed Vhyp of the challenge sessions
 * and habits not done yet (`setcredit` coverage with the definition's credit RIR), the weekly targets (`targets` with
 * the challenge's owned groups and its deload week, F8), WE-V1's deficits and the bars of `ui.volumebars`. [compute]
 * runs before the week planner (whose input it is), so its "planned" is the challenge's committed volume only;
 * [withPlan] adds the plan's remaining general sessions afterwards (ALGORITHMS_PLANNING 19.4, D113). The target ramp
 * starts at the base target until the week close is stored (M4.5).
 */
data class WeekVolume(
    val monday: LocalDate,
    val isoWeek: String,
    val goalsSet: Boolean,
    val goals: Map<String, GroupGoal>,
    val targets: WeeklyTargets,
    val account: WeekAccount,
    val bars: VolumeBars,
    val warnings: List<Warning>,
    val contributions: Map<String, List<Contribution>>,
    val runs: List<Run>,
    val challenge: WeekChallenge?,
    /** The readiness doses of the logged history and of the committed challenge sessions and habits ahead (4.2). */
    val doses: List<dev.fitnessapp.algo.accounting.readiness.Dose> = emptyList(),
) {
    /**
     * This week with [plan]'s remaining general sessions as planned (D113): every plan item with projected Vhyp (the
     * recommended sessions, the user's planned gym events, the add-ons) adds to `plannedGeneral` and to the per-group
     * session count, and the WE-V1 deficits, the bars and the sheet rows are rebuilt on done + committed + planned
     * general. The targets stay as the planner saw them; the doses are unchanged (next week adds `plan.planDoses` itself).
     * A plan of another ISO week, or one without general volume, leaves this week as it is.
     */
    fun withPlan(plan: WeekPlan, params: ParamView): WeekVolume {
        if (plan.isoWeek != isoWeek) return this
        val sessions = plan.days.flatMap { d -> d.items.filter { i -> i.vhyp.values.any { it > 0.0 } }.map { d.date to it } }
            .sortedWith(compareBy({ it.second.start }, { it.second.key }))
        if (sessions.isEmpty()) return this
        val minCredit = params.double("setcredit.trained_min_credit")
        val trained = TreeMap(account.sessionsTraining)
        for (g in Groups.CANONICAL) {
            val n = sessions.count { (it.second.vhyp[g] ?: 0.0) >= minCredit }
            if (n > 0) trained[g] = (trained[g] ?: 0) + n
        }
        val acc = account.copy(plannedGeneral = SetCredit.sum(sessions.map { it.second.vhyp }), sessionsTraining = trained)
        val (w, b) = present(targets, acc, params)
        val rows = TreeMap<String, MutableList<Contribution>>()
        contributions.forEach { (g, l) -> rows[g] = l.toMutableList() }
        for ((date, i) in sessions) for (g in Groups.CANONICAL) {
            val v = i.vhyp[g] ?: 0.0
            if (v > 0.0) rows.getOrPut(g) { ArrayList() } += Contribution(i.key, Grid.ratio(v), 0, true, date, i.label)
        }
        rows.values.forEach { it.sortWith(ORDER) }
        return copy(account = acc, bars = b, warnings = w, contributions = rows)
    }

    companion object {
        /** Sheet order: done before planned, then by Vhyp descending, then by key. */
        private val ORDER = compareBy<Contribution> { it.committed }.thenByDescending { it.vhyp }.thenBy { it.slug }

        /**
         * WE-V1 for each improve group on the projected week (done + committed + planned general), none in a deload week
         * (D61); its WARN and STRONG are the bars' DEFICIT. Then the bars of `ui.volumebars` with planned = committed +
         * planned general (19.4).
         */
        internal fun present(targets: WeeklyTargets, account: WeekAccount, params: ParamView): Pair<List<Warning>, VolumeBars> {
            val done = account.done.mapValues { it.value.vhyp }
            fun planned(g: String) = (account.committed[g] ?: 0.0) + (account.plannedGeneral[g] ?: 0.0)
            val warnings = Warnings.rank(
                targets.groups.filterValues { it.status == "improve" }.mapNotNull { (g, _) ->
                    val x = Grid.ratio((done[g] ?: 0.0) + planned(g))
                    Warnings.improveWeeklyLow(g, x, account.sessionsTraining[g] ?: 0, targets.deload.active, params)
                },
            )
            val deficit = warnings.filter { it.severity == Severity.WARN || it.severity == Severity.STRONG }.map { it.entity.removePrefix("group:") }.toSet()
            val bars = UiVolumeBars.bars(
                Groups.CANONICAL.mapNotNull { g ->
                    val gt = targets.groups[g] ?: return@mapNotNull null
                    VolumeGroup(g, gt.status, gt.focus, done[g] ?: 0.0, Grid.ratio(planned(g)), gt.targetEffective, gt.t, gt.mrv, g in deficit)
                },
                params,
            )
            return warnings to bars
        }

        /** The active challenge's groups and deload week (the contract of this week), without the coverage; null without one. */
        suspend fun challenge(c: AppContainer, now: Instant = c.clock.now()): WeekChallenge? {
            val enrollment = c.challenges.open().firstOrNull() ?: return null
            val f = c.challenges.folded(enrollment.id)
            if (f.state.status != EnrollmentState.ACTIVE) return null
            val (_, plan) = ChallengeWeek.plan(c, f, c.challengeFacts.facts(f.def, f.state), now)
            val contract = ContractBuilder.build(f.def, c.params, f.state, plan, c.profiles.get()?.changeover_min ?: AppContainer.CHANGEOVER_MIN)
            return WeekChallenge(
                enrollment.id, dev.fitnessapp.app.ui.challenges.ChallengeTexts.title(f.def), contract.blockIndex, contract.blockWeek, contract.deloadWeek,
                contract.ownsMuscles,
            )
        }

        suspend fun compute(c: AppContainer, now: Instant = c.clock.now()): WeekVolume {
            val zone = PlanningFacts.ZONE
            val today = Week.localDate(now, zone)
            val monday = Week.isoWeekStart(today)
            val isoWeek = Week.isoWeek(monday)
            val params = c.params
            val profile = c.profiles.get()
            val saved = c.goals.current()
            val credited = PlanningFacts.credited(c, now)

            // The active challenge: owned groups, deload week, and the committed sessions and habits still ahead.
            var challenge: WeekChallenge? = null
            val committedSets = ArrayList<Pair<String, CreditSet>>()
            val enrollment = c.challenges.open().firstOrNull()
            if (enrollment != null) {
                val f = c.challenges.folded(enrollment.id)
                if (f.state.status == EnrollmentState.ACTIVE) {
                    val facts = c.challengeFacts.facts(f.def, f.state)
                    val (input, plan) = ChallengeWeek.plan(c, f, facts, now)
                    val contract = ContractBuilder.build(f.def, params, f.state, plan, profile?.changeover_min ?: AppContainer.CHANGEOVER_MIN)
                    challenge = WeekChallenge(
                        enrollment.id, dev.fitnessapp.app.ui.challenges.ChallengeTexts.title(f.def), contract.blockIndex, contract.blockWeek, contract.deloadWeek,
                        contract.ownsMuscles,
                    )
                    val plans = ArrayList<Pair<SessionPlan, Instant>>()
                    // A session in progress counts its logged sets as done, not its whole plan as committed.
                    val live = c.sessionRepository.observeActiveOnce()?.takeIf { it.enrollment_id == enrollment.id }?.challenge_slot
                    for (s in plan.sessions.filter { it.source != "done" && !it.date.isBefore(today) && !(it.date == today && it.slot == live) }) {
                        runCatching { c.challengeSessions.materialize(enrollment.id, s.slot, s.date) }.getOrNull()?.let { plans += it to s.interval.start }
                    }
                    var d = today
                    while (!d.isAfter(monday.plusDays(6))) {
                        val day = runCatching { HabitDays.of(c, f, d, input, plan) }.getOrNull()
                        day?.rows?.filter { it.state == "DUE" || it.state == "SCALED" }?.forEach { row ->
                            HabitPlanner.plan(day.def, params, day.input, row, c.catalog.doc)?.let { plans += it to Week.atLocal(d, row.time, zone) }
                        }
                        d = d.plusDays(1)
                    }
                    for ((p, start) in plans) {
                        Coverage.plannedSets(f.def, p, c.catalogModel, start, params).forEach { committedSets += p.templateId to it }
                    }
                }
            }
            val lastDone = TreeMap<String, LocalDate>()
            credited.forEach { cs -> lastDone[cs.set.exerciseSlug] = maxOf(lastDone[cs.set.exerciseSlug] ?: LocalDate.MIN, Week.localDate(cs.set.ts, zone)) }
            val committedCredits = committedSets.map { (_, s) ->
                s to SetCredit.credit(s, c.catalogModel.exercise(s.exerciseSlug)!!, c.catalogModel, lastDone[s.exerciseSlug], zone, params)
            }
            val bySession = committedCredits.groupBy { it.first.sessionId }.toSortedMap().values.map { l -> SetCredit.sessionVhyp(l.map { it.second }) }
            val from = Week.atLocal(monday, LocalTime.MIDNIGHT, zone)
            val account = SetCredit.account(credited, bySession, emptyList(), from, now, zone, params)

            // Targets (5): the saved goals, training age, the challenge's groups and deload week.
            val trainedDates = TreeMap<String, MutableSet<LocalDate>>()
            val minCredit = params.double("setcredit.trained_min_credit")
            credited.filter { it.set.ts <= now }.forEach { cs ->
                cs.credit.vhyp.forEach { (g, v) -> if (v >= minCredit) trainedDates.getOrPut(g) { sortedSetOf() } += Week.localDate(cs.set.ts, zone) }
            }
            val done = account.done.mapValues { it.value.vhyp }
            val targets = Targets.compute(
                TargetsInput(
                    isoWeek = isoWeek, goals = saved, trainingAge = profile?.training_age, age = profile?.birth_year?.let { today.year - it },
                    ownedGroups = challenge?.owns.orEmpty(), contractActive = challenge != null, challengeDeloadWeek = challenge?.deload == true,
                    committed = account.committed, done = done, trainedDates = trainedDates.mapValues { it.value.toList() }, today = today,
                    deloadSyncWithChallenge = profile?.deload_sync_with_challenge ?: true,
                ),
                params,
            )

            // WE-V1 and the bars on done + committed; [withPlan] adds the plan's general sessions once the planner ran.
            val (warnings, bars) = present(targets, account, params)

            // Per-exercise contributions for the row sheet.
            val contributions = TreeMap<String, MutableList<Contribution>>()
            fun add(rows: List<Pair<String, Map<String, Double>>>, committed: Boolean) {
                rows.groupBy { it.first }.toSortedMap().forEach { (slug, l) ->
                    for (g in Groups.CANONICAL) {
                        val v = l.sumOf { it.second[g] ?: 0.0 }
                        val n = l.count { (it.second[g] ?: 0.0) > 0.0 }
                        if (v > 0.0) contributions.getOrPut(g) { ArrayList() } += Contribution(slug, Grid.ratio(v), n, committed)
                    }
                }
            }
            add(credited.filter { !it.set.ts.isBefore(from) && it.set.ts <= now }.map { it.set.exerciseSlug to it.credit.vhyp }, false)
            add(committedCredits.map { it.first.exerciseSlug to it.second.vhyp }, true)
            contributions.values.forEach { it.sortWith(ORDER) }

            return WeekVolume(
                monday, isoWeek, saved.isNotEmpty(), saved, targets, account, bars, warnings, contributions,
                PlanningFacts.runs(c, monday), challenge,
                credited.filter { it.set.ts <= now }.map { dev.fitnessapp.algo.accounting.readiness.Dose.of(it.credit, it.set.ts) } +
                    committedCredits.map { dev.fitnessapp.algo.accounting.readiness.Dose.of(it.second, it.first.ts) },
            )
        }
    }
}
