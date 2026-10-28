-- fitness_040_catalog_1_0_2_core_rotation (M3.6, DDL and data): the `core_rotation` pattern and catalog 1.0.2
-- - DATA_MODEL.md 8 (E36), 9.2.1, 9.2.2; DECISIONS D54
--
-- Delta release: the exercises.pattern CHECK gains `core_rotation` (woodchops and twists, family `core`, separate
-- from `core_anti_rot`), and two global exercises act as its prototypes for custom exercises: the cable woodchop
-- and the Russian twist, with their region coefficients. exercises.catalog_version is "the version in which the
-- row last changed", so only these two rows are at 1.0.2. Rows as printed by
-- `gradlew :sim:run --args="seedgen-catalog 1.0.2"`; jcs_sha256 = Jcs.sha256Hex of
-- algo/src/main/resources/fitness/catalog/catalog-1.0.2.json. Idempotent.
-- Manual rollback (only with explicit user approval): delete the two exercises' stimulus rows and exercises, the
-- 1.0.2 release row, and restore the CHECK without `core_rotation`.

alter table fitness.exercises drop constraint if exists exercises_pattern_check;
alter table fitness.exercises add constraint exercises_pattern_check check (pattern in (
  'squat', 'squat_machine', 'lunge', 'hinge', 'hip_ext', 'knee_ext', 'knee_flex',
  'hip_abd', 'hip_add', 'calf', 'horiz_push', 'incline_push', 'vert_push', 'dip',
  'chest_fly', 'lateral_raise', 'rear_delt_fly', 'horiz_pull', 'vert_pull',
  'straight_arm_pull', 'shrug', 'elbow_flex', 'elbow_ext', 'elbow_ext_overhead',
  'wrist_flex', 'wrist_ext', 'forearm_rot', 'grip_hold', 'ext_rotation', 'core_flex',
  'core_anti_ext', 'core_anti_rot', 'core_rotation', 'hang', 'skill_static', 'carry', 'conditioning'));

insert into fitness.catalog_releases (version, exercise_count, stimulus_count, jcs_sha256, notes)
values ('1.0.2', 160, 540, '4007d436c1eedbf3edafcb7fcf719c6349ec122e5c172246ea59d137927b2330',
        'Delta on 1.0.1: the core_rotation pattern with the cable woodchop and the Russian twist as prototypes (D54, user decision 2026-10-28).')
on conflict (version) do nothing;

insert into fitness.exercises (id, user_id, client_updated_at, device_id, app_version, slug, name_en, name_nl, aliases, pattern, family, equipment, requires_station, load_mode, allowed_load_modes, bw_fraction, implement_base_kg, laterality, measure, contraction, hold_class, ecc_emphasis, mechanics, curve_class, rest_class, safety_class, grip, stretch_emphasis, stability, skill, axial, systemic, sfr, setup_s, grip_demand, js_elbow_medial, js_elbow_lateral, js_shoulder_ant, js_shoulder_overhead, js_wrist, js_lumbar, js_knee, js_hip, tags, flags_offered, external_refs, evidence_note, catalog_version)
values
  ('9b82bde5-0820-5f1d-b995-285e39a9d8f1'::uuid, null, now(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration', 'cable_woodchop', 'Cable woodchop', 'Houthakker met kabel', array['wood chop', 'woodchopper']::text[], 'core_rotation', 'core', array['cable']::text[], array['cable_station']::text[], 'external', array['external']::text[], 0.0000, 0.00, 'unilateral', 'reps', 'dynamic', null, false, 'isolation', 'upper_isolation', 'core', 'cable', 'none', 0, 1, 2, 0, 1, 4, 45, 0, 0, 0, 0, 0, 0, 1, 0, 0, array['core']::text[], '{}'::text[], '{}'::jsonb, null, '1.0.2'),
  ('da41e524-de84-5b38-904e-c25baa1ecef1'::uuid, null, now(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration', 'russian_twist', 'Russian twist', 'Russian twist', '{}'::text[], 'core_rotation', 'core', array['bodyweight']::text[], '{}'::text[], 'external', array['external']::text[], 0.0000, 0.00, 'bilateral', 'reps', 'dynamic', null, false, 'isolation', 'upper_isolation', 'core', 'bodyweight', 'none', 0, 2, 1, 0, 1, 3, 10, 0, 0, 0, 0, 0, 0, 2, 0, 0, array['core', 'home']::text[], '{}'::text[], '{"fedb_id":"Russian_Twist"}'::jsonb, null, '1.0.2')
on conflict (id) do nothing;

insert into fitness.exercise_stimulus (id, user_id, client_updated_at, device_id, app_version, exercise_id, region_id, coeff)
values
  ('a91d871d-612e-5ec3-96f8-fc46a2ee685b'::uuid, null, now(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration', '9b82bde5-0820-5f1d-b995-285e39a9d8f1'::uuid, 'anti_rotation', 0.50),
  ('6efe9976-0a38-5c8c-9e8e-04deda1206d0'::uuid, null, now(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration', '9b82bde5-0820-5f1d-b995-285e39a9d8f1'::uuid, 'obliques', 1.00),
  ('f203ef0f-e03c-5989-844a-02e24fe5489c'::uuid, null, now(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration', '9b82bde5-0820-5f1d-b995-285e39a9d8f1'::uuid, 'rectus_abdominis', 0.25),
  ('8273869b-3dbe-51a1-9d26-3cc30140f3d1'::uuid, null, now(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration', 'da41e524-de84-5b38-904e-c25baa1ecef1'::uuid, 'obliques', 1.00),
  ('5e52ff4d-630a-5c3c-b467-1a7a7b5acee8'::uuid, null, now(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration', 'da41e524-de84-5b38-904e-c25baa1ecef1'::uuid, 'rectus_abdominis', 0.50)
on conflict (id) do nothing;

-- Assertions: 160 global exercises and 540 global stimulus rows, exactly the two new rows at 1.0.2.
do $$
begin
  if (select count(*) from fitness.exercises where user_id is null) <> 160 then
    raise exception 'catalog 1.0.2: expected 160 global exercises';
  end if;
  if (select count(*) from fitness.exercise_stimulus where user_id is null and deleted_at is null) <> 540 then
    raise exception 'catalog 1.0.2: expected 540 global stimulus rows';
  end if;
  if (select count(*) from fitness.exercises where user_id is null and catalog_version = '1.0.2') <> 2
     or (select count(*) from fitness.exercises where user_id is null and pattern = 'core_rotation') <> 2 then
    raise exception 'catalog 1.0.2: expected the two core_rotation rows at 1.0.2';
  end if;
end;
$$;
