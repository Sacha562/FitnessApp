package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.substitute.SubCandidate
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** UX 13.3 / 16.5 tags and the start line of a SUB-1 candidate. */
class UiSwapTest {
    private val doc = CatalogDoc.parse(resource("/fitness/catalog/catalog-1.0.3.json"))
    private fun ex(slug: String) = doc.exercises.first { it.slug == slug }
    private fun cand(slug: String, sim: Double, tier: PrefTier) = SubCandidate(slug, 0.9, sim, 1.0, 0.5, 1.0, 0.0, tier)

    @Test
    fun tags() {
        UiSwap.tags(cand("hack_squat_machine", 1.0, PrefTier.NEUTRAL), ex("hack_squat_machine"), onlyDisliked = false) shouldBe listOf("same muscles")
        UiSwap.tags(cand("push_up", 0.9, PrefTier.LIKE), ex("push_up"), onlyDisliked = false) shouldBe listOf("similar muscles", "liked", "no station")
        UiSwap.tags(cand("hack_squat_machine", 0.97, PrefTier.DISLIKE), ex("hack_squat_machine"), onlyDisliked = true) shouldBe
            listOf("same muscles", "disliked: closest option")
    }

    @Test
    fun startLine() {
        UiSwap.startLine("80 kg × 10", "Leg press") shouldBe "last time 80 kg × 10"
        UiSwap.startLine(null, "Leg press") shouldBe "first time: start load estimated from Leg press"
        UiSwap.startLine(null, null) shouldBe "first time: an easy calibration set first"
    }

    private fun resource(path: String): String = requireNotNull(javaClass.getResource(path)).readText(Charsets.UTF_8)
}
