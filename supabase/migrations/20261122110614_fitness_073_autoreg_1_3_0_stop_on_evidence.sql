-- fitness_073_autoreg_1_3_0_stop_on_evidence (data): DECISIONS D121; ALGORITHMS B13.4
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Registers `autoreg@1.3.0` (ENGINE_VERSION 1.27.0): AUTO-1's fatigue stop needs the last set to fall short of its
-- plan, judges by that set's own capacity, reaches for the lower of r_lo and the remaining planned targets, and sets
-- its load floor from the lighter of the planned load and the last set's load (trace `autoreg.stop.fatigue_on_plan`
-- when only the plan check holds it back). constants_hash = the `autoreg` slice, unchanged (no params change;
-- default params 1.0.8).
-- Manual rollback (only with explicit user approval): delete autoreg@1.3.0 before any recommendation references it.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'autoreg', '1.3.0', layer, '1.27.0', 'active', '1.0.8', constants_hash,
       'docs/ALGORITHMS.md section B13', 'D121: the fatigue stop needs the last set to fall short of its plan and judges by that set''s own capacity.'
  from fitness.algorithm_versions
 where module_id = 'autoreg' and version = '1.2.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'autoreg' and version = '1.3.0')
     is distinct from (select constants_hash from fitness.algorithm_versions where module_id = 'autoreg' and version = '1.2.0') then
    raise exception '073: autoreg@1.3.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.27.0') <> 1 then
    raise exception '073: expected 1 module version for engine 1.27.0';
  end if;
end;
$$;
