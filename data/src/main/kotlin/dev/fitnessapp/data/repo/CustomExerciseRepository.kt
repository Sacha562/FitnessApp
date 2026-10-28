package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.CustomExerciseEntity
import dev.fitnessapp.data.db.entity.ExerciseStimulusEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer
import java.util.Locale

/**
 * Custom exercises (UX 13.5, CAT-1 10.9, D27, D53): one `exercises` row (MIX class, the user's own) and its
 * `exercise_stimulus` rows, synced like other user data, and merged into [catalog] so every module sees them.
 * Custom slugs are `custom_<name>`, so they never collide with a global slug.
 */
class CustomExerciseRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val catalog: CatalogRepository,
) {
    fun observe(): Flow<List<Pair<String, CatalogExercise>>> =
        combine(db.customExercises().observeLive(), db.exerciseStimulus().observeLive()) { rows, stim -> toCatalog(rows, stim) }

    /** Loads the rows once (app start), then keeps [catalog] current with every change, pulls included. */
    suspend fun attach(scope: CoroutineScope) {
        catalog.setCustom(toCatalog(db.customExercises().live(), db.exerciseStimulus().live()))
        scope.launch { observe().collect { catalog.setCustom(it) } }
    }

    /** The first free `custom_<name>` slug. */
    suspend fun slugFor(nameEn: String): String {
        val base = "custom_" + Normalizer.normalize(nameEn, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "exercise" }.take(50)
        var slug = base
        var n = 2
        while (db.customExercises().bySlug(slug) != null || catalog.bySlug(slug) != null) slug = base + "_" + n++
        return slug
    }

    /** Whether another exercise already has this English name (case- and accent-insensitive). */
    fun nameTaken(nameEn: String): Boolean {
        val f = fold(nameEn)
        return catalog.exercises.any { fold(it.nameEn) == f }
    }

    /** Saves a validated copy; returns its id. */
    suspend fun create(e: CatalogExercise, prototypeSlug: String, coefficientsEdited: Boolean): String {
        val uid = ctx.requireUser()
        val id = ctx.ids.next()
        val now = ctx.nowMs()
        db.withTransaction {
            val row = toEntity(e, id, uid, catalog.bySlug(prototypeSlug)?.let(catalog::idOf))
            db.customExercises().upsert(row)
            outbox.enqueue("exercises", id, now)
            for ((region, coeff) in e.stimulus.toSortedMap()) {
                val sid = ctx.ids.next()
                db.exerciseStimulus().upsert(
                    ExerciseStimulusEntity(
                        id = sid, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                        exercise_id = id, region_id = region, coeff = Scaled.fromDouble(coeff, 2),
                        evidence = if (coefficientsEdited) "user" else "expert-opinion",
                    ),
                )
                outbox.enqueue("exercise_stimulus", sid, now)
            }
            telemetry.write(
                EventDraft(
                    type = "exercise_custom_created", screen = "exercises.custom", entityType = "exercise", entityId = id,
                    payload = mapOf(
                        "exercise_id" to JsonPrimitive(id), "pattern" to JsonPrimitive(e.pattern),
                        "equipment" to JsonArray(e.equipment.map(::JsonPrimitive)), "coefficients_edited" to JsonPrimitive(coefficientsEdited),
                    ),
                ),
            )
        }
        catalog.setCustom(toCatalog(db.customExercises().live(), db.exerciseStimulus().live()))
        scheduler.request()
        return id
    }

    private fun toEntity(e: CatalogExercise, id: String, uid: String, prototypeId: String?) = CustomExerciseEntity(
        id = id, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
        slug = e.slug, name_en = e.nameEn, name_nl = e.nameNl, aliases = e.aliases, pattern = e.pattern, family = e.family,
        equipment = e.equipment, requires_station = e.requiresStation, load_mode = e.loadMode, allowed_load_modes = e.allowedLoadModes,
        bw_fraction = Scaled.fromDouble(e.bwFraction, 4), implement_base_kg = e.implementBaseKg?.let { Scaled.fromDouble(it, 2) },
        laterality = e.laterality, measure = e.measure, contraction = e.contraction, hold_class = e.holdClass, ecc_emphasis = e.eccEmphasis,
        mechanics = e.mechanics, curve_class = e.curveClass, rest_class = e.restClass, safety_class = e.safetyClass, grip = e.grip,
        stretch_emphasis = e.stretchEmphasis, stability = e.stability, skill = e.skill, axial = e.axial, systemic = e.systemic, sfr = e.sfr,
        setup_s = e.setupS, grip_demand = e.gripDemand,
        js_elbow_medial = e.js("elbow_medial"), js_elbow_lateral = e.js("elbow_lateral"), js_shoulder_ant = e.js("shoulder_ant"),
        js_shoulder_overhead = e.js("shoulder_overhead"), js_wrist = e.js("wrist"), js_lumbar = e.js("lumbar"), js_knee = e.js("knee"),
        js_hip = e.js("hip"), tags = e.tags, flags_offered = e.flagsOffered, external_refs = e.externalRefs.toString(),
        evidence_note = e.evidenceNote, prototype_exercise_id = prototypeId, catalog_version = catalog.version,
    )

    companion object {
        fun toCatalog(rows: List<CustomExerciseEntity>, stimulus: List<ExerciseStimulusEntity>): List<Pair<String, CatalogExercise>> {
            val byExercise = stimulus.groupBy { it.exercise_id }
            return rows.filter { it.deleted_at == null && it.retired_at == null }.map { r ->
                r.id to CatalogExercise(
                    slug = r.slug, nameEn = r.name_en, nameNl = r.name_nl, aliases = r.aliases, pattern = r.pattern, family = r.family,
                    equipment = r.equipment, requiresStation = r.requires_station, loadMode = r.load_mode, allowedLoadModes = r.allowed_load_modes,
                    bwFraction = Scaled.toDouble(r.bw_fraction, 4), implementBaseKg = r.implement_base_kg?.let { Scaled.toDouble(it, 2) },
                    laterality = r.laterality, measure = r.measure, contraction = r.contraction, holdClass = r.hold_class, eccEmphasis = r.ecc_emphasis,
                    mechanics = r.mechanics, curveClass = r.curve_class, restClass = r.rest_class, safetyClass = r.safety_class, grip = r.grip,
                    stretchEmphasis = r.stretch_emphasis, stability = r.stability, skill = r.skill, axial = r.axial, systemic = r.systemic, sfr = r.sfr,
                    setupS = r.setup_s, gripDemand = r.grip_demand,
                    jointStress = linkedMapOf(
                        "elbow_medial" to r.js_elbow_medial, "elbow_lateral" to r.js_elbow_lateral, "shoulder_ant" to r.js_shoulder_ant,
                        "shoulder_overhead" to r.js_shoulder_overhead, "wrist" to r.js_wrist, "lumbar" to r.js_lumbar, "knee" to r.js_knee, "hip" to r.js_hip,
                    ),
                    tags = r.tags, flagsOffered = r.flags_offered,
                    externalRefs = runCatching { Json.parseToJsonElement(r.external_refs).jsonObject }.getOrDefault(Json.parseToJsonElement(JsonText.EMPTY_OBJECT).jsonObject),
                    evidenceNote = r.evidence_note,
                    stimulus = byExercise[r.id].orEmpty().filter { it.deleted_at == null }.associate { it.region_id to Scaled.toDouble(it.coeff, 2) }.toSortedMap(),
                )
            }
        }

        private fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }
}
