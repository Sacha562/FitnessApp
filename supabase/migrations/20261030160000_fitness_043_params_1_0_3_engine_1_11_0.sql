-- fitness_043_params_1_0_3_engine_1_11_0 (M4.4, data): algo_params 1.0.3, select@1.0.0 on 1.0.3, substitute@1.1.0 of
-- ENGINE_VERSION 1.11.0 - DATA_MODEL.md 9.1.9, 9.1.10; D56, D62
--
-- (1) algo_params 1.0.3 = 1.0.2 plus `select.per_group_k` (D56: SEL-1's candidate list takes each target group's two
--     best direct trainers first). params_hash computed by ParamsHashes (623 keys). 1.0.2 becomes `superseded`.
-- (2) select@1.0.0 ships for the first time with M4.4. D56 changed its candidate rule before any APK called it (no
--     version bump), so its registry row moves to default params 1.0.3 and the hash of its 24-key slice there. The
--     update only applies while the row still carries the 1.0.0 slice hash and no recommendation uses select yet.
-- (3) substitute@1.1.0 (D62: "Do {k} now" in ORD-1 order, busy stations ranked last). Its params slice is unchanged, so
--     the row keeps substitute@1.0.0's constants_hash. order, time and allocator ship at 1.0.0 with unchanged slices.
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Manual rollback (only with explicit user approval): re-activate 1.0.2 and delete 1.0.3, restore select@1.0.0's
-- default params 1.0.0 and hash b879eeb8..., delete substitute@1.1.0.

insert into fitness.algo_params (params_version, status, engine_version_range, params, params_hash, notes)
select '1.0.3', 'draft', engine_version_range,
       params || jsonb_build_object('select.per_group_k', '{"evidence":"expert-opinion","max":5,"min":0,"source":"D56 (the seed''s 160 rows squeeze maintain and isolation rows out of a plain top-25 cut)","tunable":true,"tuned_by":"beam-versus-exhaustive tests, review","unit":"exercises per group","value":2}'::jsonb),
       '58dd4bf2d426f574fe84bcc677c7d7543302d70506bdf9d6b7f44cc3c4568031',
       'D56 2026-10-29: select.per_group_k (each target group''s best direct trainers enter the SEL-1 candidate list first)'
  from fitness.algo_params
 where params_version = '1.0.2'
on conflict (params_version) do nothing;

do $$
declare
  old_p jsonb;
  new_p jsonb;
begin
  select params into old_p from fitness.algo_params where params_version = '1.0.2';
  select params into new_p from fitness.algo_params where params_version = '1.0.3';
  if (select count(*) from jsonb_object_keys(new_p)) <> 623 then
    raise exception 'algo_params 1.0.3: expected 623 keys';
  end if;
  if (new_p - 'select.per_group_k') <> old_p then
    raise exception 'algo_params 1.0.3: keys other than select.per_group_k differ from 1.0.2';
  end if;
  if (new_p -> 'select.per_group_k' ->> 'value')::numeric <> 2 then
    raise exception 'algo_params 1.0.3: unexpected select.per_group_k';
  end if;
end;
$$;

update fitness.algo_params set status = 'superseded'
 where params_version = '1.0.2' and status = 'active'
   and exists (select 1 from fitness.algo_params where params_version = '1.0.3' and status = 'draft');
update fitness.algo_params set status = 'active', released_at = now()
 where params_version = '1.0.3' and status = 'draft';

update fitness.algorithm_versions
   set default_params_version = '1.0.3',
       constants_hash = 'de4618fe948fa88fe04db523035e0afd4c82ce2e120f1d6bc971666c2d3ab5d6',
       description = 'D56: the candidate list takes each target group''s best direct trainers first (select.per_group_k).'
 where module_id = 'select' and version = '1.0.0'
   and constants_hash = 'b879eeb83ac5aa366ed329f6487fc91ee890b26df7f25463a5951290bca8a5dc'
   and not exists (select 1 from fitness.recommendations where module_id = 'select');

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'substitute', '1.1.0', layer, '1.11.0', 'active', '1.0.3', constants_hash,
       'docs/ALGORITHMS_PLANNING.md section 15', 'D62: "Do {k} now" in ORD-1 order; candidates on a busy station rank after the others.'
  from fitness.algorithm_versions
 where module_id = 'substitute' and version = '1.0.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select status from fitness.algo_params where params_version = '1.0.3') <> 'active' then
    raise exception '043: params 1.0.3 not active';
  end if;
  if (select params_hash from fitness.algo_params where params_version = '1.0.3')
     <> '58dd4bf2d426f574fe84bcc677c7d7543302d70506bdf9d6b7f44cc3c4568031' then
    raise exception '043: params 1.0.3 hash differs';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'select' and version = '1.0.0')
     <> 'de4618fe948fa88fe04db523035e0afd4c82ce2e120f1d6bc971666c2d3ab5d6' then
    raise exception '043: select@1.0.0 not on the 1.0.3 slice';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.11.0') <> 1 then
    raise exception '043: expected 1 module version for engine 1.11.0';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'substitute' and version = '1.1.0')
     <> 'f93bb6d442529284b9bed943224c6330b1b28f6f2ada8002ad9d89757b2947af' then
    raise exception '043: substitute@1.1.0 constants_hash differs';
  end if;
end;
$$;
