-- fitness_049_today_reentry_group (M4.5, data): the re-entry chip names the group - DECISIONS D71;
-- ALGORITHMS_PLANNING 9.6, UX 19.1.1
--
-- No DDL, no user rows. Registers `today@1.1.0` and `ui.todaycard@1.1.0` (ENGINE_VERSION 1.14.0): the `REENTRY` reason
-- names the re-entry group with the most direct sets in the option (ties by id) and reads "Lighter start for {group}".
-- constants_hash = the `today` and `ui.todaycard` slices of default params 1.0.3 (ParamsHashes.slices), equal to the
-- seeded 1.0.0 rows; no params change.
-- Manual rollback (only with explicit user approval): delete both 1.1.0 rows before any recommendation references them.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'today', '1.1.0', layer, '1.14.0', 'active', '1.0.3', 'cf9d37fc4e94c01b6f0a5c3cf60acf7b4fe2c21ef7fbe2b6c74a5b610e8c9a2e',
       'docs/ALGORITHMS_PLANNING.md section 9', 'D71: the REENTRY reason names the re-entry group with the most direct sets in the option, ties by id.'
  from fitness.algorithm_versions
 where module_id = 'today' and version = '1.0.0'
on conflict (module_id, version) do nothing;

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'ui.todaycard', '1.1.0', layer, '1.14.0', 'active', '1.0.3', '1dc31d4c47428c17853a8faa0cc79687c9e960e261664b030ae358857108f3d8',
       'docs/ALGORITHMS_PLANNING.md section 19.1', 'D71: the REENTRY chip reads "Lighter start for {group}".'
  from fitness.algorithm_versions
 where module_id = 'ui.todaycard' and version = '1.0.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'today' and version = '1.1.0')
     <> 'cf9d37fc4e94c01b6f0a5c3cf60acf7b4fe2c21ef7fbe2b6c74a5b610e8c9a2e' then
    raise exception '049: today@1.1.0 missing or with another constants_hash';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'ui.todaycard' and version = '1.1.0')
     <> '1dc31d4c47428c17853a8faa0cc79687c9e960e261664b030ae358857108f3d8' then
    raise exception '049: ui.todaycard@1.1.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.14.0') <> 2 then
    raise exception '049: expected 2 module versions for engine 1.14.0';
  end if;
end;
$$;
