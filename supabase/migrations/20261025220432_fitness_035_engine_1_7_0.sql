-- fitness_035_engine_1_7_0 (M2b.6, data): challenge.enrollment 1.4.0 of ENGINE_VERSION 1.7.0 — DATA_MODEL.md 13.2, D48
--
-- D48: an enrollment.created that supersedes the original may correct the imported earlier tests (import.past_tests)
-- and nothing else; the fold starts from the original and uses the corrected tests. No definition changes.
-- challenge.enrollment's params slice is unchanged, so it keeps the hash of its earlier rows.
-- Manual rollback (only with explicit user approval): delete the module row.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('challenge.enrollment', '1.4.0', 'challenge', '1.7.0', 'active', '1.0.1', '33c7a5ded80514bce442ee4c87c86d66d5f811b7b763fb50ec534f850a14de68',
   'docs/CHALLENGE_ENGINE.md section 4.11', 'D48: a superseding enrollment.created may correct the imported earlier tests.')
on conflict (module_id, version) do nothing;

do $$
begin
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.7.0') <> 1 then
    raise exception '035: expected 1 module version for engine 1.7.0';
  end if;
end;
$$;
