-- fitness_023_oap_definition_v1_1_0 (M2b.4, data): challenge definition oap@1.1.0 — DATA_MODEL.md 9.4.1, 13.2, D36
--
-- oap@1.1.0 is oap@1.0.1 (migration 022) plus the structured test protocols of P21 (D36): five params, P21's
-- affects.params, `stepsIfApproved` on the T1 items `str` and `assist`, and the migration notes. Instead of staging the
-- whole 100 KB text again, this migration patches the published 1.0.1 row's jsonb; the guard trigger's server_checksum
-- (definition::text) must then equal the checksum of docs/challenges/oap.challenge.v1.json rendered as jsonb text
-- (`sim seedgen-challenge`), which proves the stored definition is byte-identical to the file.
-- Manual rollback (only with explicit user approval): delete the row before any enrollment references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.0.1' and user_id is null;
  if base.id is null then
    raise exception '023: oap@1.0.1 is missing';
  end if;
  d := base.definition;
  if d->'templates'->'T1'->'items'->2->>'key' <> 'str' or d->'templates'->'T1'->'items'->6->>'key' <> 'assist'
     or d->'proposals'->20->>'id' <> 'P21' then
    raise exception '023: unexpected T1 items or proposal order in oap@1.0.1';
  end if;
  d := jsonb_set(d, '{version}', '"1.1.0"');
  d := jsonb_set(d, '{proposals,20,affects,params}', $j$["testRampFractions","testAttemptStepKg","test3rmMaxAttempts","testLadderOffsetLb","testLadderMaxAttempts"]$j$::jsonb);
  d := jsonb_set(d, '{params}', (d->'params') || $j${"testRampFractions":{"type":"list","valueType":"number","default":[0.5,0.7,0.85],"tag":"P","proposal":"P21","source":"proposal P21 (3RM warm-up ramp)","grade":"expert-opinion"},"testAttemptStepKg":{"type":"mass","default":2.5,"tag":"P","proposal":"P21","source":"proposal P21","grade":"engineering-fact","note":"Smallest belt step."},"test3rmMaxAttempts":{"type":"int","default":3,"min":1,"max":5,"tag":"P","proposal":"P21","source":"proposal P21","grade":"expert-opinion"},"testLadderOffsetLb":{"type":"pin","default":10,"tag":"P","proposal":"P21","source":"proposal P21 (least-help ladder starts at the A pin + 10 lb)","grade":"expert-opinion"},"testLadderMaxAttempts":{"type":"int","default":5,"min":1,"max":8,"tag":"P","proposal":"P21","source":"proposal P21","grade":"expert-opinion"}}$j$::jsonb);
  d := jsonb_set(d, '{templates,T1,items,2,stepsIfApproved}', $j${"rampOf":"loadForReps(bw + s.e1rmAdded, 3, userK('weighted_pullup'))","rampFractions":"p.testRampFractions","rampReps":[1,3],"start":"roundLoad(loadForReps(bw + s.e1rmAdded, 3, userK('weighted_pullup')) - bw, 'belt', 'down')","step":"p.testAttemptStepKg","maxAttempts":"p.test3rmMaxAttempts"}$j$::jsonb);
  d := jsonb_set(d, '{templates,T1,items,6,stepsIfApproved}', $j${"start":"roundPin(s.pinA + p.testLadderOffsetLb, 'up')","step":"0lb - p.pinDropLb","maxAttempts":"p.testLadderMaxAttempts"}$j$::jsonb);
  d := jsonb_set(d, '{migrations}', $j${"1.0.0":{"varMap":{},"defaults":{},"notes":"Text only (D35) and the structured test protocols of P21 (D36). No state var changes."},"1.0.1":{"varMap":{},"defaults":{},"notes":"Structured test protocols of P21 (D36): the test-day wizard computes the 3RM ramp, the first attempt and the least-help ladder. No state var changes."}}$j$::jsonb);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('bb9dd024-e5d8-5af8-8e1d-ed75e6dd357b'::uuid, null, 'oap', '1.1.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, '2fdad12cb620a78e7a1628ee9cc02346903f63010c6c2e89f964a358af80e84e', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = 'bb9dd024-e5d8-5af8-8e1d-ed75e6dd357b';
  if r.id is null then
    raise exception '023: oap@1.1.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '2fdad12cb620a78e7a1628ee9cc02346903f63010c6c2e89f964a358af80e84e' then
    raise exception '023: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> 'a5ae55f9da0671c1d800d65662111cdc9b8e1a5900f2f24e89ecdf508ad7d56f' then
    raise exception '023: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;
