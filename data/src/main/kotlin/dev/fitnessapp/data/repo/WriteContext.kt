package dev.fitnessapp.data.repo

import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import java.time.ZoneId

/**
 * What every local write needs (DATA_MODEL 11.2): the signed-in user, this device, the app version, the
 * injected clock and the profile zone, and the id generator.
 */
class WriteContext(
    val device: DeviceIdentity,
    val appVersion: String,
    val clock: Clock,
    val zone: () -> ZoneId,
    val ids: UuidV7Generator,
    val userId: () -> String?,
) {
    fun nowMs(): Long = clock.now().toEpochMilli()

    /** `client_updated_at`: strictly increasing per device. */
    fun nextClientMs(): Long = device.nextClientTimestamp(clock.now()).toEpochMilli()

    fun requireUser(): String = requireNotNull(userId()) { "not signed in" }
}
