-- fitness_015_telemetry_cache_exports (M1a) — DATA_MODEL.md 9.8, 6.1 (APPEND, CACHE), 7.1, 7.2, 7.4, 7.8, 8, 13.2
--
-- session_events: the single telemetry stream (C24, C39; registry in session_event_types, no FK).
-- exercise_model_state: rebuildable estimator caches (CACHE, last-writer-wins).
-- exports: log of data exports (F4, C40: json_gz container).

-- ---------------------------------------------------------------------------------------------------
-- 9.8.1 session_events (APPEND)
-- ---------------------------------------------------------------------------------------------------
create table fitness.session_events (
  id                 uuid primary key default gen_random_uuid(),
  user_id            uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at         timestamptz not null default now(),
  server_updated_at  timestamptz not null default now(),
  device_id          uuid not null,
  app_version        text not null check (length(app_version) between 1 and 40),

  event_type         text not null check (event_type ~ '^[a-z][a-z0-9]*(_[a-z0-9]+)+$'),
  category           text not null check (category in (
                       'lifecycle', 'logging', 'today', 'week', 'autoreg', 'warning', 'prompt', 'rest',
                       'calendar', 'sync', 'config', 'system', 'challenge', 'export')),
  occurred_at        timestamptz not null,
  tz_offset_min      smallint not null check (tz_offset_min between -840 and 840),
  screen             text check (screen is null or length(screen) <= 40),
  session_id         uuid,
  entity_type        text check (entity_type is null or entity_type in (
                       'set', 'session_exercise', 'session', 'block', 'recommendation', 'calendar_event',
                       'enrollment', 'exercise', 'gym_equipment', 'wellness_log', 'export', 'none')),
  entity_id          uuid,              -- soft reference
  recommendation_id  uuid,              -- soft reference
  module_id          text check (module_id is null or module_id ~ '^[a-z]+(\.[a-z]+)*$'),
  module_version     text check (module_version is null
                                 or module_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  ui_version         text check (ui_version is null or length(ui_version) <= 20),
  payload            jsonb not null default '{}'
                     check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 8192),

  constraint session_events_session_fk foreign key (session_id, user_id)
    references fitness.workout_sessions (id, user_id)
);
create index session_events_type_idx on fitness.session_events (user_id, event_type, occurred_at);
create index session_events_session_idx on fitness.session_events (session_id, user_id);
create index session_events_user_sync_idx on fitness.session_events (user_id, server_updated_at);

