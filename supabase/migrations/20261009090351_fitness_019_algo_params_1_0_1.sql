-- fitness_019_algo_params_1_0_1 (M1a, data): algo_params 1.0.1 - DATA_MODEL.md 9.1.10, DECISIONS F7 (2026-10-09)
--
-- Params-only release: 1.0.1 = 1.0.0 with load.default_plate_inventory counts 4 -> 20 per size (gym plates
-- unlimited). No module or engine version changes (ALGORITHMS A7). params_hash computed by ParamsHashes
-- (JCS SHA-256 of key -> value) and pinned in ParamsFileTest. Idempotent.

insert into fitness.algo_params (params_version, status, engine_version_range, params, params_hash, notes)
select '1.0.1', 'draft', engine_version_range,
       jsonb_set(params, '{load.default_plate_inventory}', '{"evidence":"expert-opinion","max":20,"min":0,"source":"load_prescription LOAD-1; F7 checklist overrides; gym plates unlimited (F7, 2026-10-09)","tunable":true,"tuned_by":"the equipment checklist (user); equipment_calibration_changed events (DATA_MODEL 9.8.1)","unit":"kg, count","value":{"1.25":20,"10":20,"15":20,"2.5":20,"20":20,"25":20,"5":20,"bar_kg":20}}'::jsonb),
       'e9c5f0ed0b315a8b51ce06ee2268a6d1402dd7c01ff6d620eed406d3f97e8469',
       'F7 2026-10-09: gym plates unlimited (default inventory 20 per size)'
  from fitness.algo_params
 where params_version = '1.0.0'
on conflict (params_version) do nothing;

do $$
declare
  old_p jsonb;
  new_p jsonb;
begin
  select params into old_p from fitness.algo_params where params_version = '1.0.0';
  select params into new_p from fitness.algo_params where params_version = '1.0.1';
  if (select count(*) from jsonb_object_keys(new_p)) <> 621 then
    raise exception 'algo_params 1.0.1: expected 621 keys';
  end if;
  if (new_p - 'load.default_plate_inventory') <> (old_p - 'load.default_plate_inventory') then
    raise exception 'algo_params 1.0.1: keys other than load.default_plate_inventory differ from 1.0.0';
  end if;
  if new_p -> 'load.default_plate_inventory' <> '{"evidence":"expert-opinion","max":20,"min":0,"source":"load_prescription LOAD-1; F7 checklist overrides; gym plates unlimited (F7, 2026-10-09)","tunable":true,"tuned_by":"the equipment checklist (user); equipment_calibration_changed events (DATA_MODEL 9.8.1)","unit":"kg, count","value":{"1.25":20,"10":20,"15":20,"2.5":20,"20":20,"25":20,"5":20,"bar_kg":20}}'::jsonb then
    raise exception 'algo_params 1.0.1: unexpected load.default_plate_inventory';
  end if;
end;
$$;

update fitness.algo_params set status = 'superseded'
 where params_version = '1.0.0' and status = 'active'
   and exists (select 1 from fitness.algo_params where params_version = '1.0.1' and status = 'draft');
update fitness.algo_params set status = 'active', released_at = now()
 where params_version = '1.0.1' and status = 'draft';
