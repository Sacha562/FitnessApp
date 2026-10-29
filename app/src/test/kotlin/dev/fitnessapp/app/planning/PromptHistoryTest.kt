package dev.fitnessapp.app.planning

import dev.fitnessapp.app.uidet.Prompt
import dev.fitnessapp.app.uidet.PromptHistoryEntry
import dev.fitnessapp.app.uidet.PromptOutcome
import dev.fitnessapp.data.repo.PromptEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The `ui.prompts` history rebuilt from the `prompt_*` events (UX 19.4): one outcome per session and prompt. */
class PromptHistoryTest {
    private fun shown(s: String, p: String, at: Long) = PromptEvent(s, "prompt_shown", p, null, at)
    private fun skipped(s: String, p: String, cause: String, at: Long) = PromptEvent(s, "prompt_skipped", p, cause, at)

    @Test
    fun outcomesPerSession() {
        val h = PlanningFacts.promptHistory(
            listOf(
                // Session 1: readiness answered, srpe skipped, soreness dropped for the budget.
                shown("s1", "readiness", 10), shown("s1", "srpe", 11), skipped("s1", "srpe", "user", 12), skipped("s1", "soreness", "budget", 10),
                // Session 2: two soreness rows, one answered: soreness counts as answered; readiness reduced.
                shown("s2", "soreness", 20), shown("s2", "soreness", 20), skipped("s2", "soreness", "user", 21), skipped("s2", "readiness", "reduced", 20),
                PromptEvent(null, "reset", "readiness", null, 30),
            ),
        )
        h shouldBe listOf(
            PromptHistoryEntry(Prompt.READINESS, PromptOutcome.ANSWERED),
            PromptHistoryEntry(Prompt.SORENESS, PromptOutcome.BUDGET),
            PromptHistoryEntry(Prompt.SRPE, PromptOutcome.SKIPPED),
            PromptHistoryEntry(Prompt.READINESS, PromptOutcome.REDUCED),
            PromptHistoryEntry(Prompt.SORENESS, PromptOutcome.ANSWERED),
            PromptHistoryEntry(Prompt.READINESS, PromptOutcome.RESET),
        )
    }

    @Test
    fun allSorenessRowsSkippedIsASkip() {
        PlanningFacts.promptHistory(
            listOf(shown("s", "soreness", 1), shown("s", "soreness", 1), skipped("s", "soreness", "user", 2), skipped("s", "soreness", "user", 2)),
        ) shouldBe listOf(PromptHistoryEntry(Prompt.SORENESS, PromptOutcome.SKIPPED))
    }
}
