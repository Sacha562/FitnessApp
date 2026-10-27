package dev.fitnessapp.data.engine

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.prescription.rest.RestClass
import dev.fitnessapp.algo.prescription.rest.RestInput
import dev.fitnessapp.algo.prescription.rest.RestPolicy
import dev.fitnessapp.algo.prescription.warnings.SchemeFacts
import dev.fitnessapp.algo.prescription.warnings.Warning
import dev.fitnessapp.algo.prescription.warnings.Warnings
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.RecommendationEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncScheduler
import androidx.room.withTransaction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * WE-1 on the phone for a live session (B15, M3.5): the scheme rules of every engine prescription (D10: only fields
 * the user owns get evidence warnings; WE-S1 binds every source), the cap clamps RX-1 enforced, the SAFETY notices of
 * logged sets (WE-S1, WE-S2 for a manual load above the cap, WE-S5 from AUTO-1's next-set prediction), and the
 * history rules WE-P10 and WE-T2. Challenge constraints (WE-S4) need the week plan and are added by the caller.
 * [store] keeps the evaluation as a `warnings.evaluation` snapshot (scope `session:<id>`).
 */
class WarningRunner(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val catalog: CatalogRepository,
    private val prescriptions: PrescriptionRunner,
    private val params: ParamView,
    private val paramsVersion: String,
) {
    suspend fun session(sessionId: String, suggestions: Map<String, Suggestion> = emptyMap()): List<Warning> {
        val session = db.sessions().get(sessionId) ?: return emptyList()
        val p = params.fork()
        val out = ArrayList<Warning>()
        val sets = db.setLogs().bySession(sessionId)
        for (se in db.sessionExercises().bySession(sessionId).filter { it.status != "removed" }) {
            val ex = catalog.byId(se.exercise_id) ?: continue
            val entity = "session_exercise:${se.id}"
            val highRisk = ex.safetyClass == "high_risk"
            val rx = se.recommendation_id?.let { prescriptions.get(it) }
            if (rx != null) {
                val scheme = rx.outputs["scheme"]?.jsonObject
                val sources = scheme?.get("sources")?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                val request = rx.inputs?.get("requests")?.jsonArray?.firstOrNull()?.jsonObject
                val prefs = request?.get("prefs")?.jsonArray?.map { it.jsonObject }.orEmpty()
                val item = request?.get("item")?.takeIf { it !is JsonNull }?.jsonObject
                val asked = item?.get("rir_target")?.jsonPrimitive?.doubleOrNull
                    ?: prefs.firstNotNullOfOrNull { it["rir_target"]?.takeIf { e -> e !is JsonNull }?.jsonPrimitive?.doubleOrNull }
                    ?: rx.qStar
                val facts = SchemeFacts(
                    rLo = rx.rLo, rHi = rx.rHi, qStar = rx.qStar, qStarAsked = asked,
                    setsPref = prefs.firstNotNullOfOrNull { it["sets_pref"]?.jsonPrimitive?.intOrNull },
                    restS = userRestS(ex.restClass, rx.rHi, prefs, p),
                    strength = scheme?.get("kind")?.jsonPrimitive?.contentOrNull == "fixed" && rx.rHi <= STRENGTH_MAX_REPS,
                    compound = ex.mechanics != "isolation", highRisk = highRisk,
                    repsPerStep = rx.trace["reps_per_step"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull,
                    rangeExhausted = "rx.double.range_too_narrow" in rx.steps,
                )
                val userOwned = sources.values.any { it.startsWith("user:") }
                out += Warnings.scheme(entity, facts, p).filter { it.ruleId == "WE-S1" || userOwned }
                rx.outputs["caps_applied"]?.jsonArray?.forEach { c ->
                    val o = c.jsonObject
                    Warnings.capApplied(
                        entity, o.getValue("type").jsonPrimitive.content,
                        o.getValue("limit_w").jsonPrimitive.content.toDouble(), o.getValue("unclamped_w").jsonPrimitive.content.toDouble(),
                    )?.let { out += it }
                }
            }
            val mine = sets.filter { it.session_exercise_id == se.id && it.set_kind != "warmup" }
            for (s in mine) {
                Warnings.loggedSet(
                    entity, highRisk, s.set_kind, ex.contraction, s.rir_reported?.let { Scaled.toDouble(it, 1) }, "failed_rep" in s.flags,
                )?.let { out += it }
            }
            // WE-S2 notice: a manual load above the cap of the last exposure (general items; RULE loads follow the challenge).
            if (se.challenge_item_key == null && rx != null) {
                val cap = capFor(se.exercise_id, sessionId, highRisk, ex.mechanics == "isolation")
                val heaviest = mine.mapNotNull { it.effective_load_kg?.let { w -> Scaled.toDouble(w, 2) } }.maxOrNull()
                if (cap != null && heaviest != null) Warnings.loadAboveCap(entity, heaviest, cap, ruleItem = false, hold = false)?.let { out += it }
            }
            suggestions[se.id]?.let { s ->
                if (s.highRisk && mine.size < (se.planned_sets ?: Int.MAX_VALUE)) Warnings.predictedFailure(entity, true, s.result.rirNextCur, p)?.let { out += it }
            }
        }
        val today = LocalDate.parse(session.local_date)
        val reasons = db.setLogs().reasonsSince(today.minusDays(28).toString(), listOf("grip_failure", "feeling_bad", "too_tired"))
        reasons.filter { it.deviation_reason_code == "grip_failure" }.groupBy { it.exercise_id }.toSortedMap().forEach { (exId, rows) ->
            Warnings.gripLimited("exercise:$exId", rows.size)?.let { out += it }
        }
        val since14 = today.minusDays(14).toString()
        val bad = reasons.count { it.deviation_reason_code != "grip_failure" && it.local_date >= since14 }
        Warnings.recoveryCheck(bad, emptyList(), p)?.let { out += it }
        return Warnings.rank(out)
    }

    /** `w_last * (1 + cap_up)` of the last earlier exposure (B9.14), or null without one. */
    private suspend fun capFor(exerciseId: String, sessionId: String, highRisk: Boolean, isolation: Boolean): Double? {
        val history = db.setLogs().historyForExercise(exerciseId, sessionId, HISTORY_LIMIT).filter { it.set.set_kind in WORKING }
        val last = history.firstOrNull()?.set?.session_id ?: return null
        val wLast = history.filter { it.set.session_id == last }.mapNotNull { it.set.effective_load_kg?.let { w -> Scaled.toDouble(w, 2) } }.maxOrNull() ?: return null
        val up = if (isolation && !highRisk) params.double("rx.cap_up_isolation") else params.double("rx.cap_up_compound")
        return wLast * (1.0 + up)
    }

    /** Stores [warnings] as `warnings.evaluation` (B15.6); identical evaluations collapse into one row. */
    suspend fun store(sessionId: String, warnings: List<Warning>): String? {
        val user = ctx.userId() ?: return null
        val session = db.sessions().get(sessionId) ?: return null
        val scope = "session:$sessionId"
        val outputs = Warnings.json(warnings)
        val inputs = JsonObject(mapOf("session_id" to JsonPrimitive(sessionId), "rule_ids" to JsonArray(warnings.map { JsonPrimitive(it.ruleId + "|" + it.entity) })))
        val inputsText = JsonText.canonical(inputs)
        val inputsHash = Jcs.sha256Hex(JsonObject(inputs + ("outputs" to outputs)))
        val id = Ids.fitness(listOf("recommendations", user, KIND, scope, params.hash, inputsHash, "").joinToString(":")).toString()
        val now = ctx.nowMs()
        val date = LocalDate.parse(session.local_date)
        db.withTransaction {
            val paramsRow = EngineFacts.paramsSnapshot(ctx, user, params, paramsVersion)
            if (db.paramsSnapshots().insertIgnore(paramsRow) != -1L) outbox.enqueue("params_snapshots", paramsRow.id, now)
            val row = RecommendationEntity(
                id = id, user_id = user, device_id = ctx.device.deviceId(), app_version = ctx.appVersion, kind = KIND,
                module_id = Warnings.ref.id.value, module_version = Warnings.ref.version.toString(), engine_version = EngineVersion.ENGINE_VERSION,
                params_hash = params.hash, catalog_version = catalog.version, inputs_hash = inputsHash, inputs = inputsText,
                inputs_bytes = inputsText.toByteArray(Charsets.UTF_8).size, outputs = JsonText.canonical(outputs), trace = "[]",
                scope_key = scope, scope_date = date.toString(), iso_week = Week.isoWeek(date), session_id = sessionId,
            )
            if (db.recommendations().insertIgnore(listOf(row)).first() != -1L) outbox.enqueue("recommendations", id, now)
        }
        scheduler.request()
        return id
    }

    /**
     * WE-P6's rest (B15.2, D50): the resolved preference of the prescription's inputs as REST-1's base, seconds or the
     * class base at the prescribed reps times the multiplier; null without a user rest preference.
     */
    private fun userRestS(restClass: String, reps: Int, prefs: List<JsonObject>, p: ParamView): Double? {
        fun num(o: JsonObject, k: String) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
        val pref = prefs.firstOrNull { num(it, "rest_pref_s") != null || num(it, "rest_pref_mult") != null } ?: return null
        return RestPolicy.decide(
            RestInput(RestClass.of(restClass), targetReps = reps, userRestPrefS = num(pref, "rest_pref_s"), userRestMult = num(pref, "rest_pref_mult")), p,
        ).baseS
    }

    companion object {
        const val KIND = "warnings.evaluation"
        private const val HISTORY_LIMIT = 200
        private const val STRENGTH_MAX_REPS = 6
        private val WORKING = setOf("working", "top", "backoff", "amrap")
    }
}
