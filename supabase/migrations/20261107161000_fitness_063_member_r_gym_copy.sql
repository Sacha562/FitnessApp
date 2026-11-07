-- fitness_063_member_r_gym_copy (data) — DATA_MODEL.md 9.3.2, 9.3.3; D3
--
-- No DDL. The member allowlisted by 062 trains at the owner's gym (the owner, 2026-11-07: "set up the equipment so
-- that it's the same as my equipment"). Copies the owner's default gym (Basic-Fit Amersfoort Ierse Pond: name,
-- location keywords, opening hours) and its gym-bound stations (type, label, available, load scheme, calibration
-- rev and source) to the member, with new ids. Portable items (gym_id null: the owner's dip belt and chalk) are the
-- owner's own and are not copied. confirmed_at stays null: the member's onboarding pulls these rows first and shows
-- them as the "Your gym" checklist, where "Confirm equipment" confirms them (EquipmentRepository.saveChecklist reuses
-- the gym and the rows by type). Rows carry the migration device and a fresh client_updated_at so the phone pulls
-- them (046). Refuses to run when the member already has a gym or equipment row.
--
-- Manual rollback (only with explicit user approval):
--   delete from fitness.gym_equipment where user_id = '222ce484-2b7a-465a-b3fd-af26032f4cd7' and app_version = 'migration-063';
--   delete from fitness.gyms where user_id = '222ce484-2b7a-465a-b3fd-af26032f4cd7' and app_version = 'migration-063';

do $$
declare
  owner_id  constant uuid := '39fca078-e316-4932-9012-b4ae72ec79c2';
  member_id constant uuid := '222ce484-2b7a-465a-b3fd-af26032f4cd7';
  mig_dev   constant uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff';
  src_gym   uuid;
  new_gym   uuid := gen_random_uuid();
  n         int;
begin
  if not exists (select 1 from fitness_private.allowed_users where user_id = member_id) then
    raise exception '063: the member is not allowlisted (062 missing)';
  end if;
  if exists (select 1 from fitness.gyms where user_id = member_id)
     or exists (select 1 from fitness.gym_equipment where user_id = member_id) then
    raise exception '063: the member already has gym rows; nothing copied';
  end if;
  select id into src_gym from fitness.gyms
   where user_id = owner_id and is_default and deleted_at is null;
  if src_gym is null then
    raise exception '063: the owner has no default gym';
  end if;

  insert into fitness.gyms (id, user_id, client_updated_at, device_id, app_version,
                            name, location_keywords, address_note, opening_hours, is_default)
  select new_gym, member_id, now(), mig_dev, 'migration-063',
         g.name, g.location_keywords, g.address_note, g.opening_hours, true
    from fitness.gyms g where g.id = src_gym;

  insert into fitness.gym_equipment (id, user_id, client_updated_at, device_id, app_version, gym_id,
                                     equipment_type, label, available, scheme_type, load_scheme,
                                     calibration_rev, calibration_source, confirmed_at)
  select gen_random_uuid(), member_id, now(), mig_dev, 'migration-063', new_gym,
         e.equipment_type, e.label, e.available, e.scheme_type, e.load_scheme,
         e.calibration_rev, e.calibration_source, null
    from fitness.gym_equipment e
   where e.user_id = owner_id and e.gym_id = src_gym and e.deleted_at is null;
  get diagnostics n = row_count;
  if n = 0 then
    raise exception '063: no owner equipment copied';
  end if;
  raise notice '063: copied 1 gym and % stations', n;
end $$;
