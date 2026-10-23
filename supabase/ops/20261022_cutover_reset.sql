-- Cutover reset (M2b.6, D40): removes the build's test data of the one user before the real OAP import.
--
-- Run once through the Supabase MCP `execute_sql`, after the user has seen this file. Only the user's rows in the
-- `fitness` schema; nothing in other schemas. Kept: profile, gym, gym equipment, exercise preferences, rep
-- preferences, muscle goals, custom exercises, tuned params and the allowlist. The phone is cleared afterwards
-- (`pm clear`), so it bootstraps the kept rows from the server and has no outbox left to re-send the deleted ones.
-- Children before parents (foreign keys, DATA_MODEL 9); one statement, so it all happens or nothing does.

do $$
declare
  uid constant uuid := '39fca078-e316-4932-9012-b4ae72ec79c2';
  n int;
  report text := '';
begin
  if (select count(*) from fitness.profiles) <> 1 or not exists (select 1 from fitness.profiles where user_id = uid) then
    raise exception 'reset: expected exactly the one user %', uid;
  end if;
  delete from fitness.challenge_rule_events where user_id = uid; get diagnostics n = row_count; report := report || ' challenge_rule_events ' || n;
  delete from fitness.calendar_events       where user_id = uid; get diagnostics n = row_count; report := report || ', calendar_events ' || n;
  delete from fitness.challenge_events      where user_id = uid; get diagnostics n = row_count; report := report || ', challenge_events ' || n;
  delete from fitness.metric_observations   where user_id = uid; get diagnostics n = row_count; report := report || ', metric_observations ' || n;
  delete from fitness.session_events        where user_id = uid; get diagnostics n = row_count; report := report || ', session_events ' || n;
  delete from fitness.wellness_logs         where user_id = uid; get diagnostics n = row_count; report := report || ', wellness_logs ' || n;
  delete from fitness.set_logs              where user_id = uid; get diagnostics n = row_count; report := report || ', set_logs ' || n;
  delete from fitness.session_exercises     where user_id = uid; get diagnostics n = row_count; report := report || ', session_exercises ' || n;
  delete from fitness.session_blocks        where user_id = uid; get diagnostics n = row_count; report := report || ', session_blocks ' || n;
  delete from fitness.workout_sessions      where user_id = uid; get diagnostics n = row_count; report := report || ', workout_sessions ' || n;
  delete from fitness.challenge_enrollments where user_id = uid; get diagnostics n = row_count; report := report || ', challenge_enrollments ' || n;
  delete from fitness.recommendations       where user_id = uid; get diagnostics n = row_count; report := report || ', recommendations ' || n;
  delete from fitness.params_snapshots      where user_id = uid; get diagnostics n = row_count; report := report || ', params_snapshots ' || n;
  delete from fitness.exercise_model_state  where user_id = uid; get diagnostics n = row_count; report := report || ', exercise_model_state ' || n;
  delete from fitness.exports               where user_id = uid; get diagnostics n = row_count; report := report || ', exports ' || n;
  raise notice 'reset:%', report;
end;
$$;
