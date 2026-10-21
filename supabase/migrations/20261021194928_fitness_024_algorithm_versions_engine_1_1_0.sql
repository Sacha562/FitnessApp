-- fitness_024_algorithm_versions_engine_1_1_0 (M2b.4, data): module versions of ENGINE_VERSION 1.1.0 — DATA_MODEL.md 9.1.9, 13.2
--
-- D36: `challenge.cds` and `challenge.materialize` read and evaluate the structured test protocols (`steps`,
-- `stepsIfApproved`) and go to 1.1.0. The release rule of 9.1.9 registers a module version before the APK that
-- uses it is installed. Both modules own no params, so their slice of params 1.0.1 is `{}` (the same constants_hash
-- as their 1.0.0 rows). Manual rollback (only with explicit user approval): delete these two rows.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('challenge.cds', '1.1.0', 'challenge', '1.1.0', 'active', '1.0.1', '44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a',
   'docs/CHALLENGE_ENGINE.md#247-templates-blocks-and-items', 'D36: test items may carry steps / stepsIfApproved.'),
  ('challenge.materialize', '1.1.0', 'challenge', '1.1.0', 'active', '1.0.1', '44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a',
   'docs/CHALLENGE_ENGINE.md#106-test-day', 'D36: plans carry the evaluated test protocol (ramp, start, step, max attempts).')
on conflict (module_id, version) do nothing;

do $$
begin
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.1.0') <> 2 then
    raise exception '024: expected 2 module versions for engine 1.1.0';
  end if;
end;
$$;
