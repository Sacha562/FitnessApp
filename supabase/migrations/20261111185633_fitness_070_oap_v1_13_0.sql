-- fitness_070_oap_v1_13_0 (M4, data): challenge definition oap@1.13.0 — DATA_MODEL.md 9.1.9, 9.4.1, D117 (revised)
--
-- D117 revised: the user keeps the full rests of the dips, the Pallof press, the assisted one-arm pull-ups and the
-- weighted pull-up sets of 4 or more reps; 1.12.0 had shortened them. A's assisted one-arm goes back to 150 s, A's
-- weighted pull-ups (4 x 4) to 180 s, the dips to 120 s and the Pallof press in A, B and C to 60 s. The set cuts of 1.12.0
-- stay, and so do B's 5 x 2 at 150 s and C's unchanged main rests. Like 069 it patches the published previous row
-- (8 jsonb operations, replayed locally to docs/challenges/oap.challenge.v1.json) and proves byte-equality through the
-- guard trigger's server_checksum. No engine change. Manual rollback (only with explicit user approval): delete the
-- definition row before any enrollment references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.12.0' and user_id is null;
  if base.id is null then
    raise exception '070: oap@1.12.0 is missing';
  end if;
  if base.server_checksum <> 'cf88f2e161ea03b920dd80adfcc44783eca07ac20948b151be77328804e48d65' then
    raise exception '070: oap@1.12.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  if base.definition #>> '{templates,A1,items,1,key}' <> 'oaa' or base.definition #>> '{templates,A1,items,3,key}' <> 'wpu'
     or base.definition #>> '{templates,A1,items,7,key}' <> 'pallof' or base.definition #>> '{templates,B1,items,4,key}' <> 'dip'
     or base.definition #>> '{templates,B1,items,5,key}' <> 'pallof' or base.definition #>> '{templates,C1,items,5,key}' <> 'pallof' then
    raise exception '070: the template items are not where 1.12.0 has them';
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.13.0"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'A1', 'items', '1', 'rest_s']::text[], $j$150$j$::jsonb, false);
  d := jsonb_set(d, array['templates', 'A1', 'items', '3', 'rest_s']::text[], $j$180$j$::jsonb, false);
  d := jsonb_set(d, array['templates', 'A1', 'items', '7', 'rest_s']::text[], $j$60$j$::jsonb, false);
  d := jsonb_set(d, array['templates', 'B1', 'items', '4', 'rest_s']::text[], $j$120$j$::jsonb, false);
  d := jsonb_set(d, array['templates', 'B1', 'items', '5', 'rest_s']::text[], $j$60$j$::jsonb, false);
  d := jsonb_set(d, array['templates', 'C1', 'items', '5', 'rest_s']::text[], $j$60$j$::jsonb, false);
  d := jsonb_set(d, array['migrations', '1.12.0']::text[], $j${"varMap": {}, "defaults": {}, "notes": "D117 revised: the rests of the dips, the Pallof press, the assisted one-arm pull-ups and the weighted pull-up sets of 4 or more reps go back to their 1.11.0 values (A assisted one-arm 150 s, A weighted pull-ups 180 s, dips 120 s, Pallof press 60 s); the set cuts stay. No state var changes."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('a4513ade-ff5c-5a08-b520-9cfe841c095d'::uuid, null, 'oap', '1.13.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, '2196cdd8e898fb8a7eab2fe90991f11aa6a5c034208d054fe0ac9fdb67f85e9d', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = 'a4513ade-ff5c-5a08-b520-9cfe841c095d';
  if r.id is null then
    raise exception '070: oap@1.13.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '2196cdd8e898fb8a7eab2fe90991f11aa6a5c034208d054fe0ac9fdb67f85e9d' then
    raise exception '070: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> 'fa1ce59a40c0d6daca100f4d44aecbbbb93233e86a6f31a48da5276d645f134c' then
    raise exception '070: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;
