-- fitness_041_catalog_1_0_3 (M4, data): catalog 1.0.3 - DECISIONS D57, D58; DATA_MODEL E42, 9.2.1
--
-- Delta release on 1.0.2, no DDL:
-- - D57: `two_arm_dead_hang_bar` leaves the bundled catalog (the picker listed "Two-arm dead hang" twice). The global
--   row stays for history with retired_at set; its stimulus row is soft-deleted and the user's preference row on it
--   (a "like") is soft-deleted, so the phone pulls the deletion. It has no set_logs, session_exercises, model states
--   or transfer ratios.
-- - D58: `high_risk` is D10's list only; the 11 barbell compounds move to `free_compound`.
-- exercises.catalog_version is "the version in which the row last changed", so these 12 rows move to 1.0.3.
-- jcs_sha256 = Jcs.sha256Hex of algo/src/main/resources/fitness/catalog/catalog-1.0.3.json (159 exercises, 539
-- stimulus rows), as printed by `gradlew :sim:run --args="seedgen-catalog 1.0.3"`. Idempotent.
-- Manual rollback (only with explicit user approval): set the 11 rows back to high_risk and 1.0.0, clear retired_at
-- and the two deleted_at values, delete the 1.0.3 release row.

insert into fitness.catalog_releases (version, exercise_count, stimulus_count, jcs_sha256, notes)
values ('1.0.3', 159, 539, '71d59f9e9505cf8affd65ffa7a960edae7d1381144a7e326e2ec69602d9e982b',
        'Delta on 1.0.2: two_arm_dead_hang_bar retired (one two-arm dead hang, D57); the 11 barbell compounds move from high_risk to free_compound (D58, user decisions 2026-10-30).')
on conflict (version) do nothing;

update fitness.user_exercise_prefs
set deleted_at = now(), client_updated_at = now(), app_version = 'migration'
where exercise_id = 'e34eac6c-7e49-505e-b50f-55250a9ced8a'::uuid and deleted_at is null;

update fitness.exercise_stimulus
set deleted_at = now(), client_updated_at = now(), app_version = 'migration'
where exercise_id = 'e34eac6c-7e49-505e-b50f-55250a9ced8a'::uuid and user_id is null and deleted_at is null;

update fitness.exercises
set retired_at = now(), catalog_version = '1.0.3', client_updated_at = now(), app_version = 'migration'
where id = 'e34eac6c-7e49-505e-b50f-55250a9ced8a'::uuid and slug = 'two_arm_dead_hang_bar' and user_id is null
  and retired_at is null;

update fitness.exercises
set safety_class = 'free_compound', catalog_version = '1.0.3', client_updated_at = now(), app_version = 'migration'
where user_id is null and safety_class = 'high_risk' and slug in (
  'back_squat_barbell', 'barbell_row_pronated', 'bench_press_barbell', 'close_grip_bench', 'deadlift_barbell',
  'decline_bench_barbell', 'front_squat_barbell', 'incline_bench_barbell', 'ohp_barbell', 'rdl_barbell', 'tbar_row');

-- Assertions: 159 active global exercises and 539 global stimulus rows, 17 high_risk rows, 12 rows at 1.0.3.
do $$
begin
  if (select count(*) from fitness.exercises where user_id is null and retired_at is null) <> 159 then
    raise exception 'catalog 1.0.3: expected 159 active global exercises';
  end if;
  if (select count(*) from fitness.exercise_stimulus where user_id is null and deleted_at is null) <> 539 then
    raise exception 'catalog 1.0.3: expected 539 global stimulus rows';
  end if;
  if (select count(*) from fitness.exercises where user_id is null and retired_at is null and safety_class = 'high_risk') <> 17 then
    raise exception 'catalog 1.0.3: expected 17 high_risk rows';
  end if;
  if (select count(*) from fitness.exercises where user_id is null and catalog_version = '1.0.3') <> 12 then
    raise exception 'catalog 1.0.3: expected 12 rows at 1.0.3';
  end if;
  if exists (select 1 from fitness.user_exercise_prefs where exercise_id = 'e34eac6c-7e49-505e-b50f-55250a9ced8a'::uuid and deleted_at is null) then
    raise exception 'catalog 1.0.3: a live preference still points at two_arm_dead_hang_bar';
  end if;
end;
$$;
