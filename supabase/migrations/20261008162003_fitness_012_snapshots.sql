-- fitness_012_snapshots (M1a) — DATA_MODEL.md 9.5, 6.1 (APPEND), 6.2 (S-APP), 7.2, 7.8, 10, 13.2
--
-- Content-addressed resolved params (params_snapshots) and the C24 snapshot envelope of every engine
-- decision (recommendations). Both are insert-only for authenticated (APPEND template); the a_append
-- trigger rejects updates except by postgres (retention pruning, section 15).

-- ---------------------------------------------------------------------------------------------------
-- 9.5.1 params_snapshots (id = UUIDv5 params_snapshots:<uid>:<params_hash>)
-- ---------------------------------------------------------------------------------------------------
create table fitness.params_snapshots (
  id                 uuid primary key default gen_random_uuid(),
  user_id            uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at         timestamptz not null default now(),
  server_updated_at  timestamptz not null default now(),
  device_id          uuid not null,
  app_version        text not null check (length(app_version) between 1 and 40),

  params_hash        text not null check (params_hash ~ '^[0-9a-f]{64}$'),
  params_version     text not null references fitness.algo_params (params_version),
  params             jsonb not null check (jsonb_typeof(params) = 'object'),
  user_param_ids     uuid[] not null default '{}',

  constraint params_snapshots_hash_uq unique (user_id, params_hash)
);
create index params_snapshots_user_sync_idx on fitness.params_snapshots (user_id, server_updated_at);
create index params_snapshots_version_idx on fitness.params_snapshots (params_version);

-- ---------------------------------------------------------------------------------------------------
-- 9.5.2 recommendations
-- ---------------------------------------------------------------------------------------------------
create table fitness.recommendations (
  id                    uuid primary key default gen_random_uuid(),
  user_id               uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at            timestamptz not null default now(),
  server_updated_at     timestamptz not null default now(),
  device_id             uuid not null,
  app_version           text not null check (length(app_version) between 1 and 40),

  kind                  text not null check (kind ~ '^[a-z]+(\.[a-z_]+)+$'),
  module_id             text not null check (module_id ~ '^[a-z]+(\.[a-z]+)*$'),
  module_version        text not null check (module_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  engine_version        text not null check (engine_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  params_hash           text not null check (params_hash ~ '^[0-9a-f]{64}$'),
  catalog_version       text not null check (catalog_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  challenge_def_sha256  text[] not null default '{}'
                        check (array_to_string(challenge_def_sha256, ',') ~ '^([0-9a-f]{64}(,|$))*$'),
  inputs_hash           text not null check (inputs_hash ~ '^[0-9a-f]{64}$'),
  inputs                jsonb check (inputs is null or octet_length(inputs::text) <= 65536),
  inputs_bytes          int not null check (inputs_bytes >= 0),
  outputs               jsonb not null check (octet_length(outputs::text) <= 65536),
  trace                 jsonb not null default '[]' check (octet_length(trace::text) <= 65536),
  warnings              jsonb not null default '[]' check (jsonb_typeof(warnings) = 'array'),
  scope_key             text not null check (length(scope_key) between 1 and 120),
  scope_date            date,
  iso_week              text check (iso_week is null or iso_week ~ '^[0-9]{4}-W(0[1-9]|[1-4][0-9]|5[0-3])$'),
  session_id            uuid,                 -- soft references (no FK), 9.5.2
  session_exercise_id   uuid,
  set_log_id            uuid,
  enrollment_id         uuid,
  supersedes_id         uuid,
  inputs_pruned_at      timestamptz,

  constraint recommendations_id_user_uq unique (id, user_id),
  constraint recommendations_params_fk foreign key (user_id, params_hash)
    references fitness.params_snapshots (user_id, params_hash)
);
create index recommendations_user_sync_idx on fitness.recommendations (user_id, server_updated_at);
create index recommendations_scope_idx on fitness.recommendations (user_id, kind, scope_key, created_at desc);
create index recommendations_session_idx on fitness.recommendations (user_id, session_id) where session_id is not null;
create index recommendations_module_idx on fitness.recommendations (user_id, module_id, module_version);
create index recommendations_params_idx on fitness.recommendations (user_id, params_hash);

-- ---------------------------------------------------------------------------------------------------
-- RLS, grants, policies (APPEND template, 7.8) and the append-only trigger (7.2).
-- ---------------------------------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array['params_snapshots', 'recommendations'] loop
    execute format('alter table fitness.%I enable row level security', t);
    execute format('revoke all on table fitness.%I from public, anon, authenticated', t);
    execute format('grant select, insert on table fitness.%I to authenticated', t);
    execute format('grant select, insert on table fitness.%I to service_role', t);
    execute format(
      'create policy %I on fitness.%I for select to authenticated '
      'using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))',
      t || '_select', t);
    execute format(
      'create policy %I on fitness.%I for insert to authenticated '
      'with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))',
      t || '_insert', t);
    execute format(
      'create trigger a_append before insert or update on fitness.%I '
      'for each row execute function fitness_private.tg_append_only()', t);
  end loop;
end;
$$;

comment on table fitness.params_snapshots is 'Content-addressed resolved effective params, referenced by params_hash (replay).';
comment on table fitness.recommendations is 'C24 snapshot envelope of every engine decision (inputs, outputs, trace, versions, hashes).';

-- Manual rollback (only with explicit user approval), reverse order:
--   drop table fitness.recommendations; drop table fitness.params_snapshots;
