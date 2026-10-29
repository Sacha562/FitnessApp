package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.entity.WellnessLogEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** One prompt of a session's plan: its code (`readiness`, `soreness`, ...), the soreness group, and whether it is reduced. */
data class PromptShown(val prompt: String, val group: String? = null, val reduced: Boolean = false)

/** A prompt not answered: `user` (shown, left empty), `budget` or `reduced` (not shown). */
data class PromptNotAnswered(val prompt: String, val group: String?, val cause: String)

/** One `prompt_shown`, `prompt_skipped` or Reset event, the raw history `ui.prompts` folds (UX 19.4). */
data class PromptEvent(val sessionId: String?, val type: String, val prompt: String, val cause: String?, val at: Long)

/**
 * The F15 feedback prompts' storage (UX 16.3, 19.4): readiness and soreness answers as `wellness_logs`, and the
 * `prompt_shown` / `prompt_answered` / `prompt_skipped` events. The adaptive state (skip streaks) is not stored: it is
 * rebuilt from these events (DATA_MODEL 8.3 `prompt_state` is a cache of them). The elbow check keeps its own write
 * ([dev.fitnessapp.data.challenge.ChallengeSessionRepository.elbowCheck]) because the challenge rules run on it before
 * the session is materialized; sRPE and pain keep theirs in [SessionRepository].
 */
class PromptRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
) {
    /**
     * The pre-session sheet of [sessionId]: `prompt_shown` per shown item, the readiness level (`worse`, `normal`,
     * `better`) and soreness codes (`never_sore`, `healed`, `still_sore`) as wellness logs with `prompt_answered`, and
     * `prompt_skipped` for the rest.
     */
    suspend fun recordPre(
        sessionId: String,
        shown: List<PromptShown>,
        readiness: String?,
        soreness: Map<String, String>,
        elbowAnswered: Boolean,
        notShown: List<PromptNotAnswered>,
        latencyMs: Long?,
    ) {
        db.withTransaction {
            shownEvents(sessionId, "pre", shown)
            val skipped = ArrayList(notShown)
            for (s in shown) {
                when (s.prompt) {
                    "readiness" -> readiness?.let { level ->
                        val w = wellness(sessionId, "readiness").copy(readiness_level = level)
                        save(w)
                        answered(sessionId, "readiness", null, level, w.id, latencyMs)
                    } ?: skipped.add(PromptNotAnswered("readiness", null, "user"))
                    "soreness" -> soreness[s.group]?.let { code ->
                        val w = wellness(sessionId, "soreness").copy(soreness_muscle_group_id = s.group, soreness_code = code)
                        save(w)
                        answered(sessionId, "soreness", s.group, code, w.id, latencyMs)
                    } ?: skipped.add(PromptNotAnswered("soreness", s.group, "user"))
                    "elbow_check" -> if (!elbowAnswered) skipped.add(PromptNotAnswered("elbow_check", null, "user"))
                }
            }
            skippedEvents(sessionId, skipped)
        }
        scheduler.request()
    }

    /** The Summary's quick check (UX 12.2): `prompt_shown` for its items, `prompt_skipped` for the ones left empty. */
    suspend fun recordPost(sessionId: String, shown: List<PromptShown>, answered: Set<String>, notShown: List<PromptNotAnswered>) {
        db.withTransaction {
            shownEvents(sessionId, "post", shown)
            skippedEvents(sessionId, notShown + shown.filter { it.prompt !in answered }.map { PromptNotAnswered(it.prompt, it.group, "user") })
        }
        scheduler.request()
    }

    /**
     * The elbow check without a running challenge (UX 16.3): the wellness log and `prompt_answered`. With a challenge,
     * [dev.fitnessapp.data.challenge.ChallengeSessionRepository.elbowCheck] writes it and runs the challenge's rules.
     */
    suspend fun elbowOnly(score: Int, symptoms: List<String>) {
        db.withTransaction {
            val w = wellness(null, "elbow_check").copy(elbow_score = score, symptom_codes = symptoms.distinct().sorted())
            save(w)
            telemetry.write(
                EventDraft(
                    type = "prompt_answered", entityType = "wellness_log", entityId = w.id,
                    payload = mapOf("prompt" to JsonPrimitive("elbow_check"), "value_code" to JsonPrimitive(score.toString()), "wellness_log_id" to JsonPrimitive(w.id)),
                ),
            )
        }
        scheduler.request()
    }

    /** The pain prompt's "None" answer (no wellness row: nothing to report). */
    suspend fun painNone(sessionId: String) {
        db.withTransaction { answered(sessionId, "joint_pain", null, "none", null, null) }
        scheduler.request()
    }

    /** You > Prompts "Reset" (UX 15.8): the prompt is asked every session again. */
    suspend fun reset(prompt: String) {
        db.withTransaction {
            telemetry.write(
                EventDraft(
                    type = "settings_changed", entityType = "none", screen = "you.prompts",
                    payload = mapOf("key" to JsonPrimitive(RESET_PREFIX + prompt), "from" to JsonPrimitive("reduced"), "to" to JsonPrimitive("normal")),
                ),
            )
        }
        scheduler.request()
    }

    /** The prompt events in time order, for the adaptive state. */
    suspend fun history(): List<PromptEvent> = db.events().ofTypes(listOf("prompt_shown", "prompt_skipped", "settings_changed")).mapNotNull { e ->
        val p = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(e.payload).jsonObject }.getOrNull() ?: return@mapNotNull null
        fun str(k: String) = p[k]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
        when (e.event_type) {
            "settings_changed" -> str("key")?.takeIf { it.startsWith(RESET_PREFIX) }?.let { PromptEvent(null, "reset", it.removePrefix(RESET_PREFIX), null, e.occurred_at) }
            else -> str("prompt")?.let { PromptEvent(e.session_id, e.event_type, it, str("cause"), e.occurred_at) }
        }
    }

    /** The newest pain report, for the pain guard. */
    suspend fun lastPainAt(): Instant? = db.wellness().painLogs().maxOfOrNull { it.observed_at }?.let(Instant::ofEpochMilli)

    /** Whether the elbow check was answered on [localDate] (it is asked once a day). */
    suspend fun elbowCheckedOn(localDate: String): Boolean = db.wellness().countKindOn("elbow_check", localDate) > 0

    private suspend fun shownEvents(sessionId: String, phase: String, shown: List<PromptShown>) {
        shown.forEachIndexed { i, s ->
            telemetry.write(
                EventDraft(
                    type = "prompt_shown", sessionId = sessionId, entityType = "session", entityId = sessionId,
                    payload = mapOf(
                        "prompt" to JsonPrimitive(s.prompt), "group" to (s.group?.let(::JsonPrimitive) ?: JsonNull), "phase" to JsonPrimitive(phase),
                        "position" to JsonPrimitive(i + 1), "budget_used" to JsonPrimitive(shown.size), "reduced" to JsonPrimitive(s.reduced),
                    ),
                ),
            )
        }
    }

    private suspend fun skippedEvents(sessionId: String, skipped: List<PromptNotAnswered>) {
        for (s in skipped) {
            telemetry.write(
                EventDraft(
                    type = "prompt_skipped", sessionId = sessionId, entityType = "session", entityId = sessionId,
                    payload = mapOf("prompt" to JsonPrimitive(s.prompt), "group" to (s.group?.let(::JsonPrimitive) ?: JsonNull), "cause" to JsonPrimitive(s.cause)),
                ),
            )
        }
    }

    private suspend fun answered(sessionId: String, prompt: String, group: String?, value: String, wellnessId: String?, latencyMs: Long?) {
        telemetry.write(
            EventDraft(
                type = "prompt_answered", sessionId = sessionId, entityType = if (wellnessId != null) "wellness_log" else "session",
                entityId = wellnessId ?: sessionId,
                payload = mapOf(
                    "prompt" to JsonPrimitive(prompt), "group" to (group?.let(::JsonPrimitive) ?: JsonNull), "value_code" to JsonPrimitive(value),
                    "latency_ms" to (latencyMs?.let(::JsonPrimitive) ?: JsonNull), "wellness_log_id" to (wellnessId?.let(::JsonPrimitive) ?: JsonNull),
                ),
            ),
        )
    }

    private suspend fun save(w: WellnessLogEntity) {
        db.wellness().upsert(w)
        outbox.enqueue("wellness_logs", w.id, ctx.nowMs())
    }

    private fun wellness(sessionId: String?, kind: String): WellnessLogEntity {
        val now = ctx.nowMs()
        return WellnessLogEntity(
            id = ctx.ids.next(), user_id = ctx.requireUser(), client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
            app_version = ctx.appVersion, kind = kind, phase = "pre", observed_at = now,
            local_date = Instant.ofEpochMilli(now).atZone(ctx.zone()).toLocalDate().toString(), session_id = sessionId,
        )
    }

    companion object {
        /** `settings_changed` key of a Reset: `prompt_adaptive.<prompt>`. */
        const val RESET_PREFIX = "prompt_adaptive."
    }
}
