package dev.fitnessapp.data.export

import androidx.room.withTransaction
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.data.db.EpochMsIso
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.entity.ExportEntity
import dev.fitnessapp.data.db.entity.SyncedRow
import dev.fitnessapp.data.db.local.CrashLogEntity
import dev.fitnessapp.data.db.local.DeadLetterEntity
import dev.fitnessapp.data.db.local.RestTimerEntity
import dev.fitnessapp.data.db.local.ScheduledAlarmEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncJson
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.sync.SyncTable
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** A built export file: the `.json.gz` bytes and what the `exports` row records about them. */
class BuiltExport(
    val kind: String,
    val bytes: ByteArray,
    val sha256: String,
    val rowCounts: Map<String, Int>,
    val generatedAtMs: Long,
) {
    val rows: Int get() = rowCounts.values.sum()
}

/**
 * The full export (ANDROID.md 20.4, DATA_MODEL 15.5, F4): one JSON document in canonical JCS form, gzip-compressed.
 * Every synced table of the user (including tombstones, rows by id, each row exactly as its sync DTO) and the
 * local-only tables except the outbox internals (`outbox`, `sync_cursor`), the auth session, `calendar_instance` and
 * `exercise_fts`. The same database always gives the same JSON bytes; the gzip header carries no timestamp.
 */
class ExportRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val tables: List<SyncTable<out SyncedRow>>,
    private val catalogVersion: () -> String,
    private val paramsVersions: () -> List<String>,
) {
    fun observeLastFull(): Flow<ExportEntity?> = db.exports().observeLastFull()

    suspend fun lastFull(): ExportEntity? = db.exports().lastFull()

    /** Builds a full export of [kind] (`manual_full` or `monthly_full`) from one consistent read. */
    suspend fun build(kind: String): BuiltExport {
        require(kind in FULL_KINDS) { "not a full export kind: $kind" }
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        val counts = sortedMapOf<String, Int>()
        lateinit var doc: JsonObject
        db.withTransaction {
            val tableJson = sortedMapOf<String, JsonElement>()
            for (t in tables) {
                val rows = t.dao.exportAll().filter { it.user_id == uid }.sortedBy { it.id }.map { t.toExport(it) }
                tableJson[t.name] = JsonArray(rows)
                counts[t.name] = rows.size
            }
            val local = sortedMapOf<String, JsonElement>(
                "dead_letter" to encode(ListSerializer(DeadLetterEntity.serializer()), db.deadLetters().all().sortedBy { it.id }),
                "rest_timer" to encode(ListSerializer(RestTimerEntity.serializer()), db.restTimers().all().sortedBy { it.id }),
                "scheduled_alarm" to encode(ListSerializer(ScheduledAlarmEntity.serializer()), db.alarms().all().sortedBy { it.request_code }),
                "crash_log" to encode(ListSerializer(CrashLogEntity.serializer()), db.crashes().all().sortedBy { it.id }),
                "device" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(1), "device_id" to JsonPrimitive(ctx.device.deviceId()),
                                "last_client_updated_at_ms" to JsonPrimitive(ctx.device.lastIssuedMs()),
                            ),
                        ),
                    ),
                ),
            )
            for ((k, v) in local) counts[k] = (v as JsonArray).size
            val generatedAt = JsonPrimitive(EpochMsIso.format(now))
            doc = JsonObject(
                mapOf(
                    "schema_version" to JsonPrimitive(SCHEMA_VERSION),
                    "export_kind" to JsonPrimitive(kind),
                    "generated_at" to generatedAt,
                    "user_id" to JsonPrimitive(uid),
                    "app_version" to JsonPrimitive(ctx.appVersion),
                    "engine_version" to JsonPrimitive(EngineVersion.ENGINE_VERSION),
                    "catalog_version" to JsonPrimitive(catalogVersion()),
                    "params_versions" to JsonArray(paramsVersions().sorted().map(::JsonPrimitive)),
                    "room_schema_version" to JsonPrimitive(FitnessDatabase.VERSION),
                    "period" to JsonObject(mapOf("start" to JsonNull, "end" to generatedAt)),
                    "includes_local_only" to JsonPrimitive(true),
                    "includes_notes" to JsonPrimitive(true),
                    "outbox_depth" to JsonPrimitive(db.outbox().depth()),
                    "dead_letters" to JsonPrimitive(counts["dead_letter"] ?: 0),
                    "tables" to JsonObject(tableJson),
                    "local_only" to JsonObject(local),
                ),
            )
        }
        val gz = gzip(Jcs.canonicalize(doc).toByteArray(Charsets.UTF_8))
        return BuiltExport(kind, gz, sha256Hex(gz), counts, now)
    }

    /**
     * Records a file that was written completely to [destination] (`device_storage` for the system file picker): the
     * `exports` row and `export_completed`, in one transaction. Returns the export id.
     */
    suspend fun record(b: BuiltExport, destination: String): String {
        val uid = ctx.requireUser()
        val id = ctx.ids.next()
        db.withTransaction {
            db.exports().insert(
                ExportEntity(
                    id = id, user_id = uid, device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                    export_kind = b.kind, schema_version = SCHEMA_VERSION,
                    table_row_counts = JsonText.canonical(JsonObject(b.rowCounts.toSortedMap().mapValues { JsonPrimitive(it.value) })),
                    bytes = b.bytes.size.toLong(), sha256 = b.sha256, destination = destination,
                    period_start = null, period_end = b.generatedAtMs, includes_local_only = true,
                    engine_version = EngineVersion.ENGINE_VERSION, catalog_version = catalogVersion(),
                ),
            )
            outbox.enqueue("exports", id, ctx.nowMs())
            telemetry.write(
                EventDraft(
                    type = "export_completed", entityType = "export", entityId = id,
                    payload = mapOf(
                        "export_id" to JsonPrimitive(id), "kind" to JsonPrimitive(b.kind), "rows" to JsonPrimitive(b.rows),
                        "bytes" to JsonPrimitive(b.bytes.size),
                    ),
                ),
            )
        }
        scheduler.request()
        return id
    }

    private fun <T> encode(s: kotlinx.serialization.KSerializer<List<T>>, rows: List<T>): JsonElement =
        SyncJson.encodeToJsonElement(s, rows).let { arr -> JsonArray((arr as JsonArray).map { it.jsonObject }) }

    companion object {
        const val SCHEMA_VERSION = "fitness-export/1"
        val FULL_KINDS = setOf("manual_full", "monthly_full")

        /** `fitnessapp-export-<yyyy-MM-dd>.json.gz` (ANDROID.md 20.4). */
        fun fileName(localDate: String): String = "fitnessapp-export-$localDate.json.gz"

        fun gzip(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size / 4 + 64)
            GZIPOutputStream(out).use { it.write(data) }
            return out.toByteArray()
        }

        fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
