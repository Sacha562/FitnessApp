package dev.fitnessapp.data.engine

import androidx.room.withTransaction
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.planning.today.TodayOptions
import dev.fitnessapp.algo.planning.today.TodayRecommender
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.entity.RecommendationEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncScheduler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * The planner's snapshots (DATA_MODEL 10.1-10.2, ALGORITHMS_PLANNING 8.11 and 9.8): `weekplan.plan` scoped
 * `week:<iso_week>` and `today.options` scoped `day:<date>`, each written when its outputs change (the row id is
 * derived from the inputs and outputs, so an unchanged plan is not stored twice).
 */
class PlanSnapshotRecorder(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val catalog: CatalogRepository,
    private val params: ParamView,
    private val paramsVersion: String,
) {
    suspend fun week(plan: WeekPlan): String? {
        if (plan.provisional) return null
        val outputs = buildJsonObject {
            put("days", buildJsonArray {
                plan.days.forEach { d ->
                    val i = d.items.firstOrNull { it.kind == WeeklyPlanner.KIND_RECOMMENDED } ?: d.items.firstOrNull()
                    add(buildJsonObject {
                        put("date", d.date.toString())
                        put("kind", i?.kind ?: "rest")
                        i?.type?.let { put("type", it) } ?: i?.slot?.let { put("type", it) }
                        put("est_minutes", JsonNumbers.grid(i?.estMinutes ?: 0.0, GridKind.S))
                        d.restReason?.let { put("rest_reason", it.code) }
                        d.items.firstOrNull { it.kind == WeeklyPlanner.KIND_ADDON }?.type?.let { put("addon", it) }
                    })
                }
            })
            put("recommended_n", JsonPrimitive(plan.recommendedN))
            put("j", JsonNumbers.grid(plan.j, GridKind.RATIO))
            put("band", buildJsonObject { put("in_band", JsonPrimitive(plan.band.candidatesInBand)); put("tie_key_used", plan.band.tieKeyUsed) })
            put("evaluated", JsonPrimitive(plan.evaluated))
        }
        val inputs = JsonObject(mapOf("plan_id" to JsonPrimitive(plan.planId), "iso_week" to JsonPrimitive(plan.isoWeek)))
        return write("weekplan.plan", WeeklyPlanner.ref.id.value, WeeklyPlanner.ref.version.toString(), "week:${plan.isoWeek}", plan.days.first().date, inputs, outputs)
    }

    suspend fun today(t: TodayOptions): String? {
        val outputs = buildJsonObject {
            put("verdict", t.verdict)
            put("options", buildJsonArray {
                t.options.forEach { o ->
                    add(buildJsonObject {
                        put("option_id", o.optionId); o.sessionType?.let { put("session_type", it) }
                        put("score", JsonNumbers.grid(o.score, GridKind.RATIO)); o.addon?.let { put("addon", it) }
                    })
                }
            })
            t.primaryOptionId?.let { put("primary_option_id", it) }
            put("rest_advice", buildJsonObject { put("flag", t.restAdvice.flag); put("hard", t.restAdvice.hard); t.restAdvice.reasonCode?.let { put("reason_code", it) } })
            put("reasons", buildJsonArray {
                (t.restReasons + t.options.firstOrNull { it.optionId == t.primaryOptionId }?.reasons.orEmpty()).forEach { r ->
                    add(buildJsonObject { put("code", r.code); put("class", JsonPrimitive(r.cls)) })
                }
            })
            put("budget_min", JsonNumbers.grid(t.budgetMin, GridKind.S))
            put("budget_source", t.budgetSource)
        }
        val inputs = JsonObject(mapOf("week_plan_id" to JsonPrimitive(t.weekPlanId), "date" to JsonPrimitive(t.date.toString()), "budget_min" to JsonNumbers.grid(t.budgetMin, GridKind.S)))
        return write("today.options", TodayRecommender.ref.id.value, TodayRecommender.ref.version.toString(), "day:${t.date}", t.date, inputs, outputs)
    }

    private suspend fun write(kind: String, moduleId: String, version: String, scope: String, date: LocalDate, inputs: JsonObject, outputs: JsonObject): String {
        val user = ctx.requireUser()
        val inputsText = JsonText.canonical(inputs)
        val inputsHash = Jcs.sha256Hex(inputs)
        val id = Ids.fitness(listOf("recommendations", user, kind, scope, params.hash, inputsHash, Jcs.sha256Hex(outputs)).joinToString(":")).toString()
        val now = ctx.nowMs()
        db.withTransaction {
            val paramsRow = EngineFacts.paramsSnapshot(ctx, user, params, paramsVersion)
            if (db.paramsSnapshots().insertIgnore(paramsRow) != -1L) outbox.enqueue("params_snapshots", paramsRow.id, now)
            val row = RecommendationEntity(
                id = id, user_id = user, device_id = ctx.device.deviceId(), app_version = ctx.appVersion, kind = kind,
                module_id = moduleId, module_version = version, engine_version = EngineVersion.ENGINE_VERSION,
                params_hash = params.hash, catalog_version = catalog.version, inputs_hash = inputsHash, inputs = inputsText,
                inputs_bytes = inputsText.toByteArray(Charsets.UTF_8).size, outputs = JsonText.canonical(outputs),
                scope_key = scope, scope_date = date.toString(), iso_week = Week.isoWeek(date),
            )
            if (db.recommendations().insertIgnore(listOf(row)).first() != -1L) outbox.enqueue("recommendations", id, now)
        }
        scheduler.request()
        return id
    }
}
