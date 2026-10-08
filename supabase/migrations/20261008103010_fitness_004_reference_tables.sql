-- fitness_004_reference_tables (M0) — DATA_MODEL.md 9.1, 6.1 (class REF), 7.3, 7.8, 8, 13.2
--
-- Global reference tables, written only by migrations. authenticated may only SELECT, and only when
-- allowlisted (REF policy template). anon gets nothing. Every table has the a_touch trigger so the app
-- can pull by updated_at. Seed data follows in fitness_005_reference_seed.

-- Shared value-domain patterns (DATA_MODEL 8):
--   E54 slug ^[a-z][a-z0-9_]{0,63}$   E48 event type ^[a-z][a-z0-9]*(_[a-z0-9]+)+$
--   E49 module id ^[a-z]+(\.[a-z]+)*$   E50 semver   E51 hex sha256 ^[0-9a-f]{64}$
-- Arrays of slugs are checked by joining them with ',' and matching the whole string.

-- ---------------------------------------------------------------------------------------------------
-- 9.1.1 muscle_groups
-- ---------------------------------------------------------------------------------------------------
create table fitness.muscle_groups (
  id              text primary key check (id ~ '^[a-z][a-z0-9_]{0,63}$'),
  family          text not null check (family in ('push', 'pull', 'legs', 'core', 'other')),
  label_en        text not null check (length(label_en) between 1 and 40),
  label_nl        text check (label_nl is null or length(label_nl) between 1 and 40),
  aliases         text[] not null default '{}'
                  check (array_to_string(aliases, ',') ~ '^([a-z][a-z0-9_]{0,63}(,|$))*$'),
  is_optional     boolean not null default false,
  svg_region_ids  text[] not null default '{}',
  active          boolean not null default true,
  sort            smallint not null default 0,
  updated_at      timestamptz not null default now()
);

-- ---------------------------------------------------------------------------------------------------
-- 9.1.2 muscle_regions
-- ---------------------------------------------------------------------------------------------------
create table fitness.muscle_regions (
  id              text primary key check (id ~ '^[a-z][a-z0-9_]{0,63}$'),
  group_id        text references fitness.muscle_groups (id),
  is_prehab_only  boolean not null default false,
  aliases         text[] not null default '{}'
                  check (array_to_string(aliases, ',') ~ '^([a-z][a-z0-9_]{0,63}(,|$))*$'),
  label_en        text not null check (length(label_en) between 1 and 60),
  label_nl        text check (label_nl is null or length(label_nl) between 1 and 60),
  active          boolean not null default true,
  sort            smallint not null default 0,
  updated_at      timestamptz not null default now(),
  constraint muscle_regions_prehab_ck check ((group_id is null) = is_prehab_only)
);
create index muscle_regions_group_idx on fitness.muscle_regions (group_id);

-- ---------------------------------------------------------------------------------------------------
-- 9.1.3 reason_codes
-- ---------------------------------------------------------------------------------------------------
create table fitness.reason_codes (
  code          text primary key check (code ~ '^[a-z][a-z0-9_]{0,63}$'),
  label_en      text not null check (length(label_en) between 1 and 40),
  label_nl      text check (label_nl is null or length(label_nl) between 1 and 40),
  ui_contexts   text[] not null
                check (ui_contexts <@ array['easier', 'harder', 'skip', 'session_end', 'substitute']::text[]),
  is_safety     boolean not null default false,
  implies_flag  text check (implies_flag is null or implies_flag in (
                  'grip_slip_before_last', 'jerky', 'torso_rotation', 'hip_rotation',
                  'technique_breakdown', 'failed_rep', 'hand_shift', 'used_straps')),
  active        boolean not null default true,
  sort          smallint not null default 0,
  updated_at    timestamptz not null default now()
);

-- ---------------------------------------------------------------------------------------------------
-- 9.1.4 metric_defs
-- ---------------------------------------------------------------------------------------------------
create table fitness.metric_defs (
  code              text primary key check (code ~ '^[a-z][a-z0-9_]{0,63}$'),
  value_type        text not null check (value_type in ('number', 'int', 'bool')),
  unit              text not null check (unit in ('kg', 's', 'reps', 'pin_lb', 'bool')),
  side_mode         text not null default 'none' check (side_mode in ('none', 'LR')),
  better            text not null default 'none' check (better in ('higher', 'lower', 'true', 'none')),
  grid              numeric(6,2) check (grid is null or grid > 0),
  is_derived        boolean not null default false,
  display_only      boolean not null default false,
  introduced_by     text not null default 'core' check (introduced_by ~ '^(core|challenge:[a-z0-9_]+)$'),
  protocol_default  text check (protocol_default is null or length(protocol_default) <= 400),
  label_en          text not null check (length(label_en) between 1 and 80),
  label_nl          text check (label_nl is null or length(label_nl) between 1 and 80),
  active            boolean not null default true,
  sort              smallint not null default 0,
  updated_at        timestamptz not null default now(),
  constraint metric_defs_display_only_ck check (not display_only or is_derived)
);

