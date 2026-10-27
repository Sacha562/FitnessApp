-- fitness_039_oap_v1_9_0 (M3, data): challenge definition oap@1.9.0 — DATA_MODEL.md 9.1.9, 9.4.1, D52
--
-- D52: the torso-rotation notice says what it means for each exercise. The one in-session rule ins.rotationStop
-- (item "*", message "Torso rotated: the set (or the clock) stops here.") becomes 6 rules, one per item: finger,
-- hang, oaa, scap (left and right side apart) and sth, with four messages. Like 038 it patches the published previous
-- row (13 jsonb operations, replayed locally to docs/challenges/oap.challenge.v1.json) and proves byte-equality
-- through the guard trigger's server_checksum. No engine change. Manual rollback (only with explicit user approval):
-- delete the definition row before any enrollment references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.8.0' and user_id is null;
  if base.id is null then
    raise exception '039: oap@1.8.0 is missing';
  end if;
  if base.server_checksum <> 'c5c1a19dda28462b5597f2820bf00feb3d2e06e3f8db15459bb144e86f071c53' then
    raise exception '039: oap@1.8.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  if base.definition #>> '{inSessionRules,6,id}' <> 'ins.rotationStop' or jsonb_array_length(base.definition -> 'inSessionRules') <> 8 then
    raise exception '039: inSessionRules[6] is not ins.rotationStop';
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.9.0"$j$::jsonb, true);
  d := jsonb_set(d, array['inSessionRules', '6']::text[], $j${"id":"ins.rotationStop.finger","tag":"A","item":"finger","on":"set.logged","if":"set.torso_rotation","then":[{"notify":"info","msg":"rotation_stop_set"}],"source":"artifact section 06 / Todoist C; D52: one message per exercise"}$j$::jsonb, false);
  d := jsonb_insert(d, array['inSessionRules', '6']::text[], $j${"id":"ins.rotationStop.hang","tag":"A","item":"hang","on":"set.logged","if":"set.torso_rotation","then":[{"notify":"info","msg":"rotation_stop_hang"}],"source":"artifact section 06 / Todoist C; D52: one message per exercise"}$j$::jsonb, true);
  d := jsonb_insert(d, array['inSessionRules', '7']::text[], $j${"id":"ins.rotationStop.oaa","tag":"A","item":"oaa","on":"set.logged","if":"set.torso_rotation","then":[{"notify":"info","msg":"rotation_stop_set"}],"source":"artifact section 06 / Todoist C; D52: one message per exercise"}$j$::jsonb, true);
  d := jsonb_insert(d, array['inSessionRules', '8']::text[], $j${"id":"ins.rotationStop.scapL","tag":"A","item":"scap","on":"set.logged","if":"set.torso_rotation && set.side == 'L'","then":[{"notify":"info","msg":"rotation_stop_set"}],"source":"artifact section 06 / Todoist C; D52: the app adds the right side's capped target (ins.scap.matchLeft)"}$j$::jsonb, true);
  d := jsonb_insert(d, array['inSessionRules', '9']::text[], $j${"id":"ins.rotationStop.scapR","tag":"A","item":"scap","on":"set.logged","if":"set.torso_rotation && set.side == 'R'","then":[{"notify":"info","msg":"rotation_stop_scap_right"}],"source":"artifact section 06 / Todoist C; D52: one message per exercise"}$j$::jsonb, true);
  d := jsonb_insert(d, array['inSessionRules', '10']::text[], $j${"id":"ins.rotationStop.sth","tag":"A","item":"sth","on":"set.logged","if":"set.torso_rotation","then":[{"notify":"info","msg":"rotation_stop_sth"}],"source":"artifact section 06 / Todoist A; D52: one message per exercise (prog.A.sth needs every hold clean)"}$j$::jsonb, true);
  d := d #- array['messages', 'rotation_stop']::text[];
  d := jsonb_set(d, array['messages', 'rotation_stop_set']::text[], $j${"en":"Torso rotated: end the set here."}$j$::jsonb, true);
  d := jsonb_set(d, array['messages', 'rotation_stop_scap_right']::text[], $j${"en":"Torso rotated: end the set here. Nothing carries over to the next set."}$j$::jsonb, true);
  d := jsonb_set(d, array['messages', 'rotation_stop_hang']::text[], $j${"en":"Torso rotated: the clock stops here. Log the time up to the rotation."}$j$::jsonb, true);
  d := jsonb_set(d, array['messages', 'rotation_stop_sth']::text[], $j${"en":"Torso rotated: end the hold here. The hold time does not go up after this session."}$j$::jsonb, true);
  d := jsonb_set(d, array['migrations', '1.8.0']::text[], $j${"varMap":{},"defaults":{},"notes":"D52: the torso-rotation notice says what it means for each exercise (one in-session rule per item instead of one for every item). No state var changes."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('db0e8ce1-55df-5f6a-9142-f0f99c3e831d'::uuid, null, 'oap', '1.9.0', 'published', base.schema_version, base.engine_range,
          base.title_en, base.title_nl, d, '235ae00edd90d85cba93d1bdf676ccd6a560dc9c6d81cf063d7ee2b5c94540f3', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = 'db0e8ce1-55df-5f6a-9142-f0f99c3e831d';
  if r.id is null then
    raise exception '039: oap@1.9.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '235ae00edd90d85cba93d1bdf676ccd6a560dc9c6d81cf063d7ee2b5c94540f3' then
    raise exception '039: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> '66d60eb6304635c764e927c9fcbaaa38c9a5db2109c57d80cba876e041eb2c23' then
    raise exception '039: stored definition differs from the file (server_checksum %)', r.server_checksum;
  end if;
end;
$$;
