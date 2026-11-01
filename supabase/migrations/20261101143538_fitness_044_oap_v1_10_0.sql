-- fitness_044_oap_v1_10_0 (M4, data): challenge definition oap@1.10.0 — DATA_MODEL.md 9.1.9, 9.4.1, D63
--
-- D63: in a re-entry week (P29) the session plans show the pins with +10 lb (reentryPinOffsetLb), and the logged pin
-- includes that offset. The two protective pin rules that may act in frozen modes, prog.A.pin.gated and
-- prog.C.pin.hard, took last(ex.oaa.pin) as their base, so a missed rep in a re-entry session would have saved the
-- offset into s.pinA (50 instead of 40) or stepped s.pinC from the offset pin (+15 lb instead of +5). Both now take
-- the logged pin minus the offset while s.reentry is true. Like 039 it patches the published previous row (4 jsonb
-- operations, replayed locally to docs/challenges/oap.challenge.v1.json) and proves byte-equality through the guard
-- trigger's server_checksum. No engine change. Manual rollback (only with explicit user approval): delete the
-- definition row before any enrollment references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.9.0' and user_id is null;
  if base.id is null then
    raise exception '044: oap@1.9.0 is missing';
  end if;
  if base.server_checksum <> '66d60eb6304635c764e927c9fcbaaa38c9a5db2109c57d80cba876e041eb2c23' then
    raise exception '044: oap@1.9.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  if base.definition #>> '{rules,37,id}' <> 'prog.A.pin.gated' or base.definition #>> '{rules,37,let,1,expr}' <> 'last(ex.oaa.pin)' then
    raise exception '044: rules[37] is not prog.A.pin.gated with base last(ex.oaa.pin)';
  end if;
  if base.definition #>> '{rules,41,id}' <> 'prog.C.pin.hard' or base.definition #>> '{rules,41,then,0,to}' <> 'roundPin(last(ex.oaa.pin) + p.pinDropLb, ''up'')' then
    raise exception '044: rules[41] is not prog.C.pin.hard with the expected step';
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.10.0"$j$::jsonb, true);
  d := jsonb_set(d, array['rules', '37', 'let', '1', 'expr']::text[], $j$"max(0lb, last(ex.oaa.pin) - (s.reentry ? p.reentryPinOffsetLb : 0lb))"$j$::jsonb, false);
  d := jsonb_set(d, array['rules', '41', 'then', '0', 'to']::text[], $j$"roundPin(max(0lb, last(ex.oaa.pin) - (s.reentry ? p.reentryPinOffsetLb : 0lb)) + p.pinDropLb, 'up')"$j$::jsonb, false);
  d := jsonb_set(d, array['migrations', '1.9.0']::text[], $j${"varMap":{},"defaults":{},"notes":"D63: in a re-entry week the protective pin rules (prog.A.pin.gated, prog.C.pin.hard) take the logged pin minus the re-entry offset as their base, so the offset never reaches s.pinA or s.pinC. No state var changes."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('70d6a8de-1276-51ca-a39e-e5e2e331c4c9'::uuid, null, 'oap', '1.10.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, 'fc95fe8843c063a80fddaec568972071f5f398a47cc426bcceb4521dfef2ead9', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = '70d6a8de-1276-51ca-a39e-e5e2e331c4c9';
  if r.id is null then
    raise exception '044: oap@1.10.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> 'fc95fe8843c063a80fddaec568972071f5f398a47cc426bcceb4521dfef2ead9' then
    raise exception '044: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> '32b2a5535de1d53f35b19f8f91d94f50c05aadc043d6d69afda88dd144cc2369' then
    raise exception '044: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;
