-- fitness_057_params_1_0_5_improve_targets (data): algo_params 1.0.5 - DATA_MODEL.md 9.1.10; DECISIONS D85;
-- ALGORITHMS_PLANNING 5.4, 5.5
--
-- algo_params 1.0.5 = 1.0.4 with `targets.special_improve` reduced to {lower_back 4} (D85: front_delts, forearms,
-- upper_traps, adductors and abductors get BASE + focus like every group, because indirect work already counts toward
-- the target). params_hash computed by ParamsHashes (624 keys). 1.0.4 becomes `superseded`.
-- Params-only release: no module or engine version change.
-- No DDL, grant, policy, function, trigger, role or extension. No user rows. Idempotent.
-- Manual rollback (only with explicit user approval): re-activate 1.0.4 and delete 1.0.5 before any recommendation
-- references it.

insert into fitness.algo_params (params_version, status, engine_version_range, params, params_hash, notes)
select '1.0.5', 'draft', engine_version_range,
       params || jsonb_build_object('targets.special_improve', '{"evidence":"expert-opinion","max":12,"min":2,"source":"planning lens; D85 (only lower_back; indirect work already counts toward the target)","tunable":true,"tuned_by":"T-TARGET","unit":"Vhyp sets/week (by group)","value":{"lower_back":4}}'::jsonb),
       'd2b509146e762fc707d070922efe248a6d21c19a150ceecadb96455257d41e5b',
       'D85 2026-11-03: targets.special_improve only lower_back 4 (front_delts, forearms, upper_traps, adductors, abductors get BASE)'
  from fitness.algo_params
 where params_version = '1.0.4'
on conflict (params_version) do nothing;

do $$
declare
  old_p jsonb;
  new_p jsonb;
begin
  select params into old_p from fitness.algo_params where params_version = '1.0.4';
  select params into new_p from fitness.algo_params where params_version = '1.0.5';
  if (select count(*) from jsonb_object_keys(new_p)) <> 624 then
    raise exception 'algo_params 1.0.5: expected 624 keys';
  end if;
  if (new_p - 'targets.special_improve') <> (old_p - 'targets.special_improve') then
    raise exception 'algo_params 1.0.5: keys other than targets.special_improve differ from 1.0.4';
  end if;
  if new_p -> 'targets.special_improve' -> 'value' <> '{"lower_back":4}'::jsonb then
    raise exception 'algo_params 1.0.5: unexpected targets.special_improve';
  end if;
end;
$$;

update fitness.algo_params set status = 'superseded'
 where params_version = '1.0.4' and status = 'active'
   and exists (select 1 from fitness.algo_params where params_version = '1.0.5' and status = 'draft');
update fitness.algo_params set status = 'active', released_at = now()
 where params_version = '1.0.5' and status = 'draft';

do $$
begin
  if (select status from fitness.algo_params where params_version = '1.0.5') <> 'active' then
    raise exception '057: params 1.0.5 not active';
  end if;
  if (select params_hash from fitness.algo_params where params_version = '1.0.5')
     <> 'd2b509146e762fc707d070922efe248a6d21c19a150ceecadb96455257d41e5b' then
    raise exception '057: params 1.0.5 hash differs';
  end if;
  if (select count(*) from fitness.algo_params where status = 'active') <> 1 then
    raise exception '057: expected exactly one active params version';
  end if;
end;
$$;
