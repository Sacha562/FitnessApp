-- fitness_076_sse_1_1_0_drift (data): algo_params 1.0.9, sse@1.1.0 - DECISIONS D123; ALGORITHMS B6.4
--
-- D123: when two sessions in a row put SSE-1's normalised innovation beyond sse.drift_gate_sd (1.5) on the same side,
-- the NORMAL step first widens P_LL to match the innovation, capped at sse.drift_max_inflate (25); the state keeps zn_prev.
-- algo_params 1.0.9 = 1.0.8 with the new keys sse.drift_gate_sd and sse.drift_max_inflate; params_hash computed by
-- ParamsHashes (`sim params-hash`, 634 keys). 1.0.8 becomes `superseded`. Registers sse@1.1.0 (constants_hash = the new
-- `sse` slice), ENGINE_VERSION 1.29.0 (1.28.0 is D122's, migration 074).
-- No DDL, grant, policy, function, trigger, role or extension. No user rows. Idempotent.
-- Manual rollback (only with explicit user approval): re-activate 1.0.8, delete 1.0.9 and sse@1.1.0 before any
-- recommendation references them.

insert into fitness.algo_params (params_version, status, engine_version_range, params, params_hash, notes)
select '1.0.9', 'draft', engine_version_range,
       params
       || jsonb_build_object(
            'sse.drift_gate_sd', '{"evidence":"expert-opinion","max":3,"min":1,"source":"D123 (two sessions in a row beyond this many sd on the same side are a sustained change, not an outlier; adaptive Kalman covariance matching)","tunable":true,"tuned_by":"M2 coverage, runs of same-sign innovations","unit":"sd","value":1.5}'::jsonb,
            'sse.drift_max_inflate', '{"evidence":"expert-opinion","max":100,"min":1,"source":"D123 (cap on the P_LL widening of one drift step)","tunable":true,"tuned_by":"M2 coverage after a drift step","unit":"dimensionless","value":25}'::jsonb
          ),
       '16a7b1b8bfa4b8bf6d350c041e707855ca12c10be5249fc8bf24dc42fd72fe5b',
       'D123 2026-11-22: SSE-1 drift rule (sse.drift_gate_sd 1.5, sse.drift_max_inflate 25)'
  from fitness.algo_params
 where params_version = '1.0.8'
on conflict (params_version) do nothing;

do $$
declare
  old_p jsonb;
  new_p jsonb;
  added text[] := array['sse.drift_gate_sd', 'sse.drift_max_inflate'];
begin
  select params into old_p from fitness.algo_params where params_version = '1.0.8';
  select params into new_p from fitness.algo_params where params_version = '1.0.9';
  if (select count(*) from jsonb_object_keys(new_p)) <> 634 then
    raise exception 'algo_params 1.0.9: expected 634 keys';
  end if;
  if (new_p - added) <> old_p then
    raise exception 'algo_params 1.0.9: keys other than the D123 keys differ from 1.0.8';
  end if;
end;
$$;

update fitness.algo_params set status = 'superseded'
 where params_version = '1.0.8' and status = 'active'
   and exists (select 1 from fitness.algo_params where params_version = '1.0.9' and status = 'draft');
update fitness.algo_params set status = 'active', released_at = now()
 where params_version = '1.0.9' and status = 'draft';

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('sse', '1.1.0', 'estimation', '1.29.0', 'active', '1.0.9',
   '14665848d13de9aa1b5e763f0a8be16291c3653d14c2b2ec2a2d8d8b0ab9919a',
   'docs/ALGORITHMS.md section B6.4', 'D123: two sessions in a row beyond the drift gate on the same side widen P_LL to match the innovation (capped).')
on conflict (module_id, version) do nothing;

do $$
begin
  if (select status from fitness.algo_params where params_version = '1.0.9') <> 'active' then
    raise exception '076: params 1.0.9 not active';
  end if;
  if (select params_hash from fitness.algo_params where params_version = '1.0.9')
     <> '16a7b1b8bfa4b8bf6d350c041e707855ca12c10be5249fc8bf24dc42fd72fe5b' then
    raise exception '076: params 1.0.9 hash differs';
  end if;
  if (select count(*) from fitness.algo_params where status = 'active') <> 1 then
    raise exception '076: expected exactly one active params version';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.29.0') <> 1 then
    raise exception '076: expected 1 module version for engine 1.29.0';
  end if;
end;
$$;
