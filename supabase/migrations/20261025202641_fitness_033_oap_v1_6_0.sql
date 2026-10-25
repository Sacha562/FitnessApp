-- fitness_033_oap_v1_6_0 (M2b.6, data): challenge definition oap@1.6.0 — DATA_MODEL.md 9.1.9, 9.4.1, D45
--
-- D45: safety.elbow.traceFromPain also fires on the new body regions elbow_posterior and forearm (migration 032), and
-- the Pallof press items of A, B and C are per side. Like 028 it patches the published previous row (6 jsonb
-- operations, replayed locally to the new file) and proves byte-equality with docs/challenges/oap.challenge.v1.json
-- through the guard trigger's server_checksum. No engine change. Manual rollback (only with explicit user approval):
-- delete the definition row before any enrollment references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  if (select count(*) from fitness.body_regions where code in ('elbow_posterior', 'forearm')) <> 2 then
    raise exception '033: run 032 first (body regions)';
  end if;
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.5.0' and user_id is null;
  if base.id is null then
    raise exception '033: oap@1.5.0 is missing';
  end if;
  if base.server_checksum <> '9ad61cf170274d989669b3cf68121787d550c75a6e949f2b21c9b88595c215dc' then
    raise exception '033: oap@1.5.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.6.0"$j$::jsonb, true);
  d := jsonb_set(d, array['rules', '2', 'if']::text[], $j$"event.location in ['elbow_medial', 'elbow_lateral', 'elbow_anterior', 'elbow_posterior', 'forearm']"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'A1', 'items', '7', 'perSide']::text[], 'true'::jsonb, true);
  d := jsonb_set(d, array['templates', 'B1', 'items', '5', 'perSide']::text[], 'true'::jsonb, true);
  d := jsonb_set(d, array['templates', 'C1', 'items', '5', 'perSide']::text[], 'true'::jsonb, true);
  d := jsonb_set(d, array['migrations', '1.5.0']::text[], $j${"varMap":{},"defaults":{},"notes":"D45: the elbow trace also fires on pain at the back of the elbow and in the forearm (new body regions); the Pallof press is per side. No state var changes."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('e0511564-bad2-58ac-9c19-095c083fcdae'::uuid, null, 'oap', '1.6.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, 'eadc0df4249ba320343d6dedd8833fd4188f846e8caedf232f646444a0ec8fe2', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = 'e0511564-bad2-58ac-9c19-095c083fcdae';
  if r.id is null then
    raise exception '033: oap@1.6.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> 'eadc0df4249ba320343d6dedd8833fd4188f846e8caedf232f646444a0ec8fe2' then
    raise exception '033: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> 'ba19a87d8a117b29929786989ceb86a578457067ef84fe574c894ddb66b84695' then
    raise exception '033: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;
