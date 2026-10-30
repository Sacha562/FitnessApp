package dev.fitnessapp.data.engine

import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.pref.Pref
import dev.fitnessapp.algo.selection.pref.PrefEvent
import dev.fitnessapp.algo.selection.pref.PrefEventKind
import dev.fitnessapp.algo.selection.pref.PrefRow
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.pref.PrefValue
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.entity.SessionEventEntity
import dev.fitnessapp.data.repo.CatalogRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The facts the selection modules read (M3.6, D53): PREF-1's explicit rows and behaviour events per exercise slug, and
 * the stations the gym profile marks available. Events are read from the local `session_events` log.
 */
class SelectionFacts(private val db: FitnessDatabase, private val catalog: () -> CatalogRepository, private val params: ParamView) {
    /** PREF-1 per slug for every exercise with a row or an event; the rest are neutral (m 1.0). */
    suspend fun prefs(today: LocalDate): Map<String, PrefValue> {
        val cat = catalog()
        val rows = db.exercisePrefs().live().mapNotNull { r ->
            val slug = cat.byId(r.exercise_id)?.slug ?: return@mapNotNull null
            slug to PrefRow(PrefTier.of(r.preference), r.unavailable_until?.let(LocalDate::parse))
        }.toMap()
        val events = events(cat)
        val p = params.fork()
        return (rows.keys + events.keys).sorted().associateWith { slug -> Pref.evaluate(rows[slug], events[slug].orEmpty(), today, p) }
    }

    /** Slugs whose PREF-1 prompt is due ("Lower your preference for {exercise}?"), skipping tiers already at dislike or never. */
    suspend fun prompts(today: LocalDate): List<String> =
        prefs(today).filter { (_, v) -> v.prompt && v.tier != PrefTier.DISLIKE && v.tier != PrefTier.NEVER }.keys.toList()

    /** The available stations, or null when no gym profile exists yet (every station then counts as available). */
    suspend fun stations(): Set<String>? {
        val rows = db.gymEquipment().all()
        return if (rows.isEmpty()) null else rows.filter { it.available }.map { it.equipment_type }.toSortedSet()
    }

    /**
     * Stations busy at [at]'s weekday and hour (REASON-1's `occupied` rule, D53, D62): the finished gym visits on the
     * same weekday whose start lies within `reason.busy_slot_hours` of [at]'s hour, newest first, each marking the
     * stations of the exercises that were occupied in it (Occupied sheet, deferral or skip with `occupied`); a station
     * is busy when [Reason.busy] says so over those visits. [zone] is the user's zone.
     */
    suspend fun busyStations(at: Instant, zone: java.time.ZoneId): Set<String> {
        val p = params.fork()
        val local = at.atZone(zone)
        val slot = p.int("reason.busy_slot_hours")
        val visits = db.sessions().finishedChronological().filter { s ->
            val t = s.started_at?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return@filter false
            s.kind == "gym" && t.dayOfWeek == local.dayOfWeek && kotlin.math.abs(t.hour - local.hour) <= slot && t.toInstant().isBefore(at)
        }.sortedByDescending { it.started_at }
        if (visits.isEmpty()) return emptySet()
        val cat = catalog()
        val occupied = visits.map { v ->
            db.events().bySession(v.id).filter { ev ->
                val pl = runCatching { Json.parseToJsonElement(ev.payload).jsonObject }.getOrNull()
                when (ev.event_type) {
                    "swap_sheet_shown" -> pl?.get("trigger_reason")?.jsonPrimitive?.contentOrNull == "occupied"
                    "exercise_skipped" -> pl?.get("reason_code")?.jsonPrimitive?.contentOrNull == "occupied"
                    "exercise_deferred" -> true
                    else -> false
                }
            }.mapNotNull { ev -> ev.entity_id?.let { db.sessionExercises().get(it)?.exercise_id }?.let { cat.byId(it)?.requiresStation } }
                .flatten().toSet()
        }
        return occupied.flatten().toSortedSet().filter { st -> dev.fitnessapp.algo.prescription.reason.Reason.busy(occupied.map { st in it }, p) }.toSortedSet()
    }

    private suspend fun events(cat: CatalogRepository): Map<String, List<PrefEvent>> {
        val out = HashMap<String, MutableList<PrefEvent>>()
        val exById = HashMap<String, String?>()
        suspend fun exerciseOfRow(seId: String?): String? = seId?.let { id -> exById.getOrPut(id) { db.sessionExercises().get(id)?.exercise_id } }
        val all = db.events().ofTypes(listOf("exercise_added", "exercise_swapped", "exercise_skipped", "action_undone"))
        val undone = all.filter { it.event_type == "action_undone" }.mapNotNull { u ->
            runCatching { Json.parseToJsonElement(u.payload).jsonObject["undone_event_id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }.toSet()
        for (ev in all) {
            if (ev.event_type == "action_undone" || ev.id in undone) continue
            val p = runCatching { Json.parseToJsonElement(ev.payload).jsonObject }.getOrNull() ?: continue
            fun s(key: String) = p[key]?.jsonPrimitive?.contentOrNull
            val (exerciseId, kind, reason) = when (ev.event_type) {
                "exercise_added" -> Triple(s("exercise_id"), PrefEventKind.ADDED, null)
                "exercise_swapped" -> Triple(s("from_exercise_id"), PrefEventKind.SWAPPED_AWAY, s("reason_code") ?: "user")
                else -> Triple(exerciseOfRow(ev.entity_id), PrefEventKind.SKIPPED, s("reason_code"))
            }
            // A repeat's copied exercises are logged as `exercise_added` with source `repeat`: not a manual add.
            if (kind == PrefEventKind.ADDED && s("source") != "catalog") continue
            val slug = exerciseId?.let { cat.byId(it)?.slug } ?: continue
            out.getOrPut(slug) { ArrayList() } += PrefEvent(kind, reason, localDate(ev))
        }
        return out
    }

    private fun localDate(ev: SessionEventEntity): LocalDate =
        Instant.ofEpochMilli(ev.occurred_at).atOffset(ZoneOffset.ofTotalSeconds(ev.tz_offset_min * 60)).toLocalDate()
}
