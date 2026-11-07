-- fitness_066_warmup_grid_count (schema + data): algo_params 1.0.8, warmup@1.1.0, ui.prefill@1.1.0,
-- user_exercise_prefs.warmup_sets, event registry - DECISIONS D104, D105, D106, D107; ALGORITHMS B10; DATA_MODEL 9.3.6, 9.8.1
--
-- D106: WARMUP-1 picks warm-up loads from a coarser grid (bar and plates in 10 kg totals, held plates and the belt in
-- 5 kg steps) and honours a per-exercise warm-up count. D105: a warm-up prefills from last time's warm-up at the same
-- position (ui.prefill@1.1.0). D107: the count is stored per exercise in user_exercise_prefs.warmup_sets.
-- algo_params 1.0.8 = 1.0.7 with the new keys warmup.grid_plates_kg (10) and warmup.grid_belt_kg (5); params_hash computed
-- by ParamsHashes (`sim params-hash`, 632 keys). 1.0.7 becomes `superseded`. Registers warmup@1.1.0 (constants_hash = the
-- new `warmup` slice) and ui.prefill@1.1.0 (constants_hash unchanged), ENGINE_VERSION 1.26.0.
-- DDL: one nullable column with a check on fitness.user_exercise_prefs (RLS and grants unchanged: the table's existing
-- owner policies cover the new column). Event registry: new `warmup_load_mode` (D104 (2)); `warmup_switched` gains `auto`
-- (D104 (3)); `preference_changed` gains `field` (D107 (3)).
-- No grant, policy, function, trigger, role or extension. No user rows. Idempotent.
-- Manual rollback (only with explicit user approval): re-activate 1.0.7, delete 1.0.8, warmup@1.1.0 and ui.prefill@1.1.0
-- before any recommendation references them; the column can stay (null = automatic).

alter table fitness.user_exercise_prefs add column if not exists warmup_sets smallint;
do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'user_exercise_prefs_warmup_sets_check'
                   and conrelid = 'fitness.user_exercise_prefs'::regclass) then
    alter table fitness.user_exercise_prefs
      add constraint user_exercise_prefs_warmup_sets_check check (warmup_sets is null or warmup_sets between 0 and 5);
  end if;
end;
$$;
comment on column fitness.user_exercise_prefs.warmup_sets is
  'D107: warm-up sets WARMUP-1 may plan for the exercise (0-5); null = automatic.';

insert into fitness.algo_params (params_version, status, engine_version_range, params, params_hash, notes)
select '1.0.8', 'draft', engine_version_range,
       params
       || jsonb_build_object(
            'warmup.grid_plates_kg', '{"evidence":"expert-opinion","max":20,"min":2.5,"source":"D106 (warm-ups need no fine loads: bar and plates in multiples of this)","tunable":true,"tuned_by":"warm-up load edits","unit":"kg","value":10}'::jsonb,
            'warmup.grid_belt_kg', '{"evidence":"expert-opinion","max":10,"min":1.25,"source":"D106 (held plates and the belt in multiples of this, else bodyweight)","tunable":true,"tuned_by":"warm-up load edits","unit":"kg","value":5}'::jsonb
          ),
       'db0aa2ae616425aa4a1b31e5ac2162269a7141e2791a3b1924bafe11b936caa8',
       'D106 2026-11-07: warm-up grid (warmup.grid_plates_kg 10, warmup.grid_belt_kg 5)'
  from fitness.algo_params
 where params_version = '1.0.7'
on conflict (params_version) do nothing;

do $$
declare
  old_p jsonb;
  new_p jsonb;
  added text[] := array['warmup.grid_plates_kg', 'warmup.grid_belt_kg'];
begin
  select params into old_p from fitness.algo_params where params_version = '1.0.7';
  select params into new_p from fitness.algo_params where params_version = '1.0.8';
  if (select count(*) from jsonb_object_keys(new_p)) <> 632 then
    raise exception 'algo_params 1.0.8: expected 632 keys';
  end if;
  if (new_p - added) <> old_p then
    raise exception 'algo_params 1.0.8: keys other than the D106 keys differ from 1.0.7';
  end if;
end;
$$;

update fitness.algo_params set status = 'superseded'
 where params_version = '1.0.7' and status = 'active'
   and exists (select 1 from fitness.algo_params where params_version = '1.0.8' and status = 'draft');
update fitness.algo_params set status = 'active', released_at = now()
 where params_version = '1.0.8' and status = 'draft';

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('warmup', '1.1.0', 'prescription', '1.26.0', 'active', '1.0.8',
   '4dc12e253120e4b39dcae9236dfb927037f94870491574a01b8ec8c39ae64fb8',
   'docs/ALGORITHMS.md section B10', 'D106: warm-up loads on a 10 kg (plates) / 5 kg (held, belt) grid; a per-exercise warm-up count keeps the middle of the ramp.'),
  ('ui.prefill', '1.1.0', 'ui', '1.26.0', 'active', '1.0.8',
   'df32e83ef3a2abfb19ad238dc37423594d537c77e6ab7889f41a951d6b9507e6',
   'docs/ALGORITHMS_PLANNING.md section 19.7', 'D105: a warm-up prefills from last time''s warm-up at the same position, ahead of the engine target.')
on conflict (module_id, version) do nothing;

insert into fitness.session_event_types (event_type, category, payload_keys, introduced_in, description, active, sort)
values ('warmup_load_mode', 'logging', array['to']::text[], '1.0.0',
        'Emitted by UX 11.5 (a load-mode change on a warm-up card, for that set only, D104)', true, 1533)
on conflict (event_type) do nothing;
update fitness.session_event_types
   set payload_keys = array['on', 'auto']::text[],
       description = 'Emitted by UX 11.5 (the live card''s warm-up switch, kept for the session, D81; auto after a manual warm-up, D104)'
 where event_type = 'warmup_switched' and payload_keys = array['on']::text[];
update fitness.session_event_types
   set payload_keys = array['exercise_id', 'from', 'to', 'surface', 'field']::text[]
 where event_type = 'preference_changed' and payload_keys = array['exercise_id', 'from', 'to', 'surface']::text[];

do $$
begin
  if (select status from fitness.algo_params where params_version = '1.0.8') <> 'active' then
    raise exception '066: params 1.0.8 not active';
  end if;
  if (select params_hash from fitness.algo_params where params_version = '1.0.8')
     <> 'db0aa2ae616425aa4a1b31e5ac2162269a7141e2791a3b1924bafe11b936caa8' then
    raise exception '066: params 1.0.8 hash differs';
  end if;
  if (select count(*) from fitness.algo_params where status = 'active') <> 1 then
    raise exception '066: expected exactly one active params version';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.26.0') <> 2 then
    raise exception '066: expected 2 module versions for engine 1.26.0';
  end if;
  if not exists (select 1 from information_schema.columns
                  where table_schema = 'fitness' and table_name = 'user_exercise_prefs' and column_name = 'warmup_sets') then
    raise exception '066: user_exercise_prefs.warmup_sets missing';
  end if;
  if (select payload_keys from fitness.session_event_types where event_type = 'warmup_switched') <> array['on', 'auto']::text[]
     or (select payload_keys from fitness.session_event_types where event_type = 'preference_changed')
        <> array['exercise_id', 'from', 'to', 'surface', 'field']::text[]
     or not exists (select 1 from fitness.session_event_types where event_type = 'warmup_load_mode' and active) then
    raise exception '066: event registry rows differ';
  end if;
end;
$$;
