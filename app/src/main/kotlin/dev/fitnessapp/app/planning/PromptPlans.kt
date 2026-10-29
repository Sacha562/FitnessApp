package dev.fitnessapp.app.planning

import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.uidet.PromptAdaptive
import dev.fitnessapp.app.uidet.PromptInput
import dev.fitnessapp.app.uidet.PromptPlan
import dev.fitnessapp.app.uidet.PromptToggles
import dev.fitnessapp.app.uidet.UiPrompts
import dev.fitnessapp.app.uidet.Prompt
import dev.fitnessapp.data.db.entity.ProfileEntity
import java.time.Instant

/** The inputs of `ui.prompts` as the phone holds them (UX 19.4): switches, history, pain reports, the challenge's rules. */
object PromptPlans {
    /** An active challenge with a rule on pain reports or the elbow check (`pain.reported`, `readiness.checked`). */
    suspend fun challengePainRule(c: AppContainer): Boolean {
        val e = c.challenges.open().firstOrNull() ?: return false
        val f = c.challenges.folded(e.id)
        if (f.state.status != EnrollmentState.ACTIVE) return false
        return f.def.rules.any { r -> r.spec.on.any { it == "pain.reported" || it == "readiness.checked" } }
    }

    fun toggles(p: ProfileEntity?): PromptToggles = PromptToggles(
        readiness = p?.prompt_readiness ?: true, elbowCheck = p?.prompt_elbow_check ?: true, soreness = p?.prompt_soreness ?: true,
        srpe = p?.prompt_srpe ?: true, painAfter = p?.prompt_pain_after ?: true,
    )

    /**
     * The plan for a session about to start (pre) or just finished (post): [plannedFs] is the session's planned direct
     * FS per group (empty after the session); [elbowRequired] when the template asks the elbow check.
     */
    suspend fun plan(c: AppContainer, home: Boolean, plannedFs: Map<String, Double>, elbowRequired: Boolean, now: Instant = c.clock.now()): PromptPlan {
        val today = Week.localDate(now, PlanningFacts.ZONE).toString()
        val input = PromptInput(
            home = home, plannedFs = plannedFs,
            lastTrained = if (plannedFs.isEmpty()) emptyMap() else PlanningFacts.lastTrained(PlanningFacts.credited(c, now)),
            now = now, toggles = toggles(c.profiles.get()), history = PlanningFacts.promptHistory(c.prompts.history()),
            lastPainAt = c.prompts.lastPainAt(), challengePainRule = challengePainRule(c), elbowRequired = elbowRequired,
            elbowCheckedToday = c.prompts.elbowCheckedOn(today),
        )
        return UiPrompts.plan(input, c.params)
    }

    suspend fun adaptive(c: AppContainer): Map<Prompt, PromptAdaptive> = UiPrompts.adaptive(PlanningFacts.promptHistory(c.prompts.history()), c.params)
}
