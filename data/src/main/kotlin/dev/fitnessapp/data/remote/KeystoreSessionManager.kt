package dev.fitnessapp.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.sessionStore by preferencesDataStore(name = "session")

/**
 * Stores the Supabase session encrypted with AES-256-GCM under a non-exportable Android Keystore key
 * (ANDROID.md 10.3). If decryption fails the session is discarded and the user signs in again; local
 * data is untouched.
 */
class KeystoreSessionManager(private val context: Context) : SessionManager {
    override suspend fun saveSession(session: UserSession) {
        val plain = FitnessJson.encodeToString(UserSession.serializer(), session).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(plain)
        context.sessionStore.edit { it[SESSION] = Base64.encodeToString(blob, Base64.NO_WRAP) }
    }

    /** Throws [NoSessionFoundException] when nothing usable is stored (supabase-kt 3.x contract). */
    override suspend fun loadSession(): UserSession {
        val stored = context.sessionStore.data.first()[SESSION] ?: throw NoSessionFoundException()
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            }
            val plain = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
            FitnessJson.decodeFromString(UserSession.serializer(), String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            deleteSession()
            throw NoSessionFoundException()
        }
    }

    override suspend fun deleteSession() {
        context.sessionStore.edit { it.remove(SESSION) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fitness_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val SESSION = stringPreferencesKey("session_v1")
    }
}
