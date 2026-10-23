-- fitness_028_oap_v1_4_0_engine_1_3_0 (M2b.6, data): challenge definition oap@1.4.0 and challenge.enrollment 1.1.0 of
-- ENGINE_VERSION 1.3.0 — DATA_MODEL.md 9.1.9, 9.4.1, 13.2, D40
--
-- D40 (cutover): the Pallof press in sessions A, B and C (B's gym plank removed; the plank rule counts the two home
-- days), P28 (Pallof press one stack step heavier when every set had 4 or more reps left) and P29 (a lighter week after
-- the elbow protocol: an extra deload week with the assistance pins 10 lb higher, the belt loads 5 kg lower and no
-- eccentric work). The cable station gets the rounding profile `cable`. Like 023, 026 and 027 it patches the published
-- previous row (54 jsonb operations generated from the two files and replayed locally to the new file) and proves
-- byte-equality with docs/challenges/oap.challenge.v1.json through the guard trigger's server_checksum. challenge.enrollment
-- 1.1.0 folds the setup's decisions with the creation (CHALLENGE_ENGINE 4.3); its params slice is unchanged, so it keeps
-- its 1.0.0 row's hash. Manual rollback (only with explicit user approval): delete the definition row before any
-- enrollment references it, and the module row.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.3.0' and user_id is null;
  if base.id is null then
    raise exception '028: oap@1.3.0 is missing';
  end if;
  if base.server_checksum <> '62d66252edb081abc2f0e38c0c1895e33b9479bc64048403c71bbc37fe603165' then
    raise exception '028: oap@1.3.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.4.0"$j$::jsonb, true);
  d := jsonb_insert(d, array['proposals', '27']::text[], $j${"id":"P28","title":{"en":"Pallof press: one step heavier when clearly easy"},"rationale":{"en":"\"Clearly easier\" read as 4 or more reps left on every set; then one stack step more next time."},"ifDeclined":{"en":"The Pallof press keeps its load until you change it."},"recommended":"approve","supersedes":[],"affects":{"rules":["prog.pallof"],"params":["pallofStepKg","pallofMinRirUp"]}}$j$::jsonb);
  d := jsonb_insert(d, array['proposals', '28']::text[], $j${"id":"P29","title":{"en":"A lighter week after the elbow protocol"},"rationale":{"en":"Before the restored loads: an extra deload week with 10 lb more assistance, 5 kg less on the belt and no negatives."},"ifDeclined":{"en":"The restored loads apply straight after the protocol."},"recommended":"approve","supersedes":[],"affects":{"rules":["safety.injury.exit","safety.injury.exit.painfree"],"params":["reentryPinOffsetLb","reentryLoadDropKg"]}}$j$::jsonb);
  d := jsonb_set(d, array['params', 'approved_P28']::text[], $j${"type":"bool","default":false,"tag":"P","proposal":"P28","userEditable":true}$j$::jsonb, true);
  d := jsonb_set(d, array['params', 'approved_P29']::text[], $j${"type":"bool","default":false,"tag":"P","proposal":"P29","userEditable":true}$j$::jsonb, true);
  d := jsonb_set(d, array['params', 'pallofStartKg']::text[], $j${"type":"mass","default":11.34,"tag":"A","source":"Todoist A, B, C (2026-10-19: 3 x 10 on 11.3 kg = 25 lb on the stack, D40)","grade":"engineering-fact"}$j$::jsonb, true);
  d := jsonb_set(d, array['params', 'pallofStepKg']::text[], $j${"type":"mass","default":2.27,"tag":"P","proposal":"P28","tunable":true,"tunedBy":"the cable station's confirmed stack step","source":"Todoist ('one step heavier on the pin'); one 5 lb step until the stack is confirmed (D40)","grade":"engineering-fact"}$j$::jsonb, true);
  d := jsonb_set(d, array['params', 'pallofMinRirUp']::text[], $j${"type":"int","default":4,"min":3,"max":5,"tag":"P","proposal":"P28","tunable":true,"tunedBy":"Pallof RIR in the session after a step","grade":"expert-opinion"}$j$::jsonb, true);
  d := jsonb_set(d, array['params', 'reentryPinOffsetLb']::text[], $j${"type":"pin","default":10,"tag":"P","proposal":"P29","source":"D40","grade":"expert-opinion"}$j$::jsonb, true);
  d := jsonb_set(d, array['params', 'reentryLoadDropKg']::text[], $j${"type":"mass","default":5.0,"tag":"P","proposal":"P29","source":"D40","grade":"expert-opinion"}$j$::jsonb, true);
  d := jsonb_set(d, array['equipment', 'required', '4', 'roundingProfile']::text[], $j$"cable"$j$::jsonb, true);
  d := jsonb_set(d, array['equipment', 'required', '4', 'note']::text[], $j$"Stack from the gym equipment record; the Pallof press steps on it (D40)."$j$::jsonb, true);
  d := jsonb_insert(d, array['exercises', '25']::text[], $j${"key":"pallof_press","catalogRef":"pallof_press"}$j$::jsonb);
  d := jsonb_insert(d, array['state', 'vars', '29']::text[], $j${"name":"pallofKg","type":"mass","init":"onboard.pallofKg ?? p.pallofStartKg","tag":"A"}$j$::jsonb);
  d := jsonb_insert(d, array['state', 'vars', '30']::text[], $j${"name":"reentry","type":"bool","init":"false","tag":"P","proposal":"P29"}$j$::jsonb);
  d := jsonb_insert(d, array['templates', 'C1', 'items', '5']::text[], $j${"key":"pallof","exercise":"pallof_press","role":"main","sets":3,"reps":10,"added":"s.pallofKg","rirTarget":2,"creditRir":2,"rest_s":60,"progression":"rule","note":{"en":"Side-on to the cable, handle against your sternum: press straight out and back, the torso does not turn. 2 reps left on every set."}}$j$::jsonb);
  d := jsonb_set(d, array['templates', 'C1', 'items', '2', 'added']::text[], $j$"s.reentry ? max(0kg, roundLoad(s.wpuC - p.reentryLoadDropKg, 'belt', 'down')) : s.wpuC"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'C1', 'items', '1', 'assistPin']::text[], $j$"s.reentry ? s.pinC + p.reentryPinOffsetLb : s.pinC"$j$::jsonb, true);
  d := jsonb_insert(d, array['templates', 'A1', 'items', '7']::text[], $j${"key":"pallof","exercise":"pallof_press","role":"main","sets":3,"reps":10,"added":"s.pallofKg","rirTarget":2,"creditRir":2,"rest_s":60,"progression":"rule","position":"after:sth","note":{"en":"Side-on to the cable, handle against your sternum: press straight out and back, the torso does not turn. 2 reps left on every set."}}$j$::jsonb);
  d := jsonb_set(d, array['templates', 'A1', 'items', '5', 'assistPin']::text[], $j$"s.reentry ? s.pinHold90 + p.reentryPinOffsetLb : s.pinHold90"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'A1', 'items', '3', 'added']::text[], $j$"s.reentry ? max(0kg, roundLoad(s.wpuA - p.reentryLoadDropKg, 'belt', 'down')) : s.wpuA"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'A1', 'items', '2', 'skipIf']::text[], $j$"s.elbowTrace || s.reentry"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'A1', 'items', '1', 'assistPin']::text[], $j$"s.reentry ? s.pinA + p.reentryPinOffsetLb : s.pinA"$j$::jsonb, true);
  d := d #- array['templates', 'B1', 'items', '5', 'hold_s']::text[];
  d := d #- array['templates', 'B1', 'items', '5', 'perSide']::text[];
  d := d #- array['templates', 'B1', 'items', '5', 'sideOrder']::text[];
  d := d #- array['templates', 'B1', 'items', '5', 'stopSetOn']::text[];
  d := jsonb_set(d, array['templates', 'B1', 'items', '5', 'key']::text[], $j$"pallof"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'B1', 'items', '5', 'exercise']::text[], $j$"pallof_press"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'B1', 'items', '5', 'reps']::text[], $j$10$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'B1', 'items', '5', 'added']::text[], $j$"s.pallofKg"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'B1', 'items', '5', 'rirTarget']::text[], $j$2$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'B1', 'items', '5', 'note']::text[], $j${"en":"Side-on to the cable, handle against your sternum: press straight out and back, the torso does not turn. 2 reps left on every set."}$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'B1', 'items', '2', 'skipIf']::text[], $j$"s.elbowTrace || s.reentry"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'B1', 'items', '1', 'added']::text[], $j$"s.reentry ? max(0kg, roundLoad(s.wpuB - p.reentryLoadDropKg, 'belt', 'down')) : s.wpuB"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'A2', 'replace', 'cal', 'skipIf']::text[], $j$"s.elbowTrace || s.reentry"$j$::jsonb, true);
  d := d #- array['rules', '42']::text[];
  d := jsonb_insert(d, array['rules', '42']::text[], $j${"id":"prog.plank","tag":"A","uses":["P22"],"stage":"progression","prio":50,"on":["week.closed"],"if":"week.ex.plank.days >= 2 && count(week.ex.plank.hold_s) >= 12 && all(week.ex.plank.hold_s >= s.plankS) && !any(week.ex.plank.hip_rotation)","then":[{"if":"s.plankS < p.plankTargetS","do":[{"set":"s.plankS","to":"p.plankTargetS"}],"else":[{"if":"s.plankLoadKg == 0kg","do":[{"if":"p.approved_P22","do":[{"set":"s.plankLoadKg","to":"p.plankPlateKg"},{"notify":"info","msg":"plank_plate"}],"else":[{"notify":"prompt","msg":"plank_plate_choose","once":"perBlock"}]}]}]}],"explain":{"en":"All six plank sets clean on both home days: 20 s, then 30 s, then a plate on your upper back."},"source":"Todoist Core / B; two days since the Pallof press replaced B's plank (D40)"}$j$::jsonb);
  d := jsonb_insert(d, array['rules', '43']::text[], $j${"id":"prog.pallof","tag":"P","proposal":"P28","stage":"progression","prio":50,"on":["session.completed"],"if":"p.approved_P28 && mode == 'normal' && valid('pallof') && count(ex.pallof.reps) >= ex.pallof.plannedRows && all(ex.pallof.reps >= ex.pallof.targetReps) && min(ex.pallof.rir) >= p.pallofMinRirUp","then":[{"adjust":"s.pallofKg","by":"p.pallofStepKg","round":{"profile":"cable","mode":"up"}}],"explain":{"en":"Every Pallof set with 4 or more reps left: one stack step heavier next time."},"source":"proposal P28 (Todoist: 'clearly easier: one step heavier')"}$j$::jsonb);
  d := jsonb_insert(d, array['rules', '25', 'then', '0', 'do', '2']::text[], $j${"set":"s.reentry","to":"false"}$j$::jsonb);
  d := d #- array['rules', '4', 'then', '4']::text[];
  d := d #- array['rules', '4', 'then', '1', 'setMode']::text[];
  d := jsonb_set(d, array['rules', '4', 'then', '1', 'if']::text[], $j$"p.approved_P29"$j$::jsonb, true);
  d := jsonb_set(d, array['rules', '4', 'then', '1', 'do']::text[], $j$[{"setMode":"extra_deload"},{"set":"s.reentry","to":"true"},{"set":"s.extraDeloadPending","to":"true"},{"notify":"info","msg":"reentry_week"}]$j$::jsonb, true);
  d := jsonb_set(d, array['rules', '4', 'then', '1', 'else']::text[], $j$[{"setMode":"normal"},{"notify":"info","msg":"back_to_two_blocks_ago"}]$j$::jsonb, true);
  d := jsonb_set(d, array['rules', '4', 'explain', 'en']::text[], $j$"14 days, the last 3 without pain: loads go back to two blocks ago; a new block starts Monday (with P29 after a lighter week)."$j$::jsonb, true);
  d := d #- array['rules', '3', 'then', '4']::text[];
  d := d #- array['rules', '3', 'then', '1', 'setMode']::text[];
  d := jsonb_set(d, array['rules', '3', 'then', '1', 'if']::text[], $j$"p.approved_P29"$j$::jsonb, true);
  d := jsonb_set(d, array['rules', '3', 'then', '1', 'do']::text[], $j$[{"setMode":"extra_deload"},{"set":"s.reentry","to":"true"},{"set":"s.extraDeloadPending","to":"true"},{"notify":"info","msg":"reentry_week"}]$j$::jsonb, true);
  d := jsonb_set(d, array['rules', '3', 'then', '1', 'else']::text[], $j$[{"setMode":"normal"},{"notify":"info","msg":"back_to_two_blocks_ago"}]$j$::jsonb, true);
  d := jsonb_set(d, array['rules', '3', 'explain', 'en']::text[], $j$"Two weeks over: loads go back to two blocks ago; a new block starts Monday (with P29 after a lighter week)."$j$::jsonb, true);
  d := jsonb_set(d, array['messages', 'reentry_week']::text[], $j${"en":"Elbow protocol over. First a lighter week: half the sets, 10 lb more assistance, 5 kg less on the belt, no negatives. Then the loads of two blocks ago."}$j$::jsonb, true);
  d := jsonb_set(d, array['migrations', '1.3.0']::text[], $j${"varMap":{},"defaults":{},"notes":"D40: the Pallof press in A, B and C (B's gym plank removed, the plank rule counts the two home days), P28 and P29 (re-entry week after the elbow protocol). New vars pallofKg and reentry start from their init."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('ab70117e-60ed-575c-89b3-1d834f262747'::uuid, null, 'oap', '1.4.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, '8fca944277e084d1d3e35ed282bfb13366ac68b95d21c8993345e04198abbd4d', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = 'ab70117e-60ed-575c-89b3-1d834f262747';
  if r.id is null then
    raise exception '028: oap@1.4.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '8fca944277e084d1d3e35ed282bfb13366ac68b95d21c8993345e04198abbd4d' then
    raise exception '028: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> '78a2019937405915e7c40d67b4657e8f9ee47e7422276cb7629263eab3ff74c0' then
    raise exception '028: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('challenge.enrollment', '1.1.0', 'challenge', '1.3.0', 'active', '1.0.1', '33c7a5ded80514bce442ee4c87c86d66d5f811b7b763fb50ec534f850a14de68',
   'docs/CHALLENGE_ENGINE.md section 4.3', 'D40: the setup''s decisions fold with the creation, before the creation day''s ticks.')
on conflict (module_id, version) do nothing;

do $$
begin
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.3.0') <> 1 then
    raise exception '028: expected 1 module version for engine 1.3.0';
  end if;
end;
$$;
