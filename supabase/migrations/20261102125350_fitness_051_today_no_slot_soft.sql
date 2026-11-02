-- fitness_051_today_no_slot_soft (M4.5, data): no free slot is a soft rest - DECISIONS D76; ALGORITHMS_PLANNING 9.4
--
-- No DDL, no user rows. Registers `today@1.3.0` (ENGINE_VERSION 1.16.0): a day without a free slot in the usual times
-- gives REST_SOFT with NO_SLOT, and "Train anyway" offers the options scored from now. constants_hash = the `today`
-- slice of default params 1.0.3 (ParamsHashes.slices), equal to the earlier rows; no params change.
-- Manual rollback (only with explicit user approval): delete today@1.3.0 before any recommendation references it.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'today', '1.3.0', layer, '1.16.0', 'active', '1.0.3', 'cf9d37fc4e94c01b6f0a5c3cf60acf7b4fe2c21ef7fbe2b6c74a5b610e8c9a2e',
       'docs/ALGORITHMS_PLANNING.md section 9', 'D76: no free slot is a soft rest (NO_SLOT); Train anyway offers the options scored from now.'
  from fitness.algorithm_versions
 where module_id = 'today' and version = '1.0.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'today' and version = '1.3.0')
     <> 'cf9d37fc4e94c01b6f0a5c3cf60acf7b4fe2c21ef7fbe2b6c74a5b610e8c9a2e' then
    raise exception '051: today@1.3.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.16.0') <> 1 then
    raise exception '051: expected 1 module version for engine 1.16.0';
  end if;
end;
$$;
