package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.algo.core.load.Loadables
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.entity.GymEntity
import dev.fitnessapp.data.db.entity.GymEquipmentEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One `equipment_types` reference row (DATA_MODEL 9.1.7). */
data class EquipmentType(
    val key: String,
    val category: String,
    val defaultSchemeType: String?,
    val defaultLoadScheme: JsonObject?,
    val portable: Boolean,
    val labelEn: String,
    val sort: Int,
)

/** The equipment that loads one exercise in one load mode, with its parsed scheme. */
data class ResolvedEquipment(val row: GymEquipmentEntity?, val scheme: LoadScheme)

/**
 * Gyms and equipment (DATA_MODEL 9.3.2-9.3.3, F7): the onboarding checklist writes them; LOAD-1 turns them
 * into loadable options. A numeric calibration change bumps `calibration_rev` and logs
 * `equipment_calibration_changed` with old and new schemes, so historic assist kg can be recomputed (B2.3).
 */
class EquipmentRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
) {
    val types: List<EquipmentType> by lazy { parseTypes(resource("/fitness/reference/equipment_types.json")) }

    fun observeGyms(): Flow<List<GymEntity>> = db.gyms().observeAll()
    fun observeEquipment(): Flow<List<GymEquipmentEntity>> = db.gymEquipment().observeAll()

    suspend fun equipment(): List<GymEquipmentEntity> = db.gymEquipment().all()
    suspend fun defaultGym(): GymEntity? = db.gyms().all().firstOrNull()

    /** Writes the gym and every checklist row in one transaction (onboarding "Confirm equipment"). */
    suspend fun saveChecklist(gym: GymDraft, items: List<EquipmentDraft>, confirmed: Boolean) {
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        db.withTransaction {
            val existingGym = db.gyms().all().firstOrNull()
            val gymRow = GymEntity(
                id = existingGym?.id ?: ctx.ids.next(), user_id = uid, client_updated_at = ctx.nextClientMs(),
                device_id = ctx.device.deviceId(), app_version = ctx.appVersion, name = gym.name,
                location_keywords = gym.locationKeywords, opening_hours = gym.openingHoursJson, is_default = true,
            )
            db.gyms().upsert(gymRow)
            outbox.enqueue("gyms", gymRow.id, now)
            val existing = db.gymEquipment().all().associateBy { it.equipment_type to (it.gym_id == null) }
            val changed = mutableListOf<String>()
            for (d in items) {
                val type = types.first { it.key == d.equipmentType }
                val old = existing[d.equipmentType to type.portable]
                val scheme = JsonText.canonical(d.loadScheme)
                val calibrationChanged = old != null && old.load_scheme != scheme
                val row = GymEquipmentEntity(
                    id = old?.id ?: ctx.ids.next(), user_id = uid, client_updated_at = ctx.nextClientMs(),
                    device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                    gym_id = if (type.portable) null else gymRow.id, equipment_type = d.equipmentType,
                    label = d.label, available = d.available, scheme_type = d.loadScheme["type"]!!.jsonPrimitive.content,
                    load_scheme = scheme, calibration_rev = (old?.calibration_rev ?: 1) + if (calibrationChanged) 1 else 0,
                    calibration_source = d.calibrationSource,
                    confirmed_at = if (confirmed) now else old?.confirmed_at,
                )
                if (old != null && old == row.copy(client_updated_at = old.client_updated_at, server_updated_at = old.server_updated_at)) continue
                db.gymEquipment().upsert(row)
                outbox.enqueue("gym_equipment", row.id, now)
                if (old != null && old.available != row.available) {
                    // UX 20.8 `equipment_changed {key, from, to}`: a station switched on or off in the checklist.
                    telemetry.write(
                        EventDraft(
                            type = "equipment_changed", entityType = "gym_equipment", entityId = row.id,
                            payload = mapOf("key" to JsonPrimitive(row.equipment_type), "from" to JsonPrimitive(old.available), "to" to JsonPrimitive(row.available)),
                        ),
                    )
                }
                if (calibrationChanged) {
                    telemetry.write(
                        EventDraft(
                            type = "equipment_calibration_changed", entityType = "gym_equipment", entityId = row.id,
                            payload = mapOf(
                                "gym_equipment_id" to JsonPrimitive(row.id),
                                "from_rev" to JsonPrimitive(old!!.calibration_rev),
                                "to_rev" to JsonPrimitive(row.calibration_rev),
                                "from_scheme" to Json.parseToJsonElement(old.load_scheme),
                                "to_scheme" to Json.parseToJsonElement(row.load_scheme),
                            ),
                        ),
                    )
                }
                if (old == null || d.changedFromDefault) changed += d.equipmentType
            }
            if (confirmed) {
                telemetry.write(
                    EventDraft(
                        type = "equipment_confirmed", entityType = "none",
                        payload = mapOf(
                            "profile_version" to JsonPrimitive(1),
                            "changed_from_default" to JsonArray(items.filter { it.changedFromDefault }.map { JsonPrimitive(it.equipmentType) }),
                        ),
                    ),
                )
            }
        }
        scheduler.request()
    }

    /**
     * One machine's own weight stack, set in the live session (D43): only row [rowId] changes, the rest of its scheme
     * (ratio, add-on steps) stays. The row becomes `measured`, so the checklist's stack setting no longer rewrites it.
     */
    suspend fun saveStack(rowId: String, unit: String, start: Double, step: Double, max: Double) {
        val old = db.gymEquipment().byIds(listOf(rowId)).firstOrNull() ?: return
        val stored = Json.parseToJsonElement(old.load_scheme).jsonObject
        require(stored["type"]?.jsonPrimitive?.content == "stack") { "not a stack: ${old.equipment_type}" }
        val edited = JsonObject(
            stored + mapOf("unit" to JsonPrimitive(unit), "start" to JsonPrimitive(start), "step" to JsonPrimitive(step), "max" to JsonPrimitive(max)),
        )
        val scheme = JsonText.canonical(edited)
        requireNotNull(parseScheme(scheme)) { "invalid stack" }
        val calibrationChanged = old.load_scheme != scheme
        val now = ctx.nowMs()
        db.withTransaction {
            val row = old.copy(
                client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                load_scheme = scheme, calibration_rev = old.calibration_rev + if (calibrationChanged) 1 else 0,
                calibration_source = "measured", confirmed_at = now,
            )
            db.gymEquipment().upsert(row)
            outbox.enqueue("gym_equipment", row.id, now)
            if (calibrationChanged) {
                telemetry.write(
                    EventDraft(
                        type = "equipment_calibration_changed", entityType = "gym_equipment", entityId = row.id,
                        payload = mapOf(
                            "gym_equipment_id" to JsonPrimitive(row.id),
                            "from_rev" to JsonPrimitive(old.calibration_rev),
                            "to_rev" to JsonPrimitive(row.calibration_rev),
                            "from_scheme" to Json.parseToJsonElement(old.load_scheme),
                            "to_scheme" to Json.parseToJsonElement(row.load_scheme),
                        ),
                    ),
                )
            }
        }
        scheduler.request()
    }

    /**
     * The equipment for [e] in [mode] (B2.1): assisted pins use the assisted machine, weighted bodyweight the
     * dip belt, external loads the first loadable station the exercise requires, then its equipment class.
     */
    suspend fun resolve(e: CatalogExercise, mode: LoadMode): ResolvedEquipment {
        val rows = db.gymEquipment().all().filter { it.available }
        fun byType(t: String) = rows.firstOrNull { it.equipment_type == t }
        val row = when (mode) {
            LoadMode.ASSISTED_MACHINE -> byType("assisted_pullup_machine")
            LoadMode.BODYWEIGHT_PLUS_EXTERNAL -> byType("dip_belt")
            LoadMode.BODYWEIGHT, LoadMode.ASSISTED_ORDINAL -> null
            LoadMode.EXTERNAL -> e.requiresStation.mapNotNull { byType(it) }.firstOrNull { it.scheme_type != "bodyweight" }
                ?: CLASS_STATIONS.entries.firstOrNull { it.key in e.equipment }?.let { byType(it.value) }
        }
        val scheme = when {
            row != null -> parseScheme(row.load_scheme) ?: LoadScheme.Bodyweight
            mode == LoadMode.BODYWEIGHT || mode == LoadMode.ASSISTED_ORDINAL -> LoadScheme.Bodyweight
            mode == LoadMode.ASSISTED_MACHINE -> parseScheme(JsonText.canonical(types.first { it.key == "assisted_pullup_machine" }.defaultLoadScheme!!))!!
            else -> LoadScheme.Bodyweight // no loadable set known: the card falls back to free entry
        }
        return ResolvedEquipment(row, scheme)
    }

    /** Loadable options of [e] in [mode] at bodyweight [bwKg] (empty for free-entry equipment). */
    suspend fun loadables(e: CatalogExercise, mode: LoadMode, bwKg: Double?, params: ParamView): Pair<ResolvedEquipment, List<LoadPoint>> {
        val r = resolve(e, mode)
        if (mode == LoadMode.EXTERNAL && r.row == null) return r to emptyList()
        val beltDumbbells = if (r.scheme is LoadScheme.Belt && (r.scheme as LoadScheme.Belt).dumbbellsOnBelt) {
            db.gymEquipment().all().firstOrNull { it.equipment_type == "dumbbells" }
                ?.let { parseScheme(it.load_scheme) as? LoadScheme.Dumbbells }
                ?.values.orEmpty().filter { it <= 20.0 } // only light dumbbells fit on a belt chain
        } else {
            emptyList()
        }
        val ctxL = Loadables.Context(CatalogRepository.loadAttrs(e), bwKg, r.row?.id, r.row?.calibration_rev, beltDumbbells)
        val points = Loadables.enumerate(r.scheme, ctxL, params)
        return r to points
    }

    companion object {
        /** Equipment class (E45) -> the station that loads it when the catalog names none. */
        private val CLASS_STATIONS = linkedMapOf(
            "barbell" to "barbell", "dumbbell" to "dumbbells", "cable" to "cable_station", "smith" to "smith_machine",
        )

        fun parseScheme(text: String): LoadScheme? = try {
            LoadScheme.parse(Json.parseToJsonElement(text).jsonObject)
        } catch (e: IllegalArgumentException) {
            null
        }

        fun parseTypes(text: String): List<EquipmentType> =
            Json.parseToJsonElement(text).jsonObject.getValue("rows").jsonArray.map { e ->
                val o = e.jsonObject
                EquipmentType(
                    key = o.getValue("key").jsonPrimitive.content,
                    category = o.getValue("category").jsonPrimitive.content,
                    defaultSchemeType = (o["default_scheme_type"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    defaultLoadScheme = o["default_load_scheme"] as? JsonObject,
                    portable = o["portable"]?.jsonPrimitive?.content == "true",
                    labelEn = o.getValue("label_en").jsonPrimitive.content,
                    sort = o["sort"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                )
            }.sortedBy { it.sort }

        private fun resource(path: String): String =
            requireNotNull(EquipmentRepository::class.java.getResource(path)) { "missing $path" }.readText(Charsets.UTF_8)
    }
}

data class GymDraft(val name: String, val locationKeywords: List<String>, val openingHoursJson: String)

data class EquipmentDraft(
    val equipmentType: String,
    val label: String,
    val available: Boolean,
    val loadScheme: JsonObject,
    val calibrationSource: String = "default",
    val changedFromDefault: Boolean = false,
)

