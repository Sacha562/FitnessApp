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
        if (trainNow) {
            val remaining = plan.sessions.filter { it.source == "suggested" }.map { it.slot }
            val start = input.now.plus(Duration.ofMinutes(params.int("challenge.plan.lead_min").toLong()))
            for (slot in remaining) {
                val dur = Duration.ofMinutes((def.doc.schedule.preferred.firstOrNull { it.slot == slot }?.durMin ?: Materializer.templateFor(def, input.state, slot)?.bookedMin ?: 0).toLong())
                val trial = WeekPlanner.plan(def, params, input.copy(matched = input.matched + SlotAnchor(slot, Interval(start, start.plus(dur)), "suggested", null)))
                val hard = trial.sessions.filter { it.slot == slot && it.date == today }
                if (!trial.partial && hard.isNotEmpty() && hard.all { it.constraintWarnings.isEmpty() }) {
                    return TodayAdvice("challenge", slot, start, reasons = listOf("train_now"))
                }
            }
        }
        val blocked = plan.blockedDays.firstOrNull { it.date == today }
        return when {
            blocked == null -> TodayAdvice("general_allowed")
            blocked.scope == "tags" -> TodayAdvice("general_allowed", forbiddenSessionTags = blocked.tags, reasons = listOf("${blocked.ruleId}: ${blocked.reason}"))
            else -> TodayAdvice("rest", reasons = listOf("${blocked.ruleId}: ${blocked.reason}"))
        }
    }
}
