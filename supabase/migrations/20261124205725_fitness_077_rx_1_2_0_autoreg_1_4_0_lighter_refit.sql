-- fitness_077_rx_1_2_0_autoreg_1_4_0_lighter_refit (data): DECISIONS D129; ALGORITHMS B9.6, B13.7
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Registers `rx@1.2.0` (DOUBLE restarts its ladder from ladderStart when a planned set of the last exposure was done
-- below L_last, trace `rx.double.mixed_loads`) and `autoreg@1.4.0` (the rest refit moves the next set to the heaviest
-- lighter loadable when even r_lo at the planned load is predicted below q* - autoreg.safety_drop_margin), both
-- ENGINE_VERSION 1.30.0. constants_hash = each module's slice, unchanged (no params change; default params 1.0.9).
-- Manual rollback (only with explicit user approval): delete rx@1.2.0 and autoreg@1.4.0 before any recommendation
-- references them.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'rx', '1.2.0', layer, '1.30.0', 'active', '1.0.9', constants_hash,
       'docs/ALGORITHMS.md section B9', 'D129: DOUBLE restarts its ladder after planned sets below its load.'
  from fitness.algorithm_versions
 where module_id = 'rx' and version = '1.1.0'
on conflict (module_id, version) do nothing;

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'autoreg', '1.4.0', layer, '1.30.0', 'active', '1.0.9', constants_hash,
       'docs/ALGORITHMS.md section B13', 'D129: the rest refit goes to a lighter load when even r_lo falls under the aim.'
  from fitness.algorithm_versions
 where module_id = 'autoreg' and version = '1.3.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'rx' and version = '1.2.0')
     is distinct from (select constants_hash from fitness.algorithm_versions where module_id = 'rx' and version = '1.1.0') then
    raise exception '077: rx@1.2.0 missing or with another constants_hash';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'autoreg' and version = '1.4.0')
     is distinct from (select constants_hash from fitness.algorithm_versions where module_id = 'autoreg' and version = '1.3.0') then
    raise exception '077: autoreg@1.4.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.30.0') <> 2 then
    raise exception '077: expected 2 module versions for engine 1.30.0';
  end if;
end;
$$;
