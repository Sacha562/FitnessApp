-- fitness_026_oap_definition_v1_2_0 (M2b, data): challenge definition oap@1.2.0 — DATA_MODEL.md 9.4.1, 13.2, D37
--
-- oap@1.2.0 is oap@1.1.0 (migration 023) plus D37: the gate e1RM is initialised from the entered 3RM, anchorFraction is no
-- longer static, the param firstAnchorRir, three engineering rules that let the first valid test fill empty loads,
-- negatives and pins, and guards on data.test.loads, data.test.loads.curve and data.test.neg.keep. Like 023 it patches
-- the published previous row; the guard trigger's server_checksum (definition::text) must equal the checksum of
-- docs/challenges/oap.challenge.v1.json rendered as jsonb text (`sim seedgen-challenge`), which proves the stored
-- definition is byte-identical to the file.
-- Manual rollback (only with explicit user approval): delete the row before any enrollment references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.1.0' and user_id is null;
  if base.id is null then
    raise exception '026: oap@1.1.0 is missing';
  end if;
  d := base.definition;
  if d->'state'->'vars'->0->>'name' <> 'e1rmAdded'
     or d->'state'->'vars'->1->>'name' <> 'e1rmAddedM110'
     or d->'state'->'vars'->2->>'name' <> 'anchorFraction'
     or d->'rules'->7->>'id' <> 'data.test.loads'
     or d->'rules'->8->>'id' <> 'data.test.loads.curve'
     or d->'rules'->10->>'id' <> 'data.test.neg.keep'
     or d->'rules'->6->>'id' <> 'data.test.e1rm' then
    raise exception '026: unexpected var or rule order in oap@1.1.0';
  end if;
  d := jsonb_set(d, '{version}', $j$"1.2.0"$j$::jsonb);
  d := jsonb_set(d, '{state,vars,0,init}', $j$"onboard.e1rmAdded ?? e1rmTotal(onboard.bw + onboard.wpu3rmAdded, 3, userK('weighted_pullup')) - onboard.bw"$j$::jsonb);
  d := jsonb_set(d, '{state,vars,0,note}', $j$"Gate e1RM (D7) of the latest valid strength test; at setup from the entered 3RM on the personal curve (D37)."$j$::jsonb);
  d := jsonb_set(d, '{state,vars,1,init}', $j$"onboard.e1rmAddedM110 ?? (onboard.bw + onboard.wpu3rmAdded) * p.m3 - onboard.bw"$j$::jsonb);
  d := d #- '{state,vars,2,static}';
  d := jsonb_set(d, '{state,vars,2,note}', $j$"Frozen at enrollment/import with the D7 method so that the current anchor is reproduced (continuity); after a test-week start, frozen by the first valid test (data.test.first, D37)."$j$::jsonb);
  d := jsonb_set(d, '{params}', (d->'params') || $j${"firstAnchorRir":{"type":"int","default":2,"min":0,"max":4,"tag":"E","tunable":true,"tunedBy":"session C reps and RIR in the first block after a test-week start","source":"D37 (user: 5 reps with 2 in reserve on the personal curve)","grade":"expert-opinion"}}$j$::jsonb);
  d := jsonb_set(d, '{rules,7,if}', $j$"test.valid && !p.approved_P10 && defined(s.wpuC) && defined(s.kgPerRep)"$j$::jsonb);
  d := jsonb_set(d, '{rules,8,if}', $j$"test.valid && p.approved_P10 && defined(t.wpu_3rm_added) && defined(s.wpuC) && defined(s.anchorFraction)"$j$::jsonb);
  d := jsonb_set(d, '{rules,10,if}', $j$"test.valid && p.approved_P09 && defined(t.wpu_3rm_added) && defined(s.negB)"$j$::jsonb);
  d := jsonb_insert(d, '{rules,6}', $j${"id":"data.test.first","tag":"E","stage":"data","prio":90,"on":["test.completed"],"if":"test.valid && defined(t.wpu_3rm_added) && !defined(s.wpuC)","let":[{"name":"T1","expr":"t.bw + t.wpu_e1rm_added"},{"name":"k","expr":"userK('weighted_pullup')"},{"name":"c","expr":"roundLoad(max(0kg, loadForReps(T1, p.anchorReps + p.firstAnchorRir, k) - bw), 'belt', 'down')"},{"name":"slope","expr":"p.approved_P10 || !defined(t.bw_max_reps) || t.bw_max_reps <= 3 ? kgPerRepAt(bw + c, p.anchorReps, k) : t.wpu_3rm_added / (t.bw_max_reps - 3)"}],"then":[{"set":"s.wpuC","to":"c"},{"set":"s.anchorFraction","to":"(bw + c) / T1"},{"set":"s.kgPerRep","to":"slope"},{"set":"s.wpuA","to":"roundLoad(c + 1 * slope, 'belt', 'nearest')"},{"set":"s.wpuB","to":"roundLoad(c + 3 * slope, 'belt', 'nearest')"}],"explain":{"en":"First test: session C starts at {c} kg (5 reps with 2 in reserve on your rep curve); A and B follow."},"source":"D37"}$j$::jsonb);
  d := jsonb_insert(d, '{rules,7}', $j${"id":"data.test.first.neg","tag":"E","stage":"data","prio":90,"on":["test.completed"],"if":"test.valid && defined(t.wpu_3rm_added) && !defined(s.negB)","let":[{"name":"n","expr":"roundLoad(p.negFraction * (t.bw + t.wpu_e1rm_added) - bw, 'belt', 'down')"}],"then":[{"set":"s.negB","to":"n"},{"set":"s.negReset","to":"roundLoad(n - p.negResetStep, 'belt', 'down')"}],"explain":{"en":"First test: negatives start at 105 % of your max, rounded down to a weight you can load."},"source":"D37 / artifact section 05"}$j$::jsonb);
  d := jsonb_insert(d, '{rules,8}', $j${"id":"data.test.first.pins","tag":"E","stage":"data","prio":90,"on":["test.completed"],"if":"test.valid && !p.approved_P11 && defined(t.oa_least_assist_pin) && !defined(s.pinA)","let":[{"name":"weak","expr":"max(t.oa_least_assist_pin)"}],"then":[{"set":"s.pinA","to":"roundPin(weak + p.pinOffsetA, 'up')"},{"set":"s.pinC","to":"roundPin(weak + p.pinOffsetA + p.pinOffsetC, 'up')"},{"set":"s.pinHold90","to":"roundPin(max(0lb, weak + p.pinOffsetA + p.pinOffsetHold90), 'up')"}],"explain":{"en":"First test: machine pins start from your weaker arm's {weak} lb."},"source":"D37 / proposal P11's formula"}$j$::jsonb);
  d := jsonb_set(d, '{migrations}', $j${"1.0.0":{"varMap":{},"defaults":{},"notes":"Text only (D35), the structured test protocols of P21 (D36) and the first-test rules (D37). No state var changes."},"1.0.1":{"varMap":{},"defaults":{},"notes":"Structured test protocols of P21 (D36) and the first-test rules (D37). No state var changes."},"1.1.0":{"varMap":{},"defaults":{},"notes":"D37: the first valid test fills empty loads, negatives and pins; the gate e1RM is initialised from the entered 3RM. Nothing changes once every value is set."}}$j$::jsonb);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('30b24220-42f4-59ed-a3b1-9301e7bd7171'::uuid, null, 'oap', '1.2.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, '479e39ff6d0cbedcb76483476dbe1a2b875fe3e3f42bc4a6450f6afbc429efe0', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = '30b24220-42f4-59ed-a3b1-9301e7bd7171';
  if r.id is null then
    raise exception '026: oap@1.2.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '479e39ff6d0cbedcb76483476dbe1a2b875fe3e3f42bc4a6450f6afbc429efe0' then
    raise exception '026: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> '06d6d75f8f722747d225ed74dfee54b3fddd1c1f233412db5347c8b7793971f6' then
    raise exception '026: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;
