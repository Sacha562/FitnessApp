package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.params.ParamView
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * UI-07 (ALGORITHMS_PLANNING 19.3, UX 25.6): GF-UX-11, friPrompts, thuPromptsBudget, elbowNeverReducedWithOap, painGuard,
 * elbowFollowUpOutsideBudget, and the adaptive reduction.
 */
class UiPromptsTest {
    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.2.json")).readText(Charsets.UTF_8),
    )

    /** Thursday 8 October 19:45 CEST; Tuesday's session C ended 48 h earlier. */
    private val thu = Instant.parse("2026-10-08T17:45:00Z")
    private val tueC = Instant.parse("2026-10-06T17:45:00Z")

    private fun input(
        planned: Map<String, Double>,
        now: Instant = thu,
        trained: Instant = tueC,
        oap: Boolean = true,
        history: List<PromptHistoryEntry> = emptyList(),
        lastPain: Instant? = null,
        toggles: PromptToggles = PromptToggles(),
        home: Boolean = false,
    ) = PromptInput(
        home = home, plannedFs = planned, lastTrained = planned.keys.associateWith { trained }, now = now, toggles = toggles,
        history = history, lastPainAt = lastPain, challengePainRule = oap, elbowRequired = oap,
    )

    private fun codes(items: List<PromptItem>) = items.map { it.prompt.code + (it.group?.let { g -> ":$g" } ?: "") }

    @Test
    fun `GF-UX-11 session A prompt plan`() {
        val p = UiPrompts.plan(input(mapOf("lats" to 5.5, "upper_back" to 4.0, "biceps" to 3.0, "forearms" to 2.5)), params)
        codes(p.pre) shouldBe listOf("readiness", "elbow_check", "soreness:lats", "soreness:upper_back")
        codes(p.post) shouldBe listOf("srpe", "joint_pain")
        p.taps shouldBe 6
        // Forearms was never a candidate (sore_max 3); biceps is dropped for the budget.
        p.dropped shouldBe listOf(PromptDropped(Prompt.SORENESS, "biceps", "budget"))
    }

    @Test
    fun friPrompts() {
        // Friday: rear delts 2.5, quads 1.5, hamstrings 1.5, all trained within 96 h; no challenge pain rule asked here.
        val fri = Instant.parse("2026-10-09T17:45:00Z")
        val p = UiPrompts.plan(input(mapOf("rear_delts" to 2.5, "quads" to 1.5, "hamstrings" to 1.5), now = fri, oap = false), params)
        codes(p.pre) shouldBe listOf("readiness", "elbow_check", "soreness:rear_delts", "soreness:quads")
        codes(p.post) shouldBe listOf("srpe", "joint_pain")
        p.taps shouldBe 6
        p.dropped shouldBe listOf(PromptDropped(Prompt.SORENESS, "hamstrings", "budget"))
    }

    @Test
    fun thuPromptsBudget() {
        val p = UiPrompts.plan(input(mapOf("lats" to 6.5, "forearms" to 5.3, "biceps" to 3.5)), params)
        codes(p.pre) shouldBe listOf("readiness", "elbow_check", "soreness:lats", "soreness:forearms")
        p.taps shouldBe 6
        p.dropped.map { it.group } shouldBe listOf("biceps")
    }

    @Test
    fun sorenessOnlyWithinWindow() {
        // Trained 97 h before: no soreness row.
        val p = UiPrompts.plan(input(mapOf("quads" to 3.0), trained = thu.minusSeconds(97 * 3600)), params)
        p.pre.none { it.prompt == Prompt.SORENESS } shouldBe true
    }

    @Test
    fun elbowNeverReducedWithOap() {
        val skips = List(6) { PromptHistoryEntry(Prompt.ELBOW_CHECK, PromptOutcome.SKIPPED) }
        val off = PromptToggles(elbowCheck = false)
        UiPrompts.plan(input(emptyMap(), history = skips, toggles = off), params).pre.map { it.prompt } shouldBe listOf(Prompt.READINESS, Prompt.ELBOW_CHECK)
        // Without the challenge rule the same history reduces it, and the switch turns it off.
        val noOap = UiPrompts.plan(input(emptyMap(), oap = false, history = skips), params)
        noOap.pre.map { it.prompt } shouldBe listOf(Prompt.READINESS)
        noOap.dropped shouldBe listOf(PromptDropped(Prompt.ELBOW_CHECK, null, "reduced"))
        UiPrompts.plan(input(emptyMap(), oap = false, toggles = off), params).pre.map { it.prompt } shouldBe listOf(Prompt.READINESS)
    }

    @Test
    fun painGuard() {
        val off = PromptToggles(painAfter = false)
        // Pain 10 days ago: the prompt stays although switched off.
        UiPrompts.plan(input(emptyMap(), oap = false, toggles = off, lastPain = thu.minusSeconds(10 * 86400)), params).post.map { it.prompt } shouldBe
            listOf(Prompt.SRPE, Prompt.JOINT_PAIN)
        // 15 days ago: the switch wins.
        UiPrompts.plan(input(emptyMap(), oap = false, toggles = off, lastPain = thu.minusSeconds(15 * 86400)), params).post.map { it.prompt } shouldBe
            listOf(Prompt.SRPE)
    }

    @Test
    fun elbowFollowUpOutsideBudget() {
        // The kind tap at a score of 2 or more is not budgeted: the plan keeps two soreness rows at 6 taps.
        val p = UiPrompts.plan(input(mapOf("rear_delts" to 2.5, "quads" to 1.5)), params)
        p.taps shouldBe 6
        p.dropped shouldBe emptyList()
    }

    @Test
    fun homeSessionAsksPainOnly() {
        val p = UiPrompts.plan(input(mapOf("forearms" to 1.0), home = true), params)
        p.pre shouldBe emptyList()
        p.post.map { it.prompt } shouldBe listOf(Prompt.JOINT_PAIN)
    }

    @Test
    fun adaptiveReductionAndRestore() {
        fun h(vararg o: PromptOutcome) = o.map { PromptHistoryEntry(Prompt.READINESS, it) }
        val s = PromptOutcome.SKIPPED
        val a = PromptOutcome.ANSWERED
        val r = PromptOutcome.REDUCED
        UiPrompts.adaptive(h(s, s), params)[Prompt.READINESS]!!.reduced shouldBe false
        UiPrompts.adaptive(h(s, a, s, s), params)[Prompt.READINESS]!!.reduced shouldBe false
        UiPrompts.adaptive(h(s, s, s), params)[Prompt.READINESS]!!.reduced shouldBe true
        // Reduced: shown every 3rd session.
        fun shown(vararg o: PromptOutcome) =
            UiPrompts.plan(input(emptyMap(), oap = false, history = h(*o)), params).pre.any { it.prompt == Prompt.READINESS }
        shown(s, s, s) shouldBe false
        shown(s, s, s, r) shouldBe false
        shown(s, s, s, r, r) shouldBe true
        // Restored after 3 answers while reduced; a Reset restores at once.
        UiPrompts.adaptive(h(s, s, s, r, r, a, r, r, a, r, r), params)[Prompt.READINESS]!!.reduced shouldBe true
        UiPrompts.adaptive(h(s, s, s, r, r, a, r, r, a, r, r, a), params)[Prompt.READINESS]!!.reduced shouldBe false
        UiPrompts.adaptive(h(s, s, s, PromptOutcome.RESET), params)[Prompt.READINESS]!!.reduced shouldBe false
    }
}
