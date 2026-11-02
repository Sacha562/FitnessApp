-- fitness_053_today_option_week (M4.5, data): a Today option is built for its own week - DECISIONS D78;
-- ALGORITHMS_PLANNING 9.3
--
-- No DDL, no user rows. Registers `today@1.5.0` (ENGINE_VERSION 1.18.0): each option carries the session types after
-- today in its own best week (`laterTypes`), which session building uses to divide the week's remaining sets.
-- constants_hash = the `today` slice of default params 1.0.4 (ParamsHashes.slices), equal to the earlier rows.
-- Manual rollback (only with explicit user approval): delete today@1.5.0 before any recommendation references it.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'today', '1.5.0', layer, '1.18.0', 'active', '1.0.4', 'cf9d37fc4e94c01b6f0a5c3cf60acf7b4fe2c21ef7fbe2b6c74a5b610e8c9a2e',
       'docs/ALGORITHMS_PLANNING.md section 9', 'D78: each option carries the session types after today in its own best week (laterTypes).'
  from fitness.algorithm_versions
 where module_id = 'today' and version = '1.0.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'today' and version = '1.5.0')
     <> 'cf9d37fc4e94c01b6f0a5c3cf60acf7b4fe2c21ef7fbe2b6c74a5b610e8c9a2e' then
    raise exception '053: today@1.5.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.18.0') <> 1 then
    raise exception '053: expected 1 module version for engine 1.18.0';
  end if;
end;
$$;
