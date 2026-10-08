package dev.fitnessapp.algo.core.det

import java.security.MessageDigest

/** Lower-case hex SHA-256 (C24 hashes: inputs_hash, params_hash, outputs hashes of golden fixtures). */
object Sha256Hex {
    private val HEX = "0123456789abcdef".toCharArray()

    fun of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        digest.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            out[2 * i] = HEX[v ushr 4]
            out[2 * i + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun ofUtf8(text: String): String = of(text.toByteArray(Charsets.UTF_8))
}
