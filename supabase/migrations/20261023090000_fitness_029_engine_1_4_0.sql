-- fitness_029_engine_1_4_0 (M2b.6, data): challenge.enrollment 1.2.0 of ENGINE_VERSION 1.4.0 — DATA_MODEL.md 9.1.9, 13.2, D41
--
-- D41: the comparison baseline `pt` is the newest gate-valid value per measurement instead of the newest gate-valid test,
-- so a partial test no longer hides earlier bodyweight and strength values. The fold keeps the baseline in its state
-- (`test_baseline`); no definition changes. challenge.enrollment's params slice is unchanged, so it keeps the hash of
-- its 1.0.0 and 1.1.0 rows. Manual rollback (only with explicit user approval): delete the module row.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('challenge.enrollment', '1.2.0', 'challenge', '1.4.0', 'active', '1.0.1', '33c7a5ded80514bce442ee4c87c86d66d5f811b7b763fb50ec534f850a14de68',
   'docs/CHALLENGE_ENGINE.md section 11.2', 'D41: the previous test (pt) is the newest gate-valid value per measurement.')
on conflict (module_id, version) do nothing;

do $$
begin
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.4.0') <> 1 then
    raise exception '029: expected 1 module version for engine 1.4.0';
  end if;
end;
$$;
