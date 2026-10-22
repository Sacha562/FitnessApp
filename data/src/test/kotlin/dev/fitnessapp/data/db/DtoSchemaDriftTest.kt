package dev.fitnessapp.data.db

import dev.fitnessapp.data.db.entity.CalendarEventEntity
import dev.fitnessapp.data.db.entity.ChallengeDefinitionEntity
import dev.fitnessapp.data.db.entity.ChallengeEnrollmentEntity
import dev.fitnessapp.data.db.entity.ChallengeEventEntity
import dev.fitnessapp.data.db.entity.ChallengeRuleEventEntity
import dev.fitnessapp.data.db.entity.ExportEntity
import dev.fitnessapp.data.db.entity.GymEntity
import dev.fitnessapp.data.db.entity.GymEquipmentEntity
import dev.fitnessapp.data.db.entity.MetricObservationEntity
import dev.fitnessapp.data.db.entity.ProfileEntity
import dev.fitnessapp.data.db.entity.SessionBlockEntity
import dev.fitnessapp.data.db.entity.SessionEventEntity
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.UserExercisePrefEntity
import dev.fitnessapp.data.db.entity.WellnessLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Test

/**
 * ANDROID.md 27.2 `DtoSchemaDriftTest`: the JSON keys of every synced entity equal the server columns of
 * migrations 006-015 (read from the live `information_schema` on 2026-10-08; `user_exercise_prefs` and `exports`
 * from migrations 010 and 015 on 2026-10-11), minus `created_at`, which the server fills. A migration that adds a column must update the entity and this list together.
 */
class DtoSchemaDriftTest {
    private val std = listOf("id", "user_id", "client_updated_at", "server_updated_at", "deleted_at", "device_id", "app_version")

    private fun keys(s: KSerializer<*>): List<String> = (0 until s.descriptor.elementsCount).map { s.descriptor.getElementName(it) }

    private fun check(s: KSerializer<*>, columns: String, standard: List<String> = std) {
        val expected = standard + columns.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        keys(s).sorted() shouldBe expected.sorted()
    }

    @Test
    fun profiles() = check(
        ProfileEntity.serializer(),
        "display_name, timezone, week_start_dow, mass_unit, ui_language, decimal_separator, sex, birth_year, training_age, " +
            "bodyweight_fallback_kg, max_gym_visits_per_week, default_session_min, changeover_min, availability, default_gym_id, " +
            "calendar_read_enabled, calendar_scope, calendar_selected_keys, calendar_ignore_all_day, calendar_ignore_name_patterns, " +
            "calendar_write_enabled, calendar_write_weeks, evidence_mode, running_priority, deload_sync_with_challenge, allow_supersets, " +
            "rir_calibration_opt_in, prompt_readiness, prompt_elbow_check, prompt_srpe, prompt_soreness, prompt_pain_after, " +
            "rest_alarm_mode, rest_tone_enabled, keep_screen_on, theme, dynamic_color, reminders_enabled, muscle_goal_preset, " +
            "disclaimer_accepted_at, onboarding_completed_at",
    )

    @Test
    fun gyms() = check(GymEntity.serializer(), "name, location_keywords, address_note, opening_hours, is_default")

    @Test
    fun gymEquipment() = check(
        GymEquipmentEntity.serializer(),
        "gym_id, equipment_type, label, available, scheme_type, load_scheme, calibration_rev, calibration_source, confirmed_at",
    )

    @Test
    fun workoutSessions() = check(
        WorkoutSessionEntity.serializer(),
        "kind, status, template_source, families, enrollment_id, challenge_slot, challenge_template_id, habit_id, phase, " +
            "block_index, block_week, enrollment_mode, mesocycle_week, is_deload, local_date, timezone, iso_week, gym_id, " +
            "planned_start_at, started_at, finished_at, bodyweight_kg, bodyweight_source, bodyweight_stale, time_budget_min, " +
            "time_budget_source, engine_version, params_hash, catalog_version, plan_recommendation_id, completion_ratio, " +
            "end_reason_code, notes",
    )

    @Test
    fun sessionBlocks() = check(
        SessionBlockEntity.serializer(),
        "session_id, position, block_kind, origin, enrollment_id, challenge_template_id, budget_min, status, recommendation_id",
    )

    @Test
    fun sessionExercises() = check(
        SessionExerciseEntity.serializer(),
        "session_id, block_id, exercise_id, challenge_item_key, position, origin, substitutes_id, substitution_reason_code, " +
            "status, status_reason_code, defer_count, load_mode, side_mode, side_order, equipment_id, progression_mode, rep_scheme, " +
            "planned_sets, planned_rep_min, planned_rep_max, planned_rir, planned_load_input, planned_load_unit, " +
            "planned_effective_load_kg, planned_duration_s, planned_ecc_s, planned_rest_s, superset_group, recommendation_id, " +
            "user_override, override_reason_code, override_fields, progression_valid, progression_invalid_reason, started_at, completed_at",
    )

