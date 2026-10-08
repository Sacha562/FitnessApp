package dev.fitnessapp.algo.common

import java.security.MessageDigest
import java.util.UUID

/**
 * Deterministic identifiers (DATA_MODEL 6.4). Global seed rows and natural-key rows use UUIDv5 in the
 * namespace FITNESS_NS, so the server seed, the bundled APK catalog and every device compute the same id.
 */
object Ids {
    /** `uuid_generate_v5(<RFC 4122 URL namespace>, 'https://fitness-app.invalid/ns/v1')`. */
    val FITNESS_NS: UUID = UUID.fromString("2611a46b-2ad8-5006-88dc-e43030211f8f")

    /** RFC 4122 version-5 (SHA-1) UUID of [name] (UTF-8) in [namespace]. */
    fun uuidV5(namespace: UUID, name: String): UUID {
        val md = MessageDigest.getInstance("SHA-1")
        val ns = ByteArray(16)
        for (i in 0 until 8) ns[i] = (namespace.mostSignificantBits ushr (56 - 8 * i)).toByte()
        for (i in 0 until 8) ns[8 + i] = (namespace.leastSignificantBits ushr (56 - 8 * i)).toByte()
        md.update(ns)
        md.update(name.toByteArray(Charsets.UTF_8))
        val h = md.digest()
        h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
        val msb = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (h[i].toLong() and 0xff) }
        val lsb = (8 until 16).fold(0L) { acc, i -> (acc shl 8) or (h[i].toLong() and 0xff) }
        return UUID(msb, lsb)
    }

    fun fitness(name: String): UUID = uuidV5(FITNESS_NS, name)

    /** Id of a global catalog row: `exercise:<slug>` (C38). */
    fun exercise(slug: String): UUID = fitness("exercise:$slug")

    /** Id of a global stimulus row: `exercise_stimulus:<slug>:<region>`. */
    fun exerciseStimulus(slug: String, regionId: String): UUID = fitness("exercise_stimulus:$slug:$regionId")
}
