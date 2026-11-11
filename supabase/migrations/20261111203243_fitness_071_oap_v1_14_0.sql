-- fitness_071_oap_v1_14_0 (M4, data): challenge definition oap@1.14.0 — DATA_MODEL.md 9.1.9, 9.4.1, D119
--
-- D119: session C's assisted one-arm pin drops 5 lb after a single C session with every set done at RIR >= 2 (the
-- prescribed RIR) or a 'too easy' tap; before it took two such sessions in a row at RIR >= 3. cEasySessions 2 -> 1,
-- cEasyMinRir 3 -> 2 (range 2-5); P03's rationale and prog.C.pin.easy's explain text follow. Like 069 and 070 it patches
-- the published previous row (6 jsonb operations, replayed locally to docs/challenges/oap.challenge.v1.json) and proves
-- byte-equality through the guard trigger's server_checksum. No engine change. Manual rollback (only with explicit user
-- approval): delete the definition row before any enrollment references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.13.0' and user_id is null;
  if base.id is null then
    raise exception '071: oap@1.13.0 is missing';
  end if;
  if base.server_checksum <> 'fa1ce59a40c0d6daca100f4d44aecbbbb93233e86a6f31a48da5276d645f134c' then
    raise exception '071: oap@1.13.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  if base.definition #>> '{proposals,2,id}' <> 'P03' or base.definition #>> '{rules,40,id}' <> 'prog.C.pin.easy'
     or base.definition #>> '{params,cEasySessions,default}' <> '2' or base.definition #>> '{params,cEasyMinRir,default}' <> '3' then
    raise exception '071: P03, prog.C.pin.easy or the C easy params are not where 1.13.0 has them';
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.14.0"$j$::jsonb, true);
  d := jsonb_set(d, array['proposals', '2', 'rationale']::text[], $j${"en": "Easy means every set had 2 or more reps left (the target), or you tapped 'too easy'."}$j$::jsonb, false);
  d := jsonb_set(d, array['params', 'cEasySessions']::text[], $j${"type": "int", "default": 1, "tag": "A", "source": "artifact section 05 / Todoist C (two sessions); one session from 1.14.0 (D119, user)", "grade": "expert-opinion"}$j$::jsonb, false);
  d := jsonb_set(d, array['params', 'cEasyMinRir']::text[], $j${"type": "int", "default": 2, "min": 2, "max": 5, "tag": "P", "proposal": "P03", "tunable": true, "tunedBy": "reported RIR on C oaa sets vs next-session performance after a -5 lb step", "grade": "expert-opinion"}$j$::jsonb, false);
  d := jsonb_set(d, array['rules', '40', 'explain']::text[], $j${"en": "Every C set done with 2 or more reps left (or a 'too easy' tap): -5 lb."}$j$::jsonb, false);
  d := jsonb_set(d, array['migrations', '1.13.0']::text[], $j${"varMap": {}, "defaults": {}, "notes": "D119: the C pin drops 5 lb after one C session with every set done at RIR >= 2 or a 'too easy' tap (cEasySessions 2 -> 1, cEasyMinRir 3 -> 2). No state var changes; a cEasyStreak of 1 simply no longer matters."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('afe10032-31c5-52a6-bdbf-c12a71948403'::uuid, null, 'oap', '1.14.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, '27d1f96d83e327aa49b6755f19e31f137a672ac23032e85edba7b3eb9576b575', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = 'afe10032-31c5-52a6-bdbf-c12a71948403';
  if r.id is null then
    raise exception '071: oap@1.14.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '27d1f96d83e327aa49b6755f19e31f137a672ac23032e85edba7b3eb9576b575' then
    raise exception '071: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> '955df1095614dbe76461a6862baf8f76d8de89a8a5ce8dcf35017135c4a3ed8e' then
    raise exception '071: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;
