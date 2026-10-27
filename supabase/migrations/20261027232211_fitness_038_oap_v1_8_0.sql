-- fitness_038_oap_v1_8_0 (M3, data): challenge definition oap@1.8.0 — DATA_MODEL.md 9.1.9, 9.4.1, D51
--
-- D51: the daily two-arm hang is skipped on every gym day (A, B, C and the test day), not only on A. The habit's skipIf
-- becomes "todayHas('A') || todayHas('B') || todayHas('C') || todayHas('TEST') || s.elbowTrace". Like 034 it patches the
-- published previous row (4 jsonb operations, replayed locally to docs/challenges/oap.challenge.v1.json) and proves
-- byte-equality through the guard trigger's server_checksum. No engine change. Manual rollback (only with explicit user
-- approval): delete the definition row before any enrollment references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.7.0' and user_id is null;
  if base.id is null then
    raise exception '038: oap@1.7.0 is missing';
  end if;
  if base.server_checksum <> '3083934c6ce6b3c565a7bf6e35a3783c134ad0b5fc8e586d07adbffaa69a6b70' then
    raise exception '038: oap@1.7.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  if base.definition #>> '{habits,0,id}' <> 'daily_hang' then
    raise exception '038: habits[0] is not daily_hang';
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.8.0"$j$::jsonb, true);
  d := jsonb_set(d, array['habits', '0', 'skipIf']::text[], $j$"todayHas('A') || todayHas('B') || todayHas('C') || todayHas('TEST') || s.elbowTrace"$j$::jsonb, true);
  d := jsonb_set(d, array['habits', '0', 'source']::text[], $j$"Todoist Hangen / artifact section 06 (skipped on A day); D51: skipped on every gym day"$j$::jsonb, true);
  d := jsonb_set(d, array['migrations', '1.7.0']::text[], $j${"varMap":{},"defaults":{},"notes":"D51: the daily two-arm hang is skipped on every gym day (A, B, C and the test day), not only on A. No state var changes."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('bc344abf-8627-5cab-bdc1-f4c46c67820b'::uuid, null, 'oap', '1.8.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, 'd9465c4a048f55a10e127a87efde3264261b385c44889eb28a4c7676359617bc', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = 'bc344abf-8627-5cab-bdc1-f4c46c67820b';
  if r.id is null then
    raise exception '038: oap@1.8.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> 'd9465c4a048f55a10e127a87efde3264261b385c44889eb28a4c7676359617bc' then
    raise exception '038: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> 'c5c1a19dda28462b5597f2820bf00feb3d2e06e3f8db15459bb144e86f071c53' then
    raise exception '038: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;
