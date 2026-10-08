-- fitness_014_challenge_events_calendar (M1a) — DATA_MODEL.md 9.7, 6.1 (APPEND, USER), 7.1, 7.2, 7.8, 8, 13.2
--
-- challenge_events: append-only input events of an enrollment (the cached state is their fold).
-- challenge_rule_events: append-only ECA audit. calendar_events: only classified calendar instances
-- (D11, C20; busy and unclassified events never reach the server, section 16), last-writer-wins.

-- ---------------------------------------------------------------------------------------------------
-- 9.7.1 challenge_events (APPEND; synthesized events use UUIDv5 challenge_events:<enrollment_id>:<dedupe_key>)
-- ---------------------------------------------------------------------------------------------------
create table fitness.challenge_events (
  id                      uuid primary key default gen_random_uuid(),
  user_id                 uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at              timestamptz not null default now(),
  server_updated_at       timestamptz not null default now(),
  device_id               uuid not null,
  app_version             text not null check (length(app_version) between 1 and 40),

  enrollment_id           uuid not null,
  event_type              text not null check (event_type in (
                            'enrollment.created', 'rules.approval_changed', 'session.started', 'session.completed',
                            'session.amended', 'set.logged', 'habit.completed', 'readiness.checked', 'pain.reported',
                            'test.due', 'test.completed', 'attempt.logged', 'week.closed', 'week.started', 'day.tick',
                            'pause', 'resume', 'abandon', 'manual.override', 'definition.migrated')),
  occurred_at             timestamptz not null,
  local_date              date not null,
  dedupe_key              text check (dedupe_key is null or length(dedupe_key) between 1 and 120),
  payload                 jsonb not null default '{}'
                          check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 65536),
  source_session_id       uuid,
  source_wellness_log_id  uuid,
  supersedes_event_id     uuid,
  definition_version      text not null
                          check (definition_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  definition_jcs_sha256   text not null check (definition_jcs_sha256 ~ '^[0-9a-f]{64}$'),
  engine_version          text not null
                          check (engine_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),

  constraint challenge_events_id_user_uq unique (id, user_id),
  constraint challenge_events_enrollment_fk foreign key (enrollment_id, user_id)
    references fitness.challenge_enrollments (id, user_id),
  constraint challenge_events_session_fk foreign key (source_session_id, user_id)
    references fitness.workout_sessions (id, user_id),
  constraint challenge_events_wellness_fk foreign key (source_wellness_log_id, user_id)
    references fitness.wellness_logs (id, user_id),
  constraint challenge_events_supersedes_fk foreign key (supersedes_event_id, user_id)
    references fitness.challenge_events (id, user_id)
);
create unique index challenge_events_dedupe_uq on fitness.challenge_events (enrollment_id, dedupe_key)
  where dedupe_key is not null;
create index challenge_events_fold_idx on fitness.challenge_events (enrollment_id, occurred_at, id);
create index challenge_events_enrollment_idx on fitness.challenge_events (enrollment_id, user_id);
create index challenge_events_user_sync_idx on fitness.challenge_events (user_id, server_updated_at);
create index challenge_events_session_idx on fitness.challenge_events (source_session_id, user_id);
create index challenge_events_wellness_idx on fitness.challenge_events (source_wellness_log_id, user_id);
create index challenge_events_supersedes_idx on fitness.challenge_events (supersedes_event_id, user_id);

