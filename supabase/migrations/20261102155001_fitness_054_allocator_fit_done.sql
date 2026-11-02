-- fitness_054_allocator_fit_done (M4.5, data): the allocator's time fit prices the sets done - DECISIONS D79;
-- ALGORITHMS_PLANNING 7.2 step 3, 21.8
--
-- No DDL, no user rows. Registers `allocator@1.1.0` (ENGINE_VERSION 1.19.0): the time fit estimates time and utility on
-- the sets done after the deload and re-entry transforms. constants_hash = the `allocator` slice of default params
-- 1.0.4 (ParamsHashes.slices), equal to the 1.0.0 row; no params change.
-- Manual rollback (only with explicit user approval): delete allocator@1.1.0 before any recommendation references it.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'allocator', '1.1.0', layer, '1.19.0', 'active', '1.0.4', 'c653b3006465e39212ea5f35d51f9e522978271d81bdfad69e41ac30895f87f2',
       'docs/ALGORITHMS_PLANNING.md section 7', 'D79: the time fit prices the sets done after the deload and re-entry transforms.'
  from fitness.algorithm_versions
 where module_id = 'allocator' and version = '1.0.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'allocator' and version = '1.1.0')
     <> 'c653b3006465e39212ea5f35d51f9e522978271d81bdfad69e41ac30895f87f2' then
    raise exception '054: allocator@1.1.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.19.0') <> 1 then
    raise exception '054: expected 1 module version for engine 1.19.0';
  end if;
end;
$$;
