-- fitness_db_tests.sql — DATA_MODEL.md 17.6, 19.2-19.5, 19.9 (C61). Covers the migrations up to 020,
-- including the fitness_review views of 017.
--
-- One DO block that always ends by raising an exception, so nothing can commit. Success is the message
-- FITNESS_DB_TESTS_PASSED <n> (<env>); any other message names the failing test id.
--   Local Docker stack: supabase/tests/run_local.sh (sets fitness_tests.env = 'local'; actors A, B, C).
--   Live shared project: run as postgres through the MCP execute_sql (actors A and C only; B tests skipped).
-- Actor A is the allowlisted owner (its id is read into a variable and never printed); B is the fixture
-- member (local only); C is a synthetic outsider that exists only in the JWT claims. Only fixed fixture
-- UUIDs (19.6), ids derived from them, and synthetic UUIDs are used.
--
-- The 19.9 view sentinels (V-*) run as postgres at the end. Their fixture rows use the fixed session-A ids
-- and FIX7(2026-10-25 17:15 CET, n) ids, and every assertion filters on those ids (or on a fixture-only
-- platform_config_hash / module version), so real rows of the owner on the live project never change a
-- result.

do $tests$
declare
  ns        constant uuid := '2611a46b-2ad8-5006-88dc-e43030211f8f';   -- FITNESS_NS (6.4)
  v_a       uuid;                                                      -- actor A, owner
  v_b       constant uuid := '2c87d2df-3ae7-5148-b047-860b3515151d';   -- actor B, fixture member (local only)
  v_c       uuid := gen_random_uuid();                                 -- actor C, synthetic outsider
  v_dev     constant uuid := '00000000-0000-4000-8000-0000000000d1';
  v_srv     constant uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff';   -- SERVER_DEVICE_ID
  v_local   boolean;
  v_n       int;
  v_t       text;
  v_ts      timestamptz;
  v_ts2     timestamptz;
  v_txt     text;
  v_pass    int := 0;
  -- fixture ids
  v_hash    constant text := '4ccac5e674e1d05ff38a16474dcc390a7d015f499157bc143151430e158f1307';
  v_ps      uuid;
  v_gym     constant uuid := '01a11597-4e80-7000-8000-000000000001';
  v_eq_m    constant uuid := '01a11597-4e80-7000-8000-000000000002';   -- assisted machine
  v_eq_b    constant uuid := '01a11597-4e80-7000-8000-000000000003';   -- dip belt
  v_sess    constant uuid := '01a11c9e-5160-7000-8000-000000000001';   -- session A
  v_blk     constant uuid := '01a11c9e-5160-7000-8000-000000000002';
  v_se_wpu  constant uuid := '01a11c9e-5160-7000-8000-000000000003';
  v_se_oaa  constant uuid := '01a11c9e-5160-7000-8000-000000000004';
  v_se_neg  constant uuid := '01a11c9e-5160-7000-8000-000000000005';
  v_se_h90  constant uuid := '01a11c9e-5160-7000-8000-000000000006';
  v_set1    constant uuid := '01a11c9e-5160-7000-8000-000000000007';
  v_sess2   constant uuid := '01a11251-9960-7000-8000-000000000001';   -- session C
  v_blk2    constant uuid := '01a11251-9960-7000-8000-000000000002';
  v_wl      constant uuid := '01a11c9e-5160-7000-8000-0000000000a1';
  v_ev      constant uuid := '01a11c9e-5160-7000-8000-0000000000e1';
  v_def     uuid;
  v_def_d   uuid;
  v_enr     constant uuid := '01a115ce-3d00-7000-8000-000000000001';
  v_goal    uuid;
  v_ex_wpu  uuid;
  v_ex_neg  uuid;
  v_ex_oaa  uuid;
  v_ex_h90  uuid;
  v_ex_cust uuid := gen_random_uuid();
  v_b_row   uuid := gen_random_uuid();
  -- 19.9 view sentinels: snapshots and events of session A (FIX7(2026-10-08 19:45, n))
  v_sse1    constant uuid := '01a11c9e-5160-7000-8000-0000000000c1';   -- estimation.sse
  v_sse2    constant uuid := '01a11c9e-5160-7000-8000-0000000000c2';   -- newer estimation.sse
  v_acc     constant uuid := '01a11c9e-5160-7000-8000-0000000000c3';   -- accounting.week 2026-W41
  v_tgt     constant uuid := '01a11c9e-5160-7000-8000-0000000000c4';   -- targets.weekly 2026-W41
  v_rv9     constant uuid := '01a11c9e-5160-7000-8000-0000000000c5';   -- unregistered module version 9.9.9
  v_ev_txt  constant uuid := '01a11c9e-5160-7000-8000-0000000000e2';   -- payload with free text
  v_ev_code constant uuid := '01a11c9e-5160-7000-8000-0000000000e3';   -- payload with codes only
  -- DST-day sentinel session (19.8): FIX7(2026-10-25 17:15 CET, n)
  v_s3      constant uuid := '01a173d8-07a0-7000-8000-000000000001';
  v_s3_blk  constant uuid := '01a173d8-07a0-7000-8000-000000000002';
  v_s3_se   constant uuid := '01a173d8-07a0-7000-8000-000000000003';
  v_s3_set1 constant uuid := '01a173d8-07a0-7000-8000-000000000004';   -- working set
  v_s3_set2 constant uuid := '01a173d8-07a0-7000-8000-000000000005';   -- warm-up, effective load missing
  v_s3_wl   constant uuid := '01a173d8-07a0-7000-8000-0000000000a1';   -- sRPE
  v_s3_plan constant uuid := '01a173d8-07a0-7000-8000-0000000000c1';   -- session.plan of the block
  v_s3_ev1  constant uuid := '01a173d8-07a0-7000-8000-0000000000e1';   -- rest_alarm_fired 02:30 CEST
  v_s3_ev2  constant uuid := '01a173d8-07a0-7000-8000-0000000000e2';   -- rest_alarm_fired 02:30 CET
  v_pch     constant text := 'c2787b7b6a44a23cf1f271950f94c4201831490ed2afc9f22306fd3a1408da4d'; -- sha256 'fixture-platform-config'
  v_views   constant text[] := array[
    'v_set_facts', 'v_set_estimates', 'v_e1rm_history', 'v_prescription_error', 'v_rir_calibration',
    'v_override_stats', 'v_skip_stats', 'v_weekly_muscle_volume', 'v_rest_compliance', 'v_rest_alarm_latency',
    'v_adherence_week', 'v_recommendation_acceptance', 'v_session_summary', 'v_exercise_time',
    'v_challenge_timeline', 'v_challenge_var_changes', 'v_challenge_tests', 'v_wellness_safety',
    'v_calendar_events', 'v_calendar_classification', 'v_data_quality', 'v_sync_health', 'v_upload_latency',
    'v_db_size', 'v_db_size_summary', 'v_replay_index'];                -- 14.3 catalogue
