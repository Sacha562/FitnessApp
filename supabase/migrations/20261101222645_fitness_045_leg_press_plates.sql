-- fitness_045_leg_press_plates (M4, data): plate-loaded 45° leg press, weighted back extension, landmine, calves in leg curls -
-- DECISIONS D64-D67;
-- DATA_MODEL 9.1.7; catalog 1.0.4
--
-- No DDL. Basic-Fit has a plate-loaded 45° sled and a seated stack leg press; F7 had one `leg_press` station with a
-- stack, so the sled asked for lb.
-- - equipment_types: `leg_press` is the 45° sled, `plates` in kg with `bar_kg` null by default (as the Smith machine);
--   the new `leg_press_seated` station keeps the stack default.
-- - catalog 1.0.4: `leg_press_seated` requires `leg_press_seated` (the other three leg press exercises stay on the
--   sled, D64); `back_extension_45` is bodyweight (f_bw 0.6) with an optional held plate (D65); `tbar_row` (alias
--   "landmine row") and `landmine_press` require the new `landmine` station (one sleeve of plates, kg stack grid) and
--   count 10 kg of the bar (D66). The phone adds the user's `landmine` row from the checklist on its next start.
--   `lying_leg_curl` and `seated_leg_curl` gain gastrocnemius 0.25, fatigue only under C44 (D67): stimulus rows 541. exercises.catalog_version is "the version in which the row last changed". jcs_sha256 = Jcs.sha256Hex of
--   algo/src/main/resources/fitness/catalog/catalog-1.0.4.json (159 exercises, 541 stimulus rows), as printed by
--   `gradlew :sim:run --args="seedgen-catalog 1.0.4"`.
-- - The user's `leg_press` row (a kg stack set up in the session of 1 Nov, calibration_rev 4) becomes the sled:
--   plates from the user's barbell row, `bar_kg` 75.7 (the sled mass the user gave), calibration_rev 5, measured.
--   The app adds the `leg_press_seated` row from the saved checklist (lb stack) on its next start (EquipmentUpgrade).
-- - The four leg_press_45 sets of 1 Nov were 80 / 140 / 140 / 140 kg of plates (the user, 2026-11-01; the first three
--   were typed as lb after converting): they become plates + sled, 155.7 / 215.7 / 215.7 / 215.7 kg, on rev 5.
--   Refuses to run while that session is still in progress.
-- - The back extension sets of 1 Nov (D65): set 1 bodyweight x 12, set 2 BW + 10 kg x 12 (held plate, resolved on the
--   dip belt's plate inventory as every BW + kg load), set 3 BW + 20 kg x 10 (the user, 2026-11-01); f_bw 0.6 and the
--   session's bodyweight.
-- - The T-bar row sets of 1 Nov (D66) were entered as barbell totals (bar 20 + plates): 40 and 70 kg were 20 and 50 kg
--   of plates on the sleeve (the user, 2026-11-01); sets 3 and 4, also entered as 70 kg, were the same 50 kg. They become plates 20 / 50 kg plus the 10 kg bar share, without an
--   equipment row (the phone creates the `landmine` row itself).
-- Manual rollback (only with explicit user approval): restore the four sets and the gym_equipment row from the values
-- in the guards below, put leg_press_seated back on leg_press at 1.0.0, delete the 1.0.4 release and the
-- leg_press_seated type, and set leg_press back to the stack default.

do $$
begin
  if (select status from fitness.workout_sessions where id = '01a198bf-2566-71dc-a23d-6a73a3573b3f'::uuid) is distinct from 'finished' then
    raise exception '045: the session of 1 Nov is not finished yet';
  end if;
  if not exists (select 1 from fitness.gym_equipment where id = '01a11fea-12e8-75bf-8c4f-f044bc36240d'::uuid
                 and equipment_type = 'leg_press' and scheme_type = 'stack' and calibration_rev = 4 and deleted_at is null) then
    raise exception '045: the leg_press row is not the kg stack at calibration_rev 4';
  end if;
  if (select count(*) from fitness.set_logs where session_exercise_id = '01a198bf-4851-77c3-a491-5d30cb18af7a'::uuid and deleted_at is null) <> 4 then
    raise exception '045: expected exactly 4 leg press sets in the session of 1 Nov';
  end if;
end;
$$;

update fitness.equipment_types
set default_scheme_type = 'plates',
    default_load_scheme = (select default_load_scheme from fitness.equipment_types where key = 'smith_machine'),
    label_en = '45° leg press', label_nl = 'Beenpers 45°', updated_at = now()
where key = 'leg_press' and default_scheme_type = 'stack';

insert into fitness.equipment_types (key, category, default_scheme_type, default_load_scheme, portable, label_en, label_nl, active, sort, updated_at)
values ('leg_press_seated', 'station', 'stack',
        '{"type": "stack", "unit": "kg", "start": null, "step": null, "max": null, "add_on_steps": [], "ratio": 1.0}'::jsonb,
        false, 'Seated leg press', 'Zittende beenpers', true, 185, now())
on conflict (key) do nothing;

insert into fitness.equipment_types (key, category, default_scheme_type, default_load_scheme, portable, label_en, label_nl, active, sort, updated_at)
values ('landmine', 'station', 'stack',
        '{"type": "stack", "unit": "kg", "start": 0, "step": 1.25, "max": 100, "add_on_steps": [], "ratio": 1.0}'::jsonb,
        false, 'Landmine (plates on one sleeve)', 'Landmine (schijven aan één kant)', true, 75, now())
on conflict (key) do nothing;

insert into fitness.catalog_releases (version, exercise_count, stimulus_count, jcs_sha256, notes)
values ('1.0.4', 159, 541, 'ec29a109c937f90f35a1aa0eac2ab4c1342083a0259659e05b96871d8136c0bc',
        'Delta on 1.0.3: leg_press_seated moves to its own station leg_press_seated, leg_press is the plate-loaded 45° sled (D64); back_extension_45 is bodyweight with an optional held plate, f_bw 0.6 (D65); tbar_row and landmine_press load one sleeve on the new landmine station, bar share 10 kg (D66); lying and seated leg curls gain gastrocnemius 0.25, fatigue only (D67). User decisions 2026-11-01.')
on conflict (version) do nothing;

update fitness.exercises
set requires_station = array['leg_press_seated']::text[], catalog_version = '1.0.4', client_updated_at = now(), app_version = 'migration'
where user_id is null and slug = 'leg_press_seated' and requires_station = array['leg_press']::text[];

update fitness.exercises
set load_mode = 'bodyweight', allowed_load_modes = array['bodyweight', 'bodyweight_plus_external']::text[], bw_fraction = 0.6,
    evidence_note = '45-degree back extension performed with spinal flexion/extension: erectors prime mover (expert-opinion). Bodyweight with an optional held plate (D65): f_bw 0.6, the upper body above the hips is about 0.68 of bodyweight (segment tables) and the 45-degree bench shortens its lever (expert-opinion, weak).',
    catalog_version = '1.0.4', client_updated_at = now(), app_version = 'migration'
where user_id is null and slug = 'back_extension_45' and load_mode = 'external';

update fitness.exercises
set requires_station = array['landmine', 'barbell']::text[], implement_base_kg = 10,
    aliases = case when slug = 'tbar_row' then array['landmine row']::text[] else aliases end,
    evidence_note = 'Landmine (D66): plates on one sleeve, entered as their total; about half of the 20 kg bar rests on the hinge, so the bar counts 10 kg (expert-opinion).',
    catalog_version = '1.0.4', client_updated_at = now(), app_version = 'migration'
where user_id is null and slug in ('tbar_row', 'landmine_press') and requires_station = array['barbell']::text[];

insert into fitness.exercise_stimulus (id, user_id, client_updated_at, device_id, app_version, exercise_id, region_id, coeff)
values
  ('afc21223-0c92-5215-9178-b7d69e76bf8b'::uuid, null, now(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration', '436cd85b-5bd6-564c-ab26-f2ae9cf20240'::uuid, 'gastrocnemius', 0.25),
  ('a0392ad7-63c4-547b-ad0d-1e7af5cce195'::uuid, null, now(), 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration', '60fb95a8-7b49-58a8-b302-b33831c5c30d'::uuid, 'gastrocnemius', 0.25)
on conflict (id) do nothing;

update fitness.exercises
set evidence_note = case slug
      when 'lying_leg_curl' then 'The gastrocnemius crosses the knee and works in leg curls (EMG), but no trial shows calf growth from them: gastrocnemius 0.25, fatigue only (C44, D67).'
      else 'Maeo 2021 (moderate): seated curls train the hamstrings at long length. The gastrocnemius crosses the knee and works in leg curls (EMG), but no trial shows calf growth from them: gastrocnemius 0.25, fatigue only (C44, D67).'
    end,
    catalog_version = '1.0.4', client_updated_at = now(), app_version = 'migration'
where user_id is null and slug in ('lying_leg_curl', 'seated_leg_curl');

update fitness.gym_equipment g
set scheme_type = 'plates',
    load_scheme = jsonb_build_object(
      'type', 'plates', 'unit', 'kg', 'bar_kg', 75.7,
      'plates', (select b.load_scheme -> 'plates' from fitness.gym_equipment b
                 where b.user_id = g.user_id and b.equipment_type = 'barbell' and b.deleted_at is null)),
    label = '45° leg press', calibration_rev = 5, calibration_source = 'measured', confirmed_at = now(),
    client_updated_at = now(), app_version = 'migration'
where g.id = '01a11fea-12e8-75bf-8c4f-f044bc36240d'::uuid and g.calibration_rev = 4;

update fitness.set_logs s
set input_value = v.total, input_unit = 'kg', added_kg = v.total, implement_base_kg = 0, effective_load_kg = v.total,
    equipment_calibration_rev = 5, client_updated_at = now(), app_version = 'migration'
from (values
  ('01a198c0-23b1-7269-88d4-2dd360250db2'::uuid, 175.00, 155.70),
  ('01a198c2-aa46-7716-ae9f-563b90a457a3'::uuid, 310.00, 215.70),
  ('01a198c6-b7a5-7280-aa36-9388f991bfbc'::uuid, 310.00, 215.70),
  ('01a198cb-f7fb-701f-aecb-bc37bdb16443'::uuid, 140.00, 215.70)
) as v(id, old_input, total)
where s.id = v.id and s.input_value = v.old_input;

-- D65: the back extension sets, at the session's bodyweight.
update fitness.set_logs s
set load_mode = v.mode, equipment_id = v.equipment_id, equipment_calibration_rev = v.rev,
    input_value = v.added, input_unit = case when v.added is null then null else 'kg' end,
    added_kg = coalesce(v.added, 0), implement_base_kg = 0, f_bw = 0.6, bodyweight_kg = w.bodyweight_kg,
    effective_load_kg = round(0.6 * w.bodyweight_kg + coalesce(v.added, 0), 2),
    client_updated_at = now(), app_version = 'migration'
from (values
  ('01a198cf-4fa3-75b6-b8f8-651d79dbfd35'::uuid, 'bodyweight', null::uuid, null::int, null::numeric),
  ('01a198d2-86e4-741e-ad85-068679ff8c75'::uuid, 'bodyweight_plus_external', '01a11fea-12d8-7593-a928-09417519f5c6'::uuid, 1, 10.00),
  ('01a198d7-4001-753c-9370-87931ec33405'::uuid, 'bodyweight_plus_external', '01a11fea-12d8-7593-a928-09417519f5c6'::uuid, 1, 20.00)
) as v(id, mode, equipment_id, rev, added),
fitness.workout_sessions w
where s.id = v.id and w.id = s.session_id and s.load_mode = 'external' and s.added_kg = 0 and w.bodyweight_kg is not null;

-- D66: the T-bar row sets, plates on the sleeve plus the bar share.
update fitness.set_logs s
set input_value = v.plates, input_unit = 'kg', added_kg = v.plates, implement_base_kg = 10, effective_load_kg = v.plates + 10,
    equipment_id = null, equipment_calibration_rev = null, client_updated_at = now(), app_version = 'migration'
from (values
  ('01a198ee-ce70-76b3-8981-04c43fa968fb'::uuid, 40.00, 20.00),
  ('01a198f0-cf5c-7257-aefb-0db19df32f50'::uuid, 70.00, 50.00),
  ('01a198f4-7119-716e-860d-12bf0ca241b2'::uuid, 70.00, 50.00),
  ('01a198f8-098c-7199-9ebd-42cb6600c6dd'::uuid, 70.00, 50.00)
) as v(id, old_input, plates)
where s.id = v.id and s.input_value = v.old_input and s.input_unit = 'kg';

-- Assertions.
do $$
begin
  if (select count(*) from fitness.exercises where user_id is null and catalog_version = '1.0.4') <> 6 then
    raise exception 'catalog 1.0.4: expected 6 rows at 1.0.4';
  end if;
  if (select count(*) from fitness.exercise_stimulus where user_id is null and deleted_at is null) <> 541 then
    raise exception 'catalog 1.0.4: expected 541 global stimulus rows';
  end if;
  if (select count(*) from fitness.exercises where user_id is null and retired_at is null) <> 159 then
    raise exception 'catalog 1.0.4: expected 159 active global exercises';
  end if;
  if exists (select 1 from fitness.exercises e where e.user_id is null and e.retired_at is null
             and exists (select 1 from unnest(e.requires_station) k where k not in (select key from fitness.equipment_types))) then
    raise exception '045: an exercise requires an unknown station';
  end if;
  if (select (load_scheme ->> 'bar_kg')::numeric from fitness.gym_equipment where id = '01a11fea-12e8-75bf-8c4f-f044bc36240d'::uuid) <> 75.7
     or (select jsonb_array_length(load_scheme -> 'plates') from fitness.gym_equipment where id = '01a11fea-12e8-75bf-8c4f-f044bc36240d'::uuid) < 1 then
    raise exception '045: the sled row is not plates with bar_kg 75.7';
  end if;
  if (select count(*) from fitness.set_logs where session_exercise_id = '01a198bf-4851-77c3-a491-5d30cb18af7a'::uuid
      and deleted_at is null and input_unit = 'kg' and equipment_calibration_rev = 5) <> 4 then
    raise exception '045: not all 4 leg press sets were rewritten';
  end if;
  if exists (select 1 from fitness.set_logs s join fitness.exercises e on e.id = s.exercise_id
             where e.slug = 'back_extension_45' and s.session_id = '01a198bf-2566-71dc-a23d-6a73a3573b3f'::uuid
               and s.deleted_at is null and (s.load_mode = 'external' or s.effective_load_kg is null)) then
    raise exception '045: a back extension set of 1 Nov is still external or has no load';
  end if;
  if exists (select 1 from fitness.set_logs s join fitness.exercises e on e.id = s.exercise_id
             where e.slug = 'tbar_row' and s.session_id = '01a198bf-2566-71dc-a23d-6a73a3573b3f'::uuid
               and s.deleted_at is null and s.implement_base_kg <> 10) then
    raise exception '045: a T-bar row set of 1 Nov was not rewritten';
  end if;
end;
$$;
