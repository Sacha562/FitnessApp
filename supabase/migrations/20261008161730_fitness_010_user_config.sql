-- fitness_010_user_config (M1a) — DATA_MODEL.md 9.3.3-9.3.7, 6.1 (USER, USER-RO), 6.4, 7.1, 7.4, 7.8, 7.9, 8
--
-- Per-user configuration: equipment and load schemes (F7, C2), set/rep preferences, muscle goals (D6),
-- exercise preferences (C26) and the server-written per-user params overrides (F26).
-- Natural-key tables (rep_preferences, muscle_goals, user_exercise_prefs) get UUIDv5 ids from the client
-- (6.4) so two devices converge on one row.
--
-- USER: authenticated may select/insert/update own rows when allowlisted; no DELETE grant.
-- USER-RO (user_algo_params): authenticated may only select; rows are written by postgres after the
-- user approved a review's change list (F26). anon gets nothing.

-- ---------------------------------------------------------------------------------------------------
-- 9.3.3 gym_equipment (gym_id null = portable, for example the dip belt)
-- ---------------------------------------------------------------------------------------------------
create table fitness.gym_equipment (
  id                  uuid primary key default gen_random_uuid(),
  user_id             uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at          timestamptz not null default now(),
  client_updated_at   timestamptz not null,
  server_updated_at   timestamptz not null default now(),
  deleted_at          timestamptz,
  device_id           uuid not null,
  app_version         text not null check (length(app_version) between 1 and 40),

  gym_id              uuid,
  equipment_type      text not null references fitness.equipment_types (key),
  label               text not null check (length(label) between 1 and 80),
  available           boolean not null default true,
  scheme_type         text not null check (scheme_type in (
                        'plates', 'belt', 'dumbbells', 'stack', 'pin_assist', 'bodyweight', 'fixed')),
  load_scheme         jsonb not null check (jsonb_typeof(load_scheme) = 'object'),
  calibration_rev     int not null default 1 check (calibration_rev >= 1),
  calibration_source  text not null default 'default' check (calibration_source in ('default', 'user_estimate', 'measured')),
  confirmed_at        timestamptz,

  constraint gym_equipment_id_user_uq unique (id, user_id),
  constraint gym_equipment_scheme_ck check (load_scheme->>'type' = scheme_type),
  constraint gym_equipment_gym_fk foreign key (gym_id, user_id) references fitness.gyms (id, user_id)
);
create index gym_equipment_user_sync_idx on fitness.gym_equipment (user_id, server_updated_at);
create index gym_equipment_gym_idx on fitness.gym_equipment (gym_id, user_id);
create index gym_equipment_type_idx on fitness.gym_equipment (equipment_type);

