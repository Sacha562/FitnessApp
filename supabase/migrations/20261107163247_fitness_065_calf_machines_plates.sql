-- fitness_065_calf_machines_plates (data): standing and seated calf machines as plate-loaded stations, held plates on the
-- back extension - DECISIONS D101, D102;
-- DATA_MODEL 9.1.7; catalog 1.0.5
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Both calf raises required the one `calf_machine` station, so setting it up for the seated calf raise on 5 Nov (kg
-- stack, rev 5) changed the standing one too, and the standing machine's 30.4 kg starting resistance was nowhere.
-- - equipment_types: `calf_machine` is the seated calf machine, `plates` in kg (as the 45° leg press); the new
--   `calf_machine_standing` station is the standing one, also `plates` in kg.
-- - catalog 1.0.5: `standing_calf_raise_machine` requires `calf_machine_standing`; `back_extension_45` gets the reserved
--   tag `held_load` (D102: its added weight is held plates, at most 2, not the dip belt). jcs_sha256 = Jcs.sha256Hex of
--   algo/src/main/resources/fitness/catalog/catalog-1.0.5.json (159 exercises, 541 stimulus rows), as printed by
--   `gradlew :sim:run --args="seedgen-catalog 1.0.5"`.
-- - gym_equipment: each user's `calf_machine` row becomes plates (the plates of their leg press row) with no base
--   (seated, unknown for now), calibration_rev + 1; each user with a `calf_machine` row gets a `calf_machine_standing`
--   row with the same plates and `base_kg` 30.4 (the user, 2026-11-07), measured.
-- - set_logs: the owner's standing calf raise sets on the old `calf_machine` row were plates only (130 lb = 58.97 kg on
--   24 Oct; 20 kg warm-up and 70 kg on 7 Nov, the user, 2026-11-07); they move to the standing row (rev 1) and gain
--   implement_base_kg 30.4: effective = added + 30.4. The seated calf sets keep their plate loads.
-- Refuses to run while the owner has a session in progress (the phone would overwrite the rewritten sets).
-- Manual rollback (only with explicit user approval): move the standing sets back to the calf_machine row with base 0,
-- delete the two calf_machine_standing rows, restore the calf_machine rows from the guard values (owner: kg stack
-- 5-200 step 5, rev 5), put standing_calf_raise_machine back on calf_machine at 1.0.0, delete the 1.0.5 release and the
-- calf_machine_standing type, and set calf_machine back to the stack default.

do $$
begin
  if exists (select 1 from fitness.workout_sessions where user_id = '39fca078-e316-4932-9012-b4ae72ec79c2'::uuid
             and status = 'in_progress' and deleted_at is null) then
    raise exception '065: a session is still in progress';
  end if;
end;
$$;

update fitness.equipment_types
set default_scheme_type = 'plates',
    default_load_scheme = (select default_load_scheme from fitness.equipment_types where key = 'leg_press'),
    label_en = 'Seated calf machine', label_nl = 'Zittende kuitmachine', updated_at = now()
where key = 'calf_machine' and default_scheme_type = 'stack';

insert into fitness.equipment_types (key, category, default_scheme_type, default_load_scheme, portable, label_en, label_nl, active, sort, updated_at)
select 'calf_machine_standing', 'station', 'plates', default_load_scheme, false, 'Standing calf machine', 'Staande kuitmachine', true, 262, now()
  from fitness.equipment_types where key = 'leg_press'
on conflict (key) do nothing;

insert into fitness.catalog_releases (version, exercise_count, stimulus_count, jcs_sha256, notes)
values ('1.0.5', 159, 541, '5cf53c502fb7507b7c72bb4392c42c34857cdcf541158dae05d031191e5dea49',
        'Delta on 1.0.4: standing_calf_raise_machine moves to its own plate-loaded station calf_machine_standing; calf_machine is the seated calf machine (D101); back_extension_45 is tagged held_load (D102).')
on conflict (version) do nothing;

update fitness.exercises
set requires_station = array['calf_machine_standing']::text[], catalog_version = '1.0.5', client_updated_at = now(), app_version = 'migration'
where user_id is null and slug = 'standing_calf_raise_machine' and requires_station = array['calf_machine']::text[];

update fitness.exercises
set tags = array['held_load']::text[], catalog_version = '1.0.5', client_updated_at = now(), app_version = 'migration'
where user_id is null and slug = 'back_extension_45' and tags = array[]::text[];

-- The standing rows first (they copy the plates from the leg press row, as the seated rows do below).
insert into fitness.gym_equipment (id, user_id, client_updated_at, device_id, app_version, gym_id, equipment_type, label, available,
                                   scheme_type, load_scheme, calibration_rev, calibration_source, confirmed_at)
