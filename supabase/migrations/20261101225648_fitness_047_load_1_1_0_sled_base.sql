-- fitness_047_load_1_1_0_sled_base (M4, data): the sled mass outside the shown load - DECISIONS D68; ALGORITHMS B2.4;
-- DATA_MODEL 9.3.3
--
-- No DDL.
-- - `load@1.1.0` (ENGINE_VERSION 1.12.0): a `plates` scheme may carry `base_kg`, a plate-loaded machine's own mass,
--   counted in the load (`implement_base_kg`) but not shown. constants_hash = the `load` slice of default params 1.0.3
--   (ParamsHashes.slices), b9d1c152...02f9.
-- - The user's sled row (gym_equipment leg_press, rev 5, bar_kg 75.7) moves the mass to base_kg (rev 6).
-- - The four leg press sets of 1 Nov show the plates (80 / 140 / 140 / 140 kg) with base 75.7; their effective load
--   (155.7 / 215.7 kg) is unchanged.
-- Changed user rows take the migration device id so the phone pulls them (046).
-- Manual rollback (only with explicit user approval): the sled row back to bar_kg 75.7 at rev 5, the sets back to
-- input/added 155.7 / 215.7 with base 0 at rev 5, delete load@1.1.0.

do $$
begin
  if not exists (select 1 from fitness.gym_equipment where id = '01a11fea-12e8-75bf-8c4f-f044bc36240d'::uuid
                 and calibration_rev = 5 and (load_scheme ->> 'bar_kg')::numeric = 75.7 and deleted_at is null) then
    raise exception '047: the sled row is not plates with bar_kg 75.7 at rev 5';
  end if;
end;
$$;

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'load', '1.1.0', layer, '1.12.0', 'active', '1.0.3', 'b9d1c152de1ad3dcd91ba9995bd98c610d5270bafc7f7210b9f3250347cf02f9',
       'docs/ALGORITHMS.md section B2.4', 'D68: a plates scheme''s base_kg (a plate-loaded machine''s own mass) is counted in the load but not shown.'
  from fitness.algorithm_versions
 where module_id = 'load' and version = '1.0.0'
on conflict (module_id, version) do nothing;

update fitness.gym_equipment
set load_scheme = (load_scheme - 'bar_kg') || '{"bar_kg": null, "base_kg": 75.7}'::jsonb,
    calibration_rev = 6, confirmed_at = now(), client_updated_at = now(),
    device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, app_version = 'migration'
where id = '01a11fea-12e8-75bf-8c4f-f044bc36240d'::uuid and calibration_rev = 5;

update fitness.set_logs s
set input_value = v.plates, added_kg = v.plates, implement_base_kg = 75.70, effective_load_kg = v.plates + 75.70,
    equipment_calibration_rev = 6, client_updated_at = now(),
    device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, app_version = 'migration'
from (values
  ('01a198c0-23b1-7269-88d4-2dd360250db2'::uuid, 155.70, 80.00),
  ('01a198c2-aa46-7716-ae9f-563b90a457a3'::uuid, 215.70, 140.00),
  ('01a198c6-b7a5-7280-aa36-9388f991bfbc'::uuid, 215.70, 140.00),
  ('01a198cb-f7fb-701f-aecb-bc37bdb16443'::uuid, 215.70, 140.00)
) as v(id, old_input, plates)
where s.id = v.id and s.input_value = v.old_input and s.equipment_calibration_rev = 5;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'load' and version = '1.1.0')
     <> 'b9d1c152de1ad3dcd91ba9995bd98c610d5270bafc7f7210b9f3250347cf02f9' then
    raise exception '047: load@1.1.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.12.0') <> 1 then
    raise exception '047: expected 1 module version for engine 1.12.0';
  end if;
  if not exists (select 1 from fitness.gym_equipment where id = '01a11fea-12e8-75bf-8c4f-f044bc36240d'::uuid
                 and calibration_rev = 6 and load_scheme ->> 'bar_kg' is null and (load_scheme ->> 'base_kg')::numeric = 75.7) then
    raise exception '047: the sled row is not on base_kg 75.7 at rev 6';
  end if;
  if (select count(*) from fitness.set_logs where session_exercise_id = '01a198bf-4851-77c3-a491-5d30cb18af7a'::uuid
      and deleted_at is null and equipment_calibration_rev = 6 and implement_base_kg = 75.7
      and effective_load_kg = input_value + 75.7) <> 4 then
    raise exception '047: not all 4 leg press sets show the plates with the 75.7 kg base';
  end if;
end;
$$;
