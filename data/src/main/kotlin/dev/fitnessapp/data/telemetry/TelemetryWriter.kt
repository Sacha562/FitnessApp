package dev.fitnessapp.data.telemetry

import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.entity.SessionEventEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.WriteContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** One event to write; the payload holds codes, numbers and ids only, never free text (C24, C39). */
data class EventDraft(
    val type: String,
    val sessionId: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val screen: String? = null,
    val moduleId: String? = null,
    val moduleVersion: String? = null,
    val recommendationId: String? = null,
    val payload: Map<String, JsonElement> = emptyMap(),
    val occurredAtMs: Long? = null,
)

/** The event-type registry (DATA_MODEL 9.8.1) as bundled in `:algo` resources. */
class EventRegistry(text: String) {
    private val rows: Map<String, Pair<String, Set<String>>> =
        Json.parseToJsonElement(text).jsonObject.getValue("rows").jsonArray.associate { e ->
            val o = e.jsonObject
            o.getValue("event_type").jsonPrimitive.content to
                (o.getValue("category").jsonPrimitive.content to o.getValue("payload_keys").jsonArray.map { it.jsonPrimitive.content }.toSet())
        }

    fun category(type: String): String? = rows[type]?.first
    fun keys(type: String): Set<String> = rows[type]?.second.orEmpty()

    companion object {
        fun bundled(): EventRegistry = EventRegistry(
            requireNotNull(EventRegistry::class.java.getResource("/fitness/reference/session_event_types.json")) {
                "event registry missing"
            }.readText(Charsets.UTF_8),
        )
    }
}

/**
 * Writes `session_events` rows (UX 19.10, ANDROID.md 8.4): inside the caller's Room transaction, with the
 * registry's category, payload keys filtered to the registry (unknown keys are dropped, DATA_MODEL 16.3),
 * and an outbox entry. Unknown event types are not written. An entity type outside the server's list ([ENTITY_TYPES])
 * is written as `none` without an entity id, so the event still uploads instead of dead-lettering.
 */
class TelemetryWriter(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val ctx: WriteContext,
    private val registry: EventRegistry,
) {
    /** Returns the event id, or null when the type is not in the registry or nobody is signed in. */
    suspend fun write(draft: EventDraft): String? {
        val category = registry.category(draft.type) ?: return null
        val user = ctx.userId() ?: return null
        val allowed = registry.keys(draft.type)
        val payload = JsonObject(draft.payload.filterKeys { it in allowed }.toSortedMap())
        val at = draft.occurredAtMs ?: ctx.nowMs()
        val id = ctx.ids.next()
        val known = draft.entityType == null || draft.entityType in ENTITY_TYPES
        db.events().insert(
            SessionEventEntity(
                id = id,
                user_id = user,
                device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion,
                event_type = draft.type,
                category = category,
                occurred_at = at,
                tz_offset_min = ctx.zone().rules.getOffset(Instant.ofEpochMilli(at)).totalSeconds / 60,
                screen = draft.screen,
                session_id = draft.sessionId,
                entity_type = if (known) draft.entityType else "none",
                entity_id = if (known) draft.entityId else null,
                recommendation_id = draft.recommendationId,
                module_id = draft.moduleId,
                module_version = draft.moduleVersion,
                payload = JsonText.canonical(payload),
            ),
        )
        outbox.enqueue("session_events", id, ctx.nowMs())
        return id
    }

    companion object {
        /** `session_events.entity_type` values the server accepts (DATA_MODEL 9.8.2, `session_events_entity_type_check`). */
        val ENTITY_TYPES: Set<String> = setOf(
            "set", "session_exercise", "session", "block", "recommendation", "calendar_event", "enrollment", "exercise",
            "gym_equipment", "wellness_log", "export", "none",
        )
    }
}
