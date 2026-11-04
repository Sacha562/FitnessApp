package dev.fitnessapp.app.ui.live

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** D88: "Do {k} now, come back after" on the Occupied sheet. */
class DoNowOrderTest {
    @Test
    fun `k takes the occupied place and the same-station next exercise waits`() {
        // x and y on the busy cable, z elsewhere: z, then x, then y.
        LiveSessionViewModel.doNowOrder(listOf("done", "x", "y", "z", "w"), "x", "z") shouldBe listOf("done", "z", "x", "y", "w")
    }

    @Test
    fun `k that is the next exercise just swaps places`() {
        LiveSessionViewModel.doNowOrder(listOf("x", "z", "w"), "x", "z") shouldBe listOf("z", "x", "w")
    }

    @Test
    fun `k listed before the occupied exercise moves right in front of it`() {
        LiveSessionViewModel.doNowOrder(listOf("z", "a", "x", "y"), "x", "z") shouldBe listOf("a", "z", "x", "y")
    }
}
