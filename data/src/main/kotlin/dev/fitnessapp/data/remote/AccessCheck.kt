package dev.fitnessapp.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable

/**
 * The allowlist read probe (ANDROID.md 10.4, DATA_MODEL 5.4): one muscle_groups row means allowed,
 * `[]` means not allowlisted (RLS hides everything), PGRST106 means the schema is not exposed yet.
 */
class AccessCheck(private val client: SupabaseClient) {
    sealed interface Result {
        data object Allowed : Result
        data object NotAllowed : Result
        data object SchemaNotExposed : Result
        data class Failed(val errorClass: String) : Result
    }

    @Serializable
    private data class IdRow(val id: String)

    suspend fun run(): Result = try {
        val rows = client.from("muscle_groups").select(Columns.list("id")) { limit(1) }.decodeList<IdRow>()
        if (rows.isEmpty()) Result.NotAllowed else Result.Allowed
    } catch (e: RestException) {
        if (e.error == "PGRST106" || e.message?.contains("PGRST106") == true) Result.SchemaNotExposed
        else Result.Failed(e::class.simpleName ?: "rest")
    } catch (e: Exception) {
        Result.Failed(e::class.simpleName ?: "unknown")
    }
}
