-- fitness_030_engine_1_5_0 (M2b.6, data): challenge.plan 1.2.0 of ENGINE_VERSION 1.5.0 — DATA_MODEL.md 13.2, D42
--
-- D42: "train now" (CHALLENGE_ENGINE 7.6 step 2) tries every slot of the week not done yet, including slots the plan
-- dropped, as a session starting now at any time of day; a trial may not make the week drop a slot the plan kept.
-- No definition changes. challenge.plan's params slice is unchanged, so it keeps the hash of its 1.0.0 and 1.1.0 rows.
-- Manual rollback (only with explicit user approval): delete the module row.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('challenge.plan', '1.2.0', 'challenge', '1.5.0', 'active', '1.0.1', 'dc11022c09596fb1766cb31a3d62e6094f7766eb90f2d08a65b4c54bb9669ee7',
   'docs/CHALLENGE_ENGINE.md section 7.6', 'D42: train now tries every slot not done yet, at any time of day.')
on conflict (module_id, version) do nothing;

do $$
begin
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.5.0') <> 1 then
    raise exception '030: expected 1 module version for engine 1.5.0';
  end if;
end;
$$;
