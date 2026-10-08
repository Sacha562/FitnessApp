package dev.fitnessapp.data.sync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** ANDROID.md 9.5 / DATA_MODEL 11.8 classification table. */
class SyncErrorClassifierTest {
    private fun c(status: Int?, code: String? = null, network: Boolean = false) =
        ErrorClassifier.classify(SyncFailure(status, code, network))

    @Test
    fun table() {
        c(null, network = true) shouldBe SyncErrorClass.TRANSIENT
        c(503) shouldBe SyncErrorClass.TRANSIENT
        c(408) shouldBe SyncErrorClass.TRANSIENT
        c(429) shouldBe SyncErrorClass.TRANSIENT
        c(500, "57014") shouldBe SyncErrorClass.TRANSIENT
        c(401, "PGRST301") shouldBe SyncErrorClass.AUTH
        c(401) shouldBe SyncErrorClass.AUTH
        c(406, "PGRST106") shouldBe SyncErrorClass.SCHEMA_NOT_EXPOSED
        c(400, "PGRST204") shouldBe SyncErrorClass.SCHEMA_DRIFT
        c(400, "42703") shouldBe SyncErrorClass.SCHEMA_DRIFT
        c(404, "PGRST205") shouldBe SyncErrorClass.SCHEMA_DRIFT
        c(403, "42501") shouldBe SyncErrorClass.ACCESS
        c(409, "23503") shouldBe SyncErrorClass.FK_ORDER
        c(409, "23505") shouldBe SyncErrorClass.ROW_PERMANENT
        c(400, "23514") shouldBe SyncErrorClass.ROW_PERMANENT
        c(400, "23502") shouldBe SyncErrorClass.ROW_PERMANENT
        c(400, "22P02") shouldBe SyncErrorClass.ROW_PERMANENT
        c(400, "22003") shouldBe SyncErrorClass.ROW_PERMANENT
        c(500, "25006") shouldBe SyncErrorClass.SERVER_READ_ONLY
        c(413) shouldBe SyncErrorClass.PAYLOAD_TOO_LARGE
    }
}
