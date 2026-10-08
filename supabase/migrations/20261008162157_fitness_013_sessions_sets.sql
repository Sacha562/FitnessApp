-- fitness_013_sessions_sets (M1a) — DATA_MODEL.md 9.6, 6.1 (USER), 7.1, 7.4, 7.7, 7.8, 7.9, 8, 13.2
--
-- Session facts: workout_sessions -> session_blocks -> session_exercises -> set_logs (structural chain
-- with composite foreign keys that pin user, session and exercise consistency, 7.9), plus wellness_logs
-- (readiness, elbow check, pain, soreness, sRPE, sleep, illness) and metric_observations (weigh-ins and
-- test results). All USER class: authenticated may select/insert/update own rows; no DELETE grant.
--
-- set_logs.effective_load_kg is written by the engine's single LOAD function (C1) and only verified here
-- with a 0.01 kg tolerance (open issue 1): PostgreSQL's round() is half-away-from-zero, so the server never
-- recomputes it.

-- ---------------------------------------------------------------------------------------------------
-- 9.6.1 workout_sessions
-- ---------------------------------------------------------------------------------------------------
create table fitness.workout_sessions (
  id                      uuid primary key default gen_random_uuid(),
  user_id                 uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at              timestamptz not null default now(),
  client_updated_at       timestamptz not null,
  server_updated_at       timestamptz not null default now(),
  deleted_at              timestamptz,
  device_id               uuid not null,
  app_version             text not null check (length(app_version) between 1 and 40),

  kind                    text not null default 'gym' check (kind in ('gym', 'home', 'test')),
  status                  text not null default 'in_progress'
                          check (status in ('planned', 'in_progress', 'finished', 'abandoned', 'skipped')),
  template_source         text not null check (template_source in (
                            'challenge_slot', 'family_combo', 'recommended', 'empty', 'habit', 'import')),
  families                text[] not null default '{}'
                          check (families <@ array['push', 'pull', 'legs', 'core', 'full']::text[]),
  enrollment_id           uuid,
  challenge_slot          text check (challenge_slot is null or challenge_slot ~ '^[A-Z][A-Z0-9_]{0,15}$'),
  challenge_template_id   text check (challenge_template_id is null or length(challenge_template_id) between 1 and 40),
  habit_id                text check (habit_id is null or habit_id ~ '^[a-z][a-z0-9_]{0,63}$'),
  phase                   smallint check (phase is null or phase between 1 and 20),
  block_index             smallint check (block_index is null or block_index >= 1),
  block_week              smallint check (block_week is null or block_week between 1 and 10),
  enrollment_mode         text check (enrollment_mode is null or enrollment_mode in (
                            'normal', 'deload', 'test_pending', 'extra_deload', 'injury')),
  mesocycle_week          smallint check (mesocycle_week is null or mesocycle_week between 1 and 10),
  is_deload               boolean not null default false,
  local_date              date not null,
  timezone                text not null check (timezone ~ '^[A-Za-z_]+(/[A-Za-z0-9_+-]+)*$'),
  iso_week                text not null check (iso_week ~ '^[0-9]{4}-W(0[1-9]|[1-4][0-9]|5[0-3])$'),
  gym_id                  uuid,
  planned_start_at        timestamptz,
  started_at              timestamptz,
  finished_at             timestamptz,
  bodyweight_kg           numeric(5,2) check (bodyweight_kg is null or bodyweight_kg between 20 and 300),
  bodyweight_source       text check (bodyweight_source is null or bodyweight_source in ('ewma', 'test_weighin', 'fallback', 'none')),
  bodyweight_stale        boolean not null default false,
  time_budget_min         smallint check (time_budget_min is null or time_budget_min between 10 and 300),
  time_budget_source      text check (time_budget_source is null or time_budget_source in ('calendar', 'chip', 'default')),
  engine_version          text not null
                          check (engine_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  params_hash             text not null check (params_hash ~ '^[0-9a-f]{64}$'),
  catalog_version         text not null
                          check (catalog_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  plan_recommendation_id  uuid,
  completion_ratio        numeric(5,4) check (completion_ratio is null or completion_ratio between 0 and 1),
  end_reason_code         text references fitness.reason_codes (code),
  notes                   text check (notes is null or length(notes) <= 2000),

  constraint workout_sessions_id_user_uq unique (id, user_id),
  constraint workout_sessions_enrollment_fk foreign key (enrollment_id, user_id)
    references fitness.challenge_enrollments (id, user_id),
  constraint workout_sessions_gym_fk foreign key (gym_id, user_id) references fitness.gyms (id, user_id),
  constraint workout_sessions_plan_fk foreign key (plan_recommendation_id, user_id)
    references fitness.recommendations (id, user_id),
  constraint workout_sessions_times_ck check (finished_at is null or started_at is null or finished_at >= started_at),
  constraint workout_sessions_in_progress_ck check (status <> 'in_progress' or started_at is not null),
  constraint workout_sessions_finished_ck check (status <> 'finished' or (started_at is not null and finished_at is not null)),
  constraint workout_sessions_slot_ck check (challenge_slot is null or enrollment_id is not null),
  constraint workout_sessions_habit_ck check (template_source <> 'habit' or habit_id is not null)
);
create unique index workout_sessions_one_in_progress on fitness.workout_sessions (user_id)
  where status = 'in_progress' and deleted_at is null;
create index workout_sessions_user_sync_idx on fitness.workout_sessions (user_id, server_updated_at);
create index workout_sessions_local_date_idx on fitness.workout_sessions (user_id, local_date desc);
create index workout_sessions_enrollment_idx on fitness.workout_sessions (enrollment_id, user_id);
create index workout_sessions_gym_idx on fitness.workout_sessions (gym_id, user_id);
create index workout_sessions_plan_idx on fitness.workout_sessions (plan_recommendation_id, user_id);
create index workout_sessions_end_reason_idx on fitness.workout_sessions (end_reason_code);

-- ---------------------------------------------------------------------------------------------------
-- 9.6.2 session_blocks (C34)
-- ---------------------------------------------------------------------------------------------------
create table fitness.session_blocks (
  id                     uuid primary key default gen_random_uuid(),
  user_id                uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at             timestamptz not null default now(),
  client_updated_at      timestamptz not null,
  server_updated_at      timestamptz not null default now(),
  deleted_at             timestamptz,
  device_id              uuid not null,
  app_version            text not null check (length(app_version) between 1 and 40),

  session_id             uuid not null,
  position               smallint not null check (position >= 0),
  block_kind             text not null check (block_kind in ('challenge', 'general')),
  origin                 text not null check (origin in ('challenge_template', 'addon', 'standalone', 'user_built')),
  enrollment_id          uuid,
  challenge_template_id  text check (challenge_template_id is null or length(challenge_template_id) between 1 and 40),
  budget_min             smallint check (budget_min is null or budget_min between 0 and 300),
  status                 text not null default 'planned'
                         check (status in ('planned', 'active', 'done', 'skipped', 'trimmed')),
  recommendation_id      uuid,

  constraint session_blocks_id_user_uq unique (id, user_id),
  constraint session_blocks_id_user_session_uq unique (id, user_id, session_id),
  constraint session_blocks_session_fk foreign key (session_id, user_id)
    references fitness.workout_sessions (id, user_id) on delete cascade,
  constraint session_blocks_enrollment_fk foreign key (enrollment_id, user_id)
    references fitness.challenge_enrollments (id, user_id),
  constraint session_blocks_recommendation_fk foreign key (recommendation_id, user_id)
    references fitness.recommendations (id, user_id),
  constraint session_blocks_challenge_ck check (block_kind <> 'challenge' or enrollment_id is not null)
);
create unique index session_blocks_position_uq on fitness.session_blocks (session_id, position) where deleted_at is null;
create index session_blocks_user_sync_idx on fitness.session_blocks (user_id, server_updated_at);
create index session_blocks_session_idx on fitness.session_blocks (session_id, user_id);
create index session_blocks_enrollment_idx on fitness.session_blocks (enrollment_id, user_id);
create index session_blocks_recommendation_idx on fitness.session_blocks (recommendation_id, user_id);

-- ---------------------------------------------------------------------------------------------------
-- 9.6.3 session_exercises
-- ---------------------------------------------------------------------------------------------------
create table fitness.session_exercises (
  id                          uuid primary key default gen_random_uuid(),
  user_id                     uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at                  timestamptz not null default now(),
  client_updated_at           timestamptz not null,
  server_updated_at           timestamptz not null default now(),
  deleted_at                  timestamptz,
  device_id                   uuid not null,
  app_version                 text not null check (length(app_version) between 1 and 40),

  session_id                  uuid not null,
  block_id                    uuid not null,
  exercise_id                 uuid not null references fitness.exercises (id),
  challenge_item_key          text check (challenge_item_key is null or challenge_item_key ~ '^[a-z][a-z0-9_]{0,63}$'),
  position                    numeric(9,4) not null,
  origin                      text not null check (origin in ('plan', 'challenge', 'user_added', 'substitute', 'live_algo')),
  substitutes_id              uuid,
  substitution_reason_code    text references fitness.reason_codes (code),
  status                      text not null default 'pending'
                              check (status in ('pending', 'active', 'done', 'skipped', 'removed', 'deferred', 'not_done')),
  status_reason_code          text references fitness.reason_codes (code),
  defer_count                 smallint not null default 0 check (defer_count between 0 and 10),
  load_mode                   text not null check (load_mode in (
                                'external', 'bodyweight', 'bodyweight_plus_external', 'assisted_machine', 'assisted_ordinal')),
  side_mode                   text not null default 'bilateral'
                              check (side_mode in ('bilateral', 'unilateral_alternating', 'unilateral_sequential')),
  side_order                  text check (side_order is null or side_order in ('LR', 'RL')),
  equipment_id                uuid,
  progression_mode            text not null check (progression_mode in ('model', 'double', 'rule', 'fixed', 'none')),
  rep_scheme                  text not null check (rep_scheme in ('range', 'fixed', 'amrap', 'hold', 'attempt', 'ladder')),
  planned_sets                smallint check (planned_sets is null or planned_sets between 0 and 20),
  planned_rep_min             smallint check (planned_rep_min is null or planned_rep_min between 0 and 100),
  planned_rep_max             smallint check (planned_rep_max is null or planned_rep_max between 0 and 100),
  planned_rir                 numeric(3,1) check (planned_rir is null or planned_rir between 0 and 10),
  planned_load_input          numeric(7,2),
  planned_load_unit           text check (planned_load_unit is null or planned_load_unit in ('kg', 'lb', 'level', 'fingers')),
  planned_effective_load_kg   numeric(7,2),
  planned_duration_s          numeric(6,1),
  planned_ecc_s               numeric(4,1),
  planned_rest_s              smallint check (planned_rest_s is null or planned_rest_s between 0 and 900),
  superset_group              smallint check (superset_group is null or superset_group between 1 and 20),
  recommendation_id           uuid,
  user_override               boolean not null default false,
  override_reason_code        text references fitness.reason_codes (code),
  override_fields             jsonb not null default '{}' check (jsonb_typeof(override_fields) = 'object'),
  progression_valid           boolean,
  progression_invalid_reason  text check (progression_invalid_reason is null or length(progression_invalid_reason) <= 200),
  started_at                  timestamptz,
  completed_at                timestamptz,

  constraint session_exercises_id_user_uq unique (id, user_id),
  constraint session_exercises_pin_uq unique (id, user_id, session_id, exercise_id),
  constraint session_exercises_block_fk foreign key (block_id, user_id, session_id)
    references fitness.session_blocks (id, user_id, session_id) on delete cascade,
  constraint session_exercises_substitutes_fk foreign key (substitutes_id, user_id)
    references fitness.session_exercises (id, user_id),
  constraint session_exercises_equipment_fk foreign key (equipment_id, user_id)
    references fitness.gym_equipment (id, user_id),
  constraint session_exercises_recommendation_fk foreign key (recommendation_id, user_id)
    references fitness.recommendations (id, user_id),
  constraint session_exercises_reason_ck check (status not in ('skipped', 'removed') or status_reason_code is not null),
  constraint session_exercises_rep_order_ck check (
    planned_rep_min is null or planned_rep_max is null or planned_rep_min <= planned_rep_max)
);
create index session_exercises_user_sync_idx on fitness.session_exercises (user_id, server_updated_at);
create index session_exercises_block_idx on fitness.session_exercises (block_id, user_id, session_id);
create index session_exercises_session_idx on fitness.session_exercises (session_id, user_id);
create index session_exercises_exercise_idx on fitness.session_exercises (exercise_id);
create index session_exercises_equipment_idx on fitness.session_exercises (equipment_id, user_id);
create index session_exercises_recommendation_idx on fitness.session_exercises (recommendation_id, user_id);
create index session_exercises_substitutes_idx on fitness.session_exercises (substitutes_id, user_id);
create index session_exercises_subst_reason_idx on fitness.session_exercises (substitution_reason_code);
create index session_exercises_status_reason_idx on fitness.session_exercises (status_reason_code);
create index session_exercises_override_reason_idx on fitness.session_exercises (override_reason_code);

-- ---------------------------------------------------------------------------------------------------
-- 9.6.4 set_logs (one row per set per side, C7)
-- ---------------------------------------------------------------------------------------------------
create table fitness.set_logs (
  id                           uuid primary key default gen_random_uuid(),
  user_id                      uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at                   timestamptz not null default now(),
  client_updated_at            timestamptz not null,
  server_updated_at            timestamptz not null default now(),
  deleted_at                   timestamptz,
  device_id                    uuid not null,
  app_version                  text not null check (length(app_version) between 1 and 40),

  -- Identity
  session_id                   uuid not null,
  session_exercise_id          uuid not null,
  exercise_id                  uuid not null,
  set_index                    smallint not null check (set_index between 1 and 100),
  side                         text not null default 'both' check (side in ('both', 'L', 'R')),
  set_kind                     text not null default 'working' check (set_kind in (
                                 'warmup', 'working', 'top', 'backoff', 'amrap', 'calibration', 'test', 'attempt')),
  round_index                  smallint check (round_index is null or round_index between 1 and 20),
  attempt_index                smallint check (attempt_index is null or attempt_index between 1 and 20),

  -- Planned values
  planned_reps                 smallint check (planned_reps is null or planned_reps between 0 and 100),
  planned_rep_min              smallint check (planned_rep_min is null or planned_rep_min between 0 and 100),
  planned_rep_max              smallint check (planned_rep_max is null or planned_rep_max between 0 and 100),
  planned_rir                  numeric(3,1) check (planned_rir is null or planned_rir between 0 and 10),
  planned_input_value          numeric(7,2),
  planned_input_unit           text check (planned_input_unit is null or planned_input_unit in ('kg', 'lb', 'level', 'fingers')),
  planned_added_kg             numeric(6,2) check (planned_added_kg is null or planned_added_kg >= 0),
  planned_assist_native        numeric(6,2) check (planned_assist_native is null or planned_assist_native >= 0),
  planned_assist_ordinal       smallint check (planned_assist_ordinal is null or planned_assist_ordinal between 0 and 10),
  planned_effective_load_kg    numeric(7,2),
  planned_duration_s           numeric(6,1) check (planned_duration_s is null or planned_duration_s between 0 and 3600),
  planned_ecc_s                numeric(4,1) check (planned_ecc_s is null or planned_ecc_s between 0 and 60),
  planned_source               text check (planned_source is null or planned_source in (
                                 'engine', 'challenge_rule', 'autoreg', 'user', 'prefill_last', 'default')),

  -- Actual load (C1, C2)
  load_mode                    text not null check (load_mode in (
                                 'external', 'bodyweight', 'bodyweight_plus_external', 'assisted_machine', 'assisted_ordinal')),
  equipment_id                 uuid,
  equipment_calibration_rev    int check (equipment_calibration_rev is null or equipment_calibration_rev >= 1),
  input_value                  numeric(7,2),
  input_unit                   text check (input_unit is null or input_unit in ('kg', 'lb', 'level', 'fingers')),
  implement_count              smallint check (implement_count is null or implement_count in (1, 2)),
  load_per_implement_kg        numeric(6,2),
  implement_base_kg            numeric(6,2) not null default 0 check (implement_base_kg >= 0),
  added_kg                     numeric(6,2) not null default 0 check (added_kg >= 0),
  assist_pin_native            numeric(6,2) check (assist_pin_native is null or assist_pin_native >= 0),
  assist_unit                  text check (assist_unit is null or assist_unit in ('lb', 'kg')),
  assist_kg                    numeric(6,2) check (assist_kg is null or assist_kg >= 0),
  assist_ordinal               smallint check (assist_ordinal is null or assist_ordinal between 0 and 10),
  bodyweight_kg                numeric(5,2) check (bodyweight_kg is null or bodyweight_kg between 20 and 300),
  f_bw                         numeric(5,4) check (f_bw is null or f_bw between 0 and 1),
  effective_load_kg            numeric(7,2),

  -- Actual performance and feedback
  reps                         smallint check (reps is null or reps between 0 and 100),
  duration_s                   numeric(6,1) check (duration_s is null or duration_s between 0 and 3600),
  hold_reserve_s               numeric(5,1) check (hold_reserve_s is null or hold_reserve_s between 0 and 60),
  hold_reserve_is_lower_bound  boolean not null default false,
  ecc_s_target                 numeric(4,1) check (ecc_s_target is null or ecc_s_target between 0 and 60),
  ecc_s_actual                 numeric(4,1) check (ecc_s_actual is null or ecc_s_actual between 0 and 60),
  rir_reported                 numeric(3,1) check (rir_reported is null or (rir_reported between 0 and 10
                                                   and rir_reported * 2 = floor(rir_reported * 2))),
  rir_is_lower_bound           boolean not null default false,
  rir_source                   text not null default 'chip'
                               check (rir_source in ('chip', 'edited', 'calibration', 'protocol', 'import', 'missing')),
  calib_extra_reps             smallint check (calib_extra_reps is null or calib_extra_reps between 0 and 50),
  flags                        text[] not null default '{}'
                               check (flags <@ array['grip_slip_before_last', 'jerky', 'torso_rotation', 'hip_rotation',
                                                     'technique_breakdown', 'failed_rep', 'hand_shift', 'used_straps']::text[]),
  deviation_reason_code        text references fitness.reason_codes (code),
  deviation_note               text check (deviation_note is null or length(deviation_note) <= 500),
  pain_flag                    boolean not null default false,

  -- Rest (C18, C55)
  rest_timer_planned_s         smallint check (rest_timer_planned_s is null or rest_timer_planned_s between 0 and 900),
  rest_adjust_s                smallint check (rest_adjust_s is null or rest_adjust_s between -900 and 900),
  rest_timer_overridden        boolean not null default false,
  rest_before_s                numeric(6,1) check (rest_before_s is null or rest_before_s between 0 and 7200),
  rest_source                  text check (rest_source is null or rest_source in ('measured', 'estimated')),
  rest_end_reason              text check (rest_end_reason is null or rest_end_reason in (
                                 'elapsed', 'skipped', 'next_set_early', 'elapsed_while_dead', 'session_finished', 'none')),

  -- Timestamps
  started_at                   timestamptz,
  completed_at                 timestamptz not null,

  constraint set_logs_id_user_uq unique (id, user_id),
  constraint set_logs_session_exercise_fk foreign key (session_exercise_id, user_id, session_id, exercise_id)
    references fitness.session_exercises (id, user_id, session_id, exercise_id) on delete cascade,
  constraint set_logs_equipment_fk foreign key (equipment_id, user_id) references fitness.gym_equipment (id, user_id),
  constraint set_logs_times_ck check (started_at is null or started_at <= completed_at),
  constraint set_logs_input_pair_ck check ((input_value is null) = (input_unit is null)),
  constraint set_logs_implement_pair_ck check ((implement_count is null) = (load_per_implement_kg is null)),
  constraint set_logs_implement_sum_ck check (
    implement_count is null or added_kg = implement_count * load_per_implement_kg),
  constraint set_logs_rest_source_ck check ((rest_source is null) = (rest_before_s is null)),
  constraint set_logs_rest_overridden_ck check (
    rest_timer_overridden or (coalesce(rest_adjust_s, 0) = 0 and rest_end_reason is distinct from 'skipped')),
  constraint set_logs_rep_order_ck check (
    planned_rep_min is null or planned_rep_max is null or planned_rep_min <= planned_rep_max),
  -- Load-mode consistency
  constraint set_logs_load_mode_ck check (
    (load_mode = 'external' and assist_kg is null and assist_ordinal is null)
    or (load_mode = 'bodyweight' and added_kg = 0 and assist_kg is null and assist_ordinal is null
        and coalesce(f_bw, 0) > 0)
    or (load_mode = 'bodyweight_plus_external' and assist_kg is null and assist_ordinal is null
        and coalesce(f_bw, 0) > 0)
    or (load_mode = 'assisted_machine' and assist_pin_native is not null and assist_unit is not null
        and assist_kg is not null and equipment_id is not null and assist_ordinal is null)
    or (load_mode = 'assisted_ordinal' and assist_ordinal is not null and assist_kg is null)),
  -- Effective load: the server verifies, it does not compute (C1)
  constraint set_logs_effective_load_ck check (
    case
      when load_mode = 'assisted_ordinal' then effective_load_kg is null
      when effective_load_kg is null then true
      else abs(effective_load_kg
               - (coalesce(bodyweight_kg, 0) * coalesce(f_bw, 0)
                  + implement_base_kg + added_kg - coalesce(assist_kg, 0))) <= 0.01
    end),
  -- Something was performed
  constraint set_logs_performed_ck check (reps is not null or duration_s is not null),
  -- One effort scale per set
  constraint set_logs_one_effort_ck check (not (rir_reported is not null and hold_reserve_s is not null)),
  constraint set_logs_effort_source_ck check ((rir_reported is null and hold_reserve_s is null) = (rir_source = 'missing')),
  constraint set_logs_calib_ck check (set_kind = 'calibration' or calib_extra_reps is null),
  -- Reasons that imply a flag (reason_codes.implies_flag)
  constraint set_logs_reason_flag_ck check (
    deviation_reason_code is null
    or deviation_reason_code not in ('torso_rotation', 'technique_breakdown')
    or flags @> array[deviation_reason_code])
);
create unique index set_logs_set_key_uq on fitness.set_logs
  (session_exercise_id, set_index, side, coalesce(round_index, 0), coalesce(attempt_index, 0))
  where deleted_at is null;
create index set_logs_user_sync_idx on fitness.set_logs (user_id, server_updated_at);
create index set_logs_session_exercise_idx on fitness.set_logs (session_exercise_id, user_id, session_id, exercise_id);
create index set_logs_exercise_history_idx on fitness.set_logs (user_id, exercise_id, completed_at desc)
  where deleted_at is null;
create index set_logs_session_idx on fitness.set_logs (session_id, user_id);
create index set_logs_equipment_idx on fitness.set_logs (equipment_id, user_id);
create index set_logs_reason_idx on fitness.set_logs (deviation_reason_code);

-- ---------------------------------------------------------------------------------------------------
-- 9.6.5 wellness_logs (the PainEvent fact of C33 lives here)
-- ---------------------------------------------------------------------------------------------------
create table fitness.wellness_logs (
  id                        uuid primary key default gen_random_uuid(),
  user_id                   uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at                timestamptz not null default now(),
  client_updated_at         timestamptz not null,
  server_updated_at         timestamptz not null default now(),
  deleted_at                timestamptz,
  device_id                 uuid not null,
  app_version               text not null check (length(app_version) between 1 and 40),

  kind                      text not null check (kind in (
                              'readiness', 'elbow_check', 'pain', 'soreness', 'srpe', 'sleep', 'illness')),
  phase                     text not null check (phase in ('pre', 'in_session', 'post', 'standalone')),
  observed_at               timestamptz not null,
  local_date                date not null,
  session_id                uuid,
  session_exercise_id       uuid,
  set_log_id                uuid,
  readiness_level           text check (readiness_level is null or readiness_level in ('worse', 'normal', 'better')),
  elbow_score               smallint check (elbow_score is null or elbow_score between 0 and 3),
  pain_body_region          text references fitness.body_regions (code),
  pain_side                 text check (pain_side is null or pain_side in ('L', 'R', 'both', 'none')),
  pain_severity             text check (pain_severity is null or pain_severity in ('mild', 'moderate', 'sharp')),
  pain_score                smallint check (pain_score is null or pain_score between 0 and 10),
  symptom_codes             text[] not null default '{}',
  soreness_muscle_group_id  text references fitness.muscle_groups (id),
  soreness_code             text check (soreness_code is null or soreness_code in ('never_sore', 'healed', 'still_sore')),
  srpe                      smallint check (srpe is null or srpe between 0 and 10),
  sleep_hours               numeric(3,1) check (sleep_hours is null or sleep_hours between 0 and 24),
  note                      text check (note is null or length(note) <= 1000),

  constraint wellness_logs_id_user_uq unique (id, user_id),
  constraint wellness_logs_session_fk foreign key (session_id, user_id)
    references fitness.workout_sessions (id, user_id),
  constraint wellness_logs_session_exercise_fk foreign key (session_exercise_id, user_id)
    references fitness.session_exercises (id, user_id),
  constraint wellness_logs_set_fk foreign key (set_log_id, user_id) references fitness.set_logs (id, user_id),
  constraint wellness_logs_kind_ck check (
    (kind <> 'readiness' or readiness_level is not null)
    and (kind <> 'elbow_check' or elbow_score is not null)
    and (kind <> 'pain' or (pain_body_region is not null and pain_severity is not null and pain_score is not null))
    and (kind <> 'soreness' or (soreness_muscle_group_id is not null and soreness_code is not null))
    and (kind <> 'srpe' or srpe is not null)
    and (kind <> 'sleep' or sleep_hours is not null))
);
create index wellness_logs_user_sync_idx on fitness.wellness_logs (user_id, server_updated_at);
create index wellness_logs_kind_idx on fitness.wellness_logs (user_id, kind, observed_at desc);
create index wellness_logs_session_idx on fitness.wellness_logs (session_id, user_id);
create index wellness_logs_session_exercise_idx on fitness.wellness_logs (session_exercise_id, user_id);
create index wellness_logs_set_idx on fitness.wellness_logs (set_log_id, user_id);
create index wellness_logs_body_region_idx on fitness.wellness_logs (pain_body_region);
create index wellness_logs_soreness_group_idx on fitness.wellness_logs (soreness_muscle_group_id);

-- ---------------------------------------------------------------------------------------------------
-- 9.6.6 metric_observations (weigh-ins and test results)
-- ---------------------------------------------------------------------------------------------------
create table fitness.metric_observations (
  id                         uuid primary key default gen_random_uuid(),
  user_id                    uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at                 timestamptz not null default now(),
  client_updated_at          timestamptz not null,
  server_updated_at          timestamptz not null default now(),
  deleted_at                 timestamptz,
  device_id                  uuid not null,
  app_version                text not null check (length(app_version) between 1 and 40),

  metric_code                text not null references fitness.metric_defs (code),
  value_num                  numeric(10,3),
  value_bool                 boolean,
  unit                       text not null check (unit in ('kg', 's', 'reps', 'pin_lb', 'bool')),
  side                       text not null default 'none' check (side in ('none', 'L', 'R')),
  observed_at                timestamptz not null,
  local_date                 date not null,
  source                     text not null check (source in ('manual', 'test_day', 'session_derived', 'import', 'health_connect')),
  session_id                 uuid,
  test_session_id            uuid,
  set_log_id                 uuid,
  enrollment_id              uuid,
  equipment_id               uuid,
  equipment_calibration_rev  int check (equipment_calibration_rev is null or equipment_calibration_rev >= 1),
  valid_for_gate             boolean not null default false,
  validity_note              text check (validity_note is null or length(validity_note) <= 300),
  protocol_code              text check (protocol_code is null or protocol_code ~ '^[a-z0-9_.]{1,80}$'),
  derivation                 text not null default 'measured'
                             check (derivation = 'measured' or derivation ~ '^calculated:[a-z0-9_]+$'),
  derived_from_ids           uuid[] not null default '{}',
  calc_params                jsonb not null default '{}' check (jsonb_typeof(calc_params) = 'object'),
  attempt_count              smallint check (attempt_count is null or attempt_count between 0 and 20),
  bodyweight_kg              numeric(5,2) check (bodyweight_kg is null or bodyweight_kg between 20 and 300),

  constraint metric_observations_id_user_uq unique (id, user_id),
  constraint metric_observations_session_fk foreign key (session_id, user_id)
    references fitness.workout_sessions (id, user_id),
  constraint metric_observations_test_session_fk foreign key (test_session_id, user_id)
    references fitness.workout_sessions (id, user_id),
  constraint metric_observations_set_fk foreign key (set_log_id, user_id) references fitness.set_logs (id, user_id),
  constraint metric_observations_enrollment_fk foreign key (enrollment_id, user_id)
    references fitness.challenge_enrollments (id, user_id),
  constraint metric_observations_equipment_fk foreign key (equipment_id, user_id)
    references fitness.gym_equipment (id, user_id),
  constraint metric_observations_one_value_ck check (num_nonnulls(value_num, value_bool) = 1),
  constraint metric_observations_gate_ck check (not valid_for_gate or enrollment_id is not null),
  constraint metric_observations_derivation_ck check (derivation = 'measured' or cardinality(derived_from_ids) > 0)
);
create index metric_observations_user_sync_idx on fitness.metric_observations (user_id, server_updated_at);
create index metric_observations_metric_idx on fitness.metric_observations (user_id, metric_code, observed_at desc);
create index metric_observations_code_idx on fitness.metric_observations (metric_code);
create index metric_observations_enrollment_idx on fitness.metric_observations (enrollment_id, user_id);
create index metric_observations_test_session_idx on fitness.metric_observations (test_session_id, user_id);
create index metric_observations_session_idx on fitness.metric_observations (session_id, user_id);
create index metric_observations_set_idx on fitness.metric_observations (set_log_id, user_id);
create index metric_observations_equipment_idx on fitness.metric_observations (equipment_id, user_id);

-- ---------------------------------------------------------------------------------------------------
-- RLS, grants, policies (USER template, 7.8) and triggers (7.1, 7.4, 7.7).
-- ---------------------------------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'workout_sessions', 'session_blocks', 'session_exercises', 'set_logs', 'wellness_logs', 'metric_observations'
  ] loop
    execute format('alter table fitness.%I enable row level security', t);
    execute format('revoke all on table fitness.%I from public, anon, authenticated', t);
    execute format('grant select, insert, update on table fitness.%I to authenticated', t);
    execute format('grant select, insert, update, delete on table fitness.%I to service_role', t);
    execute format(
      'create policy %I on fitness.%I for select to authenticated '
      'using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))',
      t || '_select', t);
    execute format(
      'create policy %I on fitness.%I for insert to authenticated '
      'with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))',
      t || '_insert', t);
    execute format(
      'create policy %I on fitness.%I for update to authenticated '
      'using ((select fitness_private.is_allowed()) and user_id = (select auth.uid())) '
      'with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))',
      t || '_update', t);
    execute format(
      'create trigger a_sync before insert or update on fitness.%I '
      'for each row execute function fitness_private.tg_sync_row()', t);
  end loop;
end;
$$;

create trigger b_exercise_visible
  before insert or update of exercise_id on fitness.session_exercises
  for each row execute function fitness_private.tg_check_exercise_visible();
create trigger b_exercise_visible
  before insert or update of exercise_id on fitness.set_logs
  for each row execute function fitness_private.tg_check_exercise_visible();
create trigger b_symptoms
  before insert or update of symptom_codes on fitness.wellness_logs
  for each row execute function fitness_private.tg_check_symptom_codes();

comment on table fitness.workout_sessions is 'One row per session (gym, home habit, test day); engine version, params and catalog pinned at start.';
comment on table fitness.session_blocks is 'C34 blocks of a session (challenge block, general or add-on block).';
comment on table fitness.session_exercises is 'Exercises of a block with the prescription summary and in-gym overrides.';
comment on table fitness.set_logs is 'Every logged set (one row per side), planned and actual values, effort, flags and rest (C55).';
comment on table fitness.wellness_logs is 'Readiness, elbow check, pain (PainEvent, C33), soreness, sRPE, sleep, illness.';
comment on table fitness.metric_observations is 'Weigh-ins and test results with validity and protocol versioning.';

-- Manual rollback (only with explicit user approval), reverse order:
--   drop table fitness.metric_observations; drop table fitness.wellness_logs; drop table fitness.set_logs;
--   drop table fitness.session_exercises; drop table fitness.session_blocks; drop table fitness.workout_sessions;
