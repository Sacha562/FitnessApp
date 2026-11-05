package dev.fitnessapp.data.engine

import androidx.room.withTransaction
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.pipeline.BuildResult
import dev.fitnessapp.algo.selection.select.Select
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.entity.RecommendationEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncScheduler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * The `session.plan` snapshot of a built general block (DATA_MODEL 10.1, D62): `select@<version>` with the params hash,
 * the canonical outputs of 10.2 (`est_min`, `budget_min`, `exercises[]: {exercise_id, sets, est_s}`) plus the type,
 * label, allocation credit and trim lines, scoped `block:<uuid>`; the block's `recommendation_id` points at it.
 */
class SessionPlanRecorder(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val catalog: CatalogRepository,
    private val paramsOf: () -> ParamView,
    private val paramsVersion: String,
) {
    /** The effective params now: the bundled defaults with the user's TIME-1 calibration (D93). */
    private val params: ParamView get() = paramsOf()

    suspend fun record(sessionId: String, blockId: String, r: BuildResult, budgetMin: Int): String? {
        val user = ctx.requireUser()
        val session = db.sessions().get(sessionId) ?: return null
        val outputs = buildJsonObject {
            put("type", r.type.code)
            put("label", r.label)
            put("est_min", JsonNumbers.grid(r.estMin, GridKind.S))
            put("budget_min", JsonPrimitive(budgetMin))
            put("exercises", buildJsonArray {
                r.exercises.forEach { x ->
                    add(buildJsonObject {
                        put("exercise_id", catalog.bySlug(x.slug)?.let { catalog.idOf(it) } ?: x.slug)
                        put("sets", JsonPrimitive(x.sets))
                        put("est_s", JsonNumbers.grid(x.estS, GridKind.S))
                        x.chainedAfter?.let { put("chained_after", it) }
                    })
                }
            })
            put("credit", JsonObject(r.allocation.credit.mapValues { JsonNumbers.grid(it.value, GridKind.RATIO) }))
            put("trimmed", buildJsonArray {
                r.trimmed.forEach { t -> add(buildJsonObject { put("exercise", t.slug); put("sets_removed", JsonPrimitive(t.setsRemoved)); put("removed", t.removed) }) }
            })
            put("core_does_not_fit", r.coreDoesNotFit)
        }
        val inputs = JsonObject(mapOf("block_id" to JsonPrimitive(blockId), "type" to JsonPrimitive(r.type.code), "budget_min" to JsonPrimitive(budgetMin)))
        val inputsText = JsonText.canonical(inputs)
        val inputsHash = Jcs.sha256Hex(inputs)
        val scope = "block:$blockId"
        val id = Ids.fitness(listOf("recommendations", user, KIND, scope, params.hash, inputsHash, Jcs.sha256Hex(outputs)).joinToString(":")).toString()
        val now = ctx.nowMs()
        val date = LocalDate.parse(session.local_date)
        db.withTransaction {
            val paramsRow = EngineFacts.paramsSnapshot(ctx, user, params, paramsVersion)
            if (db.paramsSnapshots().insertIgnore(paramsRow) != -1L) outbox.enqueue("params_snapshots", paramsRow.id, now)
            val row = RecommendationEntity(
                id = id, user_id = user, device_id = ctx.device.deviceId(), app_version = ctx.appVersion, kind = KIND,
                module_id = Select.ref.id.value, module_version = Select.ref.version.toString(), engine_version = EngineVersion.ENGINE_VERSION,
                params_hash = params.hash, catalog_version = catalog.version, inputs_hash = inputsHash, inputs = inputsText,
                inputs_bytes = inputsText.toByteArray(Charsets.UTF_8).size, outputs = JsonText.canonical(outputs),
                trace = JsonText.canonical(JsonObject(mapOf("allocation_label" to JsonPrimitive(r.allocation.label), "empty" to (r.empty?.let { JsonPrimitive(it) } ?: JsonNull)))),
                scope_key = scope, scope_date = date.toString(), iso_week = Week.isoWeek(date), session_id = sessionId,
            )
            if (db.recommendations().insertIgnore(listOf(row)).first() != -1L) outbox.enqueue("recommendations", id, now)
            val block = db.blocks().bySession(sessionId).firstOrNull { it.id == blockId } ?: return@withTransaction
            db.blocks().upsert(block.copy(recommendation_id = id, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion))
            outbox.enqueue("session_blocks", blockId, now)
        }
        scheduler.request()
        return id
    }

    private companion object {
        const val KIND = "session.plan"
    }
}