-- ---------------------------------------------------------------------------------------------------
-- 9.3.4 rep_preferences (id = UUIDv5 rep_preferences:<uid>:<scope_key>)
-- ---------------------------------------------------------------------------------------------------
create table fitness.rep_preferences (
  id                 uuid primary key default gen_random_uuid(),
  user_id            uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at         timestamptz not null default now(),
  client_updated_at  timestamptz not null,
  server_updated_at  timestamptz not null default now(),
  deleted_at         timestamptz,
  device_id          uuid not null,
  app_version        text not null check (length(app_version) between 1 and 40),

  scope_kind         text not null check (scope_kind in ('global', 'goal', 'mechanics', 'muscle_group', 'exercise')),
  scope_key          text not null check (length(scope_key) between 1 and 80),
  goal_kind          text check (goal_kind is null or goal_kind in ('hypertrophy', 'strength')),
  mechanics          text check (mechanics is null or mechanics in ('compound', 'isolation')),
  muscle_group_id    text references fitness.muscle_groups (id),
  exercise_id        uuid references fitness.exercises (id),
  sets_pref          smallint check (sets_pref is null or sets_pref between 1 and 10),
  rep_min            smallint check (rep_min is null or rep_min between 1 and 100),
  rep_max            smallint check (rep_max is null or rep_max between 1 and 100),
  rir_target         numeric(3,1) check (rir_target is null or (rir_target between 0 and 5
                                         and rir_target * 2 = floor(rir_target * 2))),
  rep_scheme         text check (rep_scheme is null or rep_scheme in ('range', 'fixed')),
  rest_pref_s        smallint check (rest_pref_s is null or rest_pref_s between 30 and 600),

  constraint rep_preferences_id_user_uq unique (id, user_id),
  constraint rep_preferences_rep_order_ck check (rep_min is null or rep_max is null or rep_min <= rep_max),
  constraint rep_preferences_something_ck check (
    num_nonnulls(sets_pref, rep_min, rir_target, rep_scheme, rest_pref_s) > 0),
  -- The scope key, its kind and the matching typed column agree (9.3.4).
  constraint rep_preferences_scope_ck check (
    (scope_kind = 'global' and scope_key = 'global'
       and goal_kind is null and mechanics is null and muscle_group_id is null and exercise_id is null)
    or (scope_kind = 'goal' and goal_kind is not null and scope_key = 'goal:' || goal_kind
       and mechanics is null and muscle_group_id is null and exercise_id is null)
    or (scope_kind = 'mechanics' and mechanics is not null and scope_key = 'mechanics:' || mechanics
       and goal_kind is null and muscle_group_id is null and exercise_id is null)
    or (scope_kind = 'muscle_group' and muscle_group_id is not null and scope_key = 'muscle_group:' || muscle_group_id
       and goal_kind is null and mechanics is null and exercise_id is null)
    or (scope_kind = 'exercise' and exercise_id is not null and scope_key = 'exercise:' || exercise_id::text
       and goal_kind is null and mechanics is null and muscle_group_id is null))
);
create unique index rep_preferences_scope_uq on fitness.rep_preferences (user_id, scope_key) where deleted_at is null;
create index rep_preferences_user_sync_idx on fitness.rep_preferences (user_id, server_updated_at);
create index rep_preferences_muscle_group_idx on fitness.rep_preferences (muscle_group_id);
create index rep_preferences_exercise_idx on fitness.rep_preferences (exercise_id);

-- ---------------------------------------------------------------------------------------------------
-- 9.3.5 muscle_goals (id = UUIDv5 muscle_goals:<uid>:<group>)
-- ---------------------------------------------------------------------------------------------------
create table fitness.muscle_goals (
  id                 uuid primary key default gen_random_uuid(),
  user_id            uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at         timestamptz not null default now(),
  client_updated_at  timestamptz not null,
  server_updated_at  timestamptz not null default now(),
  deleted_at         timestamptz,
  device_id          uuid not null,
  app_version        text not null check (length(app_version) between 1 and 40),

  muscle_group_id    text not null references fitness.muscle_groups (id),
  goal               text not null check (goal in ('improve', 'maintain', 'skip')),
  focus              boolean not null default false,
  region_emphasis    text[] not null default '{}'
                     check (array_to_string(region_emphasis, ',') ~ '^([a-z][a-z0-9_]{0,63}(,|$))*$'),
  source             text not null default 'user' check (source in ('onboarding', 'preset', 'user')),
  preset_code        text check (preset_code is null or preset_code in (
                       'legs_only', 'no_legs', 'pull_focus', 'balanced', 'custom')),

  constraint muscle_goals_id_user_uq unique (id, user_id),
  constraint muscle_goals_focus_ck check (not focus or goal = 'improve')
);
create unique index muscle_goals_group_uq on fitness.muscle_goals (user_id, muscle_group_id) where deleted_at is null;
create index muscle_goals_user_sync_idx on fitness.muscle_goals (user_id, server_updated_at);
create index muscle_goals_group_idx on fitness.muscle_goals (muscle_group_id);

-- ---------------------------------------------------------------------------------------------------
-- 9.3.6 user_exercise_prefs (id = UUIDv5 user_exercise_prefs:<uid>:<exercise_id>)
-- ---------------------------------------------------------------------------------------------------
create table fitness.user_exercise_prefs (
  id                 uuid primary key default gen_random_uuid(),
  user_id            uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at         timestamptz not null default now(),
  client_updated_at  timestamptz not null,
  server_updated_at  timestamptz not null default now(),
  deleted_at         timestamptz,
  device_id          uuid not null,
  app_version        text not null check (length(app_version) between 1 and 40),

  exercise_id        uuid not null references fitness.exercises (id),
  preference         text not null default 'neutral'
                     check (preference in ('never', 'dislike', 'neutral', 'like', 'favorite')),
  unavailable_until  date,
  note               text check (note is null or length(note) <= 200),

  constraint user_exercise_prefs_id_user_uq unique (id, user_id)
);
create unique index user_exercise_prefs_exercise_uq on fitness.user_exercise_prefs (user_id, exercise_id)
  where deleted_at is null;
