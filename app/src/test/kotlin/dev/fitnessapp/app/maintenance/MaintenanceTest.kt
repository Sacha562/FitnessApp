package dev.fitnessapp.app.maintenance

import dev.fitnessapp.app.ui.reliability.AlarmLatency
import dev.fitnessapp.app.ui.reliability.Level
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The N-SYNC rule (UX 18) and the Reliability latency statistics (ANDROID.md 15.5). */
class MaintenanceTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun syncText(dead: Int = 0, oldest: Long? = null) = Maintenance.syncText(now, dead, oldest, 7)

    @Test
    fun `sync notice for dead letters or a week-old outbox`() {
        syncText() shouldBe null
        syncText(dead = 3) shouldBe "3 changes could not be uploaded"
        syncText(dead = 1) shouldBe "1 change could not be uploaded"
        syncText(oldest = now - 6 * day) shouldBe null
        syncText(oldest = now - 8 * day) shouldBe "Changes have not been uploaded for 8 days"
    }

    @Test
    fun `latency percentiles interpolate like percentile_cont`() {
        AlarmLatency.percentile(listOf(1000L, 1200L), 0.5) shouldBe 1100L
        AlarmLatency.percentile(listOf(1000L, 1200L), 0.95) shouldBe 1190L
        AlarmLatency.percentile(listOf(7L), 0.95) shouldBe 7L
        val s = AlarmLatency.stats(listOf(4L, 10L, 7L, 9L), 0, 2000, 5000)
        s.n shouldBe 4
        s.p50 shouldBe 8L
        s.max shouldBe 10L
        s.level shouldBe Level.GREEN
    }

    @Test
    fun `latency levels follow the amber and red thresholds and dead recoveries`() {
        AlarmLatency.stats(List(20) { 100L } + List(2) { 3000L }, 0, 2000, 5000).level shouldBe Level.AMBER
        AlarmLatency.stats(List(10) { 6000L }, 0, 2000, 5000).level shouldBe Level.RED
        AlarmLatency.stats(listOf(5L), 1, 2000, 5000).level shouldBe Level.RED
        AlarmLatency.stats(emptyList(), 0, 2000, 5000).level shouldBe Level.GREEN
        AlarmLatency.latencyOf("""{"latency_ms":7,"timer_id":"x"}""") shouldBe 7L
    }
}
