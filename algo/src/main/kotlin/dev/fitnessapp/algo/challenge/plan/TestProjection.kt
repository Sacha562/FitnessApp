package dev.fitnessapp.algo.challenge.plan

import dev.fitnessapp.algo.challenge.cds.Action
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.Modes
import dev.fitnessapp.algo.challenge.eca.Records
import dev.fitnessapp.algo.challenge.eca.RuleEngine
import dev.fitnessapp.algo.challenge.eca.RuleEnv
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.EvalError
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import java.time.LocalDate

/**
 * The expected test date while none is planned yet (CHALLENGE_ENGINE 7.7, D72): the date the definition's own
 * `scheduleTest` action would set if every remaining week of the block counts. Display only, never stored.
 */
object TestProjection {
    /** [basis] is `blockRelative` or `fixedList`, as the planned test's would be. */
    data class Expected(val date: LocalDate, val basis: String)

    fun expected(def: CompiledDefinition, params: ParamView, state: EnrollmentState, facts: EngineFacts, today: LocalDate): Expected? {
        val pt = state.plannedTest
        if (state.status != EnrollmentState.ACTIVE || pt.date != null || pt.basis == "postponed" || state.mode == Modes.INJURY) return null
        val env = RuleEnv(def, params, Records(params), state, today, facts)
        val how = def.rules.filter { EventTypes.WEEK_STARTED in it.spec.on && RuleEngine.gateOpen(it, env) }
            .flatMap { scheduleTests(it.actions) }.toSortedSet().singleOrNull() ?: return null
        val counts = RuleEngine.countsAsBlockWeek(def, env, state.mode)
        val monday = Week.isoWeekStart(today)
        return try {
            when (how) {
                "testWeekday" -> {
                    val weeks = (def.blockWeeks.evaluate(env.roots(), env.host) as? QV)?.v?.toInt() ?: return null
                    val ahead = weeks - state.block.week + (if (counts) 0 else 1)
                    if (ahead < 0) null else Expected(RuleEngine.testDate(def, monday.plusWeeks(ahead.toLong())), "blockRelative")
                }
                "fixedDate" -> env.nextFixedTestDate(if (counts) monday else monday.plusWeeks(1))?.let { Expected(it, "fixedList") }
                else -> null
            }
        } catch (_: EvalError) {
            null
        }
    }

    private fun scheduleTests(actions: List<Action>): List<String> = actions.flatMap {
        when (it) {
            is Action.ScheduleTest -> listOf(it.how)
            is Action.If -> scheduleTests(it.then) + scheduleTests(it.otherwise)
            else -> emptyList()
        }
    }
}