create index user_exercise_prefs_user_sync_idx on fitness.user_exercise_prefs (user_id, server_updated_at);
create index user_exercise_prefs_exercise_idx on fitness.user_exercise_prefs (exercise_id);

-- ---------------------------------------------------------------------------------------------------
-- 9.3.7 user_algo_params (USER-RO; written by postgres after the user's batch approval, F26)
-- ---------------------------------------------------------------------------------------------------
create table fitness.user_algo_params (
  id                    uuid primary key default gen_random_uuid(),
  user_id               uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at            timestamptz not null default now(),
  client_updated_at     timestamptz not null,
  server_updated_at     timestamptz not null default now(),
  deleted_at            timestamptz,
  device_id             uuid not null,
  app_version           text not null check (length(app_version) between 1 and 40),

  param_key             text not null check (param_key ~ '^[a-z]+(\.[a-z0-9_]+)+$'),
  module_id             text not null check (module_id ~ '^[a-z]+(\.[a-z]+)*$'),
  value                 jsonb not null,
  valid_from            timestamptz not null,
  source                text not null check (source in ('default', 'fitted', 'review', 'user')),
  engine_version_range  text check (engine_version_range is null or length(engine_version_range) between 1 and 60),
  review_ref            text check (review_ref is null or length(review_ref) <= 200),
  approved_at           timestamptz,
  note                  text check (note is null or length(note) <= 1000),

  constraint user_algo_params_id_user_uq unique (id, user_id),
  constraint user_algo_params_module_prefix_ck check (starts_with(param_key, module_id || '.')),
  constraint user_algo_params_approved_ck check (source not in ('review', 'fitted') or approved_at is not null)
);
create unique index user_algo_params_key_uq on fitness.user_algo_params (user_id, param_key, valid_from)
  where deleted_at is null;
create index user_algo_params_user_sync_idx on fitness.user_algo_params (user_id, server_updated_at);

-- ---------------------------------------------------------------------------------------------------
-- RLS, grants, policies (USER and USER-RO templates, 7.8) and triggers (7.1, 7.4).
-- ---------------------------------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array['gym_equipment', 'rep_preferences', 'muscle_goals', 'user_exercise_prefs'] loop
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
  before insert or update of exercise_id on fitness.rep_preferences
  for each row execute function fitness_private.tg_check_exercise_visible();
create trigger b_exercise_visible
  before insert or update of exercise_id on fitness.user_exercise_prefs
  for each row execute function fitness_private.tg_check_exercise_visible();

alter table fitness.user_algo_params enable row level security;
revoke all on table fitness.user_algo_params from public, anon, authenticated;
grant select on table fitness.user_algo_params to authenticated;
grant select, insert, update, delete on table fitness.user_algo_params to service_role;
create policy user_algo_params_select on fitness.user_algo_params for select to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create trigger a_sync before insert or update on fitness.user_algo_params
  for each row execute function fitness_private.tg_sync_row();

comment on table fitness.gym_equipment is 'Equipment and load schemes per gym (gym_id null = portable), F7 and C2.';
comment on table fitness.rep_preferences is 'Set/rep preferences by scope; deviations from evidence warn, never change silently (D10).';
comment on table fitness.muscle_goals is 'Per-group goals improve/maintain/skip with focus (D6, C14).';
comment on table fitness.user_exercise_prefs is 'Five-state exercise preference (C26) and temporary unavailability.';
comment on table fitness.user_algo_params is 'Per-user params overrides written by postgres after user approval (F26). Read-only for the app.';

-- Manual rollback (only with explicit user approval), reverse order:
--   drop table fitness.user_algo_params; drop table fitness.user_exercise_prefs; drop table fitness.muscle_goals;
--   drop table fitness.rep_preferences; drop table fitness.gym_equipment;
