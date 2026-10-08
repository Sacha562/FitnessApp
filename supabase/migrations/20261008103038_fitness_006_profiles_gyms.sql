-- fitness_006_profiles_gyms (M0) — DATA_MODEL.md 9.3.1, 9.3.2, 6.1 (class USER), 6.2 (S-MUT), 7.1, 7.8, 7.9
--
-- The first user-owned tables, pulled forward from the user-config migration so that M0 can prove the
-- write path: the app upserts the owner's profile on first login (never a trigger on auth.users), which
-- round-trips through RLS (allowlist + owner) and the LWW trigger. gyms comes along because
-- profiles.default_gym_id references it with a composite foreign key.
--
-- Class USER: authenticated may select/insert/update own rows when allowlisted; no DELETE grant (soft
-- delete via deleted_at); anon gets nothing.

-- ---------------------------------------------------------------------------------------------------
-- 9.3.2 gyms
-- ---------------------------------------------------------------------------------------------------
create table fitness.gyms (
  id                 uuid primary key default gen_random_uuid(),
  user_id            uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at         timestamptz not null default now(),
  client_updated_at  timestamptz not null,
  server_updated_at  timestamptz not null default now(),
  deleted_at         timestamptz,
  device_id          uuid not null,
  app_version        text not null check (length(app_version) between 1 and 40),

  name               text not null check (length(name) between 1 and 80),
  location_keywords  text[] not null default '{}',
  address_note       text check (address_note is null or length(address_note) <= 200),
  opening_hours      jsonb not null default '{}' check (jsonb_typeof(opening_hours) = 'object'),
  is_default         boolean not null default false,

  constraint gyms_id_user_uq unique (id, user_id)
);
create index gyms_user_sync_idx on fitness.gyms (user_id, server_updated_at);
create unique index gyms_one_default on fitness.gyms (user_id) where is_default and deleted_at is null;

-- ---------------------------------------------------------------------------------------------------
-- 9.3.1 profiles (id = user_id; created lazily by the app on first login)
-- ---------------------------------------------------------------------------------------------------
create table fitness.profiles (
  id                             uuid primary key default gen_random_uuid(),
  user_id                        uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at                     timestamptz not null default now(),
  client_updated_at              timestamptz not null,
  server_updated_at              timestamptz not null default now(),
  deleted_at                     timestamptz,
  device_id                      uuid not null,
  app_version                    text not null check (length(app_version) between 1 and 40),

  display_name                   text check (display_name is null or length(display_name) between 1 and 60),
  timezone                       text not null default 'Europe/Amsterdam'
                                 check (timezone ~ '^[A-Za-z_]+(/[A-Za-z0-9_+-]+)*$'),
  week_start_dow                 smallint not null default 1 check (week_start_dow = 1),
  mass_unit                      text not null default 'kg' check (mass_unit in ('kg', 'lb')),
  ui_language                    text not null default 'en' check (ui_language in ('en', 'nl')),
  decimal_separator              text not null default '.' check (decimal_separator in ('.', ',')),
  sex                            text check (sex is null or sex in ('female', 'male', 'other', 'unspecified')),
  birth_year                     smallint check (birth_year is null or birth_year between 1900 and 2100),
  training_age                   text check (training_age is null or training_age in ('novice', 'intermediate', 'advanced')),
  bodyweight_fallback_kg         numeric(5,2) check (bodyweight_fallback_kg is null or bodyweight_fallback_kg between 20 and 300),
  max_gym_visits_per_week        smallint not null default 4 check (max_gym_visits_per_week between 1 and 7),
  default_session_min            smallint not null default 75 check (default_session_min between 20 and 240),
  changeover_min                 smallint not null default 5 check (changeover_min between 0 and 60),
  availability                   jsonb not null default '{}' check (jsonb_typeof(availability) = 'object'),
  default_gym_id                 uuid,
  calendar_read_enabled          boolean not null default true,
  calendar_scope                 text not null default 'primary_only' check (calendar_scope in ('primary_only', 'selected')),
  calendar_selected_keys         text[] not null default '{}'
                                 check (array_to_string(calendar_selected_keys, ',') ~ '^([0-9a-f]{64}(,|$))*$'),
  calendar_ignore_all_day        boolean not null default true,
  calendar_ignore_name_patterns  text[] not null default '{"(?i)todoist"}',
  calendar_write_enabled         boolean not null default true,
  calendar_write_weeks           smallint not null default 2 check (calendar_write_weeks between 1 and 4),
  evidence_mode                  text not null default 'warn_only'
                                 check (evidence_mode in ('warn_only', 'auto_adjust_with_warning')),
  running_priority               text not null default 'low' check (running_priority in ('low', 'medium', 'high')),
  deload_sync_with_challenge     boolean not null default true,
  allow_supersets                boolean not null default false,
  rir_calibration_opt_in         boolean not null default false,
  prompt_readiness               boolean not null default true,
  prompt_elbow_check             boolean not null default true,
  prompt_srpe                    boolean not null default true,
  prompt_soreness                boolean not null default true,
  prompt_pain_after              boolean not null default true,
  rest_alarm_mode                text not null default 'alarm_clock' check (rest_alarm_mode in ('alarm_clock', 'exact_idle')),
  rest_tone_enabled              boolean not null default false,
  keep_screen_on                 boolean not null default true,
  theme                          text not null default 'system' check (theme in ('system', 'light', 'dark')),
  dynamic_color                  boolean not null default false,
  reminders_enabled              boolean not null default true,
  muscle_goal_preset             text check (muscle_goal_preset is null or muscle_goal_preset in (
                                   'legs_only', 'no_legs', 'pull_focus', 'balanced', 'custom')),
  disclaimer_accepted_at         timestamptz,
  onboarding_completed_at        timestamptz,

  constraint profiles_id_is_user_ck check (id = user_id),
  constraint profiles_id_user_uq unique (id, user_id),
  constraint profiles_default_gym_fk foreign key (default_gym_id, user_id) references fitness.gyms (id, user_id)
);
create index profiles_user_sync_idx on fitness.profiles (user_id, server_updated_at);
create index profiles_default_gym_fk_idx on fitness.profiles (default_gym_id, user_id);

-- ---------------------------------------------------------------------------------------------------
-- RLS, grants, policies (USER template 7.8) and the LWW trigger (7.1).
-- ---------------------------------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array['gyms', 'profiles'] loop
    execute format('alter table fitness.%I enable row level security', t);
    execute format('revoke all on table fitness.%I from public, anon, authenticated', t);
    execute format('grant select, insert, update on table fitness.%I to authenticated', t);
    execute format('grant select, insert, update, delete on table fitness.%I to service_role', t);
    execute format(
      'create policy %I on fitness.%I for select to authenticated '
      'using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))',
      t || '_select', t);
    execute format(
      'create policy %I on fitness.%I for insert to authenticated '
      'with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))',
      t || '_insert', t);
    execute format(
      'create policy %I on fitness.%I for update to authenticated '
      'using ((select fitness_private.is_allowed()) and user_id = (select auth.uid())) '
      'with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))',
      t || '_update', t);
    execute format(
      'create trigger a_sync before insert or update on fitness.%I '
      'for each row execute function fitness_private.tg_sync_row()', t);
  end loop;
end;
$$;

comment on table fitness.profiles is 'One row per user (id = user_id), created by the app on first login.';
comment on table fitness.gyms is 'User gyms; location keywords feed the calendar classifier.';

-- Manual rollback (only with explicit user approval):
--   drop table fitness.profiles; drop table fitness.gyms;
