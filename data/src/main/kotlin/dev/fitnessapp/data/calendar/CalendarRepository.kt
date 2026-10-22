package dev.fitnessapp.data.calendar

import androidx.room.withTransaction
import dev.fitnessapp.algo.challenge.calendar.CalendarLink
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.calendar.Anchor
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.CalendarOverride
import dev.fitnessapp.algo.core.calendar.MatchMethod
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.entity.CalendarEventEntity
import dev.fitnessapp.data.db.entity.CalendarInstanceEntity
import dev.fitnessapp.data.db.entity.CalendarLinkEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Storage of the calendar (ANDROID 16, DATA_MODEL 9.7.3): the local instance cache with the latest classification,
 * the synced `calendar_events` rows of classified gym, challenge and run events (busy events never leave the phone),
 * the user's overrides (C20), the local `calendar_link` rows and the calendar telemetry of DATA_MODEL 9.8.1.
 */
class CalendarRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val sync: SyncScheduler,
) {
    /** Classified kinds stored on the server (E21; `busy` never, `ignored` only as an override). */
    private val stored = setOf(AnchorKind.CHALLENGE_SESSION, AnchorKind.PLANNED_GYM, AnchorKind.RUN)

    fun eventId(userId: String, instanceKey: String): String = Ids.fitness("calendar_events:$userId:$instanceKey").toString()

    /**
     * Stores one refresh: the cache rows of the window, `calendar_classified` for each instance whose classification
     * changed, and the synced rows of classified events (only when their content changed, so a refresh that finds the
     * same calendar writes nothing). Rows of the window that the provider no longer returned get `missing_since`.
     */
    suspend fun storeRefresh(
        instances: List<CalendarInstance>,
        identities: Map<Long, String>,
        anchors: List<Anchor>,
        windowFromMs: Long,
        windowToMs: Long,
    ) {
        val user = ctx.userId()
        val now = ctx.nowMs()
        val zone = ctx.zone()
        val byKey = instances.associateBy { it.instanceKey }
        var wrote = false
        db.withTransaction {
            val old = db.calendarInstances().between(windowFromMs, windowToMs).associateBy { it.instance_key }
            val rows = anchors.mapNotNull { a ->
                val inst = byKey[a.instanceKey] ?: return@mapNotNull null
                CalendarInstanceEntity(
                    instance_key = a.instanceKey, series_key = a.seriesKey, calendar_identity_sha256 = identities[inst.calendarId].orEmpty(),
                    calendar_id = inst.calendarId, event_id = inst.eventId, begin_ms = inst.beginUtc.toEpochMilli(), end_ms = inst.endUtc.toEpochMilli(),
                    title = inst.title, description = inst.description, location = inst.location, all_day = inst.allDay, status = inst.status,
                    declined = inst.declined, classified_kind = a.kind.code, slot = a.slot, method = a.method?.code, score = a.score,
                    match_milli = a.matchScore?.let { Math.round(it * MILLI).toInt() }, gym_type = a.gymType, attention = a.attention,
                    needs_confirm = a.needsConfirm, features_json = JsonArray(a.features.map { JsonPrimitive(it) }).toString(), last_seen_ms = now,
                )
            }
            db.calendarInstances().upsertAll(rows)
            db.calendarInstances().deleteUnseen(windowFromMs, windowToMs, now)
            db.calendarInstances().purgeBefore(now - PURGE_MS)
            if (user == null) return@withTransaction
            for (a in anchors) {
                val prev = old[a.instanceKey]
                val changed = prev == null || prev.classified_kind != a.kind.code || prev.slot != a.slot || prev.method != a.method?.code ||
                    prev.score != a.score || prev.features_json != JsonArray(a.features.map { JsonPrimitive(it) }).toString()
                if (changed) {
                    telemetry.write(
                        EventDraft(
                            type = "calendar_classified", entityType = "calendar_event", moduleId = "calendar.classify", moduleVersion = a.classifierVersion,
                            payload = buildMap {
                                put("instance_key", JsonPrimitive(a.instanceKey))
                                put("kind", JsonPrimitive(a.kind.code))
                                a.slot?.let { put("slot", JsonPrimitive(it)) }
                                a.gymType?.let { put("type", JsonPrimitive(it)) }
                                put("score", JsonPrimitive(a.matchScore ?: a.score.toDouble()))
                                put("features", JsonArray(a.features.map { JsonPrimitive(it) }))
                                put("classifier_version", JsonPrimitive(a.classifierVersion))
                                put("method", JsonPrimitive((a.method ?: MatchMethod.SCORE).code))
                            },
                        ),
                    )
                    wrote = true
                }
                val inst = byKey[a.instanceKey] ?: continue
                val id = eventId(user, a.instanceKey)
                val existing = db.calendarEvents().byInstanceKey(a.instanceKey)
                if (a.kind !in stored && existing?.override_kind == null) {
                    if (existing != null && existing.deleted_at == null) {
                        upsert(existing.copy(deleted_at = now, client_updated_at = ctx.nextClientMs()))
                        wrote = true
                    }
                    continue
                }
                val date = a.localDate
                val ignored = a.kind !in stored
                val base = existing ?: CalendarEventEntity(
                    id = id, user_id = user, client_updated_at = 0, device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                    instance_key = a.instanceKey, source_device_id = ctx.device.deviceId(), classified_kind = a.kind.code,
                    starts_at = 0, ends_at = 0, local_date = date.toString(), timezone = zone.id, iso_week = Week.isoWeek(date),
                    provider_status = inst.status, classifier_version = a.classifierVersion, match_method = MatchMethod.SCORE.code, last_seen_at = now,
                )
                val marker = a.marker?.render()
                val next = base.copy(
                    deleted_at = null, series_key = a.seriesKey, classified_kind = if (ignored) AnchorKind.IGNORED.code else a.kind.code,
                    title = if (ignored) null else inst.title?.take(MAX_TEXT), location = if (ignored) null else inst.location?.take(MAX_TEXT),
                    starts_at = inst.beginUtc.toEpochMilli(), ends_at = maxOf(inst.endUtc.toEpochMilli(), inst.beginUtc.toEpochMilli() + 1),
                    all_day = inst.allDay, local_date = date.toString(), timezone = zone.id, iso_week = Week.isoWeek(date),
                    provider_status = inst.status, challenge_slot = if (a.kind == AnchorKind.CHALLENGE_SESSION) a.slot else null,
                    enrollment_id = if (a.kind == AnchorKind.CHALLENGE_SESSION) a.enrollmentId else null,
                    planned_families = families(a.gymType), run_intensity = runIntensity(a), run_minutes = a.runMinutes,
                    classifier_version = a.classifierVersion, match_method = (a.method ?: MatchMethod.SCORE).code,
                    score = (a.matchScore ?: a.score.toDouble()).let { Math.round(it * MILLI) },
                    features = a.features, app_written = base.app_written || marker != null,
                    marker = marker ?: base.marker, attention = attentionOf(a, base), missing_since = null,
                )
                if (existing == null || contentDiffers(existing, next)) {
                    upsert(next.copy(client_updated_at = ctx.nextClientMs(), last_seen_at = now))
                    wrote = true
                }
            }
            for (e in db.calendarEvents().between(Week.localDate(Instant.ofEpochMilli(windowFromMs), zone).toString(), Week.localDate(Instant.ofEpochMilli(windowToMs), zone).toString())) {
                if (e.instance_key in byKey || e.missing_since != null || e.source_device_id != ctx.device.deviceId()) continue
                upsert(e.copy(missing_since = now, client_updated_at = ctx.nextClientMs()))
                wrote = true
            }
        }
        if (wrote) sync.request()
    }

    /** Every override as the classifier reads them: by instance key, plus by series key for series-scoped ones. */
    suspend fun overrides(seriesScoped: Set<String>): Map<String, CalendarOverride> {
        val out = sortedMapOf<String, CalendarOverride>()
        for (e in db.calendarEvents().overrides()) {
            val ov = CalendarOverride(
                kind = e.override_kind!!,
                slot = e.override_slot, enrollmentId = e.enrollment_id, gymType = e.planned_families.takeIf { it.isNotEmpty() }?.let(::typeOf),
            )
            out[e.instance_key] = ov
            val s = e.series_key
            if (s != null && s in seriesScoped) out[s] = ov
        }
        return out
    }

    /**
     * Stores a correction from the event sheet (UX 16.11, C20): [kind] is `challenge_session` (with [slot]),
     * `planned_gym` (with [gymType]), `run` or `ignored` ("not a gym session"); null removes the override.
     */
    suspend fun setOverride(anchor: Anchor, inst: CalendarInstanceEntity, kind: String?, slot: String?, enrollmentId: String?, gymType: String?, scope: String, from: String) {
        val user = ctx.requireUser()
        val now = ctx.nowMs()
        val zone = ctx.zone()
        db.withTransaction {
            val existing = db.calendarEvents().byInstanceKey(anchor.instanceKey)
            val date = anchor.localDate
            val base = existing ?: CalendarEventEntity(
                id = eventId(user, anchor.instanceKey), user_id = user, client_updated_at = 0, device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion, instance_key = anchor.instanceKey, series_key = anchor.seriesKey, source_device_id = ctx.device.deviceId(),
                classified_kind = kind ?: anchor.kind.code, starts_at = anchor.beginUtc.toEpochMilli(),
                ends_at = maxOf(anchor.endUtc.toEpochMilli(), anchor.beginUtc.toEpochMilli() + 1), local_date = date.toString(), timezone = zone.id,
                iso_week = Week.isoWeek(date), provider_status = inst.status, classifier_version = anchor.classifierVersion,
                match_method = MatchMethod.MANUAL.code, last_seen_at = now,
            )
            val ignored = kind == AnchorKind.IGNORED.code
            val row = if (kind == null) {
                base.copy(override_kind = null, override_slot = null, override_at = null, deleted_at = if (anchor.kind in stored) null else now)
            } else {
                base.copy(
                    deleted_at = null, override_kind = kind, override_slot = slot, override_at = now, classified_kind = kind,
                    title = if (ignored) null else inst.title?.take(MAX_TEXT), location = if (ignored) null else inst.location?.take(MAX_TEXT),
                    challenge_slot = if (kind == AnchorKind.CHALLENGE_SESSION.code) slot else null,
                    enrollment_id = if (kind == AnchorKind.CHALLENGE_SESSION.code) enrollmentId else null,
                    planned_families = if (kind == AnchorKind.PLANNED_GYM.code) families(gymType) else emptyList(),
                    run_intensity = if (kind == AnchorKind.RUN.code) (base.run_intensity ?: "easy") else null,
                    run_minutes = if (kind == AnchorKind.RUN.code) (base.run_minutes ?: minutes(anchor)) else null,
                    match_method = MatchMethod.MANUAL.code,
                )
            }
            upsert(row.copy(client_updated_at = ctx.nextClientMs()))
            telemetry.write(
                EventDraft(
                    type = "calendar_correction", entityType = "calendar_event", entityId = row.id,
                    payload = mapOf(
                        "instance_key" to JsonPrimitive(anchor.instanceKey), "from" to JsonPrimitive(from),
                        "to" to JsonPrimitive(kind?.let { k -> slot?.let { "$k:$it" } ?: gymType?.let { "$k:$it" } ?: k } ?: "none"),
                        "scope" to JsonPrimitive(scope),
                    ),
                ),
            )
        }
        sync.request()
    }

    /** Marks an unmarked event adopted (D11): an `adopt` override that still lets the matcher decide the slot. */
    suspend fun markAdopted(anchor: Anchor, inst: CalendarInstanceEntity, slot: String) {
        val user = ctx.requireUser()
        db.withTransaction {
            val existing = db.calendarEvents().byInstanceKey(anchor.instanceKey)
            if (existing != null) {
                upsert(existing.copy(adopted_at = ctx.nowMs(), client_updated_at = ctx.nextClientMs()))
            }
            telemetry.write(
                EventDraft(
                    type = "calendar_event_adopted", entityType = "calendar_event", entityId = eventId(user, anchor.instanceKey),
                    payload = mapOf("slot" to JsonPrimitive(slot), "instance_key" to JsonPrimitive(anchor.instanceKey)),
                ),
            )
        }
        sync.request()
    }

    /**
     * The post-hoc link of a finished session to its calendar event (C35, `auto_window`): set once on the synced row
     * when neither the event nor the session is linked yet; a manual link is never replaced.
     */
    suspend fun linkSession(instanceKey: String, sessionId: String) {
        var wrote = false
        db.withTransaction {
            val row = db.calendarEvents().byInstanceKey(instanceKey) ?: return@withTransaction
            if (row.linked_session_id != null || row.deleted_at != null) return@withTransaction
            if (db.calendarEvents().linkedTo(sessionId) != null) return@withTransaction
            upsert(row.copy(linked_session_id = sessionId, link_method = "auto_window", client_updated_at = ctx.nextClientMs()))
            telemetry.write(
                EventDraft(
                    type = "calendar_link_changed", entityType = "calendar_event", entityId = row.id, sessionId = sessionId,
                    payload = mapOf("to_instance_key" to JsonPrimitive(instanceKey), "method" to JsonPrimitive("auto_window")),
                ),
            )
            wrote = true
        }
        if (wrote) sync.request()
    }

    suspend fun links(): List<CalendarLink> = db.calendarLinks().all().map { it.toLink() }

    /** Replaces the links of [enrollmentId] with [links] (ANDROID 16.6); a re-keyed link (C36 rewrite) keeps its row id. */
    suspend fun saveLinks(enrollmentId: String, links: List<CalendarLink>, identity: String) {
        val now = ctx.nowMs()
        db.withTransaction {
            val old = db.calendarLinks().all().filter { it.marker_uuid == enrollmentId }.associateBy { it.slot_key }
            for (l in links.filter { it.markerUuid == enrollmentId }) {
                val prev = old[l.slotKey]
                    ?: old.values.firstOrNull { it.event_id != null && it.event_id == l.eventId && it.slot_key !in links.map { x -> x.slotKey } }
                if (prev != null && prev.slot_key != l.slotKey) db.calendarLinks().delete(prev.id)
                val row = CalendarLinkEntity(
                    id = prev?.id ?: ctx.ids.next(), marker_uuid = l.markerUuid, slot_key = l.slotKey, kind = l.kind,
                    calendar_identity_sha256 = prev?.calendar_identity_sha256 ?: identity, event_id = l.eventId, last_written_title = l.lastTitle,
                    last_written_desc_sha256 = l.lastDescSha256, last_written_dtstart_ms = l.lastStart.toEpochMilli(),
                    last_written_dtend_ms = l.lastEnd.toEpochMilli(), state = l.state, created_at_ms = prev?.created_at_ms ?: now, updated_at_ms = now,
                )
                if (prev == null || prev.copy(updated_at_ms = now) != row) db.calendarLinks().upsert(row)
            }
        }
    }

    /**
     * Writes the link states onto the synced rows of the app's events (DATA_MODEL 9.7.3 mapping, OI-26) on every refresh:
     * the row of a live event is found by its marker ([liveByMarker], marker line -> instance key) or, for a released
     * event, by its provider id ([liveByEvent]); a vanished event by its last marker among the rows the provider no longer
     * returned. [updated] holds the slot keys an update op changed in this refresh. Only rows that change are written.
     */
    suspend fun applyLinkStates(links: List<CalendarLink>, liveByMarker: Map<String, String>, liveByEvent: Map<Long, String>, updated: Set<String>) {
        if (ctx.userId() == null) return
        var wrote = false
        db.withTransaction {
            for (l in links) {
                val marker = "[fitapp:v1 ${l.markerUuid} ${l.slotKey}]"
                val key = liveByMarker[marker] ?: l.eventId?.takeIf { l.state == CalendarLink.RELEASED }?.let { liveByEvent[it] }
                val e = key?.let { db.calendarEvents().byInstanceKey(it) } ?: db.calendarEvents().missingWithMarker(marker) ?: continue
                val (writeState, attention) = when (l.state) {
                    CalendarLink.ACTIVE -> when {
                        l.slotKey in updated -> "updated"
                        e.write_state == "none" || e.write_state == "pending_create" -> "created"
                        else -> e.write_state
                    } to null
                    CalendarLink.USER_MOVED -> e.write_state to "moved_externally"
                    CalendarLink.USER_EDITED, CalendarLink.RELEASED -> e.write_state to "edited_externally"
                    CalendarLink.USER_DELETED -> e.write_state to "deleted_externally"
                    else -> "deleted" to null
                }
                val next = e.copy(write_state = writeState, attention = attention, app_written = true, marker = e.marker ?: marker)
                if (next != e) {
                    upsert(next.copy(client_updated_at = ctx.nextClientMs()))
                    wrote = true
                }
            }
        }
        if (wrote) sync.request()
    }

    /** A calendar telemetry event without a domain write (`calendar_event_written`, `calendar_write_failed`, ...). */
    suspend fun log(type: String, payload: Map<String, Any?>, entityId: String? = null) {
        if (ctx.userId() == null) return
        db.withTransaction {
            telemetry.write(
                EventDraft(
                    type = type, entityType = if (entityId != null) "calendar_event" else "none", entityId = entityId,
                    moduleId = "challenge.calendar".takeIf { type.startsWith("calendar_event") || type == "calendar_write_failed" },
                    payload = payload.mapNotNull { (k, v) -> json(v)?.let { k to it } }.toMap(),
                ),
            )
        }
        sync.request()
    }

    private fun json(v: Any?): JsonElement? = when (v) {
        null -> null
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.mapNotNull { json(it) })
        else -> JsonPrimitive(v.toString())
    }

    private suspend fun upsert(row: CalendarEventEntity) {
        db.calendarEvents().upsert(row)
        outbox.enqueue("calendar_events", row.id, ctx.nowMs())
    }

    private fun contentDiffers(a: CalendarEventEntity, b: CalendarEventEntity): Boolean =
        a.copy(client_updated_at = 0, last_seen_at = 0, server_updated_at = null) != b.copy(client_updated_at = 0, last_seen_at = 0, server_updated_at = null)

    private fun attentionOf(a: Anchor, base: CalendarEventEntity): String? = when {
        a.attention != null -> a.attention
        base.app_written -> base.attention
        a.kind == AnchorKind.CHALLENGE_SESSION && a.marker == null && base.adopted_at == null -> "unmarked_needs_adopt"
        else -> null
    }

    private fun runIntensity(a: Anchor): String? {
        val i = a.runIntensity ?: return null
        return if (a.runLong && i.code == "easy") "long" else i.code
    }

    private fun minutes(a: Anchor): Int = ((a.endUtc.toEpochMilli() - a.beginUtc.toEpochMilli()) / MS_PER_MIN).toInt().coerceIn(1, MAX_RUN_MIN)

    companion object {
        private const val MILLI = 1000.0
        private const val MAX_TEXT = 200
        private const val MS_PER_MIN = 60_000L
        private const val MAX_RUN_MIN = 600
        /** ANDROID 8.3: cache rows older than 60 days are purged. */
        private const val PURGE_MS = 60L * 24 * 3600 * 1000

        /** `planned_families` of a session type (`push`, `pull`, `legs`, `core`, `full`; the server CHECK). */
        fun families(type: String?): List<String> = when (type) {
            null, "AUTO" -> emptyList()
            "FULL" -> listOf("full")
            "UPPER" -> listOf("pull", "push")
            else -> type.split('+').map { it.lowercase() }.filter { it in setOf("push", "pull", "legs", "core") }.sorted()
        }

        fun typeOf(families: List<String>): String = when {
            "full" in families -> "FULL"
            families.sorted() == listOf("pull", "push") -> "UPPER"
            else -> families.map { it.uppercase() }.sortedBy { listOf("PUSH", "PULL", "LEGS", "CORE").indexOf(it) }.joinToString("+")
        }
    }
}

private fun CalendarLinkEntity.toLink() = CalendarLink(
    marker_uuid, slot_key, kind, event_id, state, last_written_title, last_written_desc_sha256,
    Instant.ofEpochMilli(last_written_dtstart_ms), Instant.ofEpochMilli(last_written_dtend_ms),
)
