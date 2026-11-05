-- fitness_059_time_calibration (data): algo_params 1.0.6, time@1.1.0, order@1.1.0 - DECISIONS D93;
-- ALGORITHMS_PLANNING 17.3, 17.4, 23.17; DATA_MODEL 9.1.9, 9.1.10
--
-- algo_params 1.0.6 = 1.0.5 plus the TIME-1 calibration keys (time.rest_ratio 1.0, time.calib_min_rests 3,
-- time.calib_min_switches 2, time.calib_rest_ratio_step 0.05, time.calib_transition_step_s 5) and time.transition_s
-- max 300 (D93). params_hash computed by ParamsHashes (629 keys). 1.0.5 becomes `superseded`.
-- Registers time@1.1.0 (the calibration and the rest ratio in every estimate; constants_hash = the new `time` slice)
-- and order@1.1.0 (the superset duration takes the rest ratio; its own slice is unchanged), ENGINE_VERSION 1.22.0.
-- No DDL, grant, policy, function, trigger, role or extension. No user rows. Idempotent.
-- Manual rollback (only with explicit user approval): re-activate 1.0.5 and delete 1.0.6, time@1.1.0 and order@1.1.0
-- before any recommendation references them.

insert into fitness.algo_params (params_version, status, engine_version_range, params, params_hash, notes)
select '1.0.6', 'draft', engine_version_range,
       params
       || jsonb_build_object(
            'time.rest_ratio', '{"evidence":"engineering-fact","max":3.0,"min":0.5,"source":"D93","tunable":true,"tuned_by":"calibrated per user from set timestamps (D93)","unit":"dimensionless","value":1.0}'::jsonb,
            'time.calib_min_rests', '{"evidence":"engineering-fact","max":10,"min":1,"source":"D93","tunable":false,"tuned_by":"not tunable (fixed design constant)","unit":"rest pairs per session","value":3}'::jsonb,
            'time.calib_min_switches', '{"evidence":"engineering-fact","max":10,"min":1,"source":"D93","tunable":false,"tuned_by":"not tunable (fixed design constant)","unit":"switches per session","value":2}'::jsonb,
            'time.calib_rest_ratio_step', '{"evidence":"engineering-fact","max":0.2,"min":0.01,"source":"D93","tunable":false,"tuned_by":"not tunable (fixed design constant)","unit":"dimensionless","value":0.05}'::jsonb,
            'time.calib_transition_step_s', '{"evidence":"engineering-fact","max":30,"min":1,"source":"D93","tunable":false,"tuned_by":"not tunable (fixed design constant)","unit":"s","value":5}'::jsonb,
            'time.transition_s', '{"evidence":"expert-opinion","max":300,"min":20,"source":"C19; max 300 (D93)","tunable":true,"tuned_by":"calibrated per user from set timestamps (D93)","unit":"s","value":60}'::jsonb
          ),
       'c479105f0c765a627179df884b354e444c5ea45aa3d74e6008e8c2be0cd17389',
       'D93 2026-11-05: TIME-1 calibration keys (rest ratio, changeover), time.transition_s max 300'
  from fitness.algo_params
 where params_version = '1.0.5'
on conflict (params_version) do nothing;

do $$
declare
  old_p jsonb;
  new_p jsonb;
  changed text[] := array['time.rest_ratio', 'time.calib_min_rests', 'time.calib_min_switches',
                          'time.calib_rest_ratio_step', 'time.calib_transition_step_s', 'time.transition_s'];
begin
  select params into old_p from fitness.algo_params where params_version = '1.0.5';
  select params into new_p from fitness.algo_params where params_version = '1.0.6';
  if (select count(*) from jsonb_object_keys(new_p)) <> 629 then
    raise exception 'algo_params 1.0.6: expected 629 keys';
  end if;
  if (new_p - changed) <> (old_p - changed) then
    raise exception 'algo_params 1.0.6: keys other than the D93 keys differ from 1.0.5';
  end if;
  if (new_p -> 'time.transition_s') - 'max' - 'source' - 'tuned_by' <> (old_p -> 'time.transition_s') - 'max' - 'source' - 'tuned_by' then
    raise exception 'algo_params 1.0.6: time.transition_s changed beyond max, source and tuned_by';
  end if;
end;
$$;

update fitness.algo_params set status = 'superseded'
 where params_version = '1.0.5' and status = 'active'
   and exists (select 1 from fitness.algo_params where params_version = '1.0.6' and status = 'draft');
update fitness.algo_params set status = 'active', released_at = now()
 where params_version = '1.0.6' and status = 'draft';

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('time', '1.1.0', 'selection', '1.22.0', 'active', '1.0.6',
   '9afe01af973e436f74434173080411f6b2044c7cfbd99a70dcad4a62f332f5bd',
   'docs/ALGORITHMS_PLANNING.md section 17', 'D93: TIME-1 calibrates the rest ratio and the changeover from the user''s finished sessions.'),
  ('order', '1.1.0', 'selection', '1.22.0', 'active', '1.0.6',
   'e20586e41dfb4522d6343779fc609d1a20f45b98882a27c327e08bf398970ad3',
   'docs/ALGORITHMS_PLANNING.md section 14', 'D93: the superset duration takes TIME-1''s calibrated rest ratio.')
on conflict (module_id, version) do nothing;

do $$
begin
  if (select status from fitness.algo_params where params_version = '1.0.6') <> 'active' then
    raise exception '059: params 1.0.6 not active';
  end if;
  if (select params_hash from fitness.algo_params where params_version = '1.0.6')
     <> 'c479105f0c765a627179df884b354e444c5ea45aa3d74e6008e8c2be0cd17389' then
    raise exception '059: params 1.0.6 hash differs';
  end if;
  if (select count(*) from fitness.algo_params where status = 'active') <> 1 then
    raise exception '059: expected exactly one active params version';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'order' and version = '1.1.0')
     is distinct from (select constants_hash from fitness.algorithm_versions where module_id = 'order' and version = '1.0.0') then
    raise exception '059: order@1.1.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.22.0') <> 2 then
    raise exception '059: expected 2 module versions for engine 1.22.0';
  end if;
end;
$$;
