package dev.fitnessapp.data.engine

import dev.fitnessapp.data.engine.WarmupHabits.Companion.propose
import dev.fitnessapp.data.engine.WarmupHabits.Obs
import io.kotest.matchers.shouldBe
import org.junit.Test

/** D107 (2): when the Session Summary offers to plan fewer warm-ups. */
class WarmupHabitsTest {
    @Test
    fun `all skipped twice offers none`() {
        propose(Obs(1, 0), Obs(2, 0), null, emptySet()) shouldBe 0
    }

    @Test
    fun `fewer than planned twice offers the higher count`() {
        propose(Obs(3, 1), Obs(2, 0), null, emptySet()) shouldBe 1
        propose(Obs(3, 1), Obs(3, 2), null, emptySet()) shouldBe 2
    }

    @Test
    fun `one session as planned is no habit`() {
        propose(Obs(2, 2), Obs(2, 0), null, emptySet()) shouldBe null
        propose(Obs(2, 0), Obs(1, 1), null, emptySet()) shouldBe null
        propose(Obs(1, 2), Obs(1, 0), null, emptySet()) shouldBe null // an extra warm-up is not fewer
    }

    @Test
    fun `no offer at or below the stored count or after a Keep for that count`() {
        propose(Obs(1, 0), Obs(1, 0), 0, emptySet()) shouldBe null
        propose(Obs(1, 0), Obs(1, 0), 1, emptySet()) shouldBe 0
        propose(Obs(3, 1), Obs(3, 1), null, setOf(1)) shouldBe null
        propose(Obs(3, 0), Obs(3, 0), null, setOf(1)) shouldBe 0
    }
}
