-- fitness 068: restamp the user rows that 065 changed onto the migration device (046 rule). Data only.
--
-- 065 (D101) moved the owner's standing calf sets to the new calf_machine_standing row with the 30.4 kg base, made
-- each calf_machine row plates and inserted each user's calf_machine_standing row, but kept each row's own
-- device_id. The pull skips rows written by the pulling device, so:
--   * the owner's phone never pulled its 3 sets of 2026-11-07 (still 70 kg on the old stack row, base 0, effective
--     70 kg locally instead of 100.4): on 2026-11-10 the engine planned 70 kg system load, the card snapped down to
--     37.5 kg and "Last" showed 40 × 10 10;
--   * the second user's phone never pulled her seated calf row (plates) nor her new standing calf row.
-- Fix: the 10 rows still carrying 065's stamp get device_id = migration device and client_updated_at = now(), so
-- every phone pulls them (remote tuple newer -> overwrite). Values unchanged. Guarded: a re-run changes nothing.

do $$
declare
  v_mig   uuid        := 'ffffffff-ffff-ffff-ffff-ffffffffffff';
  v_stamp timestamptz := '2026-11-07 16:32:47.932275+00';
  v_n     int;
begin
  update fitness.set_logs
     set device_id = v_mig, client_updated_at = now()
   where app_version = 'migration' and client_updated_at = v_stamp and device_id <> v_mig;
  get diagnostics v_n = row_count;
  raise notice 'set_logs restamped: %', v_n;

  update fitness.gym_equipment
     set device_id = v_mig, client_updated_at = now()
   where app_version = 'migration' and client_updated_at = v_stamp and device_id <> v_mig;
  get diagnostics v_n = row_count;
  raise notice 'gym_equipment restamped: %', v_n;

  if exists (select 1 from fitness.set_logs where app_version = 'migration' and client_updated_at = v_stamp and device_id <> v_mig)
     or exists (select 1 from fitness.gym_equipment where app_version = 'migration' and client_updated_at = v_stamp and device_id <> v_mig) then
    raise exception '068: a 065 row is still off the migration device';
  end if;
end $$;
