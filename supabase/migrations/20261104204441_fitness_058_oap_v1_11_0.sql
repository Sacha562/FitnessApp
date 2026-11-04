-- fitness_058_oap_v1_11_0 (M4, data): challenge definition oap@1.11.0 — DATA_MODEL.md 9.1.9, 9.4.1, D90
--
-- D90: the three prehab items had no `progression`, so they defaulted to `rule` with no rule for them: the load was
-- copied from last time and never went up, and no target RIR was planned (face pulls stayed at 20 lb x 15 with 5+ in
-- reserve). They are now delegated to RX-1 like the B session's row and dip: reverse wrist curls 12-20 reps at RIR 3,
-- pronation-supination 10-15 per side at RIR 3, face pulls 12-20 at RIR 2; creditRir follows the target and the
-- unused `load: light` goes. Like 044 it patches the published previous row (5 jsonb operations, replayed locally to
-- docs/challenges/oap.challenge.v1.json) and proves byte-equality through the guard trigger's server_checksum. No
-- engine change. Manual rollback (only with explicit user approval): delete the definition row before any enrollment
-- references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.10.0' and user_id is null;
  if base.id is null then
    raise exception '058: oap@1.10.0 is missing';
  end if;
  if base.server_checksum <> '32b2a5535de1d53f35b19f8f91d94f50c05aadc043d6d69afda88dd144cc2369' then
    raise exception '058: oap@1.10.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  if base.definition #>> '{blocks,prehab5,0,key}' <> 'pre_rwc' or base.definition #>> '{blocks,prehab5,1,key}' <> 'pre_ps'
     or base.definition #>> '{blocks,prehab5,2,key}' <> 'pre_fp' then
    raise exception '058: blocks.prehab5 is not pre_rwc, pre_ps, pre_fp';
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.11.0"$j$::jsonb, true);
  d := jsonb_set(d, array['blocks', 'prehab5', '0']::text[], $j${"key": "pre_rwc", "exercise": "reverse_wrist_curl_db", "role": "prehab", "sets": 2, "repsRange": [12, 20], "rirTarget": 3, "creditRir": 3, "progression": "delegate:rx", "note": {"en": "Stop with about 3 reps left. The weight goes up once you reach 20."}}$j$::jsonb, false);
  d := jsonb_set(d, array['blocks', 'prehab5', '1']::text[], $j${"key": "pre_ps", "exercise": "db_pronation_supination", "role": "prehab", "sets": 2, "repsRange": [10, 15], "perSide": true, "rirTarget": 3, "creditRir": 3, "progression": "delegate:rx", "note": {"en": "Stop with about 3 reps left. The weight goes up once you reach 15."}}$j$::jsonb, false);
  d := jsonb_set(d, array['blocks', 'prehab5', '2']::text[], $j${"key": "pre_fp", "exercise": "face_pull_cable", "role": "prehab", "sets": 2, "repsRange": [12, 20], "rirTarget": 2, "creditRir": 2, "progression": "delegate:rx", "note": {"en": "Stop with about 2 reps left. The weight goes up once you reach 20."}}$j$::jsonb, false);
  d := jsonb_set(d, array['migrations', '1.10.0']::text[], $j${"varMap": {}, "defaults": {}, "notes": "D90: the three prehab items (reverse wrist curls, pronation-supination, face pulls) are delegated to RX-1 with rep ranges and a target RIR (3, 3, 2), so their load progresses from the exercise's own history; creditRir follows the target. No state var changes."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('fe42452e-b451-5d98-a0fe-d4cd64c4541c'::uuid, null, 'oap', '1.11.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, '1cd15ed52eb7537dd025885115f608165eab3189062cec7f721f5b7ecec91199', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = 'fe42452e-b451-5d98-a0fe-d4cd64c4541c';
  if r.id is null then
    raise exception '058: oap@1.11.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '1cd15ed52eb7537dd025885115f608165eab3189062cec7f721f5b7ecec91199' then
    raise exception '058: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> '333a732cade968c8cc32bc072f9bf284ab36346236fe89454596baab19101f06' then
    raise exception '058: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;