-- ---------------------------------------------------------------------------------------------------
-- 9.1.5 body_regions
-- ---------------------------------------------------------------------------------------------------
create table fitness.body_regions (
  code             text primary key check (code ~ '^[a-z][a-z0-9_]{0,63}$'),
  joint_keys       text[] not null default '{}'
                   check (joint_keys <@ array['elbow_medial', 'elbow_lateral', 'shoulder_ant', 'shoulder_overhead',
                                              'wrist', 'lumbar', 'knee', 'hip']::text[]),
  side_applicable  boolean not null default true,
  label_en         text not null check (length(label_en) between 1 and 80),
  label_nl         text check (label_nl is null or length(label_nl) between 1 and 80),
  active           boolean not null default true,
  sort             smallint not null default 0,
  updated_at       timestamptz not null default now()
);

-- ---------------------------------------------------------------------------------------------------
-- 9.1.6 symptom_codes
-- ---------------------------------------------------------------------------------------------------
create table fitness.symptom_codes (
  code               text primary key check (code ~ '^[a-z][a-z0-9_]{0,63}$'),
  body_region_codes  text[] not null default '{}'
                     check (array_to_string(body_region_codes, ',') ~ '^([a-z][a-z0-9_]{0,63}(,|$))*$'),
  label_en           text not null check (length(label_en) between 1 and 80),
  label_nl           text check (label_nl is null or length(label_nl) between 1 and 80),
  active             boolean not null default true,
  sort               smallint not null default 0,
  updated_at         timestamptz not null default now()
);

-- ---------------------------------------------------------------------------------------------------
-- 9.1.7 equipment_types
-- ---------------------------------------------------------------------------------------------------
create table fitness.equipment_types (
  key                  text primary key check (key ~ '^[a-z][a-z0-9_]{0,63}$'),
  category             text not null check (category in ('station', 'implement', 'accessory', 'cardio', 'home')),
  default_scheme_type  text check (default_scheme_type is null or default_scheme_type in (
                         'plates', 'belt', 'dumbbells', 'stack', 'pin_assist', 'bodyweight', 'fixed')),
  default_load_scheme  jsonb check (default_load_scheme is null or jsonb_typeof(default_load_scheme) = 'object'),
  portable             boolean not null default false,
  label_en             text not null check (length(label_en) between 1 and 80),
  label_nl             text check (label_nl is null or length(label_nl) between 1 and 80),
  active               boolean not null default true,
  sort                 smallint not null default 0,
  updated_at           timestamptz not null default now(),
  constraint equipment_types_scheme_ck check (
    default_load_scheme is null or default_load_scheme->>'type' = default_scheme_type)
);

-- ---------------------------------------------------------------------------------------------------
-- 9.1.8 session_event_types (registry of 9.8.1; no FK from session_events; not pulled by the app)
-- ---------------------------------------------------------------------------------------------------
create table fitness.session_event_types (
  event_type     text primary key check (event_type ~ '^[a-z][a-z0-9]*(_[a-z0-9]+)+$'),
  category       text not null check (category in (
                   'lifecycle', 'logging', 'today', 'week', 'autoreg', 'warning', 'prompt', 'rest',
                   'calendar', 'sync', 'config', 'system', 'challenge', 'export')),
  payload_keys   text[] not null default '{}'
                 check (array_to_string(payload_keys, ',') ~ '^([a-z][a-z0-9_]{0,63}(,|$))*$'),
  introduced_in  text not null check (length(introduced_in) between 1 and 40),
  description    text not null check (length(description) between 1 and 400),
  active         boolean not null default true,
  sort           smallint not null default 0,
  updated_at     timestamptz not null default now()
);

