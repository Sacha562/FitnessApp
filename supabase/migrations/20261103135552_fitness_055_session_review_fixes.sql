-- fitness_055_session_review_fixes (M4.5, data): the 2026-11-03 session review - DECISIONS D80; ALGORITHMS B15.2,
-- B15.3; DATA_MODEL 9.6.1, 9.6.5
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- (1) Registers `warnings@1.2.0` (ENGINE_VERSION 1.20.0): WE-P12 suggests the dip belt on a bodyweight lift past the
--     DOUBLE ladder's top, and a dip is high-risk only when loaded. constants_hash = the `warnings` slice, unchanged
--     (WE-P12 reads `rx.double_max_extension`; no params change).
-- (2) Built sessions (`template_source = family_combo`) stored `families = '{}'`; their `session_started` event has the
--     families in upper case. They get the lower-case codes (the column CHECK).
-- (3) The pre-session elbow check was written before its session existed (`session_id` null), so the review views never
--     saw it. Each gets the session started within 10 minutes after it.
-- (4) The dip machine never existed at the gym (user, 2026-11-03): `available = false`, so the planner stops planning it.
-- Changed user rows take the migration device and a fresh client_updated_at, so the phone pulls them (046's rule).
-- Manual rollback (only with explicit user approval): delete warnings@1.2.0 before any recommendation references it;
-- the data fixes need no rollback (they restore facts the app already logged).

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'warnings', '1.2.0', layer, '1.20.0', 'active', '1.0.4', constants_hash,
       'docs/ALGORITHMS.md section B15', 'D80: WE-P12 suggests the dip belt past the ladder''s top; a dip is high-risk only when loaded.'
  from fitness.algorithm_versions
 where module_id = 'warnings' and version = '1.1.0'
on conflict (module_id, version) do nothing;

update fitness.workout_sessions ws
   set families = (select array_agg(lower(f.v) order by f.n) from jsonb_array_elements_text(e.payload->'families') with ordinality as f(v, n)),
       device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, app_version = 'migration', client_updated_at = now()
  from fitness.session_events e
 where e.session_id = ws.id and e.user_id = ws.user_id and e.event_type = 'session_started'
   and ws.template_source = 'family_combo' and ws.families = '{}'
   and jsonb_typeof(e.payload->'families') = 'array' and jsonb_array_length(e.payload->'families') > 0;

update fitness.wellness_logs w
   set session_id = (select s.id from fitness.workout_sessions s
                      where s.user_id = w.user_id and s.deleted_at is null
                        and s.started_at between w.observed_at and w.observed_at + interval '10 minutes'
                      order by s.started_at, s.id limit 1),
       device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, app_version = 'migration', client_updated_at = now()
 where w.kind = 'elbow_check' and w.phase = 'pre' and w.session_id is null and w.deleted_at is null
   and exists (select 1 from fitness.workout_sessions s
                where s.user_id = w.user_id and s.deleted_at is null
                  and s.started_at between w.observed_at and w.observed_at + interval '10 minutes');

update fitness.gym_equipment
   set available = false,
       device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, app_version = 'migration', client_updated_at = now()
 where id = '01a12015-d076-7693-ad3c-237b393e944c'::uuid and equipment_type = 'dip_machine' and available;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'warnings' and version = '1.2.0')
     is distinct from (select constants_hash from fitness.algorithm_versions where module_id = 'warnings' and version = '1.1.0') then
    raise exception '055: warnings@1.2.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.20.0') <> 1 then
    raise exception '055: expected 1 module version for engine 1.20.0';
  end if;
  if exists (select 1 from fitness.workout_sessions ws join fitness.session_events e
               on e.session_id = ws.id and e.user_id = ws.user_id and e.event_type = 'session_started'
              where ws.template_source = 'family_combo' and ws.families = '{}'
                and jsonb_typeof(e.payload->'families') = 'array' and jsonb_array_length(e.payload->'families') > 0) then
    raise exception '055: a built session still has empty families';
  end if;
  if exists (select 1 from fitness.wellness_logs w
              where w.kind = 'elbow_check' and w.phase = 'pre' and w.session_id is null and w.deleted_at is null
                and exists (select 1 from fitness.workout_sessions s where s.user_id = w.user_id and s.deleted_at is null
                              and s.started_at between w.observed_at and w.observed_at + interval '10 minutes')) then
    raise exception '055: a pre-session elbow check with a session in reach is still unlinked';
  end if;
  if exists (select 1 from fitness.gym_equipment where id = '01a12015-d076-7693-ad3c-237b393e944c'::uuid and available) then
    raise exception '055: the dip machine is still available';
  end if;
end;
$$;
