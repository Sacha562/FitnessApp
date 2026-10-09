package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.params.ParamView
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** ALGORITHMS_PLANNING 19.2 tests and UX GF-UX-14. */
class UiReasonChipsTest {
    private val params = ParamView.fromParamsFile(resource("/fitness/params/default-params-1.0.1.json"))
    private val vocab = ReasonVocabulary.parse(resource("/fitness/reference/reason_codes.json"))

    private fun codes(set: ChipSet) = set.visible.map { it.code } to set.more.map { it.code }

    @Test
    fun harderOrderRotationFlag() {
        val set = UiReasonChips.chipsFor(ChipContext.HARDER, ExerciseFlags(listOf("torso_rotation")), vocab, params).value
        codes(set) shouldBe (listOf("too_hard", "feeling_bad", "too_tired", "pain", "grip_failure", "torso_rotation") to
            listOf("technique_breakdown", "no_time", "occupied", "other"))
        set.visible.first { it.code == "torso_rotation" }.impliesFlag shouldBe "torso_rotation"
    }

    @Test
    fun harderOrderNoRotationFlag() {
        // Weighted pull-up (grip slip flag), the assisted one-arm machine (no flag) and the leg press (none).
        for (flags in listOf(listOf("grip_slip_before_last"), emptyList(), emptyList<String>())) {
            codes(UiReasonChips.chipsFor(ChipContext.HARDER, ExerciseFlags(flags), vocab, params).value) shouldBe
                (listOf("too_hard", "feeling_bad", "too_tired", "pain", "grip_failure", "technique_breakdown") to
                    listOf("no_time", "occupied", "other"))
        }
    }

    @Test
    fun easierSkipSessionEndAndSubstitute() {
        val none = ExerciseFlags(emptyList())
        codes(UiReasonChips.chipsFor(ChipContext.EASIER, none, vocab, params).value) shouldBe
            (listOf("too_easy", "feeling_good", "other") to emptyList())
        val skip = UiReasonChips.chipsFor(ChipContext.SKIP, none, vocab, params).value
        codes(skip) shouldBe (listOf("no_time", "occupied", "pain", "feeling_bad", "too_tired", "equipment_missing", "other") to emptyList())
        skip.required shouldBe true
        val end = UiReasonChips.chipsFor(ChipContext.SESSION_END, none, vocab, params).value
        codes(end) shouldBe (listOf("no_time", "pain", "other") to emptyList())
        end.preselected shouldBe "no_time"
        codes(UiReasonChips.chipsFor(ChipContext.SUBSTITUTE, none, vocab, params).value) shouldBe
            (listOf("occupied", "equipment_missing") to emptyList())
    }

    @Test
    fun skipOneTapAndRoutes() {
        UiReasonChips.route("no_time", ChipContext.SKIP) shouldBe ChipRoute.RECORD
        UiReasonChips.route("occupied", ChipContext.SKIP) shouldBe ChipRoute.DO_LATER
        UiReasonChips.route("occupied", ChipContext.HARDER) shouldBe ChipRoute.RECORD
        UiReasonChips.route("other", ChipContext.SKIP) shouldBe ChipRoute.OPTIONAL_TEXT
    }

    @Test
    fun painRoutesToSafety() {
        for (ctx in listOf(ChipContext.HARDER, ChipContext.SKIP, ChipContext.SESSION_END)) {
            UiReasonChips.route("pain", ctx) shouldBe ChipRoute.PAIN_SHEET
        }
    }

    @Test
    fun occupiedDeferThenSwap() {
        val order = listOf(
            SessionExerciseRef("warmup", false), SessionExerciseRef("assisted", false), SessionExerciseRef("wpu", true),
            SessionExerciseRef("top_hold", true), SessionExerciseRef("hold90", true),
        )
        val first = UiReasonChips.doLater(order, "wpu", 0, params)
        first.order shouldBe listOf("warmup", "assisted", "top_hold", "wpu", "hold90")
        first.deferCount shouldBe 1
        first.swapPrimary shouldBe false
        val order2 = first.order.map { id -> order.first { it.id == id } }
        val second = UiReasonChips.doLater(order2, "wpu", first.deferCount, params)
        second.order shouldBe listOf("warmup", "assisted", "top_hold", "hold90", "wpu")
        second.deferCount shouldBe 2
        second.swapPrimary shouldBe true
        val order3 = second.order.map { id -> order.first { it.id == id } }
        UiReasonChips.canDoLater(order3, "wpu") shouldBe false
        UiReasonChips.doLater(order3, "wpu", 2, params).moved shouldBe false
    }

    @Test
    fun inputsHashIsStable() {
        val a = UiReasonChips.chipsFor(ChipContext.HARDER, ExerciseFlags(listOf("b", "a")), vocab, params)
        val b = UiReasonChips.chipsFor(ChipContext.HARDER, ExerciseFlags(listOf("a", "b")), vocab, params)
        a.inputsHash shouldBe b.inputsHash
        a.moduleId shouldBe "ui.reasonchips"
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResource(path)) { "missing $path" }.readText(Charsets.UTF_8)
}