    @Test
    fun setLogs() = check(
        SetLogEntity.serializer(),
        "session_id, session_exercise_id, exercise_id, set_index, side, set_kind, round_index, attempt_index, planned_reps, " +
            "planned_rep_min, planned_rep_max, planned_rir, planned_input_value, planned_input_unit, planned_added_kg, " +
            "planned_assist_native, planned_assist_ordinal, planned_effective_load_kg, planned_duration_s, planned_ecc_s, " +
            "planned_source, load_mode, equipment_id, equipment_calibration_rev, input_value, input_unit, implement_count, " +
            "load_per_implement_kg, implement_base_kg, added_kg, assist_pin_native, assist_unit, assist_kg, assist_ordinal, " +
            "bodyweight_kg, f_bw, effective_load_kg, reps, duration_s, hold_reserve_s, hold_reserve_is_lower_bound, ecc_s_target, " +
            "ecc_s_actual, rir_reported, rir_is_lower_bound, rir_source, calib_extra_reps, flags, deviation_reason_code, " +
            "deviation_note, pain_flag, rest_timer_planned_s, rest_adjust_s, rest_timer_overridden, rest_before_s, rest_source, " +
            "rest_end_reason, started_at, completed_at",
    )

    @Test
    fun wellnessLogs() = check(
        WellnessLogEntity.serializer(),
        "kind, phase, observed_at, local_date, session_id, session_exercise_id, set_log_id, readiness_level, elbow_score, " +
            "pain_body_region, pain_side, pain_severity, pain_score, symptom_codes, soreness_muscle_group_id, soreness_code, srpe, " +
            "sleep_hours, note",
    )

    @Test
    fun metricObservations() = check(
        MetricObservationEntity.serializer(),
        "metric_code, value_num, value_bool, unit, side, observed_at, local_date, source, session_id, test_session_id, " +
            "set_log_id, enrollment_id, equipment_id, equipment_calibration_rev, valid_for_gate, validity_note, protocol_code, " +
            "derivation, derived_from_ids, calc_params, attempt_count, bodyweight_kg",
    )

    @Test
    fun sessionEvents() = check(
        SessionEventEntity.serializer(),
        "event_type, category, occurred_at, tz_offset_min, screen, session_id, entity_type, entity_id, recommendation_id, " +
            "module_id, module_version, ui_version, payload",
        standard = listOf("id", "user_id", "server_updated_at", "device_id", "app_version"),
    )

    // Challenge tables: migrations 011 and 014, read from information_schema on 2026-10-20 (M2b).
    @Test
    fun challengeEnrollments() = check(
        ChallengeEnrollmentEntity.serializer(),
        "definition_id, definition_slug, definition_version, definition_jcs_sha256, status, mode, phase, block_index, block_week, " +
            "week_ordinal, params, rule_decisions, state, state_rev, state_hash, fold_generation, last_folded_event_id, engine_version, " +
            "started_on, paused_at, completed_at, abandoned_at",
    )

    @Test
    fun challengeEvents() = check(
        ChallengeEventEntity.serializer(),
        "enrollment_id, event_type, occurred_at, local_date, dedupe_key, payload, source_session_id, source_wellness_log_id, " +
            "supersedes_event_id, definition_version, definition_jcs_sha256, engine_version",
        standard = listOf("id", "user_id", "server_updated_at", "device_id", "app_version"),
    )

    @Test
    fun challengeRuleEvents() = check(
        ChallengeRuleEventEntity.serializer(),
        "enrollment_id, challenge_event_id, evaluation_id, fold_generation, rule_id, rule_tag, proposal_id, rule_decision, stage, " +
            "prio, outcome, cond_value, eval_error, bindings, proposals, applied, suppressed_by, state_rev_before, state_rev_after, " +
            "state_hash_before, state_hash_after, cascade_depth, derived_event_type, definition_slug, definition_version, " +
            "definition_jcs_sha256, module_version, engine_version",
        standard = listOf("id", "user_id", "server_updated_at", "device_id", "app_version"),
    )

    // calendar_events: migration 014, read from information_schema on 2026-10-22 (M2b.5).
    @Test
    fun calendarEvents() = check(
        CalendarEventEntity.serializer(),
        "provider, instance_key, series_key, source_device_id, classified_kind, title, location, starts_at, ends_at, all_day, " +
            "local_date, timezone, iso_week, provider_status, challenge_slot, enrollment_id, planned_families, run_intensity, " +
            "run_minutes, classifier_version, match_method, score, features, override_kind, override_slot, override_at, app_written, " +
            "marker, adopted_at, write_state, attention, linked_session_id, link_method, recommendation_id, last_seen_at, missing_since",
    )

    /** Pull-only: the server columns minus `created_at`, `client_updated_at`, `device_id`, `app_version`, plus the local `source`. */
    @Test
    fun challengeDefinitions() = check(
        ChallengeDefinitionEntity.serializer(),
        "id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition, jcs_sha256, " +
            "server_checksum, supersedes_id, published_at, retired_at, server_updated_at, source",
        standard = emptyList(),
    )

    @Test
    fun userExercisePrefs() = check(UserExercisePrefEntity.serializer(), "exercise_id, preference, unavailable_until, note")

    @Test
    fun exports() = check(
        ExportEntity.serializer(),
        "export_kind, format, schema_version, table_row_counts, bytes, sha256, destination, period_start, period_end, " +
            "includes_local_only, engine_version, catalog_version",
        standard = listOf("id", "user_id", "server_updated_at", "device_id", "app_version"),
    )
}
