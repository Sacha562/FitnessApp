-- fitness_008_catalog_tables (M1a) — DATA_MODEL.md 9.2, 6.1 (class MIX, REF), 7.1, 7.3, 7.4, 7.8, 8, 13.2
--
-- The exercise catalog: global curated rows (user_id is null, written only by migrations with
-- device_id = SERVER_DEVICE_ID and app_version = 'migration') plus the user's own custom rows (MIX).
-- exercise_stimulus holds the CAT-1 region coefficients (absent row = 0); exercise_transfer_ratios is
-- global reference data for SUB-1. The seed follows in fitness_009_catalog_seed.
--
-- MIX: authenticated may read global and own rows and insert/update only own rows; no DELETE grant
-- (soft delete via deleted_at). REF: authenticated may only read. anon gets nothing.

-- Value domains used below (DATA_MODEL 8):
--   E3 load_mode, E36 pattern, E37 laterality, E38 measure, E39 contraction, E40 curve_class,
--   E41 rest_class, E42 safety_class, E43 grip, E45 equipment class, E54 slug, E55 hold_class.

-- ---------------------------------------------------------------------------------------------------
-- 9.2.1 exercises (MIX)
-- ---------------------------------------------------------------------------------------------------
create table fitness.exercises (
  id                     uuid primary key default gen_random_uuid(),
  user_id                uuid default auth.uid() references auth.users (id) on delete cascade,
  created_at             timestamptz not null default now(),
  client_updated_at      timestamptz not null,
  server_updated_at      timestamptz not null default now(),
  deleted_at             timestamptz,
  device_id              uuid not null,
  app_version            text not null check (length(app_version) between 1 and 40),

  slug                   text not null check (slug ~ '^[a-z][a-z0-9_]{0,63}$'),
  name_en                text not null check (length(name_en) between 1 and 80),
  name_nl                text check (name_nl is null or length(name_nl) between 1 and 80),
  aliases                text[] not null default '{}',
  pattern                text not null check (pattern in (
                           'squat', 'squat_machine', 'lunge', 'hinge', 'hip_ext', 'knee_ext', 'knee_flex',
                           'hip_abd', 'hip_add', 'calf', 'horiz_push', 'incline_push', 'vert_push', 'dip',
                           'chest_fly', 'lateral_raise', 'rear_delt_fly', 'horiz_pull', 'vert_pull',
                           'straight_arm_pull', 'shrug', 'elbow_flex', 'elbow_ext', 'elbow_ext_overhead',
                           'wrist_flex', 'wrist_ext', 'forearm_rot', 'grip_hold', 'ext_rotation', 'core_flex',
                           'core_anti_ext', 'core_anti_rot', 'hang', 'skill_static', 'carry', 'conditioning')),
  family                 text not null check (family ~ '^[a-z][a-z0-9_]{0,63}$'),
  equipment              text[] not null
                         check (cardinality(equipment) > 0
                                and equipment <@ array['barbell', 'dumbbell', 'machine', 'cable', 'bodyweight',
                                                       'smith', 'band', 'kettlebell', 'other']::text[]),
  requires_station       text[] not null default '{}'
                         check (array_to_string(requires_station, ',') ~ '^([a-z][a-z0-9_]{0,63}(,|$))*$'),
  load_mode              text not null check (load_mode in (
                           'external', 'bodyweight', 'bodyweight_plus_external', 'assisted_machine', 'assisted_ordinal')),
  allowed_load_modes     text[] not null
                         check (cardinality(allowed_load_modes) > 0
                                and allowed_load_modes <@ array['external', 'bodyweight', 'bodyweight_plus_external',
                                                                'assisted_machine', 'assisted_ordinal']::text[]),
  bw_fraction            numeric(5,4) not null default 0 check (bw_fraction between 0 and 1),
  implement_base_kg      numeric(6,2) check (implement_base_kg is null or implement_base_kg >= 0),
  laterality             text not null check (laterality in ('bilateral', 'unilateral', 'alternating')),
  measure                text not null check (measure in ('reps', 'duration', 'attempt', 'checklist')),
  contraction            text not null default 'dynamic'
                         check (contraction in ('dynamic', 'eccentric_only', 'isometric', 'hang')),
  hold_class             text check (hold_class is null or hold_class in ('grip', 'elbow', 'shoulder', 'trunk', 'knee', 'ankle')),
  ecc_emphasis           boolean not null default false,
  mechanics              text not null check (mechanics in ('compound', 'isolation', 'skill', 'conditioning')),
  curve_class            text check (curve_class is null or curve_class in (
                           'upper_compound', 'lower_machine_compound', 'lower_free_compound', 'upper_isolation',
                           'lower_isolation')),
  rest_class             text not null check (rest_class in (
                           'strength_heavy', 'compound_lower', 'compound_upper', 'isolation', 'hold', 'core')),
  safety_class           text not null check (safety_class in (
                           'machine', 'cable', 'isolation', 'free_compound', 'bodyweight', 'high_risk')),
  grip                   text not null default 'none' check (grip in ('supinated', 'neutral', 'pronated', 'mixed', 'none')),
  stretch_emphasis       smallint not null default 0 check (stretch_emphasis between 0 and 2),
  stability              smallint not null default 1 check (stability between 1 and 3),
  skill                  smallint not null default 1 check (skill between 1 and 3),
  axial                  smallint not null default 0 check (axial between 0 and 3),
  systemic               smallint not null default 1 check (systemic between 1 and 3),
  sfr                    smallint not null default 3 check (sfr between 1 and 5),
  setup_s                smallint not null default 30 check (setup_s between 0 and 600),
  grip_demand            smallint not null default 0 check (grip_demand between 0 and 3),
  js_elbow_medial        smallint not null default 0 check (js_elbow_medial between 0 and 3),
  js_elbow_lateral       smallint not null default 0 check (js_elbow_lateral between 0 and 3),
  js_shoulder_ant        smallint not null default 0 check (js_shoulder_ant between 0 and 3),
  js_shoulder_overhead   smallint not null default 0 check (js_shoulder_overhead between 0 and 3),
  js_wrist               smallint not null default 0 check (js_wrist between 0 and 3),
  js_lumbar              smallint not null default 0 check (js_lumbar between 0 and 3),
  js_knee                smallint not null default 0 check (js_knee between 0 and 3),
  js_hip                 smallint not null default 0 check (js_hip between 0 and 3),
  tags                   text[] not null default '{}'
                         check (array_to_string(tags, ',') ~ '^([a-z][a-z0-9_]{0,63}(,|$))*$'),
  flags_offered          text[] not null default '{}'
                         check (flags_offered <@ array['grip_slip_before_last', 'jerky', 'torso_rotation', 'hip_rotation',
                                                       'technique_breakdown', 'failed_rep', 'hand_shift', 'used_straps']::text[]),
  external_refs          jsonb not null default '{}' check (jsonb_typeof(external_refs) = 'object'),
  evidence_note          text check (evidence_note is null or length(evidence_note) <= 1000),
  prototype_exercise_id  uuid references fitness.exercises (id),
  catalog_version        text not null references fitness.catalog_releases (version),
  retired_at             timestamptz,

  constraint exercises_slug_uq unique nulls not distinct (user_id, slug),
  constraint exercises_id_user_uq unique (id, user_id),
  constraint exercises_load_mode_allowed_ck check (load_mode = any (allowed_load_modes)),
  -- C1: a bodyweight-based mode needs a bodyweight fraction (CAT-1 V5 also checks the converse).
  constraint exercises_bw_fraction_ck check (
    not (allowed_load_modes && array['bodyweight', 'bodyweight_plus_external', 'assisted_machine',
                                     'assisted_ordinal']::text[])
    or bw_fraction > 0),
  -- C43: holds and hangs carry a hold class, nothing else does.
  constraint exercises_hold_class_ck check ((hold_class is not null) = (contraction in ('isometric', 'hang')))
);
create index exercises_user_sync_idx on fitness.exercises (user_id, server_updated_at);
create index exercises_pattern_idx on fitness.exercises (pattern);
create index exercises_prototype_idx on fitness.exercises (prototype_exercise_id);
create index exercises_catalog_version_idx on fitness.exercises (catalog_version);

