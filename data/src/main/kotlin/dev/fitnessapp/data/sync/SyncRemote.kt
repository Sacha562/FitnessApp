package dev.fitnessapp.data.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Thrown by [SyncRemote] implementations; carries what [ErrorClassifier] needs, never row contents. */
class SyncRemoteException(val failure: SyncFailure, cause: Throwable? = null) : Exception(failure.code ?: failure.message, cause)

/** The PostgREST calls of the sync protocol (DATA_MODEL 11.3-11.5), behind an interface for JVM tests. */
interface SyncRemote {
    /** The signed-in user id, or null. */
    fun userId(): String?

    /**
     * Suspends until the stored session has been loaded (bounded). A worker that cold-starts the process runs before
     * the auth plugin has read its session from storage; without this wait it would see "signed out" and stop.
     */
    suspend fun awaitAuthLoaded() {}

    /**
     * True while the session exists but its refresh is failing (offline with an expired token), or is still loading
     * after [awaitAuthLoaded] gave up: retry, don't stop.
     */
    fun authRefreshPending(): Boolean = false

    /** Mutable tables: merge-duplicates upsert; returns the representation (`id, client_updated_at, device_id, server_updated_at`). */
    suspend fun upsertMerge(table: String, rows: List<JsonObject>): List<JsonObject>

    /** Append-only tables: ignore-duplicates insert. */
    suspend fun insertIgnore(table: String, rows: List<JsonObject>)

    suspend fun selectByIds(table: String, ids: List<String>): List<JsonObject>

    /**
     * One page of rows of the user, not written by [excludeDeviceId], ordered by `(server_updated_at, id)`:
     * either `server_updated_at >= since` (first page) or strictly after the keyset `(afterTs, afterId)`.
     */
    suspend fun pullPage(
        table: String,
        userId: String,
        excludeDeviceId: String,
        since: String?,
        afterTs: String?,
        afterId: String?,
        limit: Int,
    ): List<JsonObject>

    /**
     * Global reference rows readable by every allowed user (`challenge_definitions`): `server_updated_at >= since`,
     * ordered by `(server_updated_at, id)`, at most [limit] rows (a definition table stays small).
     */
    suspend fun pullGlobal(table: String, since: String?, limit: Int): List<JsonObject> = emptyList()
}

class PostgrestSyncRemote(private val client: SupabaseClient) : SyncRemote {
    override fun userId(): String? = client.auth.currentUserOrNull()?.id

    override suspend fun awaitAuthLoaded() {
        withTimeoutOrNull(AUTH_LOAD_TIMEOUT_MS) { client.auth.sessionStatus.first { it !is SessionStatus.Initializing } }
    }

    override fun authRefreshPending(): Boolean = client.auth.sessionStatus.value.let {
        it is SessionStatus.RefreshFailure || it is SessionStatus.Initializing
    }

    override suspend fun upsertMerge(table: String, rows: List<JsonObject>): List<JsonObject> = call {
        client.from(table).upsert(JsonArray(rows)) {
            onConflict = "id"
            ignoreDuplicates = false
            select(Columns.list("id", "client_updated_at", "device_id", "server_updated_at"))
        }.decodeAs<JsonArray>().map { it.jsonObject }
    }

    override suspend fun insertIgnore(table: String, rows: List<JsonObject>) {
        call {
            client.from(table).upsert(JsonArray(rows)) {
                onConflict = "id"
                ignoreDuplicates = true
            }
        }
    }

    override suspend fun selectByIds(table: String, ids: List<String>): List<JsonObject> = call {
        client.from(table).select { filter { isIn("id", ids) } }.decodeAs<JsonArray>().map { it.jsonObject }
    }

    override suspend fun pullPage(
        table: String,
        userId: String,
        excludeDeviceId: String,
        since: String?,
        afterTs: String?,
        afterId: String?,
        limit: Int,
    ): List<JsonObject> = call {
        client.from(table).select {
            filter {
                eq("user_id", userId)
                neq("device_id", excludeDeviceId)
                if (afterTs != null && afterId != null) {
                    or {
                        gt("server_updated_at", afterTs)
                        and {
                            eq("server_updated_at", afterTs)
                            gt("id", afterId)
                        }
                    }
                } else if (since != null) {
                    gte("server_updated_at", since)
                }
            }
            order("server_updated_at", Order.ASCENDING)
            order("id", Order.ASCENDING)
            limit(limit.toLong())
        }.decodeAs<JsonArray>().map { it.jsonObject }
    }

    override suspend fun pullGlobal(table: String, since: String?, limit: Int): List<JsonObject> = call {
        client.from(table).select {
            filter { if (since != null) gte("server_updated_at", since) }
            order("server_updated_at", Order.ASCENDING)
            order("id", Order.ASCENDING)
            limit(limit.toLong())
        }.decodeAs<JsonArray>().map { it.jsonObject }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: PostgrestRestException) {
        throw SyncRemoteException(SyncFailure(e.statusCode, e.code, message = e.error), e)
    } catch (e: RestException) {
        throw SyncRemoteException(SyncFailure(e.statusCode, null, message = e.error), e)
    } catch (e: HttpRequestException) {
        throw SyncRemoteException(SyncFailure(null, null, network = true, message = e::class.simpleName), e)
    } catch (e: java.io.IOException) {
        throw SyncRemoteException(SyncFailure(null, null, network = true, message = e::class.simpleName), e)
    }

    private companion object {
        /** Loading the session from storage took ~0.9 s on the phone; past this the run retries with backoff. */
        const val AUTH_LOAD_TIMEOUT_MS = 10_000L
    }
}
