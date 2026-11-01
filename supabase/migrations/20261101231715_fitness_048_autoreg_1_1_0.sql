-- fitness_048_autoreg_1_1_0 (M4, data): next-set targets from the last set and the actual rest - DECISIONS D69;
-- ALGORITHMS B13.3, B13.7
--
-- No DDL, no user rows. Registers `autoreg@1.1.0` (ENGINE_VERSION 1.13.0): the waiting next set's reps follow the last
-- set's result and the rest so far, and RANGE rep proposals use the whole range. constants_hash = the `autoreg` slice
-- of default params 1.0.3 (ParamsHashes.slices), 035cea9d...e546; no params change.
-- Manual rollback (only with explicit user approval): delete autoreg@1.1.0 before any recommendation references it.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'autoreg', '1.1.0', layer, '1.13.0', 'active', '1.0.3', '035cea9df9474ac2413ea1564227b6f51181c696efe265d29fb2f2e20739e546',
       'docs/ALGORITHMS.md section B13', 'D69: the next set''s reps refitted to the last set and the actual rest (B13.7); RANGE rep proposals across the whole range.'
  from fitness.algorithm_versions
 where module_id = 'autoreg' and version = '1.0.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'autoreg' and version = '1.1.0')
     <> '035cea9df9474ac2413ea1564227b6f51181c696efe265d29fb2f2e20739e546' then
    raise exception '048: autoreg@1.1.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.13.0') <> 1 then
    raise exception '048: expected 1 module version for engine 1.13.0';
  end if;
end;
$$;
