package dev.fitnessapp.algo.selection.elig

import dev.fitnessapp.algo.selection.pref.Pref
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.pref.PrefValue
import dev.fitnessapp.algo.testing.TestCatalog
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** ELIG-1's necessity gate, group availability and C50's `onlyIfGoalsAsk` on the seed (ALGORITHMS_PLANNING 12.4, 12.5). */
class EligibilityTest {
    private val catalog = TestCatalog.catalog
    private val params = TestParams.defaults()

    private fun prefs(vararg p: Pair<String, PrefTier>) =
        p.associate { (slug, t) -> slug to PrefValue(Pref.mult(t, params), t, hidden = false, z = 0.0, explicit = true, prompt = false) }

    private val adductorsImprove = GateInput(target = mapOf("adductors" to 4.0), goals = mapOf("adductors" to "improve"))
    private val adductorRows = arrayOf("cable_hip_adduction" to PrefTier.DISLIKE, "hip_adduction_machine" to PrefTier.DISLIKE, "leg_press_high_wide" to PrefTier.DISLIKE)

    private fun gate(ctx: EligContext, input: GateInput) = Elig.gate(Elig.pool(catalog, ctx, params), catalog, ctx, input, params)

    @Test
    fun `a disliked exercise is unlocked only when nothing else trains the group`() {
        // The seed's back squat (adductors 0.75) is the neutral option; the lumbar flag excludes it.
        val lumbar = mapOf("lumbar" to 2)
        val one = gate(EligContext(prefs = prefs("hip_adduction_machine" to PrefTier.DISLIKE), jointFlags = lumbar), adductorsImprove)
        one.unlocked.shouldBeEmpty()
        one.pool.map { it.slug } shouldNotContain "hip_adduction_machine"
        val all = gate(EligContext(prefs = prefs(*adductorRows), jointFlags = lumbar), adductorsImprove)
        all.unlocked shouldContainExactly listOf(Unlock("cable_hip_adduction", "adductors")) // coefficient 1.0, sfr 5, then slug
        all.pool.map { it.slug } shouldContain "cable_hip_adduction"
        all.pool.map { it.slug } shouldNotContain "hip_adduction_machine"
    }

    @Test
    fun `a maintain group gates at 0_5`() {
        val ctx = EligContext(prefs = prefs(*adductorRows), jointFlags = mapOf("lumbar" to 2))
        // The leg press (adductors 0.5) reaches the maintain gate, so nothing is unlocked.
        gate(ctx, GateInput(mapOf("adductors" to 2.0), mapOf("adductors" to "maintain"))).unlocked.shouldBeEmpty()
    }

    @Test
    fun `an exercise unlocked earlier this week stays out once the group has half its target`() {
        val ctx = EligContext(prefs = prefs(*adductorRows), jointFlags = mapOf("lumbar" to 2))
        val again = adductorsImprove.copy(unlockedThisWeek = setOf("cable_hip_adduction", "hip_adduction_machine", "leg_press_high_wide"),
            weeklyCoverage = mapOf("adductors" to 3.0), weeklyTarget = mapOf("adductors" to 4.0))
        gate(ctx, again).unlocked.shouldBeEmpty()
        gate(ctx, again.copy(weeklyCoverage = mapOf("adductors" to 1.0))).unlocked shouldContainExactly listOf(Unlock("cable_hip_adduction", "adductors"))
    }

    @Test
    fun `never is never unlocked and leaves a coverage gap`() {
        val never = arrayOf("cable_hip_adduction" to PrefTier.NEVER, "hip_adduction_machine" to PrefTier.NEVER, "leg_press_high_wide" to PrefTier.NEVER)
        val r = gate(EligContext(prefs = prefs(*never), jointFlags = mapOf("lumbar" to 2)), adductorsImprove)
        r.unlocked.shouldBeEmpty()
        r.coverageGaps shouldContainExactly listOf("adductors")
    }

    @Test
    fun `groups below one FS or skipped are not gated`() {
        val ctx = EligContext(prefs = prefs(*adductorRows), jointFlags = mapOf("lumbar" to 2))
        gate(ctx, GateInput(mapOf("adductors" to 0.5), mapOf("adductors" to "improve"))).unlocked.shouldBeEmpty()
    }

    @Test
    fun `light pull needs goals that ask for it`() {
        val gap = EligContext(gapDay = true, forbiddenSessionTags = listOf("pull_heavy", "grip_heavy", "elbow_flexor_heavy"), lightPull = LightPullRule(1, 0.5))
        Elig.reason(catalog.exercise("reverse_pec_deck")!!, catalog, gap, params) shouldBe null
        Elig.reason(catalog.exercise("reverse_pec_deck")!!, catalog, gap.copy(goalGroups = setOf("rear_delts", "upper_back")), params) shouldBe null
        Elig.reason(catalog.exercise("reverse_pec_deck")!!, catalog, gap.copy(goalGroups = setOf("upper_back")), params) shouldBe EligReason.GAP_DAY_PULL
        Elig.reason(catalog.exercise("seated_cable_row_neutral")!!, catalog, gap.copy(goalGroups = setOf("rear_delts", "upper_back")), params) shouldBe EligReason.GAP_DAY_PULL
    }

    @Test
    fun `group availability ignores preferences except never`() {
        val ctx = EligContext(prefs = prefs(*adductorRows))
        Elig.groupAvailability(catalog, ctx, params)["adductors"] shouldBe true
        val never = EligContext(prefs = prefs("cable_hip_adduction" to PrefTier.NEVER, "hip_adduction_machine" to PrefTier.NEVER, "leg_press_high_wide" to PrefTier.NEVER, "back_squat_barbell" to PrefTier.NEVER))
        Elig.groupAvailability(catalog, never, params)["adductors"] shouldBe false
    }
}
