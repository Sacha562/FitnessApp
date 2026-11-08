package dev.fitnessapp.app.platform

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** D108: the session clock guard. */
class GuardedClockTest {
    private class MemStore(var a: GuardedClock.Anchor? = null) : GuardedClock.AnchorStore {
        override fun load() = a
        override fun save(a: GuardedClock.Anchor) { this.a = a }
    }

    private val day = 86_400_000L
    private val t0 = 1_794_170_400_000L // 2026-11-08 20:40 UTC

    private class Device(var wall: Long, var elapsed: Long, var boot: Int = 3)

    private fun clock(d: Device, store: MemStore = MemStore()) =
        GuardedClock({ d.wall }, { d.elapsed }, { d.boot }, store, toleranceMs = 120_000L)

    @Test
    fun `follows the wall clock without a session`() {
        val d = Device(t0, 5_000_000L)
        val c = clock(d)
        c.nowMs() shouldBe t0
        d.wall -= day; d.elapsed += 60_000
        c.nowMs() shouldBe t0 - day
        c.offsetMs() shouldBe 0L
    }

    @Test
    fun `a manual date change during a session keeps the anchored time, reports once, converts back`() {
        val d = Device(t0, 5_000_000L)
        val c = clock(d)
        val jumps = mutableListOf<Pair<Long, Long>>()
        c.onJump = { w, t -> jumps += w to t }
        c.guarding = true
        d.wall += 120_000; d.elapsed += 120_000
        c.nowMs() shouldBe t0 + 120_000 // agreeing clocks: the wall clock
        d.wall = t0 + 130_000 - day; d.elapsed += 10_000 // the user sets yesterday's date
        c.nowMs() shouldBe t0 + 130_000
        c.offsetMs() shouldBe day
        c.toDeviceMs(t0 + 200_000) shouldBe t0 + 200_000 - day
        d.wall += 5_000; d.elapsed += 5_000
        c.nowMs() shouldBe t0 + 135_000
        jumps.size shouldBe 1
        jumps.single().second - jumps.single().first shouldBe day
    }

    @Test
    fun `the jump ends with the session and the wall clock is anchored again`() {
        val d = Device(t0, 5_000_000L)
        val c = clock(d)
        c.guarding = true
        d.wall -= day; d.elapsed += 1_000
        c.nowMs() shouldBe t0 + 1_000
        c.guarding = false
        c.nowMs() shouldBe t0 - day
    }

    @Test
    fun `a jump while the process was dead is caught from the persisted anchor, not after a reboot`() {
        val store = MemStore()
        val d = Device(t0, 5_000_000L)
        clock(d, store).apply { guarding = true; nowMs() }
        d.wall = t0 + 600_000 - day; d.elapsed += 600_000
        clock(d, store).nowMs() shouldBe t0 + 600_000
        d.boot = 4; d.elapsed = 30_000
        clock(d, store).nowMs() shouldBe d.wall
    }

    @Test
    fun `small corrections within the tolerance follow the wall clock`() {
        val d = Device(t0, 5_000_000L)
        val c = clock(d)
        c.guarding = true
        d.wall += 60_000 + 90_000; d.elapsed += 60_000 // NTP moves the clock 90 s
        c.nowMs() shouldBe t0 + 150_000
        c.offsetMs() shouldBe 0L
    }
}
