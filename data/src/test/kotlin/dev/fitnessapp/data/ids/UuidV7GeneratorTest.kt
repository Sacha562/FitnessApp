package dev.fitnessapp.data.ids

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class UuidV7GeneratorTest {
    @Test
    fun `version, variant, timestamp and monotonic order within one millisecond`() {
        val gen = UuidV7Generator({ 1791482280123L })
        val ids = (1..500).map { gen.next() }
        ids.forEach { s ->
            val u = UUID.fromString(s)
            u.version() shouldBe 7
            u.variant() shouldBe 2
        }
        (UUID.fromString(ids[0]).mostSignificantBits ushr 16) shouldBe 1791482280123L
        ids shouldBe ids.sorted()
        ids.toSet().size shouldBe ids.size
    }

    @Test
    fun `ids sort by creation time`() {
        var t = 1000L
        val gen = UuidV7Generator({ t })
        val a = gen.next()
        t = 1001L
        val b = gen.next()
        (a < b) shouldBe true
    }
}
