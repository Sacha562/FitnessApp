-- fitness_052_params_1_0_4_start_steps (M4.5, data): algo_params 1.0.4, weekplan@1.2.0 and today@1.4.0 of
-- ENGINE_VERSION 1.17.0 - DATA_MODEL.md 9.1.9, 9.1.10; DECISIONS D77; ALGORITHMS_PLANNING 8.4, 8.6
--
-- (1) algo_params 1.0.4 = 1.0.3 plus `weekplan.start_step_min` (D77: a free day's candidate starts are this far apart).
--     params_hash computed by ParamsHashes (624 keys). 1.0.3 becomes `superseded`.
-- (2) weekplan@1.2.0: every free stretch offers starts every START_STEP_MIN; a session takes the earliest unless a
--     later one avoids a hard rule or does better by more than the tie band while a member group recovers. Its params
--     slice now holds the new key, so the row carries the 1.0.4 slice hash.
-- (3) today@1.4.0: its week plans use those starts; its slice is unchanged, so the row keeps today's constants_hash.
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Manual rollback (only with explicit user approval): re-activate 1.0.3 and delete 1.0.4, delete weekplan@1.2.0 and
-- today@1.4.0 before any recommendation references them.

insert into fitness.algo_params (params_version, status, engine_version_range, params, params_hash, notes)
select '1.0.4', 'draft', engine_version_range,
       params || jsonb_build_object('weekplan.start_step_min', '{"evidence":"assumption","max":240,"min":30,"source":"D77 (the user: \"Every 2 hours\"; the planner tries a free day''s starts this far apart)","tunable":true,"tuned_by":"review","unit":"min","value":120}'::jsonb),
       '48800d6b7659ed6431d9b36c0f12b90c7ed51ede70b2fa49bc5c5667b1d4ed58',
       'D77 2026-11-02: weekplan.start_step_min (a free day offers a start every 120 min in each free stretch)'
  from fitness.algo_params
 where params_version = '1.0.3'
on conflict (params_version) do nothing;

do $$
declare
  old_p jsonb;
  new_p jsonb;
begin
  select params into old_p from fitness.algo_params where params_version = '1.0.3';
  select params into new_p from fitness.algo_params where params_version = '1.0.4';
  if (select count(*) from jsonb_object_keys(new_p)) <> 624 then
    raise exception 'algo_params 1.0.4: expected 624 keys';
  end if;
  if (new_p - 'weekplan.start_step_min') <> old_p then
    raise exception 'algo_params 1.0.4: keys other than weekplan.start_step_min differ from 1.0.3';
  end if;
  if (new_p -> 'weekplan.start_step_min' ->> 'value')::numeric <> 120 then
    raise exception 'algo_params 1.0.4: unexpected weekplan.start_step_min';
  end if;
end;
$$;

update fitness.algo_params set status = 'superseded'
 where params_version = '1.0.3' and status = 'active'
   and exists (select 1 from fitness.algo_params where params_version = '1.0.4' and status = 'draft');
update fitness.algo_params set status = 'active', released_at = now()
 where params_version = '1.0.4' and status = 'draft';

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'weekplan', '1.2.0', layer, '1.17.0', 'active', '1.0.4', 'dfb5c143d1c34403ca3d5ad20933ee8f1a669eb49bdae7178658dcdaf10aadb6',
       'docs/ALGORITHMS_PLANNING.md section 8', 'D77: a free day offers a start every START_STEP_MIN; each session takes the earliest unless a later one avoids a hard rule or does clearly better.'
  from fitness.algorithm_versions
 where module_id = 'weekplan' and version = '1.0.0'
on conflict (module_id, version) do nothing;

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'today', '1.4.0', layer, '1.17.0', 'active', '1.0.4', 'cf9d37fc4e94c01b6f0a5c3cf60acf7b4fe2c21ef7fbe2b6c74a5b610e8c9a2e',
       'docs/ALGORITHMS_PLANNING.md section 9', 'D77: its week plans use the candidate starts of weekplan@1.2.0.'
  from fitness.algorithm_versions
 where module_id = 'today' and version = '1.0.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select status from fitness.algo_params where params_version = '1.0.4') <> 'active' then
    raise exception '052: params 1.0.4 not active';
  end if;
  if (select params_hash from fitness.algo_params where params_version = '1.0.4')
     <> '48800d6b7659ed6431d9b36c0f12b90c7ed51ede70b2fa49bc5c5667b1d4ed58' then
    raise exception '052: params 1.0.4 hash differs';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'weekplan' and version = '1.2.0')
     <> 'dfb5c143d1c34403ca3d5ad20933ee8f1a669eb49bdae7178658dcdaf10aadb6' then
    raise exception '052: weekplan@1.2.0 missing or with another constants_hash';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'today' and version = '1.4.0')
     <> 'cf9d37fc4e94c01b6f0a5c3cf60acf7b4fe2c21ef7fbe2b6c74a5b610e8c9a2e' then
    raise exception '052: today@1.4.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.17.0') <> 2 then
    raise exception '052: expected 2 module versions for engine 1.17.0';
  end if;
end;
$$;
