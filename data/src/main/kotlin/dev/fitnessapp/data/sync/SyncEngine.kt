package dev.fitnessapp.data.sync

import androidx.room.withTransaction
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.entity.ChallengeDefinitionEntity
import dev.fitnessapp.data.db.entity.SyncedRow
import dev.fitnessapp.data.db.local.DeadLetterEntity
import dev.fitnessapp.data.db.local.OutboxEntity
import dev.fitnessapp.data.db.local.SyncCursorEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap

/** ANDROID.md 9.7 sync status. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Synced(val atMs: Long) : SyncState
    data object Offline : SyncState
    data class Unreachable(val sinceMs: Long) : SyncState
    data object NeedsSignIn : SyncState
    data object NotAllowed : SyncState
    data object SchemaNotExposed : SyncState
    data class SchemaDrift(val table: String) : SyncState
    data object ServerReadOnly : SyncState
}

/** How a run ended, for the worker's `Result`. */
enum class RunOutcome { SUCCESS, RETRY, STOPPED }

/**
 * The sync protocol of DATA_MODEL 11 / ANDROID.md 9: push the outbox table by table in dependency order with
 * plain upserts, then pull every table by its `server_updated_at` cursor with the 120 s overlap and merge by
 * last-writer-wins. Failures are classified (9.5): transient ones stop the run for a retry, row errors are
 * isolated by bisection and dead-lettered, foreign-key ordering errors are deferred.
 */
