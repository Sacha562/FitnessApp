package dev.fitnessapp.app.maintenance

import dev.fitnessapp.app.ui.reliability.AlarmLatency
import dev.fitnessapp.app.ui.reliability.Level
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** N-EXPORT / N-SYNC rules (F4, UX 18) and the Reliability latency statistics (ANDROID.md 15.5). */
class MaintenanceTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun decide(lastExport: Long? = null, first: Long? = null, enabled: Boolean = true, dead: Int = 0, oldest: Long? = null) =
        Maintenance.decide(now, enabled, lastExport, first, 30, dead, oldest, 7)

    @Test
    fun `export reminder after 30 days, counted from the first session without any export`() {
        decide(lastExport = now - 29 * day).export shouldBe false
        decide(lastExport = now - 30 * day).export shouldBe true
        decide(first = now - 31 * day).export shouldBe true
        decide(first = now - 3 * day).export shouldBe false
        decide().export shouldBe false
        decide(lastExport = now - 40 * day, enabled = false).export shouldBe false
    }

    @Test
    fun `sync notice for dead letters or a week-old outbox`() {
        decide().syncText shouldBe null
        decide(dead = 3).syncText shouldBe "3 changes could not be uploaded"
        decide(dead = 1).syncText shouldBe "1 change could not be uploaded"
        decide(oldest = now - 6 * day).syncText shouldBe null
        decide(oldest = now - 8 * day).syncText shouldBe "Changes have not been uploaded for 8 days"
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