-- ---------------------------------------------------------------------------------------------------
-- 9.7.2 challenge_rule_events (APPEND; the ECA audit, C21)
-- ---------------------------------------------------------------------------------------------------
create table fitness.challenge_rule_events (
  id                     uuid primary key default gen_random_uuid(),
  user_id                uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at             timestamptz not null default now(),
  server_updated_at      timestamptz not null default now(),
  device_id              uuid not null,
  app_version            text not null check (length(app_version) between 1 and 40),

  enrollment_id          uuid not null,
  challenge_event_id     uuid not null,
  evaluation_id          uuid not null,
  fold_generation        int not null default 0 check (fold_generation >= 0),
  rule_id                text not null check (rule_id ~ '^[A-Za-z][A-Za-z0-9_.+-]{0,79}$'),
  rule_tag               text not null check (rule_tag in ('A', 'P', 'A+P', 'E')),
  proposal_id            text check (proposal_id is null or proposal_id ~ '^P[0-9]{2,3}$'),
  rule_decision          text check (rule_decision is null or rule_decision in ('approved', 'declined', 'not_required')),
  stage                  text not null check (stage in ('safety', 'data', 'structural', 'progression', 'in_session')),
  prio                   smallint not null,
  outcome                text not null check (outcome in (
                           'applied', 'suppressed_first_writer', 'suppressed_lock', 'suppressed_injury', 'no_effect', 'error')),
  cond_value             boolean,
  eval_error             text check (eval_error is null or length(eval_error) <= 500),
  bindings               jsonb not null default '{}' check (jsonb_typeof(bindings) = 'object'),
  proposals              jsonb not null default '[]' check (jsonb_typeof(proposals) = 'array'),
  applied                jsonb not null default '{}' check (jsonb_typeof(applied) = 'object'),
  suppressed_by          text check (suppressed_by is null or length(suppressed_by) <= 120),
  state_rev_before       int not null check (state_rev_before >= 0),
  state_rev_after        int not null check (state_rev_after >= 0),
  state_hash_before      text check (state_hash_before is null or state_hash_before ~ '^[0-9a-f]{64}$'),
  state_hash_after       text check (state_hash_after is null or state_hash_after ~ '^[0-9a-f]{64}$'),
  cascade_depth          smallint not null default 0 check (cascade_depth between 0 and 3),
  derived_event_type     text check (derived_event_type is null or derived_event_type in (
                           'phase.changed', 'block.started', 'mode.changed')),
  definition_slug        text not null check (definition_slug ~ '^[a-z][a-z0-9_]{0,63}$'),
  definition_version     text not null
                         check (definition_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  definition_jcs_sha256  text not null check (definition_jcs_sha256 ~ '^[0-9a-f]{64}$'),
  module_version         text not null check (module_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  engine_version         text not null check (engine_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),

  constraint challenge_rule_events_enrollment_fk foreign key (enrollment_id, user_id)
    references fitness.challenge_enrollments (id, user_id),
  constraint challenge_rule_events_event_fk foreign key (challenge_event_id, user_id)
    references fitness.challenge_events (id, user_id)
);
create index challenge_rule_events_generation_idx on fitness.challenge_rule_events (enrollment_id, fold_generation, created_at);
create index challenge_rule_events_enrollment_idx on fitness.challenge_rule_events (enrollment_id, user_id);
create index challenge_rule_events_event_idx on fitness.challenge_rule_events (challenge_event_id, user_id);
create index challenge_rule_events_rule_idx on fitness.challenge_rule_events (user_id, rule_id);
create index challenge_rule_events_user_sync_idx on fitness.challenge_rule_events (user_id, server_updated_at);

-- ---------------------------------------------------------------------------------------------------
-- 9.7.3 calendar_events (USER; id = UUIDv5 calendar_events:<uid>:<instance_key>)
-- ---------------------------------------------------------------------------------------------------
create table fitness.calendar_events (
  id                  uuid primary key default gen_random_uuid(),
  user_id             uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at          timestamptz not null default now(),
  client_updated_at   timestamptz not null,
  server_updated_at   timestamptz not null default now(),
  deleted_at          timestamptz,
  device_id           uuid not null,
  app_version         text not null check (length(app_version) between 1 and 40),

  provider            text not null default 'android_cp' check (provider = 'android_cp'),
  instance_key        text not null check (instance_key ~ '^[0-9a-f]{64}$'),
  series_key          text check (series_key is null or series_key ~ '^[0-9a-f]{64}$'),
  source_device_id    uuid not null,
  classified_kind     text not null check (classified_kind in ('challenge_session', 'planned_gym', 'run', 'ignored')),
  title               text check (title is null or length(title) <= 200),
  location            text check (location is null or length(location) <= 200),
  starts_at           timestamptz not null,
  ends_at             timestamptz not null,
  all_day             boolean not null default false,
  local_date          date not null,
  timezone            text not null check (timezone ~ '^[A-Za-z_]+(/[A-Za-z0-9_+-]+)*$'),
  iso_week            text not null check (iso_week ~ '^[0-9]{4}-W(0[1-9]|[1-4][0-9]|5[0-3])$'),
  provider_status     text not null check (provider_status in ('confirmed', 'tentative', 'cancelled')),
  challenge_slot      text check (challenge_slot is null or challenge_slot ~ '^[A-Z][A-Z0-9_]{0,15}$'),
  enrollment_id       uuid,
  planned_families    text[] not null default '{}'
                      check (planned_families <@ array['push', 'pull', 'legs', 'core', 'full']::text[]),
  run_intensity       text check (run_intensity is null or run_intensity in ('easy', 'tempo', 'intervals', 'race', 'long')),
  run_minutes         smallint check (run_minutes is null or run_minutes between 1 and 600),
  classifier_version  text not null
                      check (classifier_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  match_method        text not null check (match_method in ('marker', 'title_pattern', 'score', 'manual', 'adopted')),
  score               numeric(6,3),
  features            text[] not null default '{}',
  override_kind       text check (override_kind is null or override_kind in ('challenge_session', 'planned_gym', 'run', 'ignored')),
  override_slot       text check (override_slot is null or override_slot ~ '^[A-Z][A-Z0-9_]{0,15}$'),
  override_at         timestamptz,
  app_written         boolean not null default false,
  marker              text check (marker is null or marker ~
                        '^\[fitapp:v1 [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12} [A-Za-z0-9_./-]{1,64}\]$'),
  adopted_at          timestamptz,
  write_state         text not null default 'none' check (write_state in (
                        'none', 'pending_create', 'created', 'pending_update', 'updated', 'pending_delete', 'deleted', 'conflict')),
  attention           text check (attention is null or attention in (
                        'slot_mismatch', 'unmarked_needs_adopt', 'moved_externally', 'edited_externally', 'deleted_externally')),
  linked_session_id   uuid,
  link_method         text check (link_method is null or link_method in ('auto_window', 'manual', 'created_from_plan')),
  recommendation_id   uuid,
  last_seen_at        timestamptz not null,
  missing_since       timestamptz,

  constraint calendar_events_id_user_uq unique (id, user_id),
  constraint calendar_events_enrollment_fk foreign key (enrollment_id, user_id)
    references fitness.challenge_enrollments (id, user_id),
  constraint calendar_events_session_fk foreign key (linked_session_id, user_id)
    references fitness.workout_sessions (id, user_id),
  constraint calendar_events_recommendation_fk foreign key (recommendation_id, user_id)
    references fitness.recommendations (id, user_id),
  constraint calendar_events_times_ck check (ends_at > starts_at),
  -- Privacy (16.2): an ignored event is stored only as a user override, without title or location.
  constraint calendar_events_ignored_ck check (classified_kind <> 'ignored' or (title is null and location is null)),
  constraint calendar_events_slot_ck check (classified_kind <> 'challenge_session' or challenge_slot is not null),
  constraint calendar_events_run_ck check (classified_kind <> 'run' or run_minutes is not null),
  constraint calendar_events_marker_ck check (not app_written or marker is not null)
);
create unique index calendar_events_instance_uq on fitness.calendar_events (user_id, instance_key) where deleted_at is null;
create index calendar_events_user_sync_idx on fitness.calendar_events (user_id, server_updated_at);
create index calendar_events_local_date_idx on fitness.calendar_events (user_id, local_date);
create index calendar_events_session_idx on fitness.calendar_events (linked_session_id, user_id);
create index calendar_events_enrollment_idx on fitness.calendar_events (enrollment_id, user_id);
create index calendar_events_recommendation_idx on fitness.calendar_events (recommendation_id, user_id);

-- ---------------------------------------------------------------------------------------------------
-- RLS, grants, policies (APPEND and USER templates, 7.8) and triggers (7.1, 7.2).
-- ---------------------------------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array['challenge_events', 'challenge_rule_events'] loop
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

alter table fitness.calendar_events enable row level security;
revoke all on table fitness.calendar_events from public, anon, authenticated;
grant select, insert, update on table fitness.calendar_events to authenticated;
grant select, insert, update, delete on table fitness.calendar_events to service_role;
create policy calendar_events_select on fitness.calendar_events for select to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create policy calendar_events_insert on fitness.calendar_events for insert to authenticated
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create policy calendar_events_update on fitness.calendar_events for update to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create trigger a_sync before insert or update on fitness.calendar_events
  for each row execute function fitness_private.tg_sync_row();

comment on table fitness.challenge_events is 'Append-only enrollment input events; state = fold over (occurred_at, id).';
comment on table fitness.challenge_rule_events is 'Append-only ECA audit: one row per rule whose condition was true or errored.';
comment on table fitness.calendar_events is 'Classified calendar instances only (D11, C20); busy and unclassified events stay on the phone.';

-- Manual rollback (only with explicit user approval), reverse order:
--   drop table fitness.calendar_events; drop table fitness.challenge_rule_events; drop table fitness.challenge_events;
