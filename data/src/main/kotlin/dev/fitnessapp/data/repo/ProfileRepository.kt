package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.ProfileEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The owner's profile (DATA_MODEL 9.3.1), created lazily on the phone after the allowlist check (never by a
 * trigger on auth.users) and written through the outbox like every other row (M1a: replaces the M0 direct
 * upsert).
 */
class ProfileRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
) {
    fun observe(): Flow<ProfileEntity?> = ctx.userId()?.let { db.profiles().observe(it) } ?: flowOf(null)

    suspend fun get(): ProfileEntity? = ctx.userId()?.let { db.profiles().get(it) }

    /** Creates the profile row if this phone has none yet (the pull may also bring an existing one). */
    suspend fun ensure(): ProfileEntity {
        val uid = ctx.requireUser()
        db.profiles().get(uid)?.let { return it }
        return update { it }
    }

    suspend fun acceptDisclaimer() {
        update { it.copy(disclaimer_accepted_at = ctx.nowMs()) }
        db.withTransaction {
            telemetry.write(EventDraft(type = "disclaimer_acknowledged", entityType = "none", payload = mapOf("version" to JsonPrimitive("1"))))
        }
    }

    suspend fun saveBasics(displayName: String?, trainingAge: String?, birthYear: Int?, fallbackBwKg: Double?) {
        val fields = listOfNotNull(
            displayName?.let { "display_name" }, trainingAge?.let { "training_age" }, birthYear?.let { "birth_year" },
            fallbackBwKg?.let { "bodyweight" },
        )
        update {
            it.copy(
                display_name = displayName?.takeIf { n -> n.isNotBlank() } ?: it.display_name,
                training_age = trainingAge ?: it.training_age,
                birth_year = birthYear ?: it.birth_year,
                bodyweight_fallback_kg = fallbackBwKg?.let { kg -> Scaled.fromDouble(kg, 2) } ?: it.bodyweight_fallback_kg,
            )
        }
        stepCompleted("profile", fields)
    }

    suspend fun setDefaultGym(gymId: String) = update { it.copy(default_gym_id = gymId) }

    suspend fun setRestAlarmMode(mode: String) = update { it.copy(rest_alarm_mode = mode) }

    /** The opt-in RIR check set (F14, UX 15.4 Sessions): off until the user switches it on (D49). */
    suspend fun setRirCalibrationOptIn(on: Boolean) {
        val before = get()?.rir_calibration_opt_in ?: false
        if (before == on) return
        update { it.copy(rir_calibration_opt_in = on) }
        db.withTransaction {
            telemetry.write(
                EventDraft(
                    type = "settings_changed", entityType = "none", screen = "you.preferences",
                    payload = mapOf("key" to JsonPrimitive("rir_calibration_opt_in"), "from" to JsonPrimitive(before), "to" to JsonPrimitive(on)),
                ),
            )
        }
        scheduler.request()
    }

    /**
     * A setting of You > Preferences or You > Prompts (UX 15.4, 15.8): writes the profile and `settings_changed {key, from,
     * to}`; nothing happens when the value is unchanged.
     */
    suspend fun <T> set(setting: Setting<T>, value: T, screen: String = "you.preferences") {
        val before = setting.read(get() ?: ensure())
        if (before == value) return
        update { setting.write(it, value) }
        db.withTransaction {
            telemetry.write(
                EventDraft(
                    type = "settings_changed", entityType = "none", screen = screen,
                    payload = mapOf("key" to JsonPrimitive(setting.key), "from" to json(before), "to" to json(value)),
                ),
            )
        }
        scheduler.request()
    }

    /** The goal preset in use (`profiles.muscle_goal_preset`, D6); [GoalRepository] logs the change as `goal_changed`. */
    suspend fun setGoalPreset(code: String) = update { it.copy(muscle_goal_preset = code) }

    private fun json(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    suspend fun completeOnboarding() {
        update { it.copy(onboarding_completed_at = it.onboarding_completed_at ?: ctx.nowMs()) }
        stepCompleted("done", emptyList())
    }

    suspend fun stepCompleted(step: String, fields: List<String>) {
        db.withTransaction {
            telemetry.write(
                EventDraft(
                    type = "onboarding_step_completed", entityType = "none",
                    payload = mapOf("step" to JsonPrimitive(step), "fields_set" to JsonArray(fields.map(::JsonPrimitive))),
                ),
            )
        }
        scheduler.request()
    }

    private suspend fun update(change: (ProfileEntity) -> ProfileEntity): ProfileEntity {
        val uid = ctx.requireUser()
        val row = db.withTransaction {
            val base = db.profiles().get(uid) ?: ProfileEntity(
                id = uid, user_id = uid, client_updated_at = 0, device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                timezone = ctx.zone().id,
            )
            val next = change(base).copy(client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion)
            db.profiles().upsert(next)
            outbox.enqueue("profiles", uid, ctx.nowMs())
            next
        }
        scheduler.request()
        return row
    }
}

/** A profile setting the Preferences and Prompts screens change: its `settings_changed` key and the column it maps to. */
class Setting<T>(val key: String, val read: (ProfileEntity) -> T, val write: (ProfileEntity, T) -> ProfileEntity) {
    companion object {
        val TRAINING_AGE = Setting<String?>("training_age", { it.training_age }, { p, v -> p.copy(training_age = v) })
        val BIRTH_YEAR = Setting<Int?>("birth_year", { it.birth_year }, { p, v -> p.copy(birth_year = v) })
        val MAX_VISITS = Setting("max_gym_visits_per_week", { it.max_gym_visits_per_week }, { p, v -> p.copy(max_gym_visits_per_week = v) })
        val DEFAULT_SESSION_MIN = Setting("default_session_min", { it.default_session_min }, { p, v -> p.copy(default_session_min = v) })
        val CHANGEOVER_MIN = Setting("changeover_min", { it.changeover_min }, { p, v -> p.copy(changeover_min = v) })
        val AVAILABILITY = Setting("availability", { it.availability }, { p, v -> p.copy(availability = v) })
        val RUNNING_PRIORITY = Setting("running_priority", { it.running_priority }, { p, v -> p.copy(running_priority = v) })
        val SUPERSETS = Setting("allow_supersets", { it.allow_supersets }, { p, v -> p.copy(allow_supersets = v) })
        val PROMPT_READINESS = Setting("prompt_readiness", { it.prompt_readiness }, { p, v -> p.copy(prompt_readiness = v) })
        val PROMPT_ELBOW = Setting("prompt_elbow_check", { it.prompt_elbow_check }, { p, v -> p.copy(prompt_elbow_check = v) })
        val PROMPT_SORENESS = Setting("prompt_soreness", { it.prompt_soreness }, { p, v -> p.copy(prompt_soreness = v) })
        val PROMPT_SRPE = Setting("prompt_srpe", { it.prompt_srpe }, { p, v -> p.copy(prompt_srpe = v) })
        val PROMPT_PAIN = Setting("prompt_pain_after", { it.prompt_pain_after }, { p, v -> p.copy(prompt_pain_after = v) })
    }
}
