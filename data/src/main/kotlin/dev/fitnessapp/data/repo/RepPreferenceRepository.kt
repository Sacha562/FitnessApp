package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.prescription.rx.RepPref
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.RepPreferenceEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * The editable fields of one Sets and reps preference (DATA_MODEL 9.3.4); all null = no preference at that scope. Rest
 * is seconds ([restPrefS]) or a multiplier of the class rest ([restPrefMult], D50), never both.
 */
data class RepPrefValues(
    val setsPref: Int? = null,
    val repMin: Int? = null,
    val repMax: Int? = null,
    val rirTarget: Double? = null,
    val repScheme: String? = null,
    val restPrefS: Int? = null,
    val restPrefMult: Double? = null,
) {
    val empty: Boolean get() = setsPref == null && repMin == null && rirTarget == null && repScheme == null && restPrefS == null && restPrefMult == null

    fun toPref(scope: String) = RepPref(scope, setsPref, repMin, repMax, rirTarget, repScheme, restPrefS?.toDouble(), restPrefMult)
}

/**
 * The Sets and reps preferences (UX 15.4, D10, D49): one row per scope key (`global`, `goal:<kind>`,
 * `mechanics:<compound|isolation>`, `muscle_group:<id>`, `exercise:<uuid>`) with a UUIDv5 id, so devices converge. RX-1
 * resolves the most specific scope per field (B9.3); a preference that deviates from evidence keeps its value and gets
 * a warning (D10). Clearing every field soft-deletes the row.
 */
class RepPreferenceRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
) {
    fun observe(): Flow<List<RepPreferenceEntity>> = db.repPreferences().observeAll()

    suspend fun all(): List<RepPreferenceEntity> = db.repPreferences().active()

    /**
     * The preferences that apply to [ex], most specific first, as RX-1's [RepPref] list. [primaryGroup] is the
     * exercise's primary muscle group; goal-scope rows wait for per-exercise goals (OI-A10 keeps them derived).
     */
    suspend fun resolve(ex: CatalogExercise, exerciseId: String, primaryGroup: String?): List<RepPref> {
        val rows = all().associateBy { it.scope_key }
        val keys = listOfNotNull(
            "exercise:$exerciseId", primaryGroup?.let { "muscle_group:$it" },
            ex.mechanics?.let { "mechanics:$it" }, GLOBAL,
        )
        return keys.mapNotNull { k -> rows[k]?.let { values(it).toPref(k.substringBefore(':')) } }
    }

    /**
     * Saves [values] at [scopeKey]; one `rep_preference_changed` per changed field, carrying the warning rules the new
     * value raises ([warningRuleIds]) and the preset that filled the editor ([presetId], D50). Returns the number of
     * changed fields.
     */
    suspend fun save(scopeKey: String, values: RepPrefValues, warningRuleIds: List<String> = emptyList(), presetId: String? = null): Int {
        val uid = ctx.requireUser()
        val id = idOf(uid, scopeKey)
        var changed = 0
        db.withTransaction {
            val old = db.repPreferences().get(id)?.takeIf { it.deleted_at == null }
            val before = old?.let { values(it) } ?: RepPrefValues()
            if (before == values) return@withTransaction
            val kind = scopeKey.substringBefore(':')
            val arg = scopeKey.substringAfter(':', "")
            val base = db.repPreferences().get(id) ?: RepPreferenceEntity(
                id = id, user_id = uid, client_updated_at = 0, device_id = "", app_version = "", scope_kind = kind, scope_key = scopeKey,
            )
            // The server keeps at least one field per row (rep_preferences_something_ck): a clear keeps the old values.
            val row = if (values.empty) base.copy(
                deleted_at = ctx.nowMs(), client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
            ) else base.copy(
                scope_kind = kind, scope_key = scopeKey,
                goal_kind = arg.takeIf { kind == "goal" }, mechanics = arg.takeIf { kind == "mechanics" },
                muscle_group_id = arg.takeIf { kind == "muscle_group" }, exercise_id = arg.takeIf { kind == "exercise" },
                sets_pref = values.setsPref, rep_min = values.repMin, rep_max = values.repMax,
                rir_target = values.rirTarget?.let { Scaled.toLong(BigDecimal.valueOf(it), 1) }, rep_scheme = values.repScheme,
                rest_pref_s = values.restPrefS, rest_pref_mult = values.restPrefMult?.let { Scaled.toLong(BigDecimal.valueOf(it), 2) },
                deleted_at = null,
                client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
            )
            db.repPreferences().upsert(row)
            outbox.enqueue("rep_preferences", id, ctx.nowMs())
            for ((field, from, to) in diff(before, values)) {
                changed++
                telemetry.write(
                    EventDraft(
                        // The registry's entity is none (UX 20.8); the scope in the payload names the row.
                        type = "rep_preference_changed", entityType = "none", screen = "you.preferences",
                        payload = mapOf(
                            "scope" to JsonPrimitive(scopeKey), "exercise_id" to (arg.takeIf { kind == "exercise" }?.let { JsonPrimitive(it) } ?: JsonNull),
                            "field" to JsonPrimitive(field), "from" to from, "to" to to,
                            "warning_rule_ids" to JsonArray(warningRuleIds.sorted().map { JsonPrimitive(it) }),
                            "preset_id" to (presetId?.let { JsonPrimitive(it) } ?: JsonNull),
                        ),
                    ),
                )
            }
        }
        if (changed > 0) scheduler.request()
        return changed
    }

    private fun diff(a: RepPrefValues, b: RepPrefValues): List<Triple<String, JsonElement, JsonElement>> = listOf(
        Triple("sets_pref", j(a.setsPref), j(b.setsPref)), Triple("rep_min", j(a.repMin), j(b.repMin)),
        Triple("rep_max", j(a.repMax), j(b.repMax)), Triple("rir_target", j(a.rirTarget), j(b.rirTarget)),
        Triple("rep_scheme", j(a.repScheme), j(b.repScheme)), Triple("rest_pref_s", j(a.restPrefS), j(b.restPrefS)),
        Triple("rest_pref_mult", j(a.restPrefMult), j(b.restPrefMult)),
    ).filter { it.second != it.third }

    private fun j(x: Any?): JsonElement = when (x) {
        null -> JsonNull
        is Number -> JsonPrimitive(x)
        else -> JsonPrimitive(x.toString())
    }

    companion object {
        const val GLOBAL = "global"

        fun idOf(userId: String, scopeKey: String): String = Ids.fitness("rep_preferences:$userId:$scopeKey").toString()

        fun values(r: RepPreferenceEntity) = RepPrefValues(
            r.sets_pref, r.rep_min, r.rep_max, r.rir_target?.let { Scaled.toDouble(it, 1) }, r.rep_scheme, r.rest_pref_s,
            r.rest_pref_mult?.let { Scaled.toDouble(it, 2) },
        )
    }
}