-- ---------------------------------------------------------------------------------------------------
-- 9.8.2 exercise_model_state (CACHE; id = UUIDv5 exercise_model_state:<uid>:<model>:<exercise_id|->:<side>:<class_key|->)
-- ---------------------------------------------------------------------------------------------------
create table fitness.exercise_model_state (
  id                 uuid primary key default gen_random_uuid(),
  user_id            uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at         timestamptz not null default now(),
  client_updated_at  timestamptz not null,
  server_updated_at  timestamptz not null default now(),
  deleted_at         timestamptz,
  device_id          uuid not null,
  app_version        text not null check (length(app_version) between 1 and 40),

  model              text not null check (model in (
                       'sse', 'rcm', 'rircal', 'fatigue', 'hold', 'readiness', 'time', 'rest', 'stall', 'pref')),
  exercise_id        uuid references fitness.exercises (id),
  side               text not null default 'both' check (side in ('both', 'L', 'R')),
  class_key          text check (class_key is null or class_key ~ '^[a-z][a-z0-9_]{0,63}$'),
  module_version     text not null check (module_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  engine_version     text not null check (engine_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  params_hash        text not null check (params_hash ~ '^[0-9a-f]{64}$'),
  state              jsonb not null check (jsonb_typeof(state) = 'object'),
  state_hash         text not null check (state_hash ~ '^[0-9a-f]{64}$'),
  n_observations     int not null default 0 check (n_observations >= 0),
  as_of              timestamptz not null,
  as_of_session_id   uuid,             -- soft reference

  constraint exercise_model_state_id_user_uq unique (id, user_id),
  constraint exercise_model_state_subject_ck check (exercise_id is not null or class_key is not null)
);
create unique index exercise_model_state_key_uq on fitness.exercise_model_state
  (user_id, model, exercise_id, side, class_key) nulls not distinct where deleted_at is null;
create index exercise_model_state_user_sync_idx on fitness.exercise_model_state (user_id, server_updated_at);
create index exercise_model_state_exercise_idx on fitness.exercise_model_state (exercise_id);

-- ---------------------------------------------------------------------------------------------------
-- 9.8.3 exports (APPEND)
-- ---------------------------------------------------------------------------------------------------
create table fitness.exports (
  id                   uuid primary key default gen_random_uuid(),
  user_id              uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at           timestamptz not null default now(),
  server_updated_at    timestamptz not null default now(),
  device_id            uuid not null,
  app_version          text not null check (length(app_version) between 1 and 40),

  export_kind          text not null check (export_kind in ('monthly_full', 'manual_full', 'review_bundle')),
  format               text not null default 'json_gz' check (format in ('json_gz', 'json')),
  schema_version       text not null check (schema_version ~ '^fitness-export/[0-9]+$'),
  table_row_counts     jsonb not null check (jsonb_typeof(table_row_counts) = 'object'),
  bytes                bigint not null check (bytes >= 0),
  sha256               text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  destination          text not null check (destination in ('device_storage', 'share_sheet', 'drive')),
  period_start         timestamptz,
  period_end           timestamptz,
  includes_local_only  boolean not null default true,
  engine_version       text not null check (engine_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  catalog_version      text not null check (catalog_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')
);
create index exports_user_sync_idx on fitness.exports (user_id, server_updated_at);

-- ---------------------------------------------------------------------------------------------------
-- RLS, grants, policies (APPEND and CACHE templates, 7.8) and triggers (7.1, 7.2, 7.4).
-- ---------------------------------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array['session_events', 'exports'] loop
    execute format('alter table fitness.%I enable row level security', t);
    execute format('revoke all on table fitness.%I from public, anon, authenticated', t);
    execute format('grant select, insert on table fitness.%I to authenticated', t);
    execute format('grant select, insert on table fitness.%I to service_role', t);
    execute format(
      'create policy %I on fitness.%I for select to authenticated '
      'using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))',
      t || '_select', t);
    execute format(
      'create policy %I on fitness.%I for insert to authenticated '
      'with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))',
      t || '_insert', t);
    execute format(
      'create trigger a_append before insert or update on fitness.%I '
      'for each row execute function fitness_private.tg_append_only()', t);
  end loop;
end;
$$;

alter table fitness.exercise_model_state enable row level security;
revoke all on table fitness.exercise_model_state from public, anon, authenticated;
grant select, insert, update on table fitness.exercise_model_state to authenticated;
grant select, insert, update, delete on table fitness.exercise_model_state to service_role;
create policy exercise_model_state_select on fitness.exercise_model_state for select to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create policy exercise_model_state_insert on fitness.exercise_model_state for insert to authenticated
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create policy exercise_model_state_update on fitness.exercise_model_state for update to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create trigger a_sync before insert or update on fitness.exercise_model_state
  for each row execute function fitness_private.tg_sync_row();
create trigger b_exercise_visible
  before insert or update of exercise_id on fitness.exercise_model_state
  for each row execute function fitness_private.tg_check_exercise_visible();

comment on table fitness.session_events is 'Single telemetry stream (C24, C39); payloads hold codes, numbers and ids only.';
comment on table fitness.exercise_model_state is 'Rebuildable estimator state caches; never authoritative.';
comment on table fitness.exports is 'Log of data exports (F4); the file itself stays on the device or share target.';

-- Manual rollback (only with explicit user approval), reverse order:
--   drop table fitness.exports; drop table fitness.exercise_model_state; drop table fitness.session_events;
