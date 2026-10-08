package dev.fitnessapp.data.remote

import dev.fitnessapp.data.device.DeviceIdentity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** The S-MUT columns plus the profile fields the app writes in M0 (DATA_MODEL 6.2, 9.3.1). */
@Serializable
data class ProfileDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("timezone") val timezone: String = "Europe/Amsterdam",
)

/**
 * Lazily creates the owner's profile row on sign-in (ANDROID.md 10.2 step 3; never a trigger on
 * auth.users) and reads it back. In M0 this is the write round trip through RLS and the LWW trigger;
 * from M1 on the profile is written through the outbox like every other row.
 */
class ProfileSync(
    private val client: SupabaseClient,
    private val device: DeviceIdentity,
    private val appVersion: String,
) {
    data class RoundTrip(val written: ProfileDto, val readBack: ProfileDto) {
        val ok: Boolean
            get() = readBack.id == written.id && readBack.deviceId == written.deviceId &&
                Instant.parse(readBack.clientUpdatedAt) == Instant.parse(written.clientUpdatedAt) &&
                readBack.serverUpdatedAt != null
    }

    suspend fun upsertAndReadBack(now: Instant): RoundTrip {
        val uid = requireNotNull(client.auth.currentUserOrNull()?.id) { "not signed in" }
        val row = ProfileDto(
            id = uid,
            userId = uid,
            clientUpdatedAt = device.nextClientTimestamp(now).toString(),
            deviceId = device.deviceId(),
            appVersion = appVersion,
        )
        client.from("profiles").upsert(row)
        val back = client.from("profiles").select { filter { eq("id", uid) } }.decodeSingle<ProfileDto>()
        return RoundTrip(row, back)
    }
}
