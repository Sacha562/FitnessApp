-- fitness_046_restamp_migrated_rows (M4, data): make the phone pull the rows that migration 045 changed.
--
-- No DDL. The pull skips rows whose device_id is the pulling device (DATA_MODEL 11, echo suppression). Migration 045
-- changed user rows but kept the phone's device_id, so the phone never pulled them: the 1 Nov leg press, back
-- extension and T-bar sets and the sled row (gym_equipment leg_press, rev 5). They get the migration device
-- (ffffffff-...) and a fresh client_updated_at, which wins the phone's LWW merge. (041's soft-deleted preference was
-- written by an earlier device id, so the current phone already pulls it.) Rule for later data migrations that change user rows: set device_id to the migration device.
-- Rollback: none needed (only device_id and client_updated_at change).

update fitness.set_logs
set device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, client_updated_at = now()
where app_version = 'migration' and session_id = '01a198bf-2566-71dc-a23d-6a73a3573b3f'::uuid
  and device_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid;

update fitness.gym_equipment
set device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, client_updated_at = now()
where id = '01a11fea-12e8-75bf-8c4f-f044bc36240d'::uuid and app_version = 'migration' and calibration_rev = 5;

do $$
begin
  if (select count(*) from fitness.set_logs where session_id = '01a198bf-2566-71dc-a23d-6a73a3573b3f'::uuid
      and app_version = 'migration' and device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid) <> 11 then
    raise exception '046: expected the 11 rewritten sets of 1 Nov on the migration device';
  end if;
  if not exists (select 1 from fitness.gym_equipment where id = '01a11fea-12e8-75bf-8c4f-f044bc36240d'::uuid
                 and device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid) then
    raise exception '046: the sled row is not on the migration device';
  end if;
end;
$$;
