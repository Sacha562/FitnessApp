package dev.fitnessapp.app.platform

import android.content.SharedPreferences
import dev.fitnessapp.algo.core.clock.Clock
import java.time.Instant
import kotlin.math.abs

/**
 * D108: the app's clock during a live session. While [guarding] is on, the clock keeps a trusted anchor (wall time,
 * elapsed-realtime, boot count) that follows the device wall clock as long as the two agree within [toleranceMs].
 * When the wall clock jumps further than that on the same boot (a manual date change, a bad network time), every
 * read returns the anchored time (`anchor.wall + elapsed since the anchor`) instead, and [onJump] is told once per
 * jump. Off a session, or after a reboot, the device wall clock is used and becomes the new anchor.
 *
 * [offsetMs] (`trusted - device wall`) converts app times back to the device clock where Android needs it: alarms
 * and notification chronometers.
 */
class GuardedClock(
    private val wallMs: () -> Long,
    private val elapsedMs: () -> Long,
    private val bootCount: () -> Int,
    private val store: AnchorStore,
    private val toleranceMs: Long = DEFAULT_TOLERANCE_MS,
) : Clock {
    /** The persisted anchor (process death must not lose a jump that happens while the app is dead). */
    interface AnchorStore {
        fun load(): Anchor?
        fun save(a: Anchor)
    }

    data class Anchor(val wallMs: Long, val elapsedMs: Long, val boot: Int, val guarding: Boolean)

    @Volatile var onJump: ((wallMs: Long, trustedMs: Long) -> Unit)? = null

    private val lock = Any()
    private var anchor: Anchor? = store.load()
    private var savedElapsed: Long = Long.MIN_VALUE
    private var reportedOffset: Long? = null

    /** On while a session is in progress; persisted with the anchor so the guard holds from the first read after a restart. */
    var guarding: Boolean
        get() = anchor?.guarding ?: false
        set(value) {
            synchronized(lock) {
                val a = current()
                anchor = a.copy(guarding = value).also { store.save(it); savedElapsed = it.elapsedMs }
                if (!value) reportedOffset = null
            }
        }

    override fun now(): Instant = Instant.ofEpochMilli(nowMs())

    fun nowMs(): Long = synchronized(lock) { current().trusted() }

    /** `trusted - device wall` in ms: 0 unless a jump is being corrected. */
    fun offsetMs(): Long = synchronized(lock) { current().trusted() - wallMs() }

    /** The device wall time of the app time [appMs] (alarms, chronometers). */
    fun toDeviceMs(appMs: Long): Long = appMs - offsetMs()

    private fun Anchor.trusted(): Long = wallMs + (elapsedMs() - elapsedMs).coerceAtLeast(0)

    /** The anchor after this read: re-anchored to the wall clock unless a guarded jump is in force. */
    private fun current(): Anchor {
        val w = wallMs()
        val e = elapsedMs()
        val b = bootCount()
        val a = anchor
        if (a == null || a.boot != b || e < a.elapsedMs || !a.guarding) return reanchor(w, e, b, a?.guarding ?: false)
        val trusted = a.wallMs + (e - a.elapsedMs)
        val offset = trusted - w
        if (abs(offset) <= toleranceMs) return reanchor(w, e, b, true)
        if (reportedOffset == null || abs(reportedOffset!! - offset) > toleranceMs) {
            reportedOffset = offset
            onJump?.let { cb -> runCatching { cb(w, trusted) } }
        }
        return a
    }

    private fun reanchor(w: Long, e: Long, b: Int, guarding: Boolean): Anchor {
        val n = Anchor(w, e, b, guarding)
        anchor = n
        reportedOffset = null
        if (e - savedElapsed >= SAVE_EVERY_MS || savedElapsed == Long.MIN_VALUE || e < savedElapsed) {
            store.save(n)
            savedElapsed = e
        }
        return n
    }

    /** [AnchorStore] in SharedPreferences. */
    class PrefsStore(private val prefs: SharedPreferences) : AnchorStore {
        override fun load(): Anchor? {
            if (!prefs.contains(K_WALL)) return null
            return Anchor(prefs.getLong(K_WALL, 0), prefs.getLong(K_ELAPSED, 0), prefs.getInt(K_BOOT, 0), prefs.getBoolean(K_GUARD, false))
        }

        override fun save(a: Anchor) {
            prefs.edit().putLong(K_WALL, a.wallMs).putLong(K_ELAPSED, a.elapsedMs).putInt(K_BOOT, a.boot).putBoolean(K_GUARD, a.guarding).apply()
        }

        private companion object {
            const val K_WALL = "anchor_wall_ms"
            const val K_ELAPSED = "anchor_elapsed_ms"
            const val K_BOOT = "anchor_boot"
            const val K_GUARD = "guarding"
        }
    }

    companion object {
        /** `clock.jump_tolerance_s` (D108): 120 s. */
        const val DEFAULT_TOLERANCE_MS = 120_000L
        private const val SAVE_EVERY_MS = 30_000L
    }
}

/**
 * The app's current time in ms for code without the container at hand (composables, receivers): [GuardedClock.nowMs]
 * once the container is built (D108), the wall clock before that.
 */
object AppClock {
    @Volatile var nowMs: () -> Long = { System.currentTimeMillis() }
}
