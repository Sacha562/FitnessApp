package dev.fitnessapp.app.planning

import dev.fitnessapp.algo.accounting.setcredit.Coverage
import dev.fitnessapp.algo.accounting.setcredit.CreditSet
import dev.fitnessapp.algo.accounting.setcredit.SetCredit
import dev.fitnessapp.algo.accounting.setcredit.WeekAccount
import dev.fitnessapp.algo.challenge.contract.ContractBuilder
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.materialize.HabitPlanner
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
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

/** One exercise's Vhyp for a group this week: done sets, or the committed challenge sessions and habits still ahead. */
data class Contribution(val slug: String, val vhyp: Double, val sets: Int, val committed: Boolean)

/** The active challenge as the Week tab names it: "Block 1 · week 5", deload or not, the groups it owns. */
data class WeekChallenge(val enrollmentId: String, val name: String, val blockIndex: Int, val blockWeek: Int, val deload: Boolean, val owns: List<String>)

/**
 * This ISO week's muscle volume (UX 10.8, M4.2): done Vhyp from the logged sets, committed Vhyp of the challenge sessions
 * and habits not done yet (`setcredit` coverage with the definition's credit RIR), the weekly targets (`targets` with
 * the challenge's owned groups and its deload week, F8), WE-V1's deficits and the bars of `ui.volumebars`. No general
 * sessions are planned before the week planner (M4.5), so "planned" is the challenge's committed volume only. The
 * target ramp starts at the base target until the week close is stored (M4.5).
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
) {
    companion object {
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

            // WE-V1 for each improve group on the projected week (done + committed); its WARN and STRONG are the bars' DEFICIT.
            val warnings = Warnings.rank(
                targets.groups.filterValues { it.status == "improve" }.mapNotNull { (g, _) ->
                    val x = Grid.ratio((done[g] ?: 0.0) + (account.committed[g] ?: 0.0))
                    Warnings.improveWeeklyLow(g, x, account.sessionsTraining[g] ?: 0, params)
                },
            )
            val deficit = warnings.filter { it.severity == Severity.WARN || it.severity == Severity.STRONG }.map { it.entity.removePrefix("group:") }.toSet()
            val bars = UiVolumeBars.bars(
                Groups.CANONICAL.mapNotNull { g ->
                    val gt = targets.groups[g] ?: return@mapNotNull null
                    VolumeGroup(
                        g, gt.status, gt.focus, done[g] ?: 0.0, account.committed[g] ?: 0.0, gt.targetEffective, gt.t, gt.mrv, g in deficit,
                    )
                },
                params,
            )

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
            contributions.values.forEach { l -> l.sortWith(compareBy<Contribution> { it.committed }.thenByDescending { it.vhyp }.thenBy { it.slug }) }

            return WeekVolume(
                monday, isoWeek, saved.isNotEmpty(), saved, targets, account, bars, warnings, contributions,
                PlanningFacts.runs(c, monday), challenge,
            )
        }
    }
}
