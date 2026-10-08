package dev.fitnessapp.data.sync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** ANDROID.md 27.2 `G9_LwwMergeTest`: tuple order and the merge decision of DATA_MODEL 11.5. */
class LwwMergeTest {
    private val a = "0f7c3a1e-0000-4000-8000-000000000001"
    private val b = "0F7C3A1E-0000-4000-8000-000000000002"

    @Test
    fun `newer client time wins, device id breaks ties case-insensitively`() {
        (LwwTuple(2, a) > LwwTuple(1, b)) shouldBe true
        (LwwTuple(1, b) > LwwTuple(1, a)) shouldBe true
        (LwwTuple(1, a.uppercase()) == LwwTuple(1, a)) shouldBe false // data equality is textual ...
        LwwTuple(1, a.uppercase()).compareTo(LwwTuple(1, a)) shouldBe 0 // ... but the order ignores case
    }

    @Test
    fun decisions() {
        LwwMerge.decide(LwwTuple(5, a), null) shouldBe MergeAction.INSERT
        LwwMerge.decide(LwwTuple(5, a), LwwTuple(4, b)) shouldBe MergeAction.OVERWRITE
        LwwMerge.decide(LwwTuple(5, a), LwwTuple(5, a)) shouldBe MergeAction.MARK_SYNCED
        LwwMerge.decide(LwwTuple(5, a), LwwTuple(6, a)) shouldBe MergeAction.KEEP_LOCAL
        LwwMerge.decide(LwwTuple(5, a), LwwTuple(5, b)) shouldBe MergeAction.KEEP_LOCAL
    }

    @Test
    fun `the server device id wins ties`() {
        val server = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        LwwMerge.decide(LwwTuple(7, server), LwwTuple(7, a)) shouldBe MergeAction.OVERWRITE
    }
}
