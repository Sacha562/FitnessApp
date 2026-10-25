-- fitness_034_oap_v1_7_0 (M2b.6, data): challenge definition oap@1.7.0 — DATA_MODEL.md 9.1.9, 9.4.1, D47
--
-- D47: prehab keeps its sets in deload weeks. The deload mode's exemptRoles becomes ["warmup", "prehab"]; test_pending
-- and extra_deload inherit it. Like 033 it patches the published previous row (3 jsonb operations, replayed locally to
-- docs/challenges/oap.challenge.v1.json) and proves byte-equality through the guard trigger's server_checksum. No engine
-- change. Manual rollback (only with explicit user approval): delete the definition row before any enrollment
-- references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.6.0' and user_id is null;
  if base.id is null then
    raise exception '034: oap@1.6.0 is missing';
  end if;
  if base.server_checksum <> 'ba19a87d8a117b29929786989ceb86a578457067ef84fe574c894ddb66b84695' then
    raise exception '034: oap@1.6.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.7.0"$j$::jsonb, true);
  d := jsonb_set(d, array['structure', 'modes', 'deload', 'exemptRoles']::text[], $j$["warmup", "prehab"]$j$::jsonb, true);
  d := jsonb_set(d, array['migrations', '1.6.0']::text[], $j${"varMap":{},"defaults":{},"notes":"D47: prehab keeps its sets in deload, test_pending and extra_deload weeks (only warm-ups were exempt from the halving). No state var changes."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('e9265ba8-e297-563e-a856-f7b987c78ce9'::uuid, null, 'oap', '1.7.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, '63b2a65045a111339dd945e30bb11e2cbb7fa437d716ee12970ac8e79968ef68', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = 'e9265ba8-e297-563e-a856-f7b987c78ce9';
  if r.id is null then
    raise exception '034: oap@1.7.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '63b2a65045a111339dd945e30bb11e2cbb7fa437d716ee12970ac8e79968ef68' then
    raise exception '034: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> '3083934c6ce6b3c565a7bf6e35a3783c134ad0b5fc8e586d07adbffaa69a6b70' then
    raise exception '034: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;