-- ---------------------------------------------------------------------------------------------------
-- 9.2.2 exercise_stimulus (MIX)
-- ---------------------------------------------------------------------------------------------------
create table fitness.exercise_stimulus (
  id                 uuid primary key default gen_random_uuid(),
  user_id            uuid default auth.uid() references auth.users (id) on delete cascade,
  created_at         timestamptz not null default now(),
  client_updated_at  timestamptz not null,
  server_updated_at  timestamptz not null default now(),
  deleted_at         timestamptz,
  device_id          uuid not null,
  app_version        text not null check (length(app_version) between 1 and 40),

  exercise_id        uuid not null references fitness.exercises (id),
  region_id          text not null references fitness.muscle_regions (id),
  coeff              numeric(3,2) not null check (coeff in (0.25, 0.50, 0.75, 1.00)),
  evidence           text check (evidence is null or length(evidence) <= 200),
  note               text check (note is null or length(note) <= 500),

  constraint exercise_stimulus_id_user_uq unique (id, user_id),
  -- MATCH SIMPLE: skipped for global rows (user_id null), enforced for user rows.
  constraint exercise_stimulus_exercise_owner_fk foreign key (exercise_id, user_id)
    references fitness.exercises (id, user_id)
);
create unique index exercise_stimulus_live_uq on fitness.exercise_stimulus (exercise_id, region_id)
  where deleted_at is null;