-- ---------------------------------------------------------------------------------------------------
-- 9.1.10 algo_params (versioned default constants; keys <module>.<constant>, E53)
-- ---------------------------------------------------------------------------------------------------
create table fitness.algo_params (
  params_version        text primary key
                        check (params_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  status                text not null default 'draft' check (status in ('draft', 'active', 'superseded')),
  engine_version_range  text not null check (length(engine_version_range) between 1 and 60),
  params                jsonb not null check (jsonb_typeof(params) = 'object'),
  params_hash           text not null check (params_hash ~ '^[0-9a-f]{64}$'),
  released_at           timestamptz,
  notes                 text check (notes is null or length(notes) <= 2000),
  updated_at            timestamptz not null default now()
);
create unique index algo_params_one_active on fitness.algo_params (status) where status = 'active';

-- ---------------------------------------------------------------------------------------------------
-- 9.1.9 algorithm_versions (every module id@version, C30)
-- ---------------------------------------------------------------------------------------------------
create table fitness.algorithm_versions (
  module_id               text not null check (module_id ~ '^[a-z]+(\.[a-z]+)*$'),
  version                 text not null check (version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  layer                   text not null check (layer in ('core', 'challenge', 'accounting', 'estimation', 'targets',
                                                         'planning', 'selection', 'prescription', 'ui', 'offline')),
  engine_version          text not null
                          check (engine_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  status                  text not null default 'active' check (status in ('active', 'shadow', 'deprecated')),
  default_params_version  text not null references fitness.algo_params (params_version),
  constants_hash          text not null check (constants_hash ~ '^[0-9a-f]{64}$'),
  git_sha                 text check (git_sha is null or git_sha ~ '^[0-9a-f]{7,40}$'),
  released_at             timestamptz not null default now(),
  spec_ref                text check (spec_ref is null or length(spec_ref) <= 200),
  description             text check (description is null or length(description) <= 400),
  updated_at              timestamptz not null default now(),
  primary key (module_id, version)
);
create index algorithm_versions_params_idx on fitness.algorithm_versions (default_params_version);

-- ---------------------------------------------------------------------------------------------------
-- 9.1.11 catalog_releases
-- ---------------------------------------------------------------------------------------------------
create table fitness.catalog_releases (
  version         text primary key check (version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  released_at     timestamptz not null default now(),
  exercise_count  int not null check (exercise_count >= 0),
  stimulus_count  int not null check (stimulus_count >= 0),
  jcs_sha256      text not null check (jcs_sha256 ~ '^[0-9a-f]{64}$'),
  notes           text check (notes is null or length(notes) <= 2000),
  updated_at      timestamptz not null default now()
);

-- ---------------------------------------------------------------------------------------------------
-- RLS, grants, policies and touch triggers (REF template, 7.8 / 7.3) for every table above.
-- ---------------------------------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'muscle_groups', 'muscle_regions', 'reason_codes', 'metric_defs', 'body_regions', 'symptom_codes',
    'equipment_types', 'session_event_types', 'algo_params', 'algorithm_versions', 'catalog_releases'
  ] loop
    execute format('alter table fitness.%I enable row level security', t);
    execute format('revoke all on table fitness.%I from public, anon, authenticated', t);
    execute format('grant select on table fitness.%I to authenticated', t);
    execute format('grant select, insert, update, delete on table fitness.%I to service_role', t);
    execute format(
      'create policy %I on fitness.%I for select to authenticated using ((select fitness_private.is_allowed()))',
      t || '_select', t);
    execute format(
      'create trigger a_touch before insert or update on fitness.%I '
      'for each row execute function fitness_private.tg_touch_reference()', t);
  end loop;
end;
$$;

comment on table fitness.muscle_groups is 'C9: the 18 goal/volume muscle groups (+ inactive neck).';
comment on table fitness.muscle_regions is 'C9/C44: 34 regions (+ inactive neck) for variety and challenge mapping.';
comment on table fitness.reason_codes is 'REASON-1 override/skip reasons (labels and UI contexts only).';
comment on table fitness.metric_defs is 'Metric definitions referenced by challenge definitions and metric_observations.';
comment on table fitness.body_regions is 'Pain locations and the INJ-1 joint keys they flag (C33).';
comment on table fitness.symptom_codes is 'Symptom chips of the pain sheet.';
comment on table fitness.equipment_types is 'Station/implement keys and default load schemes (F7).';
comment on table fitness.session_event_types is 'Registry of session_events.event_type (C39). Reviews only.';
comment on table fitness.algo_params is 'Versioned default engine params (A8); app verifies params_hash.';
comment on table fitness.algorithm_versions is 'Registry of module id@version with constants_hash (C30).';
comment on table fitness.catalog_releases is 'Catalog versions stored in snapshots (C24).';

-- Manual rollback (only with explicit user approval), reverse order:
--   drop table fitness.catalog_releases, fitness.algorithm_versions, fitness.algo_params,
--     fitness.session_event_types, fitness.equipment_types, fitness.symptom_codes, fitness.body_regions,
--     fitness.metric_defs, fitness.reason_codes, fitness.muscle_regions, fitness.muscle_groups;
