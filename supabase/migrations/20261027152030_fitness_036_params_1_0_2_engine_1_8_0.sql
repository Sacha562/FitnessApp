-- fitness_036_params_1_0_2_engine_1_8_0 (M3.4, data and one CHECK): algo_params 1.0.2, rest@1.1.0 of ENGINE_VERSION 1.8.0,
-- template_source 'repeat' - DATA_MODEL.md 9.1.9, 9.1.10, E9, 9.8.1; D49
--
-- (1) algo_params 1.0.2 = 1.0.1 plus `reason.pain_stop_js_min` (D49: a pain stop removes the remaining exercises whose
--     joint stress on the reported region is 2 or more). params_hash computed by ParamsHashes and pinned in
--     ParamsFileTest. 1.0.1 becomes `superseded`.
-- (2) rest@1.1.0 (M3.3: outcome and fatigue modifiers, alternating-pair and superset rests, the live readout, the
--     nightly adaptation). Its params slice is unchanged, so `constants_hash` equals rest@1.0.0's. fatigue, rcm, rircal,
--     sse, hold, reason, rx, warmup and stall ship at 1.0.0, registered by 005.
-- (3) `workout_sessions.template_source` gains 'repeat' (D49: a general session started as a copy of a past one).
-- (4) The registry row of `session_started` gains the payload key `source_session_id` (D49).
-- No grant, policy, function, trigger, role or extension. Idempotent.
-- Manual rollback (only with explicit user approval): re-activate 1.0.1 and delete 1.0.2, delete rest@1.1.0, restore
-- the old CHECK once no session has template_source 'repeat', drop the payload key.

insert into fitness.algo_params (params_version, status, engine_version_range, params, params_hash, notes)
select '1.0.2', 'draft', engine_version_range,
       params || jsonb_build_object('reason.pain_stop_js_min', '{"evidence":"expert-opinion","max":3,"min":1,"source":"D49 (only clear joint stress stops an exercise; light stress 1 stays)","tunable":true,"tuned_by":"Decision (D49); pain reports versus later tolerance of the exercise (v_wellness_safety)","unit":"joint-stress level","value":2}'::jsonb),
       '6298d1217c3ba230b98acc6d40777d075b50b97f2cce6db007764e60beab6c57',
       'D49 2026-10-27: reason.pain_stop_js_min (the pain stop removes exercises with joint stress 2 or more)'
  from fitness.algo_params
 where params_version = '1.0.1'
on conflict (params_version) do nothing;

do $$
declare
  old_p jsonb;
  new_p jsonb;
begin
  select params into old_p from fitness.algo_params where params_version = '1.0.1';
  select params into new_p from fitness.algo_params where params_version = '1.0.2';
  if (select count(*) from jsonb_object_keys(new_p)) <> 622 then
    raise exception 'algo_params 1.0.2: expected 622 keys';
  end if;
  if (new_p - 'reason.pain_stop_js_min') <> old_p then
    raise exception 'algo_params 1.0.2: keys other than reason.pain_stop_js_min differ from 1.0.1';
  end if;
  if (new_p -> 'reason.pain_stop_js_min' ->> 'value')::numeric <> 2 then
    raise exception 'algo_params 1.0.2: unexpected reason.pain_stop_js_min';
  end if;
end;
$$;

update fitness.algo_params set status = 'superseded'
 where params_version = '1.0.1' and status = 'active'
   and exists (select 1 from fitness.algo_params where params_version = '1.0.2' and status = 'draft');
update fitness.algo_params set status = 'active', released_at = now()
 where params_version = '1.0.2' and status = 'draft';

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('rest', '1.1.0', 'prescription', '1.8.0', 'active', '1.0.2', 'e87250d03e2bc2d024ee6f27ad367627c0ba24aea6fa7f7a1f00839099249f2c',
   'docs/ALGORITHMS.md section B11', 'M3.3: outcome and fatigue modifiers, alternating-pair and superset rests, the live readout, the nightly adaptation.')
on conflict (module_id, version) do nothing;

alter table fitness.workout_sessions drop constraint if exists workout_sessions_template_source_check;
alter table fitness.workout_sessions add constraint workout_sessions_template_source_check
  check (template_source in ('challenge_slot', 'family_combo', 'recommended', 'empty', 'habit', 'import', 'repeat'));

update fitness.session_event_types
   set payload_keys = payload_keys || array['source_session_id']::text[]
 where event_type = 'session_started' and not ('source_session_id' = any (payload_keys));

do $$
begin
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.8.0') <> 1 then
    raise exception '036: expected 1 module version for engine 1.8.0';
  end if;
  if (select status from fitness.algo_params where params_version = '1.0.2') <> 'active' then
    raise exception '036: params 1.0.2 not active';
  end if;
  if not exists (select 1 from fitness.session_event_types
                  where event_type = 'session_started' and 'source_session_id' = any (payload_keys)) then
    raise exception '036: session_started lacks source_session_id';
  end if;
end;
$$;
