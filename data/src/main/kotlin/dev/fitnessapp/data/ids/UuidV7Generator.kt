package dev.fitnessapp.data.ids

import java.security.SecureRandom

/**
 * Client-generated UUIDv7 ids (ANDROID.md 8.2): 48-bit Unix ms timestamp, version 7, 74 random bits, and a
 * monotonic counter within the same millisecond so ids sort by creation time on this device.
 */
class UuidV7Generator(private val nowMs: () -> Long, private val random: SecureRandom = SecureRandom()) {
    private var lastMs = -1L
    private var counter = 0L

    @Synchronized
    fun next(): String {
        var ms = nowMs()
        if (ms <= lastMs) {
            ms = lastMs
            counter++
            if (counter > 0xFFF) { // 12-bit rand_a used as counter; move to the next ms when it overflows
                ms = lastMs + 1
                counter = 0
            }
        } else {
            counter = random.nextInt(0x800).toLong() // random start in the lower half leaves room to count up
        }
        lastMs = ms
        val msb = (ms shl 16) or (0x7L shl 12) or (counter and 0xFFF)
        val lsb = (random.nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE // variant 10
        return java.util.UUID(msb, lsb).toString()
    }
}
