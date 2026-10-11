package dev.fitnessapp.data.device

import android.content.Context
import java.time.Instant
import java.util.UUID

/**
 * Per-install identity used by sync (DATA_MODEL 6.2): a random device id generated at install (the LWW
 * tie-break) and strictly increasing client timestamps per device: max(now_ms, last_issued_ms + 1).
 */
class DeviceIdentity(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("device_identity", Context.MODE_PRIVATE)

    @Synchronized
    fun deviceId(): String =
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).commit()
        }

    @Synchronized
    fun nextClientTimestamp(now: Instant): Instant {
        val last = prefs.getLong(KEY_LAST_MS, 0L)
        val issued = maxOf(now.toEpochMilli(), last + 1)
        prefs.edit().putLong(KEY_LAST_MS, issued).commit()
        return Instant.ofEpochMilli(issued)
    }

    /** The last issued `client_updated_at` (the local-only `device` row of the full export, DATA_MODEL 15.5). */
    @Synchronized
    fun lastIssuedMs(): Long = prefs.getLong(KEY_LAST_MS, 0L)

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_MS = "last_client_updated_ms"
    }
}
