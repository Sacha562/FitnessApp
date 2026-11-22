-- fitness_074_challenge_where_engine_1_28_0 (data): DECISIONS D122; CHALLENGE_ENGINE 2.4.7, 6.7
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Registers `challenge.cds@1.3.0` and `challenge.materialize@1.2.0` (ENGINE_VERSION 1.28.0): an `anyOf` exercise
-- choice may carry a `where` catalog filter (pattern, optionally grip) whose matching catalog exercises join the
-- choice after its listed keys, in slug order. constants_hash = the previous versions' (no params change; default
-- params 1.0.8). Applied before the APK that uses them is installed (9.1.9 release rule).
-- Manual rollback (only with explicit user approval): delete both rows before any recommendation references them.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'challenge.cds', '1.3.0', layer, '1.28.0', 'active', '1.0.8', constants_hash,
       'docs/CHALLENGE_ENGINE.md section 2.4.7', 'D122: an anyOf exercise choice may add catalog exercises through a where filter (pattern, grip).'
  from fitness.algorithm_versions
 where module_id = 'challenge.cds' and version = '1.2.0'
on conflict (module_id, version) do nothing;

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'challenge.materialize', '1.2.0', layer, '1.28.0', 'active', '1.0.8', constants_hash,
       'docs/CHALLENGE_ENGINE.md section 6.7', 'D122: the exercise choice picks among the listed keys and the catalog exercises of its where filter.'
  from fitness.algorithm_versions
 where module_id = 'challenge.materialize' and version = '1.1.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'challenge.cds' and version = '1.3.0')
     is distinct from (select constants_hash from fitness.algorithm_versions where module_id = 'challenge.cds' and version = '1.2.0') then
    raise exception '074: challenge.cds@1.3.0 missing or with another constants_hash';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'challenge.materialize' and version = '1.2.0')
     is distinct from (select constants_hash from fitness.algorithm_versions where module_id = 'challenge.materialize' and version = '1.1.0') then
    raise exception '074: challenge.materialize@1.2.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.28.0') <> 2 then
    raise exception '074: expected 2 module versions for engine 1.28.0';
  end if;
end;
$$;
