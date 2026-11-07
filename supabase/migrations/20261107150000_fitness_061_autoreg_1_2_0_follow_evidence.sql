-- fitness_061_autoreg_1_2_0_follow_evidence (data): DECISIONS D97; ALGORITHMS B13.3
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Registers `autoreg@1.2.0` (ENGINE_VERSION 1.24.0): an AUTO-1 proposal never moves against the evidence that triggered
-- it, and a safety drop after sets easier than predicted needs the last set's own capacity to predict failure too
-- (`hysteresis.rir_next_obs`). constants_hash = the `autoreg` slice, unchanged (no params change; default params 1.0.6).
-- Manual rollback (only with explicit user approval): delete autoreg@1.2.0 before any recommendation references it.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'autoreg', '1.2.0', layer, '1.24.0', 'active', '1.0.6', constants_hash,
       'docs/ALGORITHMS.md section B13', 'D97: proposals follow the triggering evidence; a safety drop after easier sets needs the last set''s own capacity to agree.'
  from fitness.algorithm_versions
 where module_id = 'autoreg' and version = '1.1.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'autoreg' and version = '1.2.0')
     is distinct from (select constants_hash from fitness.algorithm_versions where module_id = 'autoreg' and version = '1.1.0') then
    raise exception '061: autoreg@1.2.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.24.0') <> 1 then
    raise exception '061: expected 1 module version for engine 1.24.0';
  end if;
end;
$$;
