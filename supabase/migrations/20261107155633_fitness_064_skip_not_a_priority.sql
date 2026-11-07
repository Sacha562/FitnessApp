-- fitness_064_skip_not_a_priority (data): algo_params 1.0.7, elig@1.1.0, select@1.1.0 - DECISIONS D99;
-- ALGORITHMS_PLANNING 12.3, 12.5, 13.3, 23
--
-- A skip goal means "not a priority" (D99): ELIG-1 removes an exercise only when every group it trains at
-- elig.skip_coeff (0.5) or more is skip, and SEL-1 charges select.lam_skip only for direct work
-- (c >= select.skip_direct_coeff 0.75), lightly.
-- algo_params 1.0.7 = 1.0.6 with select.lam_skip 3.0 -> 0.25 (source), the new key select.skip_direct_coeff 0.75, and
-- elig.skip_coeff's source text. params_hash computed by ParamsHashes (`sim params-hash`, 630 keys). 1.0.6 becomes
-- `superseded`. Registers elig@1.1.0 (constants_hash = its slice, unchanged) and select@1.1.0 (constants_hash = the new
-- `select` slice), ENGINE_VERSION 1.25.0.
-- No DDL, grant, policy, function, trigger, role or extension. No user rows. Idempotent.
-- Manual rollback (only with explicit user approval): re-activate 1.0.6 and delete 1.0.7, elig@1.1.0 and select@1.1.0
-- before any recommendation references them.

insert into fitness.algo_params (params_version, status, engine_version_range, params, params_hash, notes)
select '1.0.7', 'draft', engine_version_range,
       params
       || jsonb_build_object(
            'select.lam_skip', '{"evidence":"expert-opinion","max":10,"min":0,"source":"exercise-selection lens; D99 (skip = not a priority, light penalty on direct work)","tunable":true,"tuned_by":"review","unit":"J per FS","value":0.25}'::jsonb,
            'select.skip_direct_coeff', '{"evidence":"expert-opinion","max":1.0,"min":0.5,"source":"D99 (only direct work on a skip group is charged)","tunable":true,"tuned_by":"review","unit":"coeff","value":0.75}'::jsonb,
            'elig.skip_coeff', (params -> 'elig.skip_coeff') || '{"source":"exercise-selection lens; D99: an exercise whose groups at this coefficient are all skip is removed"}'::jsonb
          ),
       'edf79d9dd00a59078f566b8adf387732f920e34e31b1a8ca3fcbb90f104d2003',
       'D99 2026-11-07: skip = not a priority; light skip penalty on direct work only (select.lam_skip 0.25, select.skip_direct_coeff 0.75)'
  from fitness.algo_params
 where params_version = '1.0.6'
on conflict (params_version) do nothing;

do $$
declare
  old_p jsonb;
  new_p jsonb;
  changed text[] := array['select.lam_skip', 'select.skip_direct_coeff', 'elig.skip_coeff'];
begin
  select params into old_p from fitness.algo_params where params_version = '1.0.6';
  select params into new_p from fitness.algo_params where params_version = '1.0.7';
  if (select count(*) from jsonb_object_keys(new_p)) <> 630 then
    raise exception 'algo_params 1.0.7: expected 630 keys';
  end if;
  if (new_p - changed) <> (old_p - changed) then
    raise exception 'algo_params 1.0.7: keys other than the D99 keys differ from 1.0.6';
  end if;
  if (new_p -> 'elig.skip_coeff') - 'source' <> (old_p -> 'elig.skip_coeff') - 'source' then
    raise exception 'algo_params 1.0.7: elig.skip_coeff changed beyond its source';
  end if;
end;
$$;

update fitness.algo_params set status = 'superseded'
 where params_version = '1.0.6' and status = 'active'
   and exists (select 1 from fitness.algo_params where params_version = '1.0.7' and status = 'draft');
update fitness.algo_params set status = 'active', released_at = now()
 where params_version = '1.0.7' and status = 'draft';

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('elig', '1.1.0', 'selection', '1.25.0', 'active', '1.0.7',
   'eb00b3a878bf70646eae4325ee458621d8055de534de20060b5495d273eaf403',
   'docs/ALGORITHMS_PLANNING.md section 12', 'D99: a skip goal removes only exercises that train skip groups alone.'),
  ('select', '1.1.0', 'selection', '1.25.0', 'active', '1.0.7',
   'd3be10caf86eb2fc7210df9db5938861abfdc3bb4f4368683fc90636deed7d48',
   'docs/ALGORITHMS_PLANNING.md section 13', 'D99: the skip penalty charges only direct work on a skip group, at 0.25 J per FS.')
on conflict (module_id, version) do nothing;

do $$
begin
  if (select status from fitness.algo_params where params_version = '1.0.7') <> 'active' then
    raise exception '064: params 1.0.7 not active';
  end if;
  if (select params_hash from fitness.algo_params where params_version = '1.0.7')
     <> 'edf79d9dd00a59078f566b8adf387732f920e34e31b1a8ca3fcbb90f104d2003' then
    raise exception '064: params 1.0.7 hash differs';
  end if;
  if (select count(*) from fitness.algo_params where status = 'active') <> 1 then
    raise exception '064: expected exactly one active params version';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'elig' and version = '1.1.0')
     is distinct from (select constants_hash from fitness.algorithm_versions where module_id = 'elig' and version = '1.0.0') then
    raise exception '064: elig@1.1.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.25.0') <> 2 then
    raise exception '064: expected 2 module versions for engine 1.25.0';
  end if;
end;
$$;