select v.id, c.user_id, now(), c.device_id, 'migration', c.gym_id, 'calf_machine_standing', 'Standing calf machine', c.available,
       'plates',
       jsonb_build_object('type', 'plates', 'unit', 'kg', 'bar_kg', null, 'base_kg', 30.4, 'plates', lp.load_scheme -> 'plates'),
       1, 'measured', now()
  from (values ('ffbda860-d40f-46b7-8002-f35753155630'::uuid, '39fca078-e316-4932-9012-b4ae72ec79c2'::uuid),
               ('5356a09a-1c32-4171-8fe4-fd4d724b0b03'::uuid, '222ce484-2b7a-465a-b3fd-af26032f4cd7'::uuid)) as v(id, user_id)
  join fitness.gym_equipment c on c.user_id = v.user_id and c.equipment_type = 'calf_machine' and c.deleted_at is null
  join fitness.gym_equipment lp on lp.user_id = v.user_id and lp.equipment_type = 'leg_press' and lp.deleted_at is null
on conflict (id) do nothing;

update fitness.gym_equipment c
set scheme_type = 'plates',
    load_scheme = jsonb_build_object('type', 'plates', 'unit', 'kg', 'bar_kg', null, 'plates', lp.load_scheme -> 'plates'),
    label = 'Seated calf machine', calibration_rev = c.calibration_rev + 1, calibration_source = 'default',
    client_updated_at = now(), app_version = 'migration'
from fitness.gym_equipment lp
where c.equipment_type = 'calf_machine' and c.scheme_type = 'stack' and c.deleted_at is null
  and c.user_id in ('39fca078-e316-4932-9012-b4ae72ec79c2'::uuid, '222ce484-2b7a-465a-b3fd-af26032f4cd7'::uuid)
  and lp.user_id = c.user_id and lp.equipment_type = 'leg_press' and lp.deleted_at is null;

-- The owner's standing calf raise sets: plates only, now on the standing row with its 30.4 kg base.
update fitness.set_logs s
set equipment_id = 'ffbda860-d40f-46b7-8002-f35753155630'::uuid, equipment_calibration_rev = 1,
    implement_base_kg = 30.4, effective_load_kg = s.added_kg + 30.4,
    client_updated_at = now(), app_version = 'migration'
from fitness.exercises e
where e.id = s.exercise_id and e.slug = 'standing_calf_raise_machine' and e.user_id is null
  and s.user_id = '39fca078-e316-4932-9012-b4ae72ec79c2'::uuid and s.deleted_at is null
  and s.equipment_id = '01a11fea-12f0-7629-9ffb-4e10f9455356'::uuid and s.implement_base_kg = 0 and s.load_mode = 'external';

-- Assertions.
do $$
begin
  if (select count(*) from fitness.exercises where user_id is null and slug = 'standing_calf_raise_machine'
      and requires_station = array['calf_machine_standing']::text[] and catalog_version = '1.0.5') <> 1 then
    raise exception '065: standing_calf_raise_machine not on calf_machine_standing at 1.0.5';
  end if;
  if (select count(*) from fitness.exercises where user_id is null and slug = 'back_extension_45'
      and tags = array['held_load']::text[] and catalog_version = '1.0.5') <> 1 then
    raise exception '065: back_extension_45 not tagged held_load at 1.0.5';
  end if;
  if (select count(*) from fitness.exercises where user_id is null and retired_at is null) <> 159 then
    raise exception '065: expected 159 active global exercises';
  end if;
  if exists (select 1 from fitness.exercises e where e.user_id is null and e.retired_at is null
             and exists (select 1 from unnest(e.requires_station) k where k not in (select key from fitness.equipment_types))) then
    raise exception '065: an exercise requires an unknown station';
  end if;
  if (select count(*) from fitness.gym_equipment where equipment_type = 'calf_machine_standing' and deleted_at is null
      and (load_scheme ->> 'base_kg')::numeric = 30.4 and jsonb_array_length(load_scheme -> 'plates') > 0) <> 2 then
    raise exception '065: expected 2 standing calf rows with base 30.4 and plates';
  end if;
  if exists (select 1 from fitness.gym_equipment where equipment_type = 'calf_machine' and deleted_at is null and scheme_type <> 'plates'
             and user_id in ('39fca078-e316-4932-9012-b4ae72ec79c2'::uuid, '222ce484-2b7a-465a-b3fd-af26032f4cd7'::uuid)) then
    raise exception '065: a calf_machine row is not plates';
  end if;
  if exists (select 1 from fitness.set_logs s join fitness.exercises e on e.id = s.exercise_id
             where e.slug = 'standing_calf_raise_machine' and s.user_id = '39fca078-e316-4932-9012-b4ae72ec79c2'::uuid
               and s.deleted_at is null and (s.implement_base_kg <> 30.4 or s.effective_load_kg <> s.added_kg + 30.4)) then
    raise exception '065: a standing calf set lacks the 30.4 kg base';
  end if;
end;
$$;