class SyncEngine(
    private val db: FitnessDatabase,
    private val remote: SyncRemote,
    private val tables: List<SyncTable<out SyncedRow>>,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val deviceId: () -> String,
    private val config: SyncConfig,
    private val nowMs: () -> Long,
    private val platformConfigHash: String,
    /** Called after every run that pulled rows, with the counts per table (the challenge refold, 4.11). */
    private val afterPull: suspend (Map<String, Int>) -> Unit = {},
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    private var firstFailureAtMs: Long? = null

    private class Stop(val outcome: RunOutcome, val state: SyncState, val pushOnly: Boolean = false) : Exception()

    private class RunStats {
        val pushed = TreeMap<String, Int>()
        val pulled = TreeMap<String, Int>()
        val errors = TreeMap<String, Int>()
        fun error(cls: SyncErrorClass) { errors[cls.code] = (errors[cls.code] ?: 0) + 1 }
    }

    suspend fun run(trigger: String): RunOutcome {
        remote.awaitAuthLoaded()
        val uid = remote.userId()
        if (uid == null) {
            if (remote.authRefreshPending()) {
                _state.value = SyncState.Offline
                return RunOutcome.RETRY
            }
            _state.value = SyncState.NeedsSignIn
            return RunOutcome.STOPPED
        }
        _state.value = SyncState.Running
        val started = nowMs()
        val before = db.outbox().depth()
        val oldest = db.outbox().oldestEnqueuedAt()
        val stats = RunStats()
        var outcome = RunOutcome.SUCCESS
        var finalState: SyncState? = null
        var pushAllowed = true
        try {
            try {
                push(uid, stats)
            } catch (s: Stop) {
                if (!s.pushOnly) throw s
                pushAllowed = false
                finalState = s.state
            }
            pull(uid, stats)
            if (pushAllowed) drain(uid, stats)
        } catch (s: Stop) {
            outcome = s.outcome
            finalState = s.state
        }
        val now = nowMs()
        if (outcome == RunOutcome.RETRY) {
            val since = firstFailureAtMs ?: now.also { firstFailureAtMs = it }
            finalState = if (now - since >= config.unreachableBannerH * 3_600_000L) SyncState.Unreachable(since) else SyncState.Offline
        } else {
            firstFailureAtMs = null
        }
        _state.value = finalState ?: SyncState.Synced(now)
        if (stats.pulled.isNotEmpty()) {
            try {
                afterPull(stats.pulled)
            } catch (e: Exception) {
                logError("after_pull", null, 0, SyncErrorClass.ROW_PERMANENT, SyncFailure(null, "after_pull", message = e::class.simpleName))
            }
        }

        val after = db.outbox().depth()
        val pushedTotal = stats.pushed.values.sum()
        val pulledTotal = stats.pulled.values.sum()
        if (pushedTotal > 0 || pulledTotal > 0 || stats.errors.isNotEmpty() || !pushAllowed) {
            // Written without requesting another sync (ANDROID.md 9.3): it rides along with the next run.
            db.withTransaction {
                telemetry.write(
                    EventDraft(
                        type = "sync_run",
                        entityType = "none",
                        payload = mapOf(
                            "trigger" to JsonPrimitive(trigger),
                            "pushed" to JsonNumbers.int(pushedTotal.toLong()),
                            "pulled" to JsonNumbers.int(pulledTotal.toLong()),
                            "pushed_by_table" to counts(stats.pushed),
                            "pulled_by_table" to counts(stats.pulled),
                            "duration_ms" to JsonNumbers.int(now - started),
                            "outbox_before" to JsonNumbers.int(before.toLong()),
                            "outbox_after" to JsonNumbers.int(after.toLong()),
                            "oldest_outbox_age_s" to JsonNumbers.int(oldest?.let { (now - it) / 1000 } ?: 0L),
                            "errors" to counts(stats.errors),
                            "platform_config_hash" to JsonPrimitive(platformConfigHash),
                        ),
                    ),
                )
            }
        }
        return outcome
    }

    // ---------------------------------------------------------------------------------------------- push

    private suspend fun push(uid: String, stats: RunStats) {
        for (t in tables) {
            @Suppress("UNCHECKED_CAST")
            pushTable(t as SyncTable<SyncedRow>, uid, stats)
        }
    }

    /**
     * ANDROID.md 9.3: a run keeps draining the outbox while writes arrive, because their `request()` is dropped
     * (`KEEP`) while this run is active. After a full pass every entry still due was enqueued during the run (failed
     * rows are deferred or dead-lettered), so pushing again cannot spin on a failing row; the bound only guards against
     * a writer that never stops.
     */
    private suspend fun drain(uid: String, stats: RunStats) {
        var passes = 0
        while (passes < MAX_DRAIN_PASSES && db.outbox().readyCount(nowMs()) > 0) {
            push(uid, stats)
            passes++
        }
    }

    private suspend fun pushTable(t: SyncTable<SyncedRow>, uid: String, stats: RunStats) {
        var batchSize = config.pushBatchRows
        while (true) {
            val entries = db.outbox().batch(t.name, nowMs(), batchSize)
            if (entries.isEmpty()) return
            val rows = t.dao.byIds(entries.map { it.row_id }).associateBy { it.id }
            val pairs = ArrayList<Pair<OutboxEntity, SyncedRow>>()
            for (e in entries) {
                val row = rows[e.row_id]
                when {
                    row == null -> db.outbox().deleteRow(t.name, e.row_id)
                    row.user_id != uid -> deadLetter(t, e, row, null, "owner_mismatch", "row of another account", stats)
                    else -> pairs += e to row
                }
            }
            if (pairs.isEmpty()) continue
            val tooLarge = send(t, pairs, stats)
            if (tooLarge) batchSize = maxOf(1, batchSize / 2)
        }
    }

    /** Sends [pairs]; isolates failing rows by bisection. Returns true when the server said "too large". */
    private suspend fun send(t: SyncTable<SyncedRow>, pairs: List<Pair<OutboxEntity, SyncedRow>>, stats: RunStats): Boolean {
        try {
            val bodies = pairs.map { t.toPush(it.second) }
            if (t.append) {
                remote.insertIgnore(t.name, bodies)
            } else {
                val rep = remote.upsertMerge(t.name, bodies)
                afterMerge(t, pairs, rep, stats)
            }
            for ((e, _) in pairs) db.outbox().deleteIfUnchanged(e.seq, e.gen)
            stats.pushed[t.name] = (stats.pushed[t.name] ?: 0) + pairs.size
            return false
        } catch (ex: SyncRemoteException) {
            val cls = ErrorClassifier.classify(ex.failure)
            stats.error(cls)
            when (cls) {
                SyncErrorClass.TRANSIENT -> throw Stop(RunOutcome.RETRY, SyncState.Offline)
                SyncErrorClass.AUTH -> throw authStop(t.name, ex.failure)
                SyncErrorClass.SCHEMA_NOT_EXPOSED -> throw Stop(RunOutcome.STOPPED, SyncState.SchemaNotExposed)
                SyncErrorClass.SCHEMA_DRIFT -> {
                    logError(t.name, null, pairs.size, cls, ex.failure)
                    throw Stop(RunOutcome.STOPPED, SyncState.SchemaDrift(t.name)) // later tables depend on it
                }
                SyncErrorClass.SERVER_READ_ONLY -> throw Stop(RunOutcome.STOPPED, SyncState.ServerReadOnly, pushOnly = true)
                SyncErrorClass.ACCESS -> {
                    // ANDROID 9.5 / 10.4: the access check runs before anything is dead-lettered. An account that is
                    // not (yet) allowlisted keeps its outbox and stops; its rows upload once the owner adds it.
                    if (t.name == "profiles" || !accountAllowed()) throw Stop(RunOutcome.STOPPED, SyncState.NotAllowed)
                    for ((e, row) in pairs) deadLetter(t, e, row, ex.failure.httpStatus, ex.failure.code, ex.failure.message, stats)
                    return false
                }
                SyncErrorClass.PAYLOAD_TOO_LARGE -> {
                    if (pairs.size == 1) {
                        deadLetter(t, pairs[0].first, pairs[0].second, ex.failure.httpStatus, ex.failure.code, ex.failure.message, stats)
                        return false
                    }
                    return true
                }
                SyncErrorClass.FK_ORDER, SyncErrorClass.ROW_PERMANENT -> {
                    if (pairs.size > 1) {
                        val mid = pairs.size / 2
                        val a = send(t, pairs.subList(0, mid), stats)
                        val b = send(t, pairs.subList(mid, pairs.size), stats)
                        return a || b
                    }
                    val (e, row) = pairs[0]
                    if (cls == SyncErrorClass.FK_ORDER && e.attempts + 1 < config.fkRetryAttempts) {
                        val next = nowMs() + (1L shl (e.attempts + 1)) * 60_000L
                        db.outbox().deferRow(e.seq, next, ex.failure.code ?: cls.code)
                        logError(t.name, row.id, 1, cls, ex.failure, e.attempts + 1)
                    } else {
                        deadLetter(t, e, row, ex.failure.httpStatus, ex.failure.code, ex.failure.message, stats)
                    }
                    return false
                }
            }
        }
    }

    /** DATA_MODEL 11.4: ids missing from the representation were dropped by the server trigger (older tuple). */
    private suspend fun afterMerge(t: SyncTable<SyncedRow>, pairs: List<Pair<OutboxEntity, SyncedRow>>, rep: List<JsonObject>, stats: RunStats) {
        val returned = rep.associateBy { it["id"]?.jsonPrimitive?.contentOrNull }
        for ((_, row) in pairs) {
            val r = returned[row.id] ?: continue
            val sua = r["server_updated_at"]?.jsonPrimitive?.contentOrNull ?: continue
            setServerUpdatedAt(t.name, row.id, (row as? dev.fitnessapp.data.db.entity.MutableRow)?.client_updated_at, sua)
        }
        val stale = pairs.map { it.second.id }.filter { it !in returned.keys }
        if (stale.isEmpty()) return
        val server = remote.selectByIds(t.name, stale)
        db.withTransaction {
            for (obj in server) {
                val local = pairs.firstOrNull { it.second.id == obj["id"]?.jsonPrimitive?.contentOrNull }?.second
                val remoteRow = t.fromPulled(obj)
                val rt = t.tuple(remoteRow)
                val lt = local?.let { t.tuple(it) }
                if (rt != null && lt != null && rt > lt) {
                    t.upsertAny(remoteRow)
                    conflict(t.name, remoteRow.id, lt, rt, "server")
                }
                // An equal tuple is a re-push of a stored version: nothing to do.
            }
        }
        stats.pulled[t.name] = (stats.pulled[t.name] ?: 0) + server.size
    }

    // ---------------------------------------------------------------------------------------------- pull

    private suspend fun pull(uid: String, stats: RunStats) {
        val device = deviceId()
        pullDefinitions(stats)
        for (t in tables) {
            if (!t.pull) continue
            @Suppress("UNCHECKED_CAST")
            pullTable(t as SyncTable<SyncedRow>, uid, device, stats)
        }
    }

    /**
     * DEF-PULL (DATA_MODEL 11.6): published definitions are global rows (`user_id` null), so they are pulled without
     * the user and device filters, by their own cursor. A row arrives only once per version (immutable, 7.6); a newer
     * version is activated only at the next session start after its hash checks (11.10).
     */
    private suspend fun pullDefinitions(stats: RunStats) {
        val table = "challenge_definitions"
        val cursor = db.cursors().get(table)
        val since = cursor?.server_updated_at?.let { minusSeconds(it, config.overlapS.toLong()) }
        val page = try {
            remote.pullGlobal(table, since, config.pullPageRows)
        } catch (ex: SyncRemoteException) {
            val cls = ErrorClassifier.classify(ex.failure)
            stats.error(cls)
            when (cls) {
                SyncErrorClass.AUTH -> throw authStop(table, ex.failure)
                SyncErrorClass.SCHEMA_NOT_EXPOSED -> throw Stop(RunOutcome.STOPPED, SyncState.SchemaNotExposed)
                SyncErrorClass.TRANSIENT -> throw Stop(RunOutcome.RETRY, SyncState.Offline)
                else -> {
                    logError(table, null, 0, cls, ex.failure)
                    return
                }
            }
        }
        if (page.isEmpty()) return
        var maxTs = cursor?.server_updated_at
        db.withTransaction {
            for (obj in page) {
                val row = try {
                    SyncJson.decodeFromJsonElement(ChallengeDefinitionEntity.serializer(), obj).copy(source = "server")
                } catch (e: Exception) {
                    logError(table, obj["id"]?.jsonPrimitive?.contentOrNull, 1, SyncErrorClass.SCHEMA_DRIFT, SyncFailure(null, "decode", message = e::class.simpleName))
                    continue
                }
                db.challengeDefinitions().upsert(row)
                val ts = row.server_updated_at
                if (ts != null && (maxTs == null || compareTs(ts, maxTs!!) > 0)) maxTs = ts
            }
        }
        stats.pulled[table] = (stats.pulled[table] ?: 0) + page.size
        if (maxTs != null && maxTs != cursor?.server_updated_at) db.cursors().put(SyncCursorEntity(table, maxTs!!, null, nowMs()))
    }

    private suspend fun pullTable(t: SyncTable<SyncedRow>, uid: String, device: String, stats: RunStats) {
        val cursor = db.cursors().get(t.name)
        val since = cursor?.server_updated_at?.let { minusSeconds(it, config.overlapS.toLong()) }
        var maxTs = cursor?.server_updated_at
        var maxId = cursor?.last_id
        var afterTs: String? = null
        var afterId: String? = null
        while (true) {
            val page = try {
                remote.pullPage(t.name, uid, device, since, afterTs, afterId, config.pullPageRows)
            } catch (ex: SyncRemoteException) {
                val cls = ErrorClassifier.classify(ex.failure)
                stats.error(cls)
                when (cls) {
                    SyncErrorClass.AUTH -> throw authStop(t.name, ex.failure)
                    SyncErrorClass.SCHEMA_NOT_EXPOSED -> throw Stop(RunOutcome.STOPPED, SyncState.SchemaNotExposed)
                    SyncErrorClass.TRANSIENT -> throw Stop(RunOutcome.RETRY, SyncState.Offline)
                    // Schema drift, access and the rest: log it, skip this table, pull the others.
                    else -> {
                        logError(t.name, null, 0, cls, ex.failure)
                        return
                    }
                }
            }
            if (page.isEmpty()) break
            db.withTransaction {
                for (obj in page) applyPulled(t, obj)
            }
            stats.pulled[t.name] = (stats.pulled[t.name] ?: 0) + page.size
            val last = page.last()
            val lastTs = last["server_updated_at"]?.jsonPrimitive?.contentOrNull
            val lastId = last["id"]?.jsonPrimitive?.contentOrNull
            if (lastTs != null && (maxTs == null || compareTs(lastTs, maxTs) > 0)) {
                maxTs = lastTs
                maxId = lastId
            }
            if (page.size < config.pullPageRows) break
            afterTs = lastTs
            afterId = lastId
        }
        if (maxTs != null && maxTs != cursor?.server_updated_at) {
            db.cursors().put(SyncCursorEntity(t.name, maxTs, maxId, nowMs()))
        }
    }

    private suspend fun applyPulled(t: SyncTable<SyncedRow>, obj: JsonObject) {
        val remoteRow = try {
            t.fromPulled(obj)
        } catch (e: Exception) {
            logError(t.name, obj["id"]?.jsonPrimitive?.contentOrNull, 1, SyncErrorClass.SCHEMA_DRIFT, SyncFailure(null, "decode", message = e::class.simpleName))
            return
        }
        val local = t.dao.byIds(listOf(remoteRow.id)).firstOrNull()
        if (t.append) {
            if (local == null) t.upsertAny(remoteRow)
            return
        }
        val rt = t.tuple(remoteRow)!!
        val lt = local?.let { t.tuple(it) }
        val pending = db.outbox().pendingCount(t.name, remoteRow.id) > 0
        when (LwwMerge.decide(rt, lt)) {
            MergeAction.INSERT -> t.upsertAny(remoteRow)
            MergeAction.OVERWRITE -> {
                t.upsertAny(remoteRow)
                if (pending) {
                    db.outbox().deleteRow(t.name, remoteRow.id)
                    conflict(t.name, remoteRow.id, lt!!, rt, "server")
                }
            }
            MergeAction.MARK_SYNCED -> {
                remoteRow.server_updated_at?.let { setServerUpdatedAt(t.name, remoteRow.id, rt.clientUpdatedAtMs, it) }
                if (pending) db.outbox().deleteRow(t.name, remoteRow.id)
            }
            MergeAction.KEEP_LOCAL -> if (!pending) outbox.enqueue(t.name, remoteRow.id, nowMs())
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** Records the server timestamp without touching a row that changed meanwhile (guarded by its tuple). */
    private fun setServerUpdatedAt(table: String, id: String, clientMs: Long?, sua: String) {
        val sql = if (clientMs != null) {
            "UPDATE $table SET server_updated_at = ? WHERE id = ? AND client_updated_at = ?"
        } else {
            "UPDATE $table SET server_updated_at = ? WHERE id = ?"
        }
        val args: Array<Any?> = if (clientMs != null) arrayOf(sua, id, clientMs) else arrayOf(sua, id)
        db.openHelper.writableDatabase.execSQL(sql, args)
    }

    /** The access check after a `42501`; a failing probe ends the run for a retry (nothing is dead-lettered). */
    private suspend fun accountAllowed(): Boolean = try {
        remote.accountAllowed()
    } catch (p: SyncRemoteException) {
        throw Stop(RunOutcome.RETRY, SyncState.Offline)
    }

    private suspend fun deadLetter(
        t: SyncTable<SyncedRow>,
        e: OutboxEntity,
        row: SyncedRow,
        status: Int?,
        code: String?,
        message: String?,
        stats: RunStats,
    ) {
        db.withTransaction {
            db.deadLetters().insert(
                DeadLetterEntity(
                    table_name = t.name, row_id = row.id, http_status = status, error_code = code,
                    message = message?.take(300), payload_json = t.toPush(row).toString(),
                    first_failed_at_ms = nowMs(), attempts = e.attempts + 1,
                ),
            )
            db.outbox().deleteRow(t.name, row.id)
            // A dead-lettered telemetry row gets no telemetry event of its own: that event would be a new
            // `session_events` row failing the same way, one more per pass.
            if (t.name != TELEMETRY_TABLE) telemetry.write(
                EventDraft(
                    type = "sync_dead_letter",
                    entityType = "none",
                    payload = buildMap {
                        put("table", JsonPrimitive(t.name))
                        put("row_id", JsonPrimitive(row.id))
                        status?.let { put("http_status", JsonNumbers.int(it.toLong())) }
                        code?.let { if (it.startsWith("PGRST")) put("pgrst_code", JsonPrimitive(it)) else put("pg_code", JsonPrimitive(it)) }
                        put("attempts", JsonNumbers.int((e.attempts + 1).toLong()))
                    },
                ),
            )
        }
        stats.errors["dead_letter"] = (stats.errors["dead_letter"] ?: 0) + 1
    }

    /**
     * A 401 (ANDROID 9.5): refresh the session once and retry the run with backoff; only when the refresh token is
     * no longer valid does sync stop and ask the user to sign in. The outbox is kept either way.
     */
    private suspend fun authStop(table: String, f: SyncFailure): Stop {
        logError(table, null, 0, SyncErrorClass.AUTH, f)
        return if (remote.refreshSession() || remote.authRefreshPending()) {
            Stop(RunOutcome.RETRY, SyncState.Offline)
        } else {
            Stop(RunOutcome.STOPPED, SyncState.NeedsSignIn)
        }
    }

    private suspend fun logError(table: String, rowId: String?, rows: Int, cls: SyncErrorClass, f: SyncFailure, attempts: Int = 1) {
        db.withTransaction {
            telemetry.write(
                EventDraft(
                    type = "sync_error",
                    entityType = "none",
                    payload = buildMap {
                        put("table", JsonPrimitive(table))
                        rowId?.let { put("row_id", JsonPrimitive(it)) }
                        if (rows > 0) put("rows", JsonNumbers.int(rows.toLong()))
                        put("error_class", JsonPrimitive(cls.code))
                        f.httpStatus?.let { put("http_status", JsonNumbers.int(it.toLong())) }
                        f.code?.let { if (it.startsWith("PGRST")) put("pgrst_code", JsonPrimitive(it)) else put("pg_code", JsonPrimitive(it)) }
                        put("attempts", JsonNumbers.int(attempts.toLong()))
                    },
                ),
            )
        }
    }

    private suspend fun conflict(table: String, rowId: String, local: LwwTuple, server: LwwTuple, winner: String) {
        telemetry.write(
            EventDraft(
                type = "sync_conflict",
                entityType = "none",
                payload = mapOf(
                    "table" to JsonPrimitive(table),
                    "row_id" to JsonPrimitive(rowId),
                    "local_tuple" to tupleJson(local),
                    "server_tuple" to tupleJson(server),
                    "winner" to JsonPrimitive(winner),
                ),
            ),
        )
    }

    private fun tupleJson(t: LwwTuple) = buildJsonObject {
        put("client_updated_at", JsonNumbers.int(t.clientUpdatedAtMs))
        put("device_id", JsonPrimitive(t.deviceId))
    }

    private fun counts(m: Map<String, Int>) = JsonObject(m.mapValues { JsonNumbers.int(it.value.toLong()) })

    companion object {
        private val MICROS: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")

        /** Guard of [drain]; not a tunable (a pass only finds rows written during the run). */
        private const val MAX_DRAIN_PASSES = 5
        private const val TELEMETRY_TABLE = "session_events"

        /** `server_updated_at` minus the overlap, kept at microsecond precision (ANDROID.md 8.2). */
        fun minusSeconds(serverTs: String, seconds: Long): String =
            OffsetDateTime.parse(serverTs).minusSeconds(seconds).withOffsetSameInstant(ZoneOffset.UTC).format(MICROS)

        fun compareTs(a: String, b: String): Int = OffsetDateTime.parse(a).toInstant().compareTo(OffsetDateTime.parse(b).toInstant())
    }
}
