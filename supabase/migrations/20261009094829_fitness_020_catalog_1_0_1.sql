-- fitness_020_catalog_1_0_1 (M1a, data): catalog 1.0.1 - DATA_MODEL.md 9.1.7, 9.1.11, 9.2.1; DECISIONS changelog 2026-10-09
--
-- Delta release: four stack stations for machine exercises that had no loadable station (the set card found no
-- stack values), and the four exercises linked to them. exercises.catalog_version is "the version in which the
-- row last changed", so only these four rows move to 1.0.1. jcs_sha256 = Jcs.sha256Hex of
-- algo/src/main/resources/fitness/catalog/catalog-1.0.1.json (printed by `gradlew :sim:run --args="seedgen-catalog 1.0.1"`).
-- Idempotent.

insert into fitness.equipment_types (key, category, default_scheme_type, default_load_scheme, portable, label_en, label_nl, active, sort)
values
  ('preacher_curl_machine', 'station', 'stack', '{"add_on_steps":[],"max":null,"ratio":1,"start":null,"step":null,"type":"stack","unit":"kg"}'::jsonb, false, 'Preacher curl machine', 'Preacher curl machine', true, 172),
  ('triceps_extension_machine', 'station', 'stack', '{"add_on_steps":[],"max":null,"ratio":1,"start":null,"step":null,"type":"stack","unit":"kg"}'::jsonb, false, 'Triceps extension machine', 'Tricepsmachine', true, 174),
  ('dip_machine', 'station', 'stack', '{"add_on_steps":[],"max":null,"ratio":1,"start":null,"step":null,"type":"stack","unit":"kg"}'::jsonb, false, 'Dip machine', 'Dipmachine', true, 176),
  ('ab_crunch_machine', 'station', 'stack', '{"add_on_steps":[],"max":null,"ratio":1,"start":null,"step":null,"type":"stack","unit":"kg"}'::jsonb, false, 'Ab crunch machine', 'Buikspiermachine', true, 265)
on conflict (key) do nothing;

insert into fitness.catalog_releases (version, exercise_count, stimulus_count, jcs_sha256, notes)
values ('1.0.1', 158, 535, '5d2ee60901da8cf1dcf0b72f11dfee8ddd5c0bc938223ce4fdfbc87228f53548',
        'Delta on 1.0.0: stack stations for the ab crunch, preacher curl, triceps extension and dip machines (user decision 2026-10-09).')
on conflict (version) do nothing;

update fitness.exercises e
   set requires_station = v.stations, catalog_version = '1.0.1',
       client_updated_at = now(), device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, app_version = 'migration'
  from (values
          ('machine_crunch', array['ab_crunch_machine']),
          ('preacher_curl_machine', array['preacher_curl_machine']),
          ('triceps_extension_machine', array['triceps_extension_machine']),
          ('dip_machine', array['dip_machine'])
       ) as v(slug, stations)
 where e.user_id is null and e.slug = v.slug and e.requires_station is distinct from v.stations;

-- Assertions: the four stations exist, the four exercises point at them, nothing else moved to 1.0.1.
do $$
begin
  if (select count(*) from fitness.equipment_types
       where key in ('preacher_curl_machine', 'triceps_extension_machine', 'dip_machine', 'ab_crunch_machine')
         and category = 'station' and default_scheme_type = 'stack') <> 4 then
    raise exception 'catalog 1.0.1: expected 4 new stack stations';
  end if;
  if (select count(*) from fitness.exercises where user_id is null and catalog_version = '1.0.1') <> 4 then
    raise exception 'catalog 1.0.1: expected exactly 4 global exercises at 1.0.1';
  end if;
  if exists (select 1 from fitness.exercises e
              where e.user_id is null and e.catalog_version = '1.0.1'
                and not exists (select 1 from fitness.equipment_types t
                                 where t.key = any (e.requires_station) and t.default_scheme_type = 'stack')) then
    raise exception 'catalog 1.0.1: an updated exercise has no stack station';
  end if;
  if (select count(*) from fitness.exercises where user_id is null) <> 158 then
    raise exception 'catalog 1.0.1: expected 158 global exercises';
  end if;
end;
$$;
