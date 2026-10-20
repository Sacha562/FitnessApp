package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.eca.ChallengeNotification
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The pause helpers of CHALLENGE_ENGINE 4.6 and 10.5: the absence prompt ("Were you away?") and the dry run
 * of the definition's `resume` rules that the pause sheet shows ("Back within N days: ..."). Both are pure:
 * the dry run folds synthetic events on a copy of the state and never stores them.
 */
object PauseTools {
    /** 4.6: ask before synthesizing when the last challenge session is at least `absence_prompt_days` ago. */
    fun absencePromptDue(state: EnrollmentState, today: LocalDate, params: ParamView): Boolean {
        if (state.status != EnrollmentState.ACTIVE) return false
        val last = state.lastChallengeSessionDate ?: return false
        return Week.calendarDays(last, today) >= params.int("challenge.enrollment.absence_prompt_days")
    }

    /** What resuming after [gapDays] would do: the resulting state and the notifications the rules raise. */
    data class ResumePreview(val gapDays: Long, val state: EnrollmentState, val notifications: List<ChallengeNotification>)

    /**
     * Dry-runs a pause on [pauseDate] and a resume [gapDays] later for each gap (10.5: 1, 7 and 28 days),
     * including the ticks in between, so the rules see the same calendar a real pause would.
     */
    fun previewResume(
        def: CompiledDefinition,
        params: ParamView,
        state: EnrollmentState,
        pauseDate: LocalDate,
        zone: ZoneId,
        facts: EngineFacts?,
        gaps: List<Long>,
    ): List<ResumePreview> = gaps.map { gap ->
        fun at(d: LocalDate, t: LocalTime): Instant = Week.atLocal(d, t, zone)
        val pause = ChallengeEvent(
            Ids.fitness("dryrun:pause:${state.enrollmentId}:$pauseDate"), EventTypes.PAUSE, at(pauseDate, LocalTime.NOON), pauseDate,
            EventPayload.Pause(null), facts,
        )
        var s = EnrollmentFold.apply(def, params, state, pause).state
        val resumeDate = pauseDate.plusDays(gap)
        s = EnrollmentFold.catchUp(def, params, s, at(resumeDate, LocalTime.NOON), zone, facts).second.lastOrNull()?.state ?: s
        val resume = ChallengeEvent(
            Ids.fitness("dryrun:resume:${state.enrollmentId}:$resumeDate"), EventTypes.RESUME, at(resumeDate, LocalTime.NOON).plusMillis(1L),
            resumeDate, EventPayload.Resume(null, gap), facts,
        )
        val step = EnrollmentFold.apply(def, params, s, resume)
        ResumePreview(gap, step.state, step.notifications)
    }
}
