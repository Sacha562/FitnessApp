package dev.fitnessapp.data.sync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** UX 7.1 sync status rows. */
class PendingStatusTest {
    private val cfg = SyncConfig()
    private val now = 100L * 3_600_000L

    @Test
    fun `online with the last run's own sync event queued shows nothing`() {
        PendingStatus.of(SyncState.Synced(now), 1, now - 2_000, now, cfg) shouldBe PendingStatus.None
    }

    @Test
    fun `empty outbox shows nothing, even offline`() {
        PendingStatus.of(SyncState.Offline, 0, null, now, cfg) shouldBe PendingStatus.None
    }

    @Test
    fun `offline with changes shows the offline line`() {
        PendingStatus.of(SyncState.Offline, 23, now - 60_000, now, cfg) shouldBe PendingStatus.Offline(23)
    }

    @Test
    fun `online but an entry waits 24 h or more shows the retrying line`() {
        PendingStatus.of(SyncState.Synced(now), 3, now - 26 * 3_600_000L, now, cfg) shouldBe PendingStatus.Long(26)
        PendingStatus.of(SyncState.Synced(now), 3, now - 23 * 3_600_000L, now, cfg) shouldBe PendingStatus.None
    }
}
