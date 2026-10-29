package dev.fitnessapp.algo.selection.time

import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** TIME-1 (ALGORITHMS_PLANNING 17.8) and its dose estimator against the worked numbers of 17.6 and 21.8. */
class TimeModelTest {
    private val params = TestParams.defaults()

    @Test
    fun `budget is the event minus the changeover, else the default`() {
        TimeModel.budget(null, 90.0, 5.0, 75.0) shouldBe Budget(85.0, BudgetSource.CALENDAR)
        TimeModel.budget(null, null, 5.0, 75.0) shouldBe Budget(75.0, BudgetSource.DEFAULT)
        TimeModel.budget(45.0, 90.0, 5.0, 75.0) shouldBe Budget(45.0, BudgetSource.CHIP)
    }

    @Test
    fun `add-on capacity of C49 and the 8-minute minimum`() {
        TimeModel.addOnCapacity(90.0, 50.7, 5.0, params) shouldBe AddOnCapacity(34, true) // A1 deload
        TimeModel.addOnCapacity(90.0, 60.8, 5.0, params) shouldBe AddOnCapacity(24, true) // C1 normal
        TimeModel.addOnCapacity(90.0, 79.7, 5.0, params) shouldBe AddOnCapacity(5, false) // A1 normal: no add-on
    }

    @Test
    fun `work per set for dynamic, eccentric, hold, attempts and a unilateral pair`() {
        fun item(reps: Double? = null, ecc: Double? = null, hold: Double? = null, attempts: Int? = null, pair: Boolean = false) =
            TimeItem("x", 3, 90.0, 30.0, reps, ecc, hold, attempts, pair)
        TimeModel.work(item(reps = 10.0), params) shouldBe 35.0
        TimeModel.work(item(reps = 1.0, ecc = 5.0), params) shouldBe 12.0
        TimeModel.work(item(hold = 20.0), params) shouldBe 35.0
        TimeModel.work(item(hold = 10.0, attempts = 4), params) shouldBe 100.0
        TimeModel.work(item(reps = 10.0, pair = true), params) shouldBe 80.0
    }

    @Test
    fun `exercise time is setup plus sets of work and rest, one rest fewer, plus the transition`() {
        // 45 + 3 * (35 + 90) - 90 + 60 = 390 s
        TimeModel.exerciseSeconds(TimeItem("x", 3, 90.0, 45.0, reps = 10.0), params) shouldBe 390.0
        TimeModel.exerciseSeconds(TimeItem("x", 0, 90.0, 45.0, reps = 10.0), params) shouldBe 0.0
    }

    @Test
    fun `warm-up per block kind`() {
        TimeModel.warmupSeconds(WarmupKind.GENERAL, params) shouldBe 778.0
        TimeModel.warmupSeconds(WarmupKind.ADDON, params) shouldBe 178.0
        TimeModel.warmupSeconds(WarmupKind.CHALLENGE, params) shouldBe 0.0
    }

    @Test
    fun `dose estimator reproduces the 21_8 minutes`() {
        val friNormal = mapOf("chest" to 8.5, "side_delts" to 3.5, "rear_delts" to 5.0, "lower_back" to 1.0, "abductors" to 1.0, "quads" to 2.5, "hamstrings" to 3.0, "calves" to 1.0)
        val friDirect = mapOf("chest" to 4.5, "side_delts" to 2.0, "rear_delts" to 2.5, "lower_back" to 1.0, "abductors" to 1.0, "quads" to 1.5, "hamstrings" to 1.5, "calves" to 1.0)
        TimeModel.estDoseMinutes(friNormal, true, params) shouldBe 74.8
        TimeModel.estDoseMinutes(friDirect, true, params) shouldBe 49.6
        TimeModel.estDoseMinutes(mapOf("quads" to 4.0, "hamstrings" to 5.5, "abductors" to 1.0, "calves" to 1.0), false, params) shouldBe 32.5
        TimeModel.estDoseMinutes(mapOf("quads" to 2.0, "hamstrings" to 2.0), false, params) shouldBe 13.8
    }

    @Test
    fun `trim order is accessory, then maintain, then the improve compound`() {
        fun t(key: String, sets: Int, tier: Int) = TrimItem(TimeItem(key, sets, 90.0, 30.0, reps = 10.0), tier, 1.0, 0.0)
        val items = listOf(t("press", 3, 1), t("calf", 2, 2), t("raise", 3, 3), t("prehab", 1, 0))
        val full = TimeModel.blockSeconds(items.map { it.time }, WarmupKind.ADDON, params) / 60.0
        val r = TimeModel.trim(items, WarmupKind.ADDON, full - 5.0, 1.0, params)
        r.removed shouldContainExactly listOf("raise")
        r.items.map { it.time.key to it.time.sets } shouldContainExactly listOf("press" to 3, "calf" to 2, "prehab" to 1)
        val tight = TimeModel.trim(items, WarmupKind.ADDON, 10.0, 1.0, params)
        tight.removed shouldContainExactly listOf("raise", "calf")
        tight.items.first { it.time.key == "press" }.time.sets shouldBe 2
        tight.coreDoesNotFit shouldBe false
    }

    @Test
    fun `core that does not fit offers the short version`() {
        val core = listOf(TrimItem(TimeItem("oap", 6, 180.0, 20.0, reps = 3.0), 0, 1.0, 0.0), TrimItem(TimeItem("curl", 2, 90.0, 20.0, reps = 10.0), 3, 1.0, 0.0))
        val r = TimeModel.trim(core, WarmupKind.CHALLENGE, 5.0, 1.0, params)
        r.removed shouldContainExactly listOf("curl")
        r.coreDoesNotFit shouldBe true
        r.shortVersion!!.single().time.sets shouldBe 3
    }

    @Test
    fun `EWMA calibration after three exposures`() {
        TimeModel.calibrate(30.0, listOf(60.0, 60.0), params) shouldBe 30.0
        TimeModel.calibrate(30.0, listOf(60.0, 60.0, 60.0), params) shouldBe 49.7
    }
}