begin
  select a.user_id into strict v_a from fitness_private.allowed_users a where a.role = 'owner';
  v_local := coalesce(current_setting('fitness_tests.env', true), 'live') = 'local';
  if v_local then
    insert into fitness_private.allowed_users (user_id, role, note) values (v_b, 'member', 'db-test, rolled back');
  end if;

  v_ps     := extensions.uuid_generate_v5(ns, 'params_snapshots:' || v_a || ':' || v_hash);
  v_goal   := extensions.uuid_generate_v5(ns, 'muscle_goals:' || v_a || ':quads');
  v_def    := extensions.uuid_generate_v5(ns, 'challenge_definition:dbtestchal@1.0.0');
  v_def_d  := extensions.uuid_generate_v5(ns, 'challenge_definition:dbtestchal@2.0.0');
  v_ex_wpu := extensions.uuid_generate_v5(ns, 'exercise:chinup_weighted');
  v_ex_neg := extensions.uuid_generate_v5(ns, 'exercise:weighted_negative_pullup');
  v_ex_oaa := extensions.uuid_generate_v5(ns, 'exercise:oap_assisted_machine');
  v_ex_h90 := extensions.uuid_generate_v5(ns, 'exercise:oa_hold90_machine');
  assert v_ex_wpu = '1bf2755f-6a6e-5998-9327-79648dda4205', 'SETUP FAILED: FITNESS_NS / uuid v5';

  -- ------------------------------------------------------------------------------------------------
  -- Fixture rows as postgres (RLS bypassed). Global catalog rows are inserted only if the seed (009) is
  -- not there yet; everything is rolled back.
  -- ------------------------------------------------------------------------------------------------
  insert into fitness.catalog_releases (version, exercise_count, stimulus_count, jcs_sha256)
  values ('1.0.0', 0, 0, repeat('0', 64)) on conflict (version) do nothing;
  insert into fitness.exercises (id, user_id, client_updated_at, device_id, app_version, slug, name_en, pattern, family,
      equipment, requires_station, load_mode, allowed_load_modes, bw_fraction, laterality, measure, contraction,
      hold_class, mechanics, curve_class, rest_class, safety_class, catalog_version)
  values
    (v_ex_wpu, null, now(), v_srv, 'migration', 'chinup_weighted', 'Weighted chin-up', 'vert_pull', 'vpull',
     '{bodyweight}', '{pullup_bar,dip_belt}', 'bodyweight_plus_external', '{bodyweight,bodyweight_plus_external}', 1,
     'bilateral', 'reps', 'dynamic', null, 'compound', 'upper_compound', 'compound_upper', 'high_risk', '1.0.0'),
    (v_ex_neg, null, now(), v_srv, 'migration', 'weighted_negative_pullup', 'Weighted negative', 'vert_pull', 'vpull',
     '{bodyweight}', '{pullup_bar}', 'bodyweight_plus_external', '{bodyweight_plus_external}', 1,
     'bilateral', 'reps', 'eccentric_only', null, 'compound', null, 'compound_upper', 'high_risk', '1.0.0'),
    (v_ex_oaa, null, now(), v_srv, 'migration', 'oap_assisted_machine', 'Assisted one-arm pull-up', 'vert_pull', 'vpull',
     '{machine}', '{assisted_pullup_machine}', 'assisted_machine', '{assisted_machine}', 1,
     'unilateral', 'reps', 'dynamic', null, 'compound', 'upper_compound', 'compound_upper', 'high_risk', '1.0.0'),
    (v_ex_h90, null, now(), v_srv, 'migration', 'oa_hold90_machine', 'One-arm 90 hold', 'skill_static', 'skill',
     '{machine}', '{assisted_pullup_machine}', 'assisted_machine', '{assisted_machine}', 1,
     'unilateral', 'duration', 'isometric', 'elbow', 'skill', null, 'hold', 'high_risk', '1.0.0')
  on conflict do nothing;

  insert into fitness.gyms (id, user_id, client_updated_at, device_id, app_version, name, is_default)
  values (v_gym, v_a, '2026-10-07T09:00:00Z', v_dev, 'dbtest', 'DB test gym', false);
  insert into fitness.gym_equipment (id, user_id, client_updated_at, device_id, app_version, gym_id, equipment_type,
      label, scheme_type, load_scheme, calibration_source)
  values
    (v_eq_m, v_a, '2026-10-07T09:00:00Z', v_dev, 'dbtest', v_gym, 'assisted_pullup_machine', 'Assisted machine',
     'pin_assist', '{"type":"pin_assist","unit":"lb","step":5,"min":0,"max":null,"offset_kg":6.5,"ratio":1.0}',
     'user_estimate'),
    (v_eq_b, v_a, '2026-10-07T09:00:00Z', v_dev, 'dbtest', null, 'dip_belt', 'Dip belt',
     'belt', '{"type":"belt","unit":"kg","plates":[{"kg":20,"count":2}],"max_kg":60,"max_items":4,"dumbbells_on_belt":false}',
     'default');
  insert into fitness.params_snapshots (id, user_id, device_id, app_version, params_hash, params_version, params)
  values (v_ps, v_a, v_dev, 'dbtest', v_hash, '1.0.0', '{"fixture.constant":1}')
  on conflict do nothing;

  insert into fitness.challenge_definitions (id, user_id, slug, version, status, schema_version, engine_range,
      title_en, definition, jcs_sha256, client_updated_at, device_id, app_version)
  values
    (v_def, null, 'dbtestchal', '1.0.0', 'published', 'fitapp.challenge/1', '>=1.0.0 <2.0.0', 'DB test',
     '{"k":1}', repeat('a', 64), now(), v_srv, 'migration'),
    (v_def_d, v_a, 'dbtestchal', '2.0.0', 'draft', 'fitapp.challenge/1', '>=1.0.0 <2.0.0', 'DB test draft',
     '{"k":2}', repeat('b', 64), now(), v_srv, 'migration');
  insert into fitness.challenge_enrollments (id, user_id, client_updated_at, device_id, app_version, definition_id,
      definition_slug, definition_version, definition_jcs_sha256, status, engine_version, state_rev)
  values (v_enr, v_a, '2026-10-07T10:00:00Z', v_dev, 'dbtest', v_def, 'dbtestchal', '1.0.0', repeat('a', 64),
          'active', '1.0.0', 2);

  insert into fitness.workout_sessions (id, user_id, client_updated_at, device_id, app_version, kind, status,
      template_source, local_date, timezone, iso_week, engine_version, params_hash, catalog_version, started_at, finished_at)
  values
    (v_sess, v_a, '2026-10-08T19:05:00Z', v_dev, 'dbtest', 'gym', 'finished', 'empty', '2026-10-08',
     'Europe/Amsterdam', '2026-W41', '1.0.0', v_hash, '1.0.0', '2026-10-08T17:50:00Z', '2026-10-08T19:05:00Z'),
    (v_sess2, v_a, '2026-10-06T19:05:00Z', v_dev, 'dbtest', 'gym', 'finished', 'empty', '2026-10-06',
     'Europe/Amsterdam', '2026-W41', '1.0.0', v_hash, '1.0.0', '2026-10-06T17:50:00Z', '2026-10-06T19:05:00Z');
  insert into fitness.session_blocks (id, user_id, client_updated_at, device_id, app_version, session_id, position,
      block_kind, origin)
  values (v_blk, v_a, '2026-10-08T17:50:00Z', v_dev, 'dbtest', v_sess, 0, 'general', 'user_built'),
         (v_blk2, v_a, '2026-10-06T17:50:00Z', v_dev, 'dbtest', v_sess2, 0, 'general', 'user_built');
  insert into fitness.session_exercises (id, user_id, client_updated_at, device_id, app_version, session_id, block_id,
      exercise_id, position, origin, load_mode, side_mode, equipment_id, progression_mode, rep_scheme)
  values
    (v_se_wpu, v_a, '2026-10-08T17:50:00Z', v_dev, 'dbtest', v_sess, v_blk, v_ex_wpu, 1, 'user_added',
     'bodyweight_plus_external', 'bilateral', v_eq_b, 'none', 'fixed'),
    (v_se_oaa, v_a, '2026-10-08T17:50:00Z', v_dev, 'dbtest', v_sess, v_blk, v_ex_oaa, 2, 'user_added',
     'assisted_machine', 'unilateral_alternating', v_eq_m, 'none', 'fixed'),
    (v_se_neg, v_a, '2026-10-08T17:50:00Z', v_dev, 'dbtest', v_sess, v_blk, v_ex_neg, 3, 'user_added',
     'bodyweight_plus_external', 'bilateral', v_eq_b, 'none', 'fixed'),
    (v_se_h90, v_a, '2026-10-08T17:50:00Z', v_dev, 'dbtest', v_sess, v_blk, v_ex_h90, 4, 'user_added',
     'assisted_machine', 'unilateral_alternating', v_eq_m, 'none', 'hold');
  insert into fitness.set_logs (id, user_id, client_updated_at, device_id, app_version, session_id, session_exercise_id,
      exercise_id, set_index, load_mode, equipment_id, input_value, input_unit, added_kg, bodyweight_kg, f_bw,
      effective_load_kg, reps, rir_reported, completed_at)
  values (v_set1, v_a, '2026-10-08T18:00:00Z', v_dev, 'dbtest', v_sess, v_se_wpu, v_ex_wpu, 1,
          'bodyweight_plus_external', v_eq_b, 22.5, 'kg', 22.5, 70.70, 1, 93.20, 4, 2, '2026-10-08T18:00:00Z');
  insert into fitness.wellness_logs (id, user_id, client_updated_at, device_id, app_version, kind, phase, observed_at,
      local_date, session_id, set_log_id, pain_body_region, pain_side, pain_severity, pain_score)
  values (v_wl, v_a, '2026-10-08T18:01:00Z', v_dev, 'dbtest', 'pain', 'in_session', '2026-10-08T18:01:00Z',
          '2026-10-08', v_sess, v_set1, (select code from fitness.body_regions order by sort, code limit 1), 'L', 'mild', 2);
  insert into fitness.session_events (id, user_id, device_id, app_version, event_type, category, occurred_at,
      tz_offset_min, session_id, payload)
  values (v_ev, v_a, v_dev, 'dbtest', 'set_logged', 'logging', '2026-10-08T18:00:00Z', 120, v_sess, '{"set_index":1}');
  delete from fitness.muscle_goals where id = v_goal;

  -- ================================================================================================
  -- 19.3 RLS and grant tests
  -- ================================================================================================
  perform set_config('request.jwt.claims', json_build_object('sub', v_a, 'role', 'authenticated')::text, true);
  set local role authenticated;

  -- RLS-01: A sees its fixture rows.
  select (select count(*) from fitness.gyms where id = v_gym)
       + (select count(*) from fitness.gym_equipment where id in (v_eq_m, v_eq_b))
       + (select count(*) from fitness.params_snapshots where id = v_ps)
       + (select count(*) from fitness.challenge_enrollments where id = v_enr)
       + (select count(*) from fitness.workout_sessions where id in (v_sess, v_sess2))
       + (select count(*) from fitness.session_blocks where id in (v_blk, v_blk2))
       + (select count(*) from fitness.session_exercises where id in (v_se_wpu, v_se_oaa, v_se_neg, v_se_h90))
       + (select count(*) from fitness.set_logs where id = v_set1)
       + (select count(*) from fitness.wellness_logs where id = v_wl)
       + (select count(*) from fitness.session_events where id = v_ev)
    into v_n;
  assert v_n = 16, 'RLS-01 FAILED: owner sees ' || v_n || ' of 16 fixture rows';
  v_pass := v_pass + 1;

  -- RLS-11: no DELETE grant.
  begin
    delete from fitness.set_logs where id = v_set1;
    raise exception 'RLS-11 FAILED: owner deleted a set';
  exception when insufficient_privilege then v_pass := v_pass + 1;
  end;

  -- RLS-12: no UPDATE grant on APPEND tables.
  begin
    update fitness.session_events set payload = '{}' where id = v_ev;
    raise exception 'RLS-12 FAILED: owner updated a session event';
  exception when insufficient_privilege then v_pass := v_pass + 1;
  end;

  -- RLS-13: REF tables are read-only.
  begin
    insert into fitness.muscle_groups (id, family, label_en) values ('dbtest_group', 'other', 'x');
    raise exception 'RLS-13 FAILED: owner wrote reference data';
  exception when insufficient_privilege then v_pass := v_pass + 1;
  end;

  -- RLS-14: user_algo_params is USER-RO.
  begin
    insert into fitness.user_algo_params (client_updated_at, device_id, app_version, param_key, module_id, value,
        valid_from, source)
    values (now(), v_dev, 'dbtest', 'rest.min_s', 'rest', '60', now(), 'user');
    raise exception 'RLS-14 FAILED: owner wrote user_algo_params';
  exception when insufficient_privilege then v_pass := v_pass + 1;
  end;

  -- RLS-15: challenge definitions are read-only (insert and update).
  begin
    insert into fitness.challenge_definitions (id, slug, version, schema_version, engine_range, title_en, definition,
        jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
    values (gen_random_uuid(), 'x', '9.9.9', 'fitapp.challenge/1', 'x', 'x', '{}', repeat('c', 64), repeat('c', 64),
            now(), v_dev, 'dbtest');
    raise exception 'RLS-15 FAILED: owner inserted a definition';
  exception when insufficient_privilege then null;
  end;
  begin
    update fitness.challenge_definitions set title_en = 'y' where id = v_def;
    raise exception 'RLS-15 FAILED: owner updated a definition';
  exception when insufficient_privilege then v_pass := v_pass + 1;
  end;

  -- RLS-19: the allowlist is invisible.
  begin
    perform 1 from fitness_private.allowed_users;
    raise exception 'RLS-19 FAILED: owner read the allowlist';
  exception when insufficient_privilege then v_pass := v_pass + 1;
  end;

  -- RLS-20 (A part): is_allowed() is true for the owner.
  assert (select fitness_private.is_allowed()), 'RLS-20 FAILED: is_allowed() false for the owner';

  -- RLS-21: no USAGE on fitness_review, so even the owner cannot read a review view (R-V2).
  assert not has_schema_privilege('authenticated', 'fitness_review', 'USAGE'),
    'RLS-21 FAILED: authenticated has USAGE on fitness_review';
  begin
    perform 1 from fitness_review.v_set_facts limit 1;
    raise exception 'RLS-21 FAILED: authenticated read fitness_review.v_set_facts';
  exception when insufficient_privilege then v_pass := v_pass + 1;
  end;

  -- RLS-22 (A part): the trigger keeps user_id immutable.
  update fitness.gyms set user_id = v_c, client_updated_at = '2026-10-07T09:00:01Z' where id = v_gym;
  select count(*) into v_n from fitness.gyms where id = v_gym and user_id = v_a;
  assert v_n = 1, 'RLS-22 FAILED: user_id changed by an update';
  v_pass := v_pass + 1;

  -- RLS-07 .. RLS-09 and the C part of RLS-20: the outsider.
  perform set_config('request.jwt.claims', json_build_object('sub', v_c, 'role', 'authenticated')::text, true);
  select count(*) into v_n from fitness.muscle_groups;
  assert v_n = 0, 'RLS-07 FAILED: outsider sees reference data';
  v_pass := v_pass + 1;
  for v_t in
    select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'fitness' and c.relkind = 'r' order by 1
  loop
    execute format('select count(*) from fitness.%I', v_t) into v_n;
    assert v_n = 0, 'RLS-08 FAILED: outsider sees rows of ' || v_t;
  end loop;
  v_pass := v_pass + 1;
  begin
    insert into fitness.profiles (id, user_id, client_updated_at, device_id, app_version)
    values (v_c, v_c, now(), v_dev, 'dbtest');
    raise exception 'RLS-09 FAILED: outsider inserted a profile';
  exception when insufficient_privilege then v_pass := v_pass + 1;
  end;
  assert not (select fitness_private.is_allowed()), 'RLS-20 FAILED: is_allowed() true for an outsider';
  v_pass := v_pass + 1;

  -- RLS-10: anon has no USAGE on fitness.
  set local role anon;
  begin
    perform 1 from fitness.muscle_groups;
    raise exception 'RLS-10 FAILED: anon read fitness';
  exception when insufficient_privilege then v_pass := v_pass + 1;
  end;
  set local role postgres;

  if v_local then
    perform set_config('request.jwt.claims', json_build_object('sub', v_b, 'role', 'authenticated')::text, true);
    set local role authenticated;

    -- RLS-02: B does not see A's rows.
    select (select count(*) from fitness.workout_sessions where id = v_sess)
         + (select count(*) from fitness.set_logs where id = v_set1)
         + (select count(*) from fitness.gym_equipment where id = v_eq_m)
      into v_n;
    assert v_n = 0, 'RLS-02 FAILED: member sees the owner''s rows';
    v_pass := v_pass + 1;

    -- RLS-03: B cannot insert a row owned by A.
    begin
      insert into fitness.gyms (user_id, client_updated_at, device_id, app_version, name)
      values (v_a, now(), v_dev, 'dbtest', 'x');
      raise exception 'RLS-03 FAILED: insert with a foreign user_id succeeded';
    exception when insufficient_privilege then v_pass := v_pass + 1;
    end;

    -- RLS-04: B's update of A's row affects nothing.
    update fitness.workout_sessions set status = 'abandoned', client_updated_at = now() + interval '1 day'
     where id = v_sess;
    get diagnostics v_n = row_count;
    assert v_n = 0, 'RLS-04 FAILED: member updated the owner''s row';
    v_pass := v_pass + 1;

    -- RLS-05: B's upsert with the id of A's row fails the update policy.
    begin
      insert into fitness.gyms (id, user_id, client_updated_at, device_id, app_version, name)
      values (v_gym, v_b, now() + interval '1 day', v_dev, 'dbtest', 'hijack')
      on conflict (id) do update set name = excluded.name, client_updated_at = excluded.client_updated_at;
      raise exception 'RLS-05 FAILED: upsert over a foreign row succeeded';
    exception when insufficient_privilege then v_pass := v_pass + 1;
    end;

    -- RLS-06: B inserts an own row (user_id defaulted); A cannot see it.
    insert into fitness.gyms (id, client_updated_at, device_id, app_version, name)
    values (v_b_row, now(), v_dev, 'dbtest', 'member gym');
    select count(*) into v_n from fitness.gyms where id = v_b_row and user_id = v_b;
    assert v_n = 1, 'RLS-06 FAILED: member could not insert an own row';

    -- RLS-16: B does not see A's draft definition; it sees the published global one.
    select count(*) into v_n from fitness.challenge_definitions where id = v_def_d;
    assert v_n = 0, 'RLS-16 FAILED: member sees the owner''s draft definition';
    select count(*) into v_n from fitness.challenge_definitions where id = v_def;
    assert v_n = 1, 'RLS-16 FAILED: member does not see the published definition';
    v_pass := v_pass + 1;

    set local role postgres;
    perform set_config('request.jwt.claims', json_build_object('sub', v_a, 'role', 'authenticated')::text, true);
    set local role authenticated;
    select count(*) into v_n from fitness.gyms where id = v_b_row;
    assert v_n = 0, 'RLS-06 FAILED: owner sees the member''s row';
    v_pass := v_pass + 1;

    -- RLS-17 (A part): A creates a custom exercise and sees global rows.
    insert into fitness.exercises (id, client_updated_at, device_id, app_version, slug, name_en, pattern, family,
        equipment, load_mode, allowed_load_modes, laterality, measure, mechanics, curve_class, rest_class,
        safety_class, catalog_version)
    values (v_ex_cust, now(), v_dev, 'dbtest', 'dbtest_custom', 'Custom', 'elbow_flex', 'curl', '{cable}', 'external',
            '{external}', 'bilateral', 'reps', 'isolation', 'upper_isolation', 'isolation', 'cable', '1.0.0');
    select count(*) into v_n from fitness.exercises where id in (v_ex_wpu, v_ex_cust);
    assert v_n = 2, 'RLS-17 FAILED: owner does not see global + own exercises';

    set local role postgres;
    perform set_config('request.jwt.claims', json_build_object('sub', v_b, 'role', 'authenticated')::text, true);
    set local role authenticated;
    select count(*) into v_n from fitness.exercises where id = v_ex_wpu;
    assert v_n = 1, 'RLS-17 FAILED: member does not see global exercises';
    select count(*) into v_n from fitness.exercises where id = v_ex_cust;
    assert v_n = 0, 'RLS-17 FAILED: member sees the owner''s custom exercise';
    v_pass := v_pass + 1;

    -- RLS-18: the visibility trigger rejects a reference to A's custom exercise.
    begin
      insert into fitness.user_exercise_prefs (client_updated_at, device_id, app_version, exercise_id, preference)
      values (now(), v_dev, 'dbtest', v_ex_cust, 'like');
      raise exception 'RLS-18 FAILED: member referenced a foreign custom exercise';
    exception when foreign_key_violation then v_pass := v_pass + 1;
    end;

    -- FK-01: B cannot hang a block under A's session.
    begin
      insert into fitness.session_blocks (client_updated_at, device_id, app_version, session_id, position, block_kind, origin)
      values (now(), v_dev, 'dbtest', v_sess, 5, 'general', 'user_built');
      raise exception 'FK-01 FAILED: member attached a block to the owner''s session';
    exception when foreign_key_violation then v_pass := v_pass + 1;
    end;

    -- FK-05: B cannot reference A's set from a wellness log.
    begin
      insert into fitness.wellness_logs (client_updated_at, device_id, app_version, kind, phase, observed_at, local_date,
          set_log_id, srpe)
      values (now(), v_dev, 'dbtest', 'srpe', 'post', now(), '2026-10-08', v_set1, 5);
      raise exception 'FK-05 FAILED: member referenced the owner''s set';
    exception when foreign_key_violation then v_pass := v_pass + 1;
    end;

    -- FK-06: B cannot link a calendar event to A's session.
    begin
      insert into fitness.calendar_events (client_updated_at, device_id, app_version, instance_key, source_device_id,
          classified_kind, title, starts_at, ends_at, local_date, timezone, iso_week, provider_status,
          classifier_version, match_method, linked_session_id, last_seen_at)
      values (now(), v_dev, 'dbtest', repeat('e', 64), v_dev, 'planned_gym', 'Gym', '2026-10-08T17:45:00Z',
              '2026-10-08T19:15:00Z', '2026-10-08', 'Europe/Amsterdam', '2026-W41', 'confirmed', '1.0.0', 'score',
              v_sess, now());
      raise exception 'FK-06 FAILED: member linked the owner''s session';
    exception when foreign_key_violation then v_pass := v_pass + 1;
    end;

    -- RLS-22 (B part): B still sees nothing of A's gym after A's update attempt above.
    select count(*) into v_n from fitness.gyms where id = v_gym;
    assert v_n = 0, 'RLS-22 FAILED: member sees the owner''s gym';
    v_pass := v_pass + 1;

    -- RLS-23: removing B from the allowlist hides B's own row immediately.
    set local role postgres;
    delete from fitness_private.allowed_users where user_id = v_b;
    set local role authenticated;
    select count(*) into v_n from fitness.gyms where id = v_b_row;
    assert v_n = 0, 'RLS-23 FAILED: a removed member still sees its rows';
    v_pass := v_pass + 1;

    set local role postgres;
  end if;

  -- ================================================================================================
  -- 19.4 LWW and trigger tests (as A, PostgREST merge-duplicates statement shape)
  -- ================================================================================================
  perform set_config('request.jwt.claims', json_build_object('sub', v_a, 'role', 'authenticated')::text, true);
  set local role authenticated;

  -- L-01: server_updated_at from the payload is ignored.
  insert into fitness.muscle_goals (id, user_id, client_updated_at, server_updated_at, device_id, app_version,
      muscle_group_id, goal, focus)
  values (v_goal, v_a, '2026-10-07T10:00:00.000Z', '2000-01-01', v_dev, 'dbtest', 'quads', 'maintain', false);
  select server_updated_at into v_ts from fitness.muscle_goals where id = v_goal;
  assert v_ts = now(), 'L-01 FAILED: server_updated_at taken from the payload';
  v_pass := v_pass + 1;

  -- L-02: newer tuple applies.
  insert into fitness.muscle_goals as t (id, user_id, client_updated_at, device_id, app_version, muscle_group_id, goal, focus)
  values (v_goal, v_a, '2026-10-07T10:00:00.001Z', v_dev, 'dbtest', 'quads', 'improve', false)
  on conflict (id) do update set client_updated_at = excluded.client_updated_at, device_id = excluded.device_id,
      app_version = excluded.app_version, muscle_group_id = excluded.muscle_group_id, goal = excluded.goal,
      focus = excluded.focus;
  get diagnostics v_n = row_count;
  select goal into v_txt from fitness.muscle_goals where id = v_goal;
  assert v_n = 1 and v_txt = 'improve', 'L-02 FAILED: newer tuple not applied';
  v_pass := v_pass + 1;

  -- L-03: older tuple is dropped silently.
  insert into fitness.muscle_goals as t (id, user_id, client_updated_at, device_id, app_version, muscle_group_id, goal, focus)
  values (v_goal, v_a, '2026-10-07T10:00:00.000Z', v_dev, 'dbtest', 'quads', 'skip', false)
  on conflict (id) do update set client_updated_at = excluded.client_updated_at, device_id = excluded.device_id,
      goal = excluded.goal;
  get diagnostics v_n = row_count;
  select goal into v_txt from fitness.muscle_goals where id = v_goal;
  assert v_n = 0 and v_txt = 'improve', 'L-03 FAILED: stale tuple applied';
  v_pass := v_pass + 1;

  -- L-04: equal time, greater device wins.
  insert into fitness.muscle_goals as t (id, user_id, client_updated_at, device_id, app_version, muscle_group_id, goal, focus)
  values (v_goal, v_a, '2026-10-07T10:00:00.001Z', '00000000-0000-4000-8000-0000000000d2', 'dbtest', 'quads', 'maintain', false)
  on conflict (id) do update set client_updated_at = excluded.client_updated_at, device_id = excluded.device_id,
      goal = excluded.goal;
  get diagnostics v_n = row_count;
  assert v_n = 1, 'L-04 FAILED: greater device_id did not win the tie';
  v_pass := v_pass + 1;

  -- L-05: equal time, smaller device is dropped.
  insert into fitness.muscle_goals as t (id, user_id, client_updated_at, device_id, app_version, muscle_group_id, goal, focus)
  values (v_goal, v_a, '2026-10-07T10:00:00.001Z', '00000000-0000-4000-8000-0000000000d0', 'dbtest', 'quads', 'skip', false)
  on conflict (id) do update set client_updated_at = excluded.client_updated_at, device_id = excluded.device_id,
      goal = excluded.goal;
  get diagnostics v_n = row_count;
  assert v_n = 0, 'L-05 FAILED: smaller device_id won the tie';
  v_pass := v_pass + 1;

  -- L-06: re-push of the stored tuple is a no-op.
  insert into fitness.muscle_goals as t (id, user_id, client_updated_at, device_id, app_version, muscle_group_id, goal, focus)
  values (v_goal, v_a, '2026-10-07T10:00:00.001Z', '00000000-0000-4000-8000-0000000000d2', 'dbtest', 'quads', 'maintain', false)
  on conflict (id) do update set client_updated_at = excluded.client_updated_at, device_id = excluded.device_id,
      goal = excluded.goal;
  get diagnostics v_n = row_count;
  select goal into v_txt from fitness.muscle_goals where id = v_goal;
  assert v_n = 0 and v_txt = 'maintain', 'L-06 FAILED: identical re-push was applied';
  v_pass := v_pass + 1;

  -- L-07: immutable columns survive a newer tuple. (The proposed insert row must pass the insert policy,
  -- so the foreign user_id is set in the DO UPDATE branch, which runs the trigger before the policy check.)
  select created_at into v_ts from fitness.muscle_goals where id = v_goal;
  insert into fitness.muscle_goals as t (id, user_id, created_at, client_updated_at, device_id, app_version,
      muscle_group_id, goal, focus)
  values (v_goal, v_a, '2000-01-01', '2026-10-07T10:00:00.002Z', v_dev, 'dbtest', 'quads', 'maintain', false)
  on conflict (id) do update set user_id = v_c, created_at = excluded.created_at,
      client_updated_at = excluded.client_updated_at, device_id = excluded.device_id;
  select count(*) into v_n from fitness.muscle_goals where id = v_goal and user_id = v_a and created_at = v_ts;
  assert v_n = 1, 'L-07 FAILED: user_id or created_at changed';
  v_pass := v_pass + 1;

  -- L-09: tombstone then undelete with newer tuples.
  update fitness.muscle_goals set deleted_at = now(), client_updated_at = '2026-10-07T10:00:00.003Z' where id = v_goal;
  select count(*) into v_n from fitness.muscle_goals where id = v_goal and deleted_at is not null;
  assert v_n = 1, 'L-09 FAILED: tombstone not stored';
  update fitness.muscle_goals set deleted_at = null, client_updated_at = '2026-10-07T10:00:00.004Z' where id = v_goal;
  select count(*) into v_n from fitness.muscle_goals where id = v_goal and deleted_at is null;
  assert v_n = 1, 'L-09 FAILED: undelete not applied';
  v_pass := v_pass + 1;

  -- L-10: a lower state_rev never wins, even with a newer time.
  update fitness.challenge_enrollments set state_rev = 1, client_updated_at = '2026-10-08T10:00:00Z' where id = v_enr;
  select state_rev into v_n from fitness.challenge_enrollments where id = v_enr;
  assert v_n = 2, 'L-10 FAILED: lower state_rev applied';
  v_pass := v_pass + 1;

  -- L-11: same state_rev: older dropped, newer applied.
  update fitness.challenge_enrollments set phase = 3, client_updated_at = '2026-10-07T09:00:00Z' where id = v_enr;
  select count(*) into v_n from fitness.challenge_enrollments where id = v_enr and phase is null;
  assert v_n = 1, 'L-11 FAILED: older tuple with the same state_rev applied';
  update fitness.challenge_enrollments set phase = 3, client_updated_at = '2026-10-07T11:00:00Z' where id = v_enr;
  select count(*) into v_n from fitness.challenge_enrollments where id = v_enr and phase = 3;
  assert v_n = 1, 'L-11 FAILED: newer tuple with the same state_rev dropped';
  v_pass := v_pass + 1;

  -- L-12: a higher state_rev wins with an older time.
  update fitness.challenge_enrollments set state_rev = 3, client_updated_at = '2026-10-01T00:00:00Z' where id = v_enr;
  select state_rev into v_n from fitness.challenge_enrollments where id = v_enr;
  assert v_n = 3, 'L-12 FAILED: higher state_rev dropped';
  v_pass := v_pass + 1;

  -- L-13: duplicate APPEND insert with ignore-duplicates.
  insert into fitness.session_events (id, user_id, device_id, app_version, event_type, category, occurred_at,
      tz_offset_min, payload)
  values (v_ev, v_a, v_dev, 'dbtest', 'set_logged', 'logging', now(), 120, '{}')
  on conflict do nothing;
  get diagnostics v_n = row_count;
  assert v_n = 0, 'L-13 FAILED: duplicate append row inserted';
  v_pass := v_pass + 1;

  -- L-18: two writes in one transaction share server_updated_at.
  insert into fitness.session_events (user_id, device_id, app_version, event_type, category, occurred_at, tz_offset_min)
  values (v_a, v_dev, 'dbtest', 'app_opened', 'system', now(), 120);
  select count(distinct server_updated_at) into v_n from fitness.session_events
   where user_id = v_a and server_updated_at = now();
  assert v_n = 1, 'L-18 FAILED: server_updated_at differs within one transaction';
  v_pass := v_pass + 1;

  set local role postgres;

  -- L-08: SERVER_DEVICE_ID wins an equal-time tie (postgres write).
  update fitness.muscle_goals set goal = 'skip', device_id = v_srv, client_updated_at = '2026-10-07T10:00:00.004Z'
   where id = v_goal;
  select goal into v_txt from fitness.muscle_goals where id = v_goal;
  assert v_txt = 'skip', 'L-08 FAILED: SERVER_DEVICE_ID lost the tie';
  v_pass := v_pass + 1;

  -- L-14: postgres may update an append-only row (pruning path); server_updated_at is unchanged.
  select server_updated_at into v_ts from fitness.session_events where id = v_ev;
  update fitness.session_events set payload = '{}' where id = v_ev;
  select server_updated_at into v_ts2 from fitness.session_events where id = v_ev;
  assert v_ts2 = v_ts, 'L-14 FAILED: append-only update by postgres changed server_updated_at';
  v_pass := v_pass + 1;

  -- L-15: REF seed re-run keeps updated_at; a changed label touches it.
  select code, updated_at into v_txt, v_ts from fitness.reason_codes order by code limit 1;
  insert into fitness.reason_codes (code, label_en, ui_contexts)
  select code, label_en, ui_contexts from fitness.reason_codes where code = v_txt
  on conflict (code) do update set label_en = excluded.label_en
   where fitness.reason_codes.label_en is distinct from excluded.label_en;
  select updated_at into v_ts2 from fitness.reason_codes where code = v_txt;
  assert v_ts2 = v_ts, 'L-15 FAILED: identical seed re-run touched updated_at';
  update fitness.reason_codes set label_en = 'dbtest label' where code = v_txt;
  select updated_at into v_ts2 from fitness.reason_codes where code = v_txt;
  assert v_ts2 = now(), 'L-15 FAILED: changed label did not touch updated_at';
  v_pass := v_pass + 1;

  -- L-16: the definition guard.
  begin
    update fitness.challenge_definitions set definition = '{"k":9}' where id = v_def;
    raise exception 'L-16 FAILED: a published definition changed';
  exception when object_not_in_prerequisite_state then null;
  end;
  update fitness.challenge_definitions set status = 'retired' where id = v_def;
  select count(*) into v_n from fitness.challenge_definitions where id = v_def and retired_at is not null;
  assert v_n = 1, 'L-16 FAILED: retiring did not set retired_at';
  begin
    update fitness.challenge_definitions set status = 'published' where id = v_def;
    raise exception 'L-16 FAILED: a retired definition was re-published';
  exception when object_not_in_prerequisite_state then v_pass := v_pass + 1;
  end;

  -- L-17: the server checksum.
  select count(*) into v_n from fitness.challenge_definitions
   where id = v_def and server_checksum = encode(sha256(convert_to(definition::text, 'UTF8')), 'hex');
  assert v_n = 1, 'L-17 FAILED: server_checksum mismatch';
  v_pass := v_pass + 1;

  -- ================================================================================================
  -- 19.5 Composite foreign keys and CHECK constraints (as A unless noted)
  -- ================================================================================================
  set local role authenticated;

  -- FK-02: block of S1 with session_id S2.
  begin
    insert into fitness.session_exercises (client_updated_at, device_id, app_version, session_id, block_id, exercise_id,
        position, origin, load_mode, progression_mode, rep_scheme)
    values (now(), v_dev, 'dbtest', v_sess2, v_blk, v_ex_wpu, 9, 'user_added', 'bodyweight', 'none', 'fixed');
    raise exception 'FK-02 FAILED: block and session mismatch accepted';
  exception when foreign_key_violation then v_pass := v_pass + 1;
  end;

  -- FK-03: set exercise differs from its session exercise.
  begin
    insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id,
        exercise_id, set_index, load_mode, bodyweight_kg, f_bw, reps, rir_reported, completed_at)
    values (now(), v_dev, 'dbtest', v_sess, v_se_wpu, v_ex_neg, 9, 'bodyweight', 70.70, 1, 3, 2, now());
    raise exception 'FK-03 FAILED: set with a different exercise accepted';
  exception when foreign_key_violation then v_pass := v_pass + 1;
  end;

  -- FK-04: the exercise of a session exercise is pinned once sets exist.
  begin
    update fitness.session_exercises set exercise_id = v_ex_neg, client_updated_at = '2099-01-01' where id = v_se_wpu;
    raise exception 'FK-04 FAILED: exercise changed under logged sets';
  exception when foreign_key_violation then v_pass := v_pass + 1;
  end;

  -- FK-07: recommendation with an unknown params_hash.
  begin
    insert into fitness.recommendations (device_id, app_version, kind, module_id, module_version, engine_version,
        params_hash, catalog_version, inputs_hash, inputs_bytes, outputs, scope_key)
    values (v_dev, 'dbtest', 'ui.prefill', 'ui.prefill', '1.0.0', '1.0.0', repeat('9', 64), '1.0.0', repeat('1', 64), 2,
            '{}', 'set:x');
    raise exception 'FK-07 FAILED: unknown params_hash accepted';
  exception when foreign_key_violation then v_pass := v_pass + 1;
  end;

  -- FK-08: enrollment with a wrong definition hash.
  begin
    insert into fitness.challenge_enrollments (client_updated_at, device_id, app_version, definition_id, definition_slug,
        definition_version, definition_jcs_sha256, status, engine_version)
    values (now(), v_dev, 'dbtest', v_def, 'dbtestchal', '1.0.0', repeat('f', 64), 'completed', '1.0.0');
    raise exception 'FK-08 FAILED: wrong definition hash accepted';
  exception when foreign_key_violation then v_pass := v_pass + 1;
  end;

  -- FK-09: unknown symptom code.
  begin
    insert into fitness.wellness_logs (client_updated_at, device_id, app_version, kind, phase, observed_at, local_date,
        pain_body_region, pain_severity, pain_score, symptom_codes)
    values (now(), v_dev, 'dbtest', 'pain', 'standalone', now(), '2026-10-08',
            (select code from fitness.body_regions order by sort, code limit 1), 'mild', 2, '{made_up}');
    raise exception 'FK-09 FAILED: unknown symptom code accepted';
  exception when foreign_key_violation then v_pass := v_pass + 1;
  end;

  -- CK-01 / CK-02: weighted pull-up effective load and the 0.01 kg tolerance.
  insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
      set_index, load_mode, equipment_id, input_value, input_unit, added_kg, bodyweight_kg, f_bw, effective_load_kg,
      reps, rir_reported, completed_at)
  values (now(), v_dev, 'dbtest', v_sess, v_se_wpu, v_ex_wpu, 2, 'bodyweight_plus_external', v_eq_b, 20, 'kg', 20.00,
          70.70, 1.0000, 90.70, 5, 2, now());
  v_pass := v_pass + 1;
  begin
    insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
        set_index, load_mode, equipment_id, input_value, input_unit, added_kg, bodyweight_kg, f_bw, effective_load_kg,
        reps, rir_reported, completed_at)
    values (now(), v_dev, 'dbtest', v_sess, v_se_wpu, v_ex_wpu, 3, 'bodyweight_plus_external', v_eq_b, 20, 'kg', 20.00,
            70.70, 1.0000, 90.72, 5, 2, now());
    raise exception 'CK-02 FAILED: effective load 0.02 kg off accepted';
  exception when check_violation then v_pass := v_pass + 1;
  end;

  -- CK-03, CK-16, CK-17: assisted one-arm sets (arithmetic only; CK-17 is caught by the Kotlin calibration test).
  insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
      set_index, side, load_mode, equipment_id, equipment_calibration_rev, assist_pin_native, assist_unit, assist_kg,
      bodyweight_kg, f_bw, effective_load_kg, reps, rir_reported, completed_at)
  values
    (now(), v_dev, 'dbtest', v_sess, v_se_oaa, v_ex_oaa, 1, 'L', 'assisted_machine', v_eq_m, 1, 40, 'lb', 24.64,
     70.70, 1, 46.06, 3, 1, now()),
    (now(), v_dev, 'dbtest', v_sess, v_se_oaa, v_ex_oaa, 2, 'L', 'assisted_machine', v_eq_m, 1, 50, 'lb', 29.18,
     70.70, 1, 41.52, 5, 2, now()),
    (now(), v_dev, 'dbtest', v_sess, v_se_oaa, v_ex_oaa, 3, 'L', 'assisted_machine', v_eq_m, 1, 40, 'lb', 18.14,
     70.70, 1, 52.56, 3, 1, now());
  v_pass := v_pass + 3;

  -- CK-04: assisted set without a machine.
  begin
    insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
        set_index, side, load_mode, assist_pin_native, assist_unit, assist_kg, bodyweight_kg, f_bw, effective_load_kg,
        reps, rir_reported, completed_at)
    values (now(), v_dev, 'dbtest', v_sess, v_se_oaa, v_ex_oaa, 4, 'L', 'assisted_machine', 40, 'lb', 24.64,
            70.70, 1, 46.06, 3, 1, now());
    raise exception 'CK-04 FAILED: assisted set without equipment accepted';
  exception when check_violation then v_pass := v_pass + 1;
  end;

  -- CK-05: finger assistance has no effective load.
  begin
    insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
        set_index, side, load_mode, assist_ordinal, bodyweight_kg, f_bw, effective_load_kg, reps, rir_reported, completed_at)
    values (now(), v_dev, 'dbtest', v_sess, v_se_oaa, v_ex_oaa, 5, 'L', 'assisted_ordinal', 3, 70.70, 1, 70.70, 3, 1, now());
    raise exception 'CK-05 FAILED: ordinal set with an effective load accepted';
  exception when check_violation then v_pass := v_pass + 1;
  end;

  -- CK-06: RIR in half steps only.
  begin
    insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
        set_index, load_mode, bodyweight_kg, f_bw, reps, rir_reported, completed_at)
    values (now(), v_dev, 'dbtest', v_sess, v_se_wpu, v_ex_wpu, 6, 'bodyweight', 70.70, 1, 3, 1.3, now());
    raise exception 'CK-06 FAILED: rir 1.3 accepted';
  exception when check_violation then v_pass := v_pass + 1;
  end;

  -- CK-07: one effort scale per set.
  begin
    insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
        set_index, load_mode, bodyweight_kg, f_bw, reps, rir_reported, hold_reserve_s, completed_at)
    values (now(), v_dev, 'dbtest', v_sess, v_se_wpu, v_ex_wpu, 7, 'bodyweight', 70.70, 1, 3, 1, 2, now());
    raise exception 'CK-07 FAILED: rir and hold reserve both accepted';
  exception when check_violation then v_pass := v_pass + 1;
  end;

  -- CK-08: a torso_rotation reason needs the flag.
  begin
    insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
        set_index, load_mode, bodyweight_kg, f_bw, reps, rir_reported, deviation_reason_code, completed_at)
    values (now(), v_dev, 'dbtest', v_sess, v_se_wpu, v_ex_wpu, 8, 'bodyweight', 70.70, 1, 3, 1,
            (select code from fitness.reason_codes where code = 'torso_rotation'), now());
    if not exists (select 1 from fitness.reason_codes where code = 'torso_rotation') then
      raise exception 'CK-08 SKIPPED-AS-FAILED: reason code torso_rotation not seeded';
    end if;
    raise exception 'CK-08 FAILED: torso_rotation reason without flag accepted';
  exception when check_violation then v_pass := v_pass + 1;
  end;

  -- CK-09: one live set per set key.
  begin
    insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
        set_index, load_mode, equipment_id, input_value, input_unit, added_kg, bodyweight_kg, f_bw, effective_load_kg,
        reps, rir_reported, completed_at)
    values (now(), v_dev, 'dbtest', v_sess, v_se_wpu, v_ex_wpu, 1, 'bodyweight_plus_external', v_eq_b, 22.5, 'kg', 22.5,
            70.70, 1, 93.20, 4, 2, now());
    raise exception 'CK-09 FAILED: duplicate set key accepted';
  exception when unique_violation then v_pass := v_pass + 1;
  end;

  -- CK-10: a second in-progress session.
  begin
    insert into fitness.workout_sessions (client_updated_at, device_id, app_version, status, template_source, local_date,
        timezone, iso_week, engine_version, params_hash, catalog_version, started_at)
    values (now(), v_dev, 'dbtest', 'in_progress', 'empty', '2026-10-09', 'Europe/Amsterdam', '2026-W41', '1.0.0',
            v_hash, '1.0.0', now()),
           (now(), v_dev, 'dbtest', 'in_progress', 'empty', '2026-10-09', 'Europe/Amsterdam', '2026-W41', '1.0.0',
            v_hash, '1.0.0', now());
    raise exception 'CK-10 FAILED: two in-progress sessions accepted';
  exception when unique_violation then v_pass := v_pass + 1;
  end;

  -- CK-11: an ignored calendar event carries no title.
  begin
    insert into fitness.calendar_events (client_updated_at, device_id, app_version, instance_key, source_device_id,
        classified_kind, title, starts_at, ends_at, local_date, timezone, iso_week, provider_status, classifier_version,
        match_method, last_seen_at)
    values (now(), v_dev, 'dbtest', repeat('d', 64), v_dev, 'ignored', 'Dentist', '2026-10-08T08:00:00Z',
            '2026-10-08T09:00:00Z', '2026-10-08', 'Europe/Amsterdam', '2026-W41', 'confirmed', '1.0.0', 'manual', now());
    raise exception 'CK-11 FAILED: ignored event with a title accepted';
  exception when check_violation then v_pass := v_pass + 1;
  end;

  -- CK-12: a gate value needs an enrollment.
  begin
    insert into fitness.metric_observations (client_updated_at, device_id, app_version, metric_code, value_num, unit,
        observed_at, local_date, source, valid_for_gate)
    values (now(), v_dev, 'dbtest', (select code from fitness.metric_defs where unit = 'kg' order by code limit 1),
            70.7, 'kg', now(), '2026-10-08', 'manual', true);
    raise exception 'CK-12 FAILED: gate value without enrollment accepted';
  exception when check_violation then v_pass := v_pass + 1;
  end;

  -- CK-13: weighted negative (effort chips, no RIR).
  insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
      set_index, load_mode, equipment_id, input_value, input_unit, added_kg, bodyweight_kg, f_bw, effective_load_kg,
      reps, ecc_s_target, ecc_s_actual, hold_reserve_s, flags, completed_at)
  values (now(), v_dev, 'dbtest', v_sess, v_se_neg, v_ex_neg, 1, 'bodyweight_plus_external', v_eq_b, 47.5, 'kg', 47.50,
          70.70, 1, 118.20, 2, 5, 6, 2, '{jerky}', now());
  v_pass := v_pass + 1;

  -- CK-14: 90-degree hold on the machine.
  insert into fitness.set_logs (client_updated_at, device_id, app_version, session_id, session_exercise_id, exercise_id,
      set_index, side, load_mode, equipment_id, equipment_calibration_rev, assist_pin_native, assist_unit, assist_kg,
      bodyweight_kg, f_bw, effective_load_kg, duration_s, hold_reserve_s, completed_at)
  values (now(), v_dev, 'dbtest', v_sess, v_se_h90, v_ex_h90, 1, 'L', 'assisted_machine', v_eq_m, 1, 35, 'lb', 22.38,
          70.70, 1, 48.32, 6.0, 2.0, now());
  v_pass := v_pass + 1;

  -- CK-15: profiles.id must equal user_id.
  begin
    insert into fitness.profiles (id, user_id, client_updated_at, device_id, app_version)
    values (gen_random_uuid(), v_a, now(), v_dev, 'dbtest');
    raise exception 'CK-15 FAILED: profile with id <> user_id accepted';
  exception when check_violation then v_pass := v_pass + 1;
  end;

  -- FK-10: erasing a session whose set is referenced by a wellness log needs the documented order (16.6).
  set local role postgres;
  begin
    delete from fitness.workout_sessions where id = v_sess;
    raise exception 'FK-10 FAILED: session deleted while a wellness log references it';
  exception when foreign_key_violation then v_pass := v_pass + 1;
  end;

  -- ================================================================================================
  -- 19.9 View sentinel tests (as postgres: the review views are owner-only, R-V2). Every assertion filters
  -- on fixture ids, so the owner's real rows on the live project never change a result.
  -- ================================================================================================

  -- V-09: exactly the 14.3 catalogue exists in fitness_review (26 views, nothing else).
  select count(*) into v_n
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'fitness_review' and c.relkind = 'v' and c.relname = any (v_views);
  assert v_n = cardinality(v_views),
    'V-09 FAILED: ' || v_n || ' of ' || cardinality(v_views) || ' review views exist (migration 017 applied?)';
  select count(*) into v_n
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'fitness_review' and not (c.relkind = 'v' and c.relname = any (v_views));
  assert v_n = 0, 'V-09 FAILED: ' || v_n || ' relations in fitness_review outside the 14.3 catalogue';
  v_pass := v_pass + 1;

  -- V-10: every review view is security_invoker (R-V1, 17.4 item V).
  select count(*) into v_n
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'fitness_review' and c.relkind = 'v'
    and not coalesce(c.reloptions @> array['security_invoker=true'], false);
  assert v_n = 0, 'V-10 FAILED: ' || v_n || ' review views without security_invoker = true';
  v_pass := v_pass + 1;

  -- V-11: no grants (R-V2, 17.4 item V): no ACL entry for anyone but the owner (PUBLIC included), and
  -- anon, authenticated and service_role have neither USAGE on the schema nor SELECT on a view.
  select count(*) into v_n
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  cross join lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
  where n.nspname = 'fitness_review' and a.grantee <> c.relowner;
  assert v_n = 0, 'V-11 FAILED: ' || v_n || ' ACL entries for non-owners on review views';
  select count(*) into v_n
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  cross join unnest(array['anon', 'authenticated', 'service_role']) as r(rolname)
  where n.nspname = 'fitness_review' and c.relkind = 'v'
    and (has_table_privilege(r.rolname, c.oid, 'SELECT')
         or has_schema_privilege(r.rolname, 'fitness_review', 'USAGE'));
  assert v_n = 0, 'V-11 FAILED: an API role can read a review view';
  v_pass := v_pass + 1;

  -- V-12: user_id is the first column of every user-scoped view; the two size views have none (R-V3).
  select count(*) into v_n
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  join pg_attribute a on a.attrelid = c.oid and a.attnum = 1
  where n.nspname = 'fitness_review' and c.relkind = 'v'
    and c.relname not in ('v_db_size', 'v_db_size_summary') and a.attname <> 'user_id';
  assert v_n = 0, 'V-12 FAILED: ' || v_n || ' user-scoped review views without user_id as first column';
  select count(*) into v_n from information_schema.columns
   where table_schema = 'fitness_review' and table_name in ('v_db_size', 'v_db_size_summary')
     and column_name = 'user_id';
  assert v_n = 0, 'V-12 FAILED: a size view exposes user_id';
  v_pass := v_pass + 1;

  -- V-13: every view answers over all rows the project holds (R-V8; catches runtime casts and joins).
  foreach v_t in array v_views loop
    execute format('select count(*) from fitness_review.%I', v_t) into v_n;
  end loop;
  v_pass := v_pass + 1;

  -- V-14: the size guard answers (13.2: v_db_size returns rows).
  select count(*) into v_n from fitness_review.v_db_size
   where schema_name = 'fitness' and table_name = 'set_logs' and total_bytes > 0;
  assert v_n = 1, 'V-14 FAILED: v_db_size has no row for fitness.set_logs';
  select count(*) into v_n from fitness_review.v_db_size
   where schema_name in ('fitness', 'fitness_private');
  assert v_n >= 38, 'V-14 FAILED: v_db_size returns only ' || v_n || ' tables';
  select count(*) into v_n from fitness_review.v_db_size_summary
   where status in ('ok', 'warn', 'critical') and db_mb > 0 and fitness_mb > 0 and measured_at = now();
  assert v_n = 1, 'V-14 FAILED: v_db_size_summary does not return exactly one valid row';
  v_pass := v_pass + 1;

  -- Sentinel SSE-1 snapshot for session A (19.9): deliberately implausible values for the wpu set.
  insert into fitness.recommendations (id, user_id, created_at, device_id, app_version, kind, module_id,
      module_version, engine_version, params_hash, catalog_version, inputs_hash, inputs, inputs_bytes, outputs,
      scope_key, session_id)
  values (v_sse1, v_a, now(), v_dev, 'dbtest', 'estimation.sse', 'sse', '1.0.0', '1.0.0', v_hash, '1.0.0',
          repeat('1', 64), '{}', 2,
          jsonb_build_object(
            'sets', jsonb_build_array(jsonb_build_object(
              'set_id', v_set1, 'estimator_class', 'rir', 'q_true', 2.5, 'n_eff', 6.5, 'n_fresh', 6.5,
              'fatigue_f', 1.0, 'z', 0.1, 's', 1.2, 'e1rm_kg', 123.45, 'e1rm_added_kg', 52.75, 'k_used', 31.7,
              'residual', 0.25)),
            'states', jsonb_build_array(jsonb_build_object(
              'exercise_id', v_ex_wpu, 'side', 'both', 'm_kg', 118.5, 'ci80_lo_kg', 112.0, 'ci80_hi_kg', 125.0,
              'g_per_week', 0.4))),
          'session:' || v_sess, v_sess);

  -- V-01: v_set_estimates returns the logged values unchanged (C3, R-V4).
  select count(*) into v_n from fitness_review.v_set_estimates
   where user_id = v_a and set_id = v_set1 and sse_recommendation_id = v_sse1
     and e1rm_kg = 123.45 and e1rm_added_kg = 52.75 and k_used = 31.7 and q_true = 2.5 and q_minus_reported = 0.5;
  assert v_n = 1, 'V-01 FAILED: v_set_estimates does not return the logged sentinel values';
  v_pass := v_pass + 1;

  -- V-02: the same values through aggregation, next to the logged state M.
  select count(*) into v_n from fitness_review.v_e1rm_history
   where user_id = v_a and exercise_id = v_ex_wpu and side = 'both' and local_date = '2026-10-08'
     and best_set_e1rm_kg = 123.45 and best_set_e1rm_added_kg = 52.75 and k_used_last = 31.7
     and state_m_kg = 118.5;
  assert v_n = 1, 'V-02 FAILED: v_e1rm_history does not return the logged sentinel values';
  v_pass := v_pass + 1;

  -- V-03: a newer snapshot listing the same set wins (R-V6); the set still appears once.
  insert into fitness.recommendations (id, user_id, created_at, device_id, app_version, kind, module_id,
      module_version, engine_version, params_hash, catalog_version, inputs_hash, inputs, inputs_bytes, outputs,
      scope_key, session_id)
  values (v_sse2, v_a, now() + interval '1 second', v_dev, 'dbtest', 'estimation.sse', 'sse', '1.0.0', '1.0.0',
          v_hash, '1.0.0', repeat('2', 64), '{}', 2,
          jsonb_build_object('sets', jsonb_build_array(jsonb_build_object(
            'set_id', v_set1, 'estimator_class', 'rir', 'q_true', 2.5, 'e1rm_kg', 124.00, 'e1rm_added_kg', 53.30,
            'k_used', 31.7))),
          'session:' || v_sess, v_sess);
  select count(*) into v_n from fitness_review.v_set_estimates
   where set_id = v_set1 and sse_recommendation_id = v_sse2 and e1rm_kg = 124.00;
  assert v_n = 1, 'V-03 FAILED: the newer estimation.sse snapshot was not selected';
  select count(*) into v_n from fitness_review.v_set_estimates where set_id = v_set1;
  assert v_n = 1, 'V-03 FAILED: the set appears ' || v_n || ' times in v_set_estimates';
  v_pass := v_pass + 1;

  -- V-04: tombstoning the set removes it from every set-level view (R-V5).
  update fitness.set_logs set deleted_at = now(), client_updated_at = '2026-10-08T18:00:01Z' where id = v_set1;
  select (select count(*) from fitness.set_logs where id = v_set1 and deleted_at is not null)
    into v_n;
  assert v_n = 1, 'V-04 FAILED: the tombstone was not stored';
  select (select count(*) from fitness_review.v_set_facts where set_id = v_set1)
       + (select count(*) from fitness_review.v_set_estimates where set_id = v_set1)
       + (select count(*) from fitness_review.v_e1rm_history
           where user_id = v_a and exercise_id = v_ex_wpu and local_date = '2026-10-08'
             and best_set_e1rm_kg in (123.45, 124.00))
    into v_n;
  assert v_n = 0, 'V-04 FAILED: a tombstoned set is still visible in a review view';
  v_pass := v_pass + 1;

  -- V-05: logged weekly volume and targets appear unchanged; no set credit is recomputed (C6, C14).
  insert into fitness.recommendations (id, user_id, created_at, device_id, app_version, kind, module_id,
      module_version, engine_version, params_hash, catalog_version, inputs_hash, inputs, inputs_bytes, outputs,
      scope_key, iso_week)
  values
    (v_acc, v_a, now(), v_dev, 'dbtest', 'accounting.week', 'setcredit', '1.0.0', '1.0.0', v_hash, '1.0.0',
     repeat('3', 64), '{}', 2,
     '{"groups":{"lats":{"vhyp_done":7.25,"vstr_done":3.5,"fatigue_done":2.0,"committed":0}},"sets":[]}',
     'week:2026-W41', '2026-W41'),
    (v_tgt, v_a, now(), v_dev, 'dbtest', 'targets.weekly', 'targets', '1.0.0', '1.0.0', v_hash, '1.0.0',
     repeat('4', 64), '{}', 2,
     '{"groups":{"lats":{"goal":"maintain","target":0,"mrv":20,"cap_s":12,"covered_by_challenge":true,"reentry":false,"deload":true}}}',
     'week:2026-W41', '2026-W41');
  select count(*) into v_n from fitness_review.v_weekly_muscle_volume
   where user_id = v_a and iso_week = '2026-W41' and group_id = 'lats'
     and accounting_id = v_acc and targets_id = v_tgt
     and vhyp_done = 7.25 and vstr_done = 3.5 and target = 0 and goal = 'maintain'
     and covered_by_challenge = 'true'::jsonb and deload = 'true'::jsonb
     and family = (select mg.family from fitness.muscle_groups mg where mg.id = 'lats');
  assert v_n = 1, 'V-05 FAILED: v_weekly_muscle_volume does not return the logged lats values';
  select count(*) into v_n from fitness_review.v_weekly_muscle_volume
   where user_id = v_a and iso_week = '2026-W41';
  assert v_n = 1, 'V-05 FAILED: v_weekly_muscle_volume invents ' || (v_n - 1) || ' groups';
  v_pass := v_pass + 1;

  -- V-06: free text in a telemetry payload is reported (DQ13, 16.3); a codes-only payload is not.
  insert into fitness.session_events (id, user_id, device_id, app_version, event_type, category, occurred_at,
      tz_offset_min, session_id, payload)
  values
    (v_ev_txt, v_a, v_dev, 'dbtest', 'set_logged', 'logging', '2026-10-08T18:02:00Z', 120, v_sess,
     '{"note":"felt bad today"}'),
    (v_ev_code, v_a, v_dev, 'dbtest', 'set_logged', 'logging', '2026-10-08T18:03:00Z', 120, v_sess,
     '{"set_index":2,"side":"both","input_method":"chip","deviation_fields":["reps","rir"]}');
  select count(*) into v_n from fitness_review.v_data_quality
   where check_code = 'DQ13' and severity = 'error' and user_id = v_a and entity_id = v_ev_txt::text;
  assert v_n = 1, 'V-06 FAILED: free text in a telemetry payload was not reported (DQ13)';
  select count(*) into v_n from fitness_review.v_data_quality
   where check_code = 'DQ13' and entity_id = v_ev_code::text;
  assert v_n = 0, 'V-06 FAILED: a codes-only payload was reported as free text';
  v_pass := v_pass + 1;

  -- V-07: a snapshot from an unregistered module version is reported (DQ01, 9.1.9) and flagged for replay;
  -- the registered sse@1.0.0 snapshots are not.
  insert into fitness.recommendations (id, user_id, created_at, device_id, app_version, kind, module_id,
      module_version, engine_version, params_hash, catalog_version, inputs_hash, inputs, inputs_bytes, outputs,
      scope_key)
  values (v_rv9, v_a, now(), v_dev, 'dbtest', 'today.options', 'today', '9.9.9', '1.0.0', v_hash, '1.0.0',
          repeat('5', 64), '{}', 2, '{"options":[]}', 'day:2026-10-08');
  select count(*) into v_n from fitness_review.v_data_quality
   where check_code = 'DQ01' and severity = 'warn' and user_id = v_a and entity = 'module_version'
     and entity_id = 'today@9.9.9' and (detail->>'count')::int = 1;
  assert v_n = 1, 'V-07 FAILED: the unregistered module version 9.9.9 was not reported (DQ01)';
  select count(*) into v_n from fitness_review.v_data_quality
   where check_code = 'DQ01' and user_id = v_a and entity_id = 'sse@1.0.0';
  assert v_n = 0, 'V-07 FAILED: the registered sse@1.0.0 was reported';
  select count(*) into v_n from fitness_review.v_replay_index
   where recommendation_id = v_rv9 and not version_registered and params_available and inputs_available;
  assert v_n = 1, 'V-07 FAILED: v_replay_index does not flag the unregistered version';
  v_pass := v_pass + 1;

  -- V-08: no personal free text (R-V7): v_set_facts has no deviation_note, v_data_quality has only its six
  -- columns, and no view reads a free-text column except the two named exceptions.
  select count(*) into v_n from information_schema.columns
   where table_schema = 'fitness_review' and table_name = 'v_set_facts' and column_name = 'deviation_note';
  assert v_n = 0, 'V-08 FAILED: v_set_facts exposes deviation_note';
  select string_agg(column_name::text, ',' order by ordinal_position) into v_txt
  from information_schema.columns where table_schema = 'fitness_review' and table_name = 'v_data_quality';
  assert v_txt = 'user_id,check_code,severity,entity,entity_id,detail',
    'V-08 FAILED: v_data_quality columns are ' || coalesce(v_txt, 'none');
  select count(*) into v_n from information_schema.columns
   where table_schema = 'fitness'
     and (table_name::text, column_name::text) in (
       ('workout_sessions', 'notes'), ('set_logs', 'deviation_note'), ('wellness_logs', 'note'),
       ('user_exercise_prefs', 'note'), ('gyms', 'address_note'), ('profiles', 'display_name'),
       ('user_algo_params', 'note'));
  assert v_n = 7, 'V-08 FAILED: the R-V7 column list is stale (' || v_n || ' of 7 columns exist)';
  select count(*) into v_n from information_schema.view_column_usage u
   where u.view_schema = 'fitness_review' and u.table_schema = 'fitness'
     and ((u.table_name::text, u.column_name::text) in (
            ('workout_sessions', 'notes'), ('set_logs', 'deviation_note'), ('wellness_logs', 'note'),
            ('user_exercise_prefs', 'note'), ('gyms', 'address_note'), ('profiles', 'display_name'),
            ('user_algo_params', 'note'))
          or (u.table_name::text = 'calendar_events' and u.column_name::text in ('title', 'location')
              and u.view_name::text <> 'v_calendar_events')
          or (u.table_name::text = 'metric_observations' and u.column_name::text = 'validity_note'
              and u.view_name::text <> 'v_challenge_tests'));
  assert v_n = 0, 'V-08 FAILED: ' || v_n || ' review view columns read personal free text (R-V7)';
  v_pass := v_pass + 1;

  -- Sentinel session on the DST day (19.8): one block with its session.plan, one done exercise, a working
  -- set, a warm-up set whose effective load the engine did not write, an sRPE answer, and two rest alarms
  -- in the repeated local hour (02:30 CEST and 02:30 CET) with a fixture-only platform_config_hash.
  insert into fitness.workout_sessions (id, user_id, client_updated_at, device_id, app_version, kind, status,
      template_source, local_date, timezone, iso_week, engine_version, params_hash, catalog_version, started_at,
      finished_at)
  values (v_s3, v_a, '2026-10-25T17:20:00Z', v_dev, 'dbtest', 'gym', 'finished', 'empty', '2026-10-25',
          'Europe/Amsterdam', '2026-W43', '1.0.0', v_hash, '1.0.0', '2026-10-25T16:15:00Z', '2026-10-25T17:20:00Z');
  insert into fitness.recommendations (id, user_id, created_at, device_id, app_version, kind, module_id,
      module_version, engine_version, params_hash, catalog_version, inputs_hash, inputs, inputs_bytes, outputs,
      scope_key, session_id)
  values (v_s3_plan, v_a, '2026-10-25T16:14:00Z', v_dev, 'dbtest', 'session.plan', 'select', '1.0.0', '1.0.0',
          v_hash, '1.0.0', repeat('6', 64), '{}', 2,
          jsonb_build_object('est_min', 42.5, 'budget_min', 45, 'exercises',
            jsonb_build_array(jsonb_build_object('exercise_id', v_ex_wpu, 'sets', 2, 'est_s', 600))),
          'block:' || v_s3_blk, v_s3);
  insert into fitness.session_blocks (id, user_id, client_updated_at, device_id, app_version, session_id, position,
      block_kind, origin, status, recommendation_id)
  values (v_s3_blk, v_a, '2026-10-25T16:15:00Z', v_dev, 'dbtest', v_s3, 0, 'general', 'user_built', 'done',
          v_s3_plan);
  insert into fitness.session_exercises (id, user_id, client_updated_at, device_id, app_version, session_id, block_id,
      exercise_id, position, origin, status, load_mode, side_mode, equipment_id, progression_mode, rep_scheme,
      started_at, completed_at)
  values (v_s3_se, v_a, '2026-10-25T16:32:00Z', v_dev, 'dbtest', v_s3, v_s3_blk, v_ex_wpu, 1, 'plan', 'done',
          'bodyweight_plus_external', 'bilateral', v_eq_b, 'none', 'fixed',
          '2026-10-25T16:20:00Z', '2026-10-25T16:32:00Z');
  insert into fitness.set_logs (id, user_id, client_updated_at, device_id, app_version, session_id,
      session_exercise_id, exercise_id, set_index, set_kind, load_mode, equipment_id, input_value, input_unit,
      added_kg, bodyweight_kg, f_bw, effective_load_kg, reps, rir_reported, rir_source, started_at, completed_at)
  values
    (v_s3_set2, v_a, '2026-10-25T16:25:00Z', v_dev, 'dbtest', v_s3, v_s3_se, v_ex_wpu, 1, 'warmup',
     'bodyweight_plus_external', v_eq_b, 10, 'kg', 10.00, 70.70, 1, null, 5, null, 'missing',
     '2026-10-25T16:24:00Z', '2026-10-25T16:25:00Z'),
    (v_s3_set1, v_a, '2026-10-25T16:32:00Z', v_dev, 'dbtest', v_s3, v_s3_se, v_ex_wpu, 2, 'working',
     'bodyweight_plus_external', v_eq_b, 20, 'kg', 20.00, 70.70, 1, 90.70, 5, 2, 'chip',
     '2026-10-25T16:31:00Z', '2026-10-25T16:32:00Z');
  insert into fitness.wellness_logs (id, user_id, client_updated_at, device_id, app_version, kind, phase,
      observed_at, local_date, session_id, srpe)
  values (v_s3_wl, v_a, '2026-10-25T17:21:00Z', v_dev, 'dbtest', 'srpe', 'post', '2026-10-25T17:21:00Z',
          '2026-10-25', v_s3, 7);
  insert into fitness.session_events (id, user_id, device_id, app_version, event_type, category, occurred_at,
      tz_offset_min, payload)
  values
    (v_s3_ev1, v_a, v_dev, 'dbtest', 'rest_alarm_fired', 'rest', '2026-10-25T00:30:00Z', 120,
     jsonb_build_object('latency_ms', 800, 'alarm_mode', 'alarm_clock', 'app_state', 'fgs_background',
                        'screen_interactive', false, 'platform_config_hash', v_pch)),
    (v_s3_ev2, v_a, v_dev, 'dbtest', 'rest_alarm_fired', 'rest', '2026-10-25T01:30:00Z', 60,
     jsonb_build_object('latency_ms', 1200, 'alarm_mode', 'alarm_clock', 'app_state', 'fgs_background',
                        'screen_interactive', false, 'platform_config_hash', v_pch));

  -- V-15: v_set_facts returns both live sets with their session, block and exercise context.
  select count(*) into v_n from fitness_review.v_set_facts where session_id = v_s3;
  assert v_n = 2, 'V-15 FAILED: v_set_facts returns ' || v_n || ' of 2 sentinel sets';
  select count(*) into v_n from fitness_review.v_set_facts
   where user_id = v_a and set_id = v_s3_set1 and session_exercise_id = v_s3_se and block_id = v_s3_blk
     and block_kind = 'general' and exercise_id = v_ex_wpu and exercise_slug = 'chinup_weighted'
     and session_kind = 'gym' and session_status = 'finished' and local_date = '2026-10-25'
     and iso_week = '2026-W43' and params_hash = v_hash and exercise_origin = 'plan' and exercise_status = 'done'
     and set_kind = 'working' and is_work_set and effective_load_kg = 90.70 and reps = 5 and rir_reported = 2;
  assert v_n = 1, 'V-15 FAILED: the working set context differs';
  select count(*) into v_n from fitness_review.v_set_facts
   where set_id = v_s3_set2 and set_kind = 'warmup' and not is_work_set and effective_load_kg is null
     and rir_source = 'missing';
  assert v_n = 1, 'V-15 FAILED: the warm-up set context differs';
  v_pass := v_pass + 1;

  -- V-16: v_session_summary: duration, block plan estimate, set counts and the sRPE answer.
  select count(*) into v_n from fitness_review.v_session_summary
   where user_id = v_a and session_id = v_s3 and local_date = '2026-10-25' and iso_week = '2026-W43'
     and kind = 'gym' and status = 'finished' and actual_min = 65.0
     and est_min_total = 42.5 and budget_min_total = 45
     and n_sets = 2 and n_work_sets = 1 and n_pain_flags = 0
     and srpe = 7 and readiness_level is null and elbow_score is null;
  assert v_n = 1, 'V-16 FAILED: v_session_summary does not summarise the sentinel session';
  v_pass := v_pass + 1;

  -- V-17: v_exercise_time: the TIME-1 estimate of the block plan next to the actual duration.
  select count(*) into v_n from fitness_review.v_exercise_time
   where user_id = v_a and session_exercise_id = v_s3_se and block_kind = 'general'
     and plan_kind = 'session.plan' and plan_version = '1.0.0' and plan_sets = 2 and est_s = 600
     and actual_s = 720 and n_sets = 2;
  assert v_n = 1, 'V-17 FAILED: v_exercise_time does not match the sentinel plan and timing';
  v_pass := v_pass + 1;

  -- V-18: both alarms of the repeated hour fall into 2026-W43 and one group (19.8): n 2, p50 1000 ms.
  select count(*) into v_n from fitness_review.v_rest_alarm_latency
   where user_id = v_a and platform_config_hash = v_pch and iso_week = '2026-W43'
     and app_state = 'fgs_background' and screen_interactive = false and alarm_mode = 'alarm_clock'
     and n = 2 and p50_ms = 1000 and round(p95_ms::numeric, 3) = 1180 and max_ms = 1200;
  assert v_n = 1, 'V-18 FAILED: v_rest_alarm_latency does not return n 2, p50 1000, p95 1180, max 1200 in W43';
  select count(*) into v_n from fitness_review.v_rest_alarm_latency where platform_config_hash = v_pch;
  assert v_n = 1, 'V-18 FAILED: the two alarms of the repeated hour were split into ' || v_n || ' groups';
  v_pass := v_pass + 1;

  -- V-19: v_data_quality reports exactly one finding on the sentinel rows: DQ06 for the warm-up set.
  select count(*), string_agg(check_code || ':' || entity_id, ',') into v_n, v_txt
  from fitness_review.v_data_quality
  where entity_id in (v_s3::text, v_s3_blk::text, v_s3_se::text, v_s3_set1::text, v_s3_set2::text,
                      v_s3_wl::text, v_s3_plan::text, v_s3_ev1::text, v_s3_ev2::text);
  assert v_n = 1 and v_txt = 'DQ06:' || v_s3_set2,
    'V-19 FAILED: findings on the sentinel session: ' || coalesce(v_txt, 'none');
  select count(*) into v_n from fitness_review.v_data_quality
   where user_id = v_a and check_code = 'DQ06' and severity = 'error' and entity = 'set'
     and entity_id = v_s3_set2::text and detail->>'load_mode' = 'bodyweight_plus_external';
  assert v_n = 1, 'V-19 FAILED: the DQ06 finding has the wrong shape';
  v_pass := v_pass + 1;

  -- V-20: tombstoning the session removes it and its sets from the session- and set-level views (R-V5).
  update fitness.workout_sessions set deleted_at = now(), client_updated_at = '2026-10-25T17:20:01Z'
   where id = v_s3;
  select (select count(*) from fitness_review.v_session_summary where session_id = v_s3)
       + (select count(*) from fitness_review.v_set_facts where session_id = v_s3)
       + (select count(*) from fitness_review.v_exercise_time where session_id = v_s3)
    into v_n;
  assert v_n = 0, 'V-20 FAILED: a tombstoned session is still visible in a review view';
  v_pass := v_pass + 1;

  raise exception 'FITNESS_DB_TESTS_PASSED % (%)', v_pass, case when v_local then 'local' else 'live' end;
end
$tests$;
