-- fitness_027_oap_v1_3_0_engine_1_2_0 (M2b, data): challenge definition oap@1.3.0 and the module versions of ENGINE_VERSION 1.2.0 — DATA_MODEL.md 9.1.9, 9.4.1, 13.2, D38
--
-- D38: the test may fall on any day (the hard `testWeekday` constraint is removed; the rest day before it stays) and a
-- definition can put a slot first in its week while a condition holds (`schedule.order.first`; OAP: the test while there
-- are no loads). Like 023 and 026 it patches the published previous row and proves byte-equality with the file through
-- the guard trigger's server_checksum. It also registers challenge.cds 1.2.0 and challenge.plan 1.1.0 (engine 1.2.0;
-- challenge.cds owns no params (the empty slice's hash); challenge.plan's slice is the same in params 1.0.0 and 1.0.1, so
-- it keeps its 1.0.0 row's hash). Manual rollback (only with explicit user approval):
-- delete the definition row before any enrollment references it, and the two module rows.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.2.0' and user_id is null;
  if base.id is null then
    raise exception '027: oap@1.2.0 is missing';
  end if;
  d := base.definition;
  if d->'schedule'->'hard'->3->>'id' <> 'testWeekday' then
    raise exception '027: unexpected hard constraint order in oap@1.2.0';
  end if;
  d := jsonb_set(d, '{version}', $j$"1.3.0"$j$::jsonb);
  d := jsonb_set(d, '{schedule,order,first}', $j$[{"slot":"TEST","if":"!defined(s.wpuC)","tag":"D","source":"D38: without loads the test comes first in its week"}]$j$::jsonb);
  d := d #- '{schedule,hard,3}';
  d := jsonb_set(d, '{migrations}', $j${"1.0.0":{"varMap":{},"defaults":{},"notes":"Text only (D35), the structured test protocols of P21 (D36) and the first-test rules (D37). No state var changes."},"1.0.1":{"varMap":{},"defaults":{},"notes":"Structured test protocols of P21 (D36) and the first-test rules (D37). No state var changes."},"1.1.0":{"varMap":{},"defaults":{},"notes":"D37: the first valid test fills empty loads, negatives and pins; the gate e1RM is initialised from the entered 3RM. Nothing changes once every value is set."},"1.2.0":{"varMap":{},"defaults":{},"notes":"D38: the test may fall on any day (a rest day before it stays required) and goes first in its week while there are no loads."}}$j$::jsonb);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('aaf19205-bd34-5f21-83ef-1e87a168b3a5'::uuid, null, 'oap', '1.3.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, '91c54120f2eed9383244f4addbced80280e27d7e0eb72c6a9ddb7a00ebfe1521', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = 'aaf19205-bd34-5f21-83ef-1e87a168b3a5';
  if r.id is null then
    raise exception '027: oap@1.3.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '91c54120f2eed9383244f4addbced80280e27d7e0eb72c6a9ddb7a00ebfe1521' then
    raise exception '027: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> '62d66252edb081abc2f0e38c0c1895e33b9479bc64048403c71bbc37fe603165' then
    raise exception '027: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('challenge.cds', '1.2.0', 'challenge', '1.2.0', 'active', '1.0.1', '44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a',
   'docs/CHALLENGE_ENGINE.md section 2.4', 'D38: schedule.order.first.'),
  ('challenge.plan', '1.1.0', 'challenge', '1.2.0', 'active', '1.0.1', 'dc11022c09596fb1766cb31a3d62e6094f7766eb90f2d08a65b4c54bb9669ee7',
   'docs/CHALLENGE_ENGINE.md section 7', 'D38: a slot goes first in its week while its condition holds.')
on conflict (module_id, version) do nothing;

do $$
begin
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.2.0') <> 2 then
    raise exception '027: expected 2 module versions for engine 1.2.0';
  end if;
end;
$$;
