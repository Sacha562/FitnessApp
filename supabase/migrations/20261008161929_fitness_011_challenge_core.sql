-- fitness_011_challenge_core (M1a) — DATA_MODEL.md 9.4, 6.1, 7.5, 7.6, 7.8, 7.9, 8, 13.2
--
-- Challenge definitions (immutable once published, guarded by tg_challenge_definition_guard) and
-- enrollments (cached fold over challenge_events, LWW-REV through tg_enrollment_rev). The OAP definition
-- itself is inserted by fitness_016 (M2). The tables exist in M1a because sessions, wellness logs and
-- calendar events reference enrollments.
--
-- challenge_definitions: v1 has only global rows published by migrations; authenticated may only read
-- non-draft global rows (and, reserved for later, its own drafts). challenge_enrollments: USER template.

-- ---------------------------------------------------------------------------------------------------
-- 9.4.1 challenge_definitions
-- ---------------------------------------------------------------------------------------------------
create table fitness.challenge_definitions (
  id                 uuid primary key,
  user_id            uuid references auth.users (id) on delete cascade,
  slug               text not null check (slug ~ '^[a-z][a-z0-9_]{0,63}$'),
  version            text not null check (version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  status             text not null default 'draft' check (status in ('draft', 'published', 'retired')),
  schema_version     text not null check (schema_version ~ '^fitapp\.challenge/[0-9]+$'),
  engine_range       text not null check (length(engine_range) between 1 and 60),
  title_en           text not null check (length(title_en) between 1 and 120),
  title_nl           text check (title_nl is null or length(title_nl) between 1 and 120),
  definition         jsonb not null check (jsonb_typeof(definition) = 'object'),
  jcs_sha256         text not null check (jcs_sha256 ~ '^[0-9a-f]{64}$'),
  server_checksum    text not null check (server_checksum ~ '^[0-9a-f]{64}$'),
  supersedes_id      uuid references fitness.challenge_definitions (id),
  published_at       timestamptz,
  retired_at         timestamptz,
  created_at         timestamptz not null default now(),
  client_updated_at  timestamptz not null,
  server_updated_at  timestamptz not null default now(),
  device_id          uuid not null,
  app_version        text not null check (length(app_version) between 1 and 40),

  constraint challenge_definitions_version_uq unique nulls not distinct (user_id, slug, version),
  constraint challenge_definitions_pin_uq unique (id, slug, version, jcs_sha256)
);
create index challenge_definitions_slug_status_idx on fitness.challenge_definitions (slug, status);
create index challenge_definitions_sync_idx on fitness.challenge_definitions (server_updated_at);
create index challenge_definitions_user_idx on fitness.challenge_definitions (user_id);
create index challenge_definitions_supersedes_idx on fitness.challenge_definitions (supersedes_id);

alter table fitness.challenge_definitions enable row level security;
revoke all on table fitness.challenge_definitions from public, anon, authenticated;
grant select on table fitness.challenge_definitions to authenticated;
grant select, insert, update on table fitness.challenge_definitions to service_role;
create policy challenge_definitions_select on fitness.challenge_definitions for select to authenticated
  using ((select fitness_private.is_allowed())
         and (user_id is null or user_id = (select auth.uid()))
         and (status <> 'draft' or user_id = (select auth.uid())));
create trigger a_guard before insert or update on fitness.challenge_definitions
  for each row execute function fitness_private.tg_challenge_definition_guard();

-- ---------------------------------------------------------------------------------------------------
-- 9.4.2 challenge_enrollments (LWW-REV)
-- ---------------------------------------------------------------------------------------------------
create table fitness.challenge_enrollments (
  id                     uuid primary key default gen_random_uuid(),
  user_id                uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at             timestamptz not null default now(),
  client_updated_at      timestamptz not null,
  server_updated_at      timestamptz not null default now(),
  deleted_at             timestamptz,
  device_id              uuid not null,
  app_version            text not null check (length(app_version) between 1 and 40),

  definition_id          uuid not null,
  definition_slug        text not null check (definition_slug ~ '^[a-z][a-z0-9_]{0,63}$'),
  definition_version     text not null
                         check (definition_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  definition_jcs_sha256  text not null check (definition_jcs_sha256 ~ '^[0-9a-f]{64}$'),
  status                 text not null default 'onboarding'
                         check (status in ('onboarding', 'active', 'paused', 'completed', 'abandoned')),
  mode                   text check (mode is null or mode in ('normal', 'deload', 'test_pending', 'extra_deload', 'injury')),
  phase                  smallint check (phase is null or phase between 1 and 20),
  block_index            smallint check (block_index is null or block_index >= 1),
  block_week             smallint check (block_week is null or block_week between 1 and 10),
  week_ordinal           int check (week_ordinal is null or week_ordinal >= 0),
  params                 jsonb not null default '{}' check (jsonb_typeof(params) = 'object'),
  rule_decisions         jsonb not null default '{}' check (jsonb_typeof(rule_decisions) = 'object'),
  state                  jsonb not null default '{}'
                         check (jsonb_typeof(state) = 'object' and octet_length(state::text) <= 65536),
  state_rev              int not null default 0 check (state_rev >= 0),
  state_hash             text check (state_hash is null or state_hash ~ '^[0-9a-f]{64}$'),
  fold_generation        int not null default 0 check (fold_generation >= 0),
  last_folded_event_id   uuid,
  engine_version         text not null
                         check (engine_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  started_on             date,
  paused_at              timestamptz,
  completed_at           timestamptz,
  abandoned_at           timestamptz,

  constraint challenge_enrollments_id_user_uq unique (id, user_id),
  constraint challenge_enrollments_definition_fk
    foreign key (definition_id, definition_slug, definition_version, definition_jcs_sha256)
    references fitness.challenge_definitions (id, slug, version, jcs_sha256)
);
create unique index challenge_enrollments_one_open on fitness.challenge_enrollments (user_id, definition_slug)
  where status in ('onboarding', 'active', 'paused') and deleted_at is null;
create index challenge_enrollments_definition_idx on fitness.challenge_enrollments
  (definition_id, definition_slug, definition_version, definition_jcs_sha256);
create index challenge_enrollments_user_sync_idx on fitness.challenge_enrollments (user_id, server_updated_at);

alter table fitness.challenge_enrollments enable row level security;
revoke all on table fitness.challenge_enrollments from public, anon, authenticated;
grant select, insert, update on table fitness.challenge_enrollments to authenticated;
grant select, insert, update, delete on table fitness.challenge_enrollments to service_role;
create policy challenge_enrollments_select on fitness.challenge_enrollments for select to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create policy challenge_enrollments_insert on fitness.challenge_enrollments for insert to authenticated
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create policy challenge_enrollments_update on fitness.challenge_enrollments for update to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create trigger a_rev before insert or update on fitness.challenge_enrollments
  for each row execute function fitness_private.tg_enrollment_rev();

comment on table fitness.challenge_definitions is
  'Immutable versioned challenge definitions (CDS v1). Global rows are published by migrations only.';
comment on table fitness.challenge_enrollments is
  'Enrollment with a cached deterministic fold over challenge_events (state_rev = events folded).';

-- Manual rollback (only with explicit user approval), reverse order:
--   drop table fitness.challenge_enrollments; drop table fitness.challenge_definitions;
