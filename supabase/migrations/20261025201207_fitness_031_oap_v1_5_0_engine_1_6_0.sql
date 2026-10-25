-- fitness_031_oap_v1_5_0_engine_1_6_0 (M2b.6, data): challenge definition oap@1.5.0 and challenge.enrollment 1.3.0 of
-- ENGINE_VERSION 1.6.0 — DATA_MODEL.md 9.1.9, 9.4.1, 13.2, D44
--
-- D44: no rowing machine in the OAP warm-up. oap@1.5.0 drops the warm-up item wu_row (3 minutes on the rower) and the
-- rower_erg equipment and exercise entries. Like 028 it patches the published previous row (5 jsonb operations, replayed
-- locally to docs/challenges/oap.challenge.v1.json) and proves the result through the guard trigger's server_checksum.
-- challenge.enrollment 1.3.0 folds definition.migrated (CHALLENGE_ENGINE 4.12): a running enrollment moves to 1.5.0 when
-- the user taps Update after the preview, which rewrites challenge_enrollments.definition_id; that row's foreign key
-- needs this definition row first. Its params slice is unchanged, so it keeps its 1.0.0 row's hash. Manual rollback
-- (only with explicit user approval): delete the definition row before any enrollment references it, and the module row.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.4.0' and user_id is null;
  if base.id is null then
    raise exception '031: oap@1.4.0 is missing';
  end if;
  if base.server_checksum <> '78a2019937405915e7c40d67b4657e8f9ee47e7422276cb7629263eab3ff74c0' then
    raise exception '031: oap@1.4.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.5.0"$j$::jsonb, true);
  d := d #- array['equipment', 'required', '7']::text[];
  d := d #- array['exercises', '23']::text[];
  d := d #- array['blocks', 'warmup10', '0']::text[];
  d := jsonb_set(d, array['migrations', '1.4.0']::text[], $j${"varMap":{},"defaults":{},"notes":"D44: no rowing machine in the warm-up (wu_row and the rower_erg equipment and exercise removed). No state var changes."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('405ece39-a335-5b41-af88-840a7e3e27c8'::uuid, null, 'oap', '1.5.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, '4db8dfc1aad0de274cb6c1a7c1e647a3166d985e0ffd671bff48157d494bbaae', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = '405ece39-a335-5b41-af88-840a7e3e27c8';
  if r.id is null then
    raise exception '031: oap@1.5.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '4db8dfc1aad0de274cb6c1a7c1e647a3166d985e0ffd671bff48157d494bbaae' then
    raise exception '031: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> '9ad61cf170274d989669b3cf68121787d550c75a6e949f2b21c9b88595c215dc' then
    raise exception '031: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('challenge.enrollment', '1.3.0', 'challenge', '1.6.0', 'active', '1.0.1', '33c7a5ded80514bce442ee4c87c86d66d5f811b7b763fb50ec534f850a14de68',
   'docs/CHALLENGE_ENGINE.md section 4.12', 'D44: definition.migrated folds the later events under the new definition version.')
on conflict (module_id, version) do nothing;

do $$
begin
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.6.0') <> 1 then
    raise exception '031: expected 1 module version for engine 1.6.0';
  end if;
end;
$$;
