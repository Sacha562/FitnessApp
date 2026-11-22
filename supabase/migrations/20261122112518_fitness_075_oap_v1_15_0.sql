-- fitness_075_oap_v1_15_0 (M4, data): challenge definition oap@1.15.0 — DATA_MODEL.md 9.1.9, 9.4.1, D122
--
-- D122: session B's row may be any catalog row. Its exercise choice keeps the three pronated rows first (barbell,
-- wide seated cable, chest-supported) and adds every catalog exercise with pattern horiz_pull, any grip, in slug order;
-- the item note no longer asks for pronated only. Needs engine 1.28.0 (the `where` filter, migration 074), so the
-- engine range becomes >=1.28.0 <2.0.0 and a phone on an older app keeps 1.14.0. Like 069-071 it patches the published
-- previous row (5 jsonb operations, replayed locally to docs/challenges/oap.challenge.v1.json) and proves byte-equality
-- through the guard trigger's server_checksum. Manual rollback (only with explicit user approval): delete the
-- definition row before any enrollment references it.

do $$
declare
  base fitness.challenge_definitions;
  d jsonb;
  r fitness.challenge_definitions;
begin
  select * into base from fitness.challenge_definitions where slug = 'oap' and version = '1.14.0' and user_id is null;
  if base.id is null then
    raise exception '075: oap@1.14.0 is missing';
  end if;
  if base.server_checksum <> '955df1095614dbe76461a6862baf8f76d8de89a8a5ce8dcf35017135c4a3ed8e' then
    raise exception '075: oap@1.14.0 differs from the frozen file (server_checksum %)', base.server_checksum;
  end if;
  if base.definition #>> '{templates,B1,items,3,key}' <> 'row' or base.definition #> '{templates,B1,items,3,exercise,where}' is not null then
    raise exception '075: the B1 row item is not where 1.14.0 has it';
  end if;
  d := base.definition;
  d := jsonb_set(d, array['version']::text[], $j$"1.15.0"$j$::jsonb, true);
  d := jsonb_set(d, array['engine']::text[], $j$">=1.28.0 <2.0.0"$j$::jsonb, true);
  d := jsonb_set(d, array['templates', 'B1', 'items', '3', 'exercise']::text[], $j${"anyOf": ["barbell_row_pronated", "seated_cable_row_wide_pronated", "chest_supported_row_pronated"], "where": {"pattern": ["horiz_pull"]}, "pick": "preference"}$j$::jsonb, false);
  d := jsonb_set(d, array['templates', 'B1', 'items', '3', 'note']::text[], $j${"en": "Heavy. A pronated row is the best counterweight to all the supinated work; any row counts."}$j$::jsonb, false);
  d := jsonb_set(d, array['migrations', '1.14.0']::text[], $j${"varMap": {}, "defaults": {}, "notes": "D122: the B row's exercise choice adds every catalog row (where pattern horiz_pull, any grip) after its three pronated options; the item note no longer asks for pronated only. Needs engine 1.28.0. No state var changes."}$j$::jsonb, true);

  insert into fitness.challenge_definitions
    (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
     jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
  values ('7fe68ecb-2ccf-5c31-9694-271899f5f051'::uuid, null, 'oap', '1.15.0', 'published', base.schema_version, '>=1.28.0 <2.0.0',
          base.title_en, base.title_nl, d, '893fa425ef10e7d408776870b71aaed9c3d75e28dc3b9917f53e4c7732e28726', repeat('0', 64), now(),
          'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'migration')
  on conflict (id) do nothing;

  select * into r from fitness.challenge_definitions where id = '7fe68ecb-2ccf-5c31-9694-271899f5f051';
  if r.id is null then
    raise exception '075: oap@1.15.0 was not inserted';
  end if;
  if r.status <> 'published' or r.jcs_sha256 <> '893fa425ef10e7d408776870b71aaed9c3d75e28dc3b9917f53e4c7732e28726' then
    raise exception '075: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
  end if;
  if r.server_checksum <> '18d74f5cb8ee72444c5096e7f64d34b4fe12795ef05c85e7a6358cebcee34e0f' then
    raise exception '075: server_checksum % differs from the local file', r.server_checksum;
  end if;
end;
$$;
