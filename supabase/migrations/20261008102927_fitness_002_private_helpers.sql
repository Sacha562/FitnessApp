-- fitness_002_private_helpers (M0) — DATA_MODEL.md 5.2, 7.1-7.7, 4.3, 13.2
--
-- The allowlist table, the is_allowed() helper used by every RLS policy, and the trigger functions the
-- later table migrations attach. Every function has search_path = '' and fully qualified names, and its
-- built-in EXECUTE-to-PUBLIC grant is revoked (4.3). Trigger functions get no EXECUTE grant at all.
-- No trigger is created on auth.users.

-- ---------------------------------------------------------------------------------------------------
-- Allowlist (5.2). RLS is off on purpose: no grants for anon/authenticated and the schema is unexposed.
-- ---------------------------------------------------------------------------------------------------
create table fitness_private.allowed_users (
  user_id   uuid primary key references auth.users (id) on delete cascade,
  role      text not null default 'member' check (role in ('owner', 'member')),
  added_at  timestamptz not null default now(),
  note      text check (note is null or length(note) <= 200)
);
create unique index allowed_users_one_owner on fitness_private.allowed_users (role) where role = 'owner';
revoke all on table fitness_private.allowed_users from public, anon, authenticated;
grant select on table fitness_private.allowed_users to service_role;
comment on table fitness_private.allowed_users is
  'FitnessApp allowlist (D3). Adding a user = one row. Invisible to the app.';

create function fitness_private.is_allowed()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from fitness_private.allowed_users a
    where a.user_id = (select auth.uid())
  );
$$;
revoke all on function fitness_private.is_allowed() from public;
grant execute on function fitness_private.is_allowed() to authenticated, service_role;

-- ---------------------------------------------------------------------------------------------------
-- 7.1 Sync trigger for mutable rows (C23): last writer wins on (client_updated_at, device_id).
-- ---------------------------------------------------------------------------------------------------
create function fitness_private.tg_sync_row()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    new.server_updated_at := now();
    return new;
  end if;

  -- UPDATE (including the DO UPDATE branch of an upsert). An equal or older tuple is a duplicate or a
  -- stale write: keep the stored row silently.
  if (new.client_updated_at, new.device_id) <= (old.client_updated_at, old.device_id) then
    return null;
  end if;

  -- Immutable columns.
  new.id         := old.id;
  new.user_id    := old.user_id;
  new.created_at := old.created_at;

  new.server_updated_at := now();
  return new;
end;
$$;
revoke all on function fitness_private.tg_sync_row() from public;

-- ---------------------------------------------------------------------------------------------------
-- 7.2 Append-only trigger.
-- ---------------------------------------------------------------------------------------------------
create function fitness_private.tg_append_only()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    new.server_updated_at := now();
    return new;
  end if;
  -- Only the owner role (retention pruning, DATA_MODEL 15) may update an append-only row.
  if current_user = 'postgres' then
    return new;
  end if;
  raise exception 'fitness.%: rows are append-only', tg_table_name using errcode = '42501';
end;
$$;
revoke all on function fitness_private.tg_append_only() from public;

-- ---------------------------------------------------------------------------------------------------
-- 7.3 Reference-data touch trigger.
-- ---------------------------------------------------------------------------------------------------
create function fitness_private.tg_touch_reference()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;
revoke all on function fitness_private.tg_touch_reference() from public;

-- ---------------------------------------------------------------------------------------------------
-- 7.4 Exercise visibility check (fitness.exercises arrives with the catalog migration). Invoker rights:
-- runs under the caller's RLS and also checks ownership for roles that bypass RLS.
-- ---------------------------------------------------------------------------------------------------
create function fitness_private.tg_check_exercise_visible()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.exercise_id is null then
    return new;
  end if;
  if not exists (
    select 1
    from fitness.exercises e
    where e.id = new.exercise_id
      and (e.user_id is null or e.user_id = new.user_id)
  ) then
    raise exception 'exercise % is not visible to this user', new.exercise_id
      using errcode = '23503';   -- same code as an FK violation, so the client handles it alike
  end if;
  return new;
end;
$$;
revoke all on function fitness_private.tg_check_exercise_visible() from public;

-- ---------------------------------------------------------------------------------------------------
-- 7.5 Enrollment revision trigger (LWW-REV).
-- ---------------------------------------------------------------------------------------------------
create function fitness_private.tg_enrollment_rev()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    new.server_updated_at := now();
    return new;
  end if;
  -- The cached state is a fold over challenge_events; state_rev = number of events folded.
  if new.state_rev < old.state_rev then
    return null;                      -- the server already holds a later fold
  end if;
  if new.state_rev = old.state_rev
     and (new.client_updated_at, new.device_id) <= (old.client_updated_at, old.device_id) then
    return null;                      -- same fold: plain last-writer-wins
  end if;
  new.id := old.id;
  new.user_id := old.user_id;
  new.created_at := old.created_at;
  new.server_updated_at := now();
  return new;
end;
$$;
revoke all on function fitness_private.tg_enrollment_rev() from public;

-- ---------------------------------------------------------------------------------------------------
-- 7.6 Challenge definition guard and server checksum.
-- ---------------------------------------------------------------------------------------------------
create function fitness_private.tg_challenge_definition_guard()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' and old.status in ('published', 'retired') then
    if new.definition is distinct from old.definition
       or new.slug is distinct from old.slug
       or new.version is distinct from old.version
       or new.jcs_sha256 is distinct from old.jcs_sha256
       or new.schema_version is distinct from old.schema_version then
      raise exception 'challenge definition %@% is immutable once published', old.slug, old.version
        using errcode = '55000';
    end if;
    if old.status = 'retired' and new.status <> 'retired' then
      raise exception 'a retired challenge definition cannot be reactivated' using errcode = '55000';
    end if;
    if old.status = 'published' and new.status not in ('published', 'retired') then
      raise exception 'a published challenge definition can only be retired' using errcode = '55000';
    end if;
  end if;
  -- Server checksum: SHA-256 of the canonical jsonb text. Not RFC 8785; never compared with jcs_sha256.
  new.server_checksum := encode(sha256(convert_to(new.definition::text, 'UTF8')), 'hex');
  if new.status = 'published' and new.published_at is null then
    new.published_at := now();
  end if;
  if new.status = 'retired' and new.retired_at is null then
    new.retired_at := now();
  end if;
  new.server_updated_at := now();
  return new;
end;
$$;
revoke all on function fitness_private.tg_challenge_definition_guard() from public;

-- ---------------------------------------------------------------------------------------------------
-- 7.7 Symptom-code array check (arrays cannot carry foreign keys).
-- ---------------------------------------------------------------------------------------------------
create function fitness_private.tg_check_symptom_codes()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if exists (
    select 1 from unnest(new.symptom_codes) as s(code)
    where not exists (select 1 from fitness.symptom_codes c where c.code = s.code)
  ) then
    raise exception 'unknown symptom code in %', new.symptom_codes using errcode = '23503';
  end if;
  return new;
end;
$$;
revoke all on function fitness_private.tg_check_symptom_codes() from public;

-- Manual rollback (only with explicit user approval), reverse order:
--   drop function fitness_private.tg_check_symptom_codes(); drop function fitness_private.tg_challenge_definition_guard();
--   drop function fitness_private.tg_enrollment_rev(); drop function fitness_private.tg_check_exercise_visible();
--   drop function fitness_private.tg_touch_reference(); drop function fitness_private.tg_append_only();
--   drop function fitness_private.tg_sync_row(); drop function fitness_private.is_allowed();
--   drop table fitness_private.allowed_users;