create index exercise_stimulus_exercise_idx on fitness.exercise_stimulus (exercise_id);
create index exercise_stimulus_exercise_owner_idx on fitness.exercise_stimulus (exercise_id, user_id);
create index exercise_stimulus_region_idx on fitness.exercise_stimulus (region_id);
create index exercise_stimulus_user_sync_idx on fitness.exercise_stimulus (user_id, server_updated_at);

-- ---------------------------------------------------------------------------------------------------
-- 9.2.3 exercise_transfer_ratios (REF; global exercises only, written by the seed)
-- ---------------------------------------------------------------------------------------------------
create table fitness.exercise_transfer_ratios (
  from_exercise_id  uuid not null references fitness.exercises (id),
  to_exercise_id    uuid not null references fitness.exercises (id),
  ratio             numeric(6,4) not null check (ratio between 0.2000 and 3.0000),
  evidence          text not null default 'expert-opinion' check (length(evidence) between 1 and 200),
  catalog_version   text not null references fitness.catalog_releases (version),
  active            boolean not null default true,
  updated_at        timestamptz not null default now(),
  primary key (from_exercise_id, to_exercise_id),
  constraint exercise_transfer_ratios_distinct_ck check (from_exercise_id <> to_exercise_id)
);
create index exercise_transfer_ratios_to_idx on fitness.exercise_transfer_ratios (to_exercise_id);
create index exercise_transfer_ratios_catalog_version_idx on fitness.exercise_transfer_ratios (catalog_version);

-- ---------------------------------------------------------------------------------------------------
-- RLS, grants, policies (MIX and REF templates, 7.8) and triggers (7.1, 7.3, 7.4).
-- ---------------------------------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array['exercises', 'exercise_stimulus'] loop
    execute format('alter table fitness.%I enable row level security', t);
    execute format('revoke all on table fitness.%I from public, anon, authenticated', t);
    execute format('grant select, insert, update on table fitness.%I to authenticated', t);
    execute format('grant select, insert, update, delete on table fitness.%I to service_role', t);
    execute format(
      'create policy %I on fitness.%I for select to authenticated '
      'using ((select fitness_private.is_allowed()) and (user_id is null or user_id = (select auth.uid())))',
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
  before insert or update of exercise_id on fitness.exercise_stimulus
  for each row execute function fitness_private.tg_check_exercise_visible();

alter table fitness.exercise_transfer_ratios enable row level security;
revoke all on table fitness.exercise_transfer_ratios from public, anon, authenticated;
grant select on table fitness.exercise_transfer_ratios to authenticated;
grant select, insert, update, delete on table fitness.exercise_transfer_ratios to service_role;
create policy exercise_transfer_ratios_select on fitness.exercise_transfer_ratios
  for select to authenticated using ((select fitness_private.is_allowed()));
create trigger a_touch before insert or update on fitness.exercise_transfer_ratios
  for each row execute function fitness_private.tg_touch_reference();

comment on table fitness.exercises is
  'CAT-1 catalog (MIX): global curated rows (user_id null) plus user custom exercises. Ids of global rows are UUIDv5 exercise:<slug>.';
comment on table fitness.exercise_stimulus is
  'CAT-1 region coefficients in {0.25, 0.5, 0.75, 1.0} (C10); absent row = 0.';
comment on table fitness.exercise_transfer_ratios is
  'SUB-1 start-load transfer between global exercises on the effective-load scale (expert-opinion).';

-- Manual rollback (only with explicit user approval), reverse order:
--   drop table fitness.exercise_transfer_ratios; drop table fitness.exercise_stimulus; drop table fitness.exercises;
