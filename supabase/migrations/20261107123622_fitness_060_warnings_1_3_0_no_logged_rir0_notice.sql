-- fitness_060_warnings_1_3_0_no_logged_rir0_notice (data): DECISIONS D96; ALGORITHMS B15.2, B15.3
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Registers `warnings@1.3.0` (ENGINE_VERSION 1.23.0): a logged RIR 0 or failed rep on a high-risk working set no longer
-- raises the WE-S1 SAFETY notice; the enforced RIR-1 floor on planned targets stays. constants_hash = the `warnings`
-- slice, unchanged (no params change; default params stay 1.0.6).
-- Manual rollback (only with explicit user approval): delete warnings@1.3.0 before any recommendation references it.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'warnings', '1.3.0', layer, '1.23.0', 'active', '1.0.6', constants_hash,
       'docs/ALGORITHMS.md section B15', 'D96: a logged RIR 0 on a high-risk lift raises no WE-S1 notice; planned targets keep the RIR-1 floor.'
  from fitness.algorithm_versions
 where module_id = 'warnings' and version = '1.2.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'warnings' and version = '1.3.0')
     is distinct from (select constants_hash from fitness.algorithm_versions where module_id = 'warnings' and version = '1.2.0') then
    raise exception '060: warnings@1.3.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.23.0') <> 1 then
    raise exception '060: expected 1 module version for engine 1.23.0';
  end if;
end;
$$;
