-- fitness 067: repair the second user's session of 2026-11-08 after her phone clock was set back 24 h by hand
-- (about 21:41 Europe/Amsterdam, mid-session). Data only, no schema change.
--
-- Effects of the jump (session 01a1bcb4-5bcc-7792-87ff-0c8c9fee54a1):
--   * the 3 cable_crunch sets and their session_exercise carry completed_at / started_at on 2026-11-07;
--   * the finished session row was rejected by workout_sessions_times_ck (finished_at < started_at, 23514) and
--     dead-lettered, so the server row is still 'in_progress' with no finished_at;
--   * the rest columns of the 3 sets point at the stale lat-pulldown timer (144 s, rest_before_s 0.0).
--
-- Fix: shift the wall times +24 h, rebuild the rest columns from the logged instants (set_logged/rest_ended
-- payloads +24 h), and write the finish the phone computed (session_finished event: 15 of 22 working sets,
-- 2 exercises not done with reason 'other'). Every update is guarded so a re-run changes nothing.
-- device_id = migration device and client_updated_at = now() so the phone pulls the corrected rows.

do $$
declare
  v_user    uuid := (select user_id from fitness.workout_sessions where id = '01a1bcb4-5bcc-7792-87ff-0c8c9fee54a1');
  v_session uuid := '01a1bcb4-5bcc-7792-87ff-0c8c9fee54a1';
  v_mig     uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff';
  v_n       int;
begin
  -- 1. The three cable crunch sets: +24 h, rest rebuilt.
  --    set 1: previous set 21:40:07.982, first touch 21:44:54.402 -> 286.4 s; timer = the lat pulldown's 144 s,
  --           its alarm never fired (clock moved past it) -> elapsed_while_dead stays.
  --    set 2: previous 21:45:09.548, first touch 21:47:22.019 -> 132.5 s; own 72 s timer, alarm fired -> elapsed.
  --    set 3: previous 21:47:27.158, no first touch; completed 21:49:52.111 minus TIME-1 (10 reps x 3 s + 5 s = 35 s)
  --           -> 110.0 s; own 72 s timer, alarm fired -> elapsed.
  update fitness.set_logs s
     set completed_at        = s.completed_at + interval '1 day',
         started_at          = case when s.started_at is null then null else s.started_at + interval '1 day' end,
         rest_timer_planned_s = v.rtp,
         rest_before_s       = v.rb,
         rest_end_reason     = v.rer,
         device_id           = v_mig,
         client_updated_at   = now()
    from (values
      ('01a1bce3-cf80-780c-bf94-d419956e0d78'::uuid, 144, 286.4, 'elapsed_while_dead'),
      ('01a1bce3-cf80-7819-8522-37ae9831378c'::uuid,  72, 132.5, 'elapsed'),
      ('01a1bce3-cf80-7825-8e21-68c589455b7c'::uuid,  72, 110.0, 'elapsed')
    ) as v(id, rtp, rb, rer)
   where s.id = v.id and s.user_id = v_user and s.session_id = v_session
     and s.completed_at < '2026-11-08 00:00+00';
  get diagnostics v_n = row_count;
  raise notice 'set_logs shifted: %', v_n;

  -- 2. Their session_exercise (cable_crunch): started/completed +24 h.
  update fitness.session_exercises se
     set started_at        = se.started_at + interval '1 day',
         completed_at      = se.completed_at + interval '1 day',
         device_id         = v_mig,
         client_updated_at = now()
   where se.user_id = v_user and se.session_id = v_session
     and se.completed_at < '2026-11-08 00:00+00';
  get diagnostics v_n = row_count;
  raise notice 'session_exercises shifted: %', v_n;

  -- 3. The session: the finish the phone computed, at its real time (event time + 24 h).
  update fitness.workout_sessions ws
     set status            = 'finished',
         finished_at       = (select e.occurred_at + interval '1 day'
                                from fitness.session_events e
                               where e.user_id = v_user and e.session_id = v_session
                                 and e.event_type = 'session_finished'
                               order by e.occurred_at desc limit 1),
         completion_ratio  = round(15.0 / 22.0, 4),
         end_reason_code   = 'other',
         device_id         = v_mig,
         client_updated_at = now()
   where ws.id = v_session and ws.user_id = v_user and ws.status = 'in_progress';
  get diagnostics v_n = row_count;
  raise notice 'workout_sessions finished: %', v_n;
end $$;
