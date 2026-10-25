package dev.fitnessapp.algo.challenge.plan

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import java.time.Duration
import java.time.Instant

/**
 * `ChallengeTodayAdvice` of CHALLENGE_ENGINE 7.6: `challenge` (with slot and start), `general_allowed` (with
 * the session tags a general session must avoid) or `rest` (with the rule). `today` (ALGORITHMS_PLANNING 9)
 * turns it into TRAIN / REST_SOFT / REST_HARD.
 */
data class TodayAdvice(
    val kind: String,
    val slot: String? = null,
    val start: Instant? = null,
    val forbiddenSessionTags: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
)

object TodayAdvisor {
    fun advise(def: CompiledDefinition, params: ParamView, input: PlanInput, plan: ChallengeWeekPlan, trainNow: Boolean = false): TodayAdvice {
        val today = Week.localDate(input.now, input.zone)
        plan.sessions.firstOrNull { it.date == today }?.let { return TodayAdvice("challenge", it.slot, it.interval.start, reasons = listOf("planned:${it.source}")) }
        if (trainNow) trainNow(def, params, input, plan, today)?.let { return it }
        val blocked = plan.blockedDays.firstOrNull { it.date == today }
        return when {
            blocked == null -> TodayAdvice("general_allowed")
            blocked.scope == "tags" -> TodayAdvice("general_allowed", forbiddenSessionTags = blocked.tags, reasons = listOf("${blocked.ruleId}: ${blocked.reason}"))
            else -> TodayAdvice("rest", reasons = listOf("${blocked.ruleId}: ${blocked.reason}"))
        }
    }

    /**
     * 7.6 step 2 (D42): every slot of the week not done yet, kept by the plan or dropped from it, is tried as a session
     * starting now, whatever the time of day. A trial counts when the session itself breaks no hard constraint and the
     * week drops no slot that the plan kept. Of those, the trial that leaves the fewest slots out wins; then the one that
     * leaves out the less important slots (`slotPriority`); then the program's `order`.
     */
    private fun trainNow(def: CompiledDefinition, params: ParamView, input: PlanInput, plan: ChallengeWeekPlan, today: java.time.LocalDate): TodayAdvice? {
        val sequence = def.doc.schedule.order.sequence
        val priority = def.doc.schedule.slotPriority
        fun seq(slot: String) = sequence.indexOfFirst { slot in it }.let { if (it < 0) Int.MAX_VALUE else it }
        fun prio(slot: String) = priority.indexOf(slot).let { if (it < 0) Int.MAX_VALUE else it }
        val left = plan.droppedSlots.toSet()
        val remaining = (plan.sessions.filter { it.source == "suggested" }.map { it.slot } + plan.droppedSlots).distinct().sortedWith(compareBy({ seq(it) }, { it }))
        val start = input.now
        var best: Pair<String, List<Int>>? = null
        for (slot in remaining) {
            val dur = Duration.ofMinutes((def.doc.schedule.preferred.firstOrNull { it.slot == slot }?.durMin ?: Materializer.templateFor(def, input.state, slot)?.bookedMin ?: 0).toLong())
            // The trial is fixed like a matched event, so every hard constraint it breaks is charged to it (7.4).
            val trial = WeekPlanner.plan(def, params, input.copy(matched = input.matched + SlotAnchor(slot, Interval(start, start.plus(dur)), "train_now", null)))
            val hard = trial.sessions.filter { it.slot == slot && it.date == today && it.source == "train_now" }
            if (hard.isEmpty() || hard.any { it.constraintWarnings.isNotEmpty() }) continue
            if (trial.droppedSlots.any { it !in left }) continue
            // Fewer slots left out, then the left-out slots least important first (higher index = less important).
            val key = listOf(trial.droppedSlots.size) + trial.droppedSlots.map { -prio(it) }.sorted()
            if (best == null || compareKeys(key, best.second) < 0) best = slot to key
        }
        return best?.let { TodayAdvice("challenge", it.first, start, reasons = listOf("train_now")) }
    }

    private fun compareKeys(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return a.size.compareTo(b.size)
    }
}
