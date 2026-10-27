-- fitness_037_rest_multiplier_engine_1_9_0 (D50, DDL and data): rep_preferences.rest_pref_mult, rest@1.2.0 and rx@1.1.0
-- of ENGINE_VERSION 1.9.0, `preset_id` on rep_preference_changed - DATA_MODEL.md 9.3.4, 13.2; D50
--
-- (1) rep_preferences.rest_pref_mult numeric(3,2), 0.50-2.00: a multiplier of REST-1's class base, offered for the
--     broader scopes (an exercise override keeps seconds). rep_preferences_something_ck counts it; the new
--     rep_preferences_rest_one_ck allows at most one of rest_pref_s and rest_pref_mult. The table-level grants to
--     authenticated cover the new column; no grant changes.
-- (2) rest@1.2.0 (the multiplier) and rx@1.1.0 (rest resolves as one field; its inputs carry rest_pref_mult) for
--     engine 1.9.0. Their params slices are unchanged, so they keep the hashes of their earlier rows.
-- (3) The registry row of rep_preference_changed gains the payload key preset_id.
-- No grant, policy, function, trigger, role or extension. Idempotent.
-- Manual rollback (only with explicit user approval): drop rep_preferences_rest_one_ck, restore the old
-- rep_preferences_something_ck, drop the column once no row uses it, delete the two module rows, drop the payload key.

alter table fitness.rep_preferences
  add column if not exists rest_pref_mult numeric(3,2)
  constraint rep_preferences_rest_pref_mult_check check (rest_pref_mult is null or rest_pref_mult between 0.5 and 2.0);

alter table fitness.rep_preferences drop constraint if exists rep_preferences_something_ck;
alter table fitness.rep_preferences add constraint rep_preferences_something_ck check (
  num_nonnulls(sets_pref, rep_min, rir_target, rep_scheme, rest_pref_s, rest_pref_mult) > 0);

alter table fitness.rep_preferences drop constraint if exists rep_preferences_rest_one_ck;
alter table fitness.rep_preferences add constraint rep_preferences_rest_one_ck check (
  num_nonnulls(rest_pref_s, rest_pref_mult) <= 1);

comment on column fitness.rep_preferences.rest_pref_mult is
  'Multiplier of the REST-1 class base (D50); at most one of rest_pref_s and rest_pref_mult per row.';

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
values
  ('rest', '1.2.0', 'prescription', '1.9.0', 'active', '1.0.2', 'e87250d03e2bc2d024ee6f27ad367627c0ba24aea6fa7f7a1f00839099249f2c',
   'docs/ALGORITHMS.md section B11', 'D50: the rest multiplier of the broader preference scopes scales the class base.'),
  ('rx', '1.1.0', 'prescription', '1.9.0', 'active', '1.0.2', '048f62b1394aa44f3da7021ccf95b11c0c2df6ed2c87681402d41947f23816b9',
   'docs/ALGORITHMS.md section B9', 'D50: rest resolves as one field in two forms; the inputs carry rest_pref_mult.')
on conflict (module_id, version) do nothing;

update fitness.session_event_types
   set payload_keys = payload_keys || array['preset_id']::text[]
 where event_type = 'rep_preference_changed' and not ('preset_id' = any (payload_keys));

do $$
begin
  if not exists (select 1 from information_schema.columns
                  where table_schema = 'fitness' and table_name = 'rep_preferences' and column_name = 'rest_pref_mult'
                    and data_type = 'numeric' and numeric_precision = 3 and numeric_scale = 2) then
    raise exception '037: rep_preferences.rest_pref_mult missing or wrong type';
  end if;
  if (select count(*) from pg_constraint
       where conrelid = 'fitness.rep_preferences'::regclass
         and conname in ('rep_preferences_something_ck', 'rep_preferences_rest_one_ck', 'rep_preferences_rest_pref_mult_check')) <> 3 then
    raise exception '037: expected the three rest_pref_mult constraints';
  end if;
  if pg_get_constraintdef((select oid from pg_constraint
                            where conrelid = 'fitness.rep_preferences'::regclass and conname = 'rep_preferences_something_ck'))
     not like '%rest_pref_mult%' then
    raise exception '037: rep_preferences_something_ck does not count rest_pref_mult';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.9.0') <> 2 then
    raise exception '037: expected 2 module versions for engine 1.9.0';
  end if;
  if not exists (select 1 from fitness.session_event_types
                  where event_type = 'rep_preference_changed' and 'preset_id' = any (payload_keys)) then
    raise exception '037: rep_preference_changed lacks preset_id';
  end if;
end;
$$;
