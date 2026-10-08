# LENS data_model

## SUMMARY
Lens: Supabase schema, security, data architecture. Live project findings (read-only): project "ChessAnalysis" (eu-west-1, PG 17.6, org plan FREE). Schemas in use: public (13 tables, 107 functions) and assistant (chess); also cron, supabase_migrations, vault. The Data API is ON, and the probe returned PGRST106 "Only the following schemas are exposed: public, graphql_public". The chess app runs server-side only: its public tables grant only to postgres and service_role, have RLS enabled with zero policies, and the default privileges for postgres in public were already narrowed to service_role (migration "restrict_data_api_roles"). The legacy anon key is DISABLED. Only the publishable key (sb_publishable_...) is active. auth.users has 6 users, the only provider is email, and an account for sivucinec@gmail.com EXISTS. auth.users already has the trigger on_auth_user_created -> handle_new_user (chess). There are 56 migrations in supabase_migrations.schema_migrations, all from chess. pg_cron runs 3 chess jobs. Installed extensions: pgcrypto, uuid-ossp, pg_stat_statements, index_advisor, hypopg, ltree (in public), pg_cron. pg_jsonschema and moddatetime are available but not installed. DB size is 95 MB of the 500 MB free limit. The authenticator role preloads 'safeupdate' (UPDATE/DELETE without WHERE are rejected). Statement timeouts: anon 3 s, authenticated 8 s. Advisors: security has 14 INFO rls_enabled_no_policy (by design for chess), WARN ltree in public, WARN leaked-password protection off. Performance has 3 unindexed FKs (chess). Nothing fitness-related exists yet.
Recommendation: create a new schema `fitness`, the only one exposed to the Data API, granted to authenticated and service_role and NOT to anon. Add `fitness_private` (trigger/helper functions, not exposed) and `fitness_review` (security_invoker analysis views for Claude Code reviews, not exposed, no grants). Expose it with Management API PATCH /v1/projects/{ref}/postgrest {db_schema:"public,graphql_public,fitness"} or the dashboard Data API settings. Do NOT use `supabase config push` (it would push the whole config.toml, including auth, over the chess app's settings). Avoid `alter role authenticator set pgrst.db_schemas` (a hidden override that drifts from the dashboard). Every user-owned table gets user_id uuid default auth.uid() → auth.users(id) on delete cascade. Child tables carry the same user_id and use a composite FK (parent_id,user_id) → parent(id,user_id). This stops cross-user parent references, because FK checks bypass RLS. Policies: one per command, `to authenticated`, `user_id = (select auth.uid())`. No delete grants: soft delete via deleted_at. Append-only tables (session_events, recommendations, challenge_rule_events) get only select+insert grants. Use text+CHECK for states, lookup tables for user-facing lists (reason codes, muscles, metrics), no PG enums. Client-generated UUIDv7 (PG17 has no uuidv7()). Sync is offline-first: local SQLite is the source of truth, with an outbox push via PostgREST upsert and a server-side LWW trigger keyed on client_updated_at. Pull uses a server_updated_at cursor with a 120 s overlap. Store raw facts (sets, overrides, events, metrics) plus immutable recommendation snapshots (algorithm, version, params_hash, inputs_hash (RFC 8785 JCS + SHA-256), inputs, outputs, explanation, warnings), so any recommendation can be replayed deterministically. Store derived estimator state only as a rebuildable cache. Challenges: versioned immutable jsonb definitions with a server-computed checksum, enrollments with state jsonb + state_rev optimistic lock, and an append-only rule-event audit. Migrations: SQL files in repo supabase/migrations, named `fitness_*`, all objects schema-qualified, applied with Supabase MCP apply_migration. `supabase db push` from this repo would fail: there are 56 remote chess versions missing locally, and repairing them would corrupt the chess history. Reference data (muscles, exercises, reasons, metric defs, the OAP challenge template) is shipped as idempotent data migrations. Run get_advisors after each migration. Free-plan risks: no backups, pause after 7 days of low activity, read-only above 500 MB. Mitigate with on-device primary storage and a periodic export/db dump.

## FINDINGS
### Live project inventory (read-only queries) [engineering-fact]
Project nuvjdvpqibqeyafzgaye 'ChessAnalysis', region eu-west-1, Postgres 17.6.1.063, created 2025-12-28, org 'Sacha' plan=free. Non-system schemas: public (13 tables, 1 matview user_analysis_stats, 107 functions: 12 SECURITY DEFINER, 80 without pinned search_path, 81 executable by anon/authenticated), assistant (table memory, view chess_tournaments, 2 functions; schema has NO grants so it is unreachable via API). DB size 95 MB. Realtime publication supabase_realtime has 0 tables. No edge functions. pg_cron jobs: prune-expired-imported-games (0 3 * * 0), cleanup-expired-daily-schedules (0 3 * * *), prune-assistant-week-rows (17 3 * * 1). Event triggers include pgrst_ddl_watch/pgrst_drop_watch, so the PostgREST schema cache reloads automatically after DDL.
SRC: Supabase MCP get_project / execute_sql on nuvjdvpqibqeyafzgaye, 2026-10-07 | get_organization afobxhminbgdmbnoubxd -> plan free

### Chess app access model = server-side only; Data API roles locked down [engineering-fact]
All 13 public tables have relacl {postgres, service_role} only: no anon/authenticated table grants and RLS enabled with ZERO policies (14 INFO lints rls_enabled_no_policy). Default ACL for role postgres in schema public = postgres+service_role only (migration 20260821193012 restrict_data_api_roles). Probe GET /rest/v1/profiles with the publishable key returns 401. Implication: the chess app talks via service_role/secret key on a server. The fitness app (native Android, no server) must use a different model: the Data API with publishable key + user JWT + explicit grants to authenticated + RLS. The two models coexist without interference because fitness lives in its own schema.
SRC: pg_class.relacl / pg_default_acl / pg_policies queries | list_migrations output | curl probe of https://nuvjdvpqibqeyafzgaye.supabase.co/rest/v1/profiles -> 401

### Exposed schemas currently [engineering-fact]
Probe with Accept-Profile: fitness_probe_schema returned {code:PGRST106, hint:'Only the following schemas are exposed: public, graphql_public'}. So the Data API is enabled. To expose fitness, db_schema must become 'public,graphql_public,fitness'. Keep the existing entries; PATCH replaces the whole string. The same probe is the cheap post-change verification: after the change the hint must list fitness.
SRC: curl probe 2026-10-07 | https://supabase.com/docs/reference/api/v1-update-postgrest-service-config

### Keys and auth [engineering-fact]
get_publishable_keys: legacy JWT anon key disabled=true; publishable key sb_publishable_... active. The Android client must use the publishable key (supabase-kt v3.x / supabase-js v2 support it). Auth providers in use: email only. auth.users count=6; an account for sivucinec@gmail.com exists, so the user signs in with existing chess credentials (same auth.users). auth.users already has trigger on_auth_user_created -> public.handle_new_user (chess). Do NOT add a second auth.users trigger for fitness: a failure there would break chess sign-ups. Create fitness.profiles lazily from the app (upsert on first login). Existing advisor WARN: leaked password protection disabled (project-wide, not fitness-specific).
SRC: get_publishable_keys | auth.users count/boolean query (no PII printed) | pg_trigger on auth.users | get_advisors security

### Role settings that constrain the client [engineering-fact]
authenticator: session_preload_libraries=safeupdate (pg-safeupdate rejects UPDATE/DELETE without WHERE via Data API), statement_timeout=8s, lock_timeout=8s. anon statement_timeout=3s; authenticated 8s. Consequence: every PostgREST update/delete must carry a filter (eq id). Any bulk 'reset' operation must be an RPC or a filtered call. 8 s is ample for fitness queries if indexes exist.
SRC: pg_roles.rolconfig query | https://github.com/eradman/pg-safeupdate

### Custom schema exposure: required GRANTs (docs) and least-privilege deviation [engineering-fact]
Docs recipe: GRANT USAGE ON SCHEMA s TO anon, authenticated, service_role; GRANT ALL ON ALL TABLES/ROUTINES/SEQUENCES ... ; ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA s GRANT ALL ... TO anon, authenticated, service_role; then add the schema to 'Exposed schemas'. Recommendation: deviate toward least privilege. Grant USAGE to authenticated and service_role only (never anon). Use explicit per-table grants (select/insert/update; no delete where soft-delete applies; select/insert only for append-only tables). No blanket default privileges for tables. ALTER DEFAULT PRIVILEGES ... REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, because Postgres grants EXECUTE to PUBLIC on every new function by default. The chess project already had to fix this (migration security_revoke_definer_rpc_public_execute), and the assistant functions are currently PUBLIC-executable. Docs tip: bundle grants with RLS in the same migration. A missing grant yields PostgREST 42501 with a hint naming the GRANT.
SRC: https://supabase.com/docs/guides/api/using-custom-schemas | https://supabase.com/docs/guides/api/securing-your-api | https://www.postgresql.org/docs/17/ddl-priv.html

### 2026 platform change: Data API exposure becomes opt-in [engineering-fact]
Supabase removes automatic SELECT/INSERT/UPDATE/DELETE grants to anon/authenticated/service_role on NEW tables in public. Timeline: 2026-04-28 opt-in toggle; 2026-05-30 default for new projects; 2026-10-30 applied to all existing projects (tables created before keep grants). storage/auth/realtime and custom schemas keep current behaviour. Custom schemas never had auto-grants anyway (only public's default ACL from supabase_admin). Impact on fitness: none if every migration grants explicitly, which it should regardless. This project already opted in manually for postgres-owned public objects.
SRC: https://github.com/orgs/supabase/discussions/45329 | https://supabase.com/docs/guides/api/securing-your-api

### How to add 'fitness' to Exposed schemas (SQL vs API vs CLI) [engineering-fact]
(1) Dashboard: Data API settings -> Exposed schemas. (2) Management API PATCH /v1/projects/{ref}/postgrest, body {db_schema, db_extra_search_path, max_rows, db_pool, db_pool_acquisition_timeout}; scope rest:write / fine-grained data_api_config_write; GET counterpart exists. (3) CLI config.toml [api] schemas (default ['public','storage','graphql_public']) is applied to remote by `supabase config push`. That command pushes the whole linked config, so it is dangerous on a project shared with the chess app (auth site_url, redirect URLs, email settings could be overwritten). (4) SQL: `alter role authenticator set pgrst.db_schemas='...'; notify pgrst,'reload config'` works on hosted (used as a workaround in issue #45904, where dashboard changes did not propagate to running PostgREST), but it creates a DB-level override that diverges from dashboard state. Recommended: dashboard or Management API, then verify with the PGRST106 probe. Supabase MCP has no tool for postgrest config. Max_rows default 1000 caps page size.
SRC: https://supabase.com/docs/reference/api/v1-update-postgrest-service-config | https://supabase.com/docs/guides/local-development/cli/config | https://supabase.com/docs/reference/cli/supabase-config-push | https://github.com/supabase/supabase/issues/45904

### RLS performance and correctness patterns [strong]
Wrap auth functions: `(select auth.uid()) = user_id` lets Postgres cache the value per statement (initPlan). Benchmarks (GaryAustin1, Supabase discussion 14576): auth.uid() wrap 179 ms -> 9 ms; index on user_id 171 ms -> <0.1 ms; explicit query filter 171 -> 9 ms; security-definer function wrapped in select 178,000 ms -> 12 ms; `TO authenticated` lets anon short-circuit (170 ms -> <0.1 ms). Docs: index every policy column (leading btree column); always use TO clause; auth.uid() is null for anon. Lints to stay clean: 0003 auth_rls_initplan, 0006 multiple_permissive_policies (avoid FOR ALL + FOR SELECT overlap: write one policy per command), 0010 security_definer_view, 0011 function_search_path_mutable (use `set search_path = ''`), 0016 materialized_view_in_api, 0021 fkey_to_auth_unique (FK only to auth.users(id)), 0024 permissive_rls_policy (avoid USING(true); for global catalogs use `(select auth.uid()) is not null`), 0028/0029 security definer functions executable by anon/authenticated, 0001 unindexed FKs.
SRC: https://supabase.com/docs/guides/database/postgres/row-level-security | https://github.com/orgs/supabase/discussions/14576 | https://supabase.com/docs/guides/database/database-advisors

### Cross-user parent reference hole and composite FKs [engineering-fact]
RLS WITH CHECK only validates the row's own columns. FK existence checks run with owner rights and ignore RLS, so user A could insert a set_log pointing at user B's session id if the UUID were known. The fix is a deterministic schema rule: every parent table gets UNIQUE (id, user_id), and children reference (parent_id, user_id) via a composite FK. A child row can then only attach to a parent with the same user_id, which is the caller's own because of the RLS check. Cost: one extra unique index per parent, negligible at this scale.
SRC: https://www.postgresql.org/docs/17/ddl-rowsecurity.html (referential integrity checks bypass RLS) | https://www.postgresql.org/docs/17/ddl-constraints.html#DDL-CONSTRAINTS-FK

### Migrations on a project shared with another repo [engineering-fact]
supabase_migrations.schema_migrations (columns: version, statements[], name, created_by, idempotency_key, rollback[]) holds 56 chess migrations (20260715140504 ... 20261005083742), apparently applied through MCP. The CLI `db push` compares the local migrations dir with this table and refuses when remote versions are missing locally. `migration repair --status reverted` would corrupt the chess history. So: keep SQL files in FitnessApp/supabase/migrations (source of truth, prefix names fitness_), apply each through Supabase MCP apply_migration (records the version/name in the same table, which is consistent with how chess applies), then rename the local file to the assigned version. Reference data (muscles, exercises, reason codes, metric defs, OAP template) goes in idempotent data migrations (INSERT ... ON CONFLICT (slug/code) DO UPDATE), not seed.sql (seed.sql is dev-only; `--include-seed` is not for prod). Branching is not on the free plan. Test first in a local Docker stack (`supabase start` + `db reset`) if Docker is available, else a pgTAP RLS test file run in a rollback transaction. After every migration run get_advisors (security+performance) and expect zero new fitness findings. Type generation: `supabase gen types --lang typescript|go|swift|python --schema fitness` (no Kotlin/Dart). With a Kotlin client, use hand-written @Serializable DTOs plus a committed TS types file as a diffable schema snapshot to detect drift.
SRC: list_migrations output | https://supabase.com/docs/guides/deployment/database-migrations | https://supabase.com/docs/guides/local-development/cli-workflows | https://supabase.com/docs/guides/local-development/seeding-your-database

### Free plan constraints [engineering-fact]
Free plan: DB enters read-only mode above 500 MB (currently 95 MB used by chess). No daily backups on Free (only Pro 7 d, Team 14 d, Enterprise 30 d); docs advise `supabase db dump` off-site. Projects pause after low activity over 7 days (dashboard visits and API calls count; chess usage probably keeps it alive); restore window 1 year. Fitness data volume estimate: ~15k set rows/yr x ~300 B = ~5 MB; ~50k events/yr x ~400 B = ~20 MB; ~3k recommendation snapshots/yr x <=8 KB = ~24 MB. Total <60 MB/yr including indexes x2. OK, but add a size guard. Free plan has no leaked-password protection, branching, or PITR.
SRC: https://supabase.com/docs/guides/platform/database-size | https://supabase.com/docs/guides/platform/backups | https://supabase.com/docs/guides/platform/free-project-pausing

### Kotlin client custom schema [engineering-fact]
supabase-kt: install(Postgrest) { defaultSchema = "fitness"; propertyConversionMethod = ... }; per-call schema also possible. JS: createClient(url, key, { db: { schema: 'fitness' } }) or supabase.schema('fitness'). REST: Accept-Profile (GET/HEAD) / Content-Profile (POST/PATCH/PUT/DELETE) headers. Upsert = POST with Prefer: resolution=merge-duplicates (needs INSERT+UPDATE grants/policies; SELECT policy if return=representation).
SRC: https://supabase.com/docs/reference/kotlin/initializing | https://supabase.com/docs/guides/api/using-custom-schemas | https://postgrest.org/en/stable/references/api/tables_views.html#upsert

### Offline sync engines vs custom outbox [moderate]
PowerSync (Supabase partner) gives offline-first sync for Kotlin/Flutter/RN. It needs `CREATE ROLE ... WITH REPLICATION BYPASSRLS LOGIN`, SELECT grants, a publication named 'powersync', and a third-party service holding a copy of the data. On a shared free project that means a BYPASSRLS role able to read the chess data unless grants are tightly scoped. For a single-user app with ~200 set rows/week, a custom outbox (local SQLite authoritative, PostgREST upserts, cursor pulls) is simpler, has no extra infra, and is fully deterministic. Recommend custom; revisit PowerSync only if multi-device editing conflicts become real.
SRC: https://docs.powersync.com/integration-guides/supabase-+-powersync | https://docs.powersync.com/configuration/source-db/setup.md

### Hashing / canonical JSON determinism [engineering-fact]
Postgres jsonb::text is canonical but NOT RFC 8785. Test: '{"bb":1,"a":2,"c":[1,2.50]}' -> '{"a": 2, "c": [1, 2.50], "bb": 1}' (keys ordered by length then bytes; numeric scale kept). Equal hashes for permuted input (sha256 2803ede7...). So server-side checksums (challenge_definitions.checksum = encode(sha256(convert_to(definition::text,'UTF8')),'hex')) are stable but differ from client JCS hashes. Never compare the two; each hash has one producer. convert_to() is STABLE, so it cannot be used in a GENERATED column: compute it in a BEFORE INSERT/UPDATE trigger. sha256(bytea) and encode() are IMMUTABLE. PG17 lacks uuidv7() (added in PG18): generate UUIDv7 client-side (e.g. uuid-creator / kotlin.uuid), with server default gen_random_uuid() as fallback.
SRC: execute_sql SELECT test 2026-10-07 | pg_proc.provolatile query | https://www.rfc-editor.org/rfc/rfc8785 | https://www.postgresql.org/docs/18/functions-uuid.html

### JSON validation for challenge definitions [engineering-fact]
pg_jsonschema (available, not installed): `create extension pg_jsonschema with schema extensions`; extensions.jsonb_matches_schema(schema json, instance jsonb) usable in CHECK constraints. Gives server-side rejection of malformed challenge definitions/load schemes. It is optional: the client validator remains authoritative, and installing an extension on a shared project is low-risk (lives in extensions schema).
SRC: https://supabase.com/docs/guides/database/extensions/pg_jsonschema | list_extensions output

### Calendar source affects what DB stores [moderate]
Android CalendarContract (READ_CALENDAR permission) reads on-device-synced Google calendars without OAuth, a Google Cloud project, or a consent screen. Google Calendar REST from an unverified OAuth app in 'Testing' status gets refresh tokens that expire after 7 days. Recommendation: CalendarContract and the user's primary calendar only (skips the stale Todoist-synced 'Gym' all-day tasks). In Supabase, store only classified gym/challenge/test events (title, location, start/end, ids). Store other events as anonymous busy blocks or not at all (privacy, size). The cache exists for adherence analysis (planned vs done) in reviews, not as the source of truth.
SRC: https://developer.android.com/identity/providers/calendar-provider | https://developers.google.com/identity/protocols/oauth2#expiration

### Volume counting default for analysis views [moderate]
Fractional set counting (direct/target muscle = 1.0, indirect/synergist = 0.5) fitted hypertrophy/strength dose-response best in the Pelland et al. meta-regression. Use it as the default exercise_muscles.stimulus coefficient and in fitness_review.v_weekly_muscle_volume. Unilateral sets logged per side count 0.5 per row for the muscle (an L+R pair = 1 set per limb).
SRC: Pelland JC et al. 2024/2025, 'The Resistance Training Dose-Response: Meta-Regressions Exploring the Effects of Weekly Volume and Frequency on Muscle Hypertrophy and Strength Gain', SportRxiv / Sports Med

## ALGORITHMS
### S0 Schema bootstrap, grants and RLS template
PURPOSE: Create isolated fitness schemas with least-privilege Data API access and uniform, lint-clean RLS.
IN: Migration run as postgres via MCP apply_migration.
OUT: Schemas fitness (exposed), fitness_private (helpers, not exposed), fitness_review (analysis views, not exposed); policies; triggers.
PSEUDO:
create schema fitness; create schema fitness_private; create schema fitness_review;
revoke all on schema fitness, fitness_private, fitness_review from public;
grant usage on schema fitness to authenticated, service_role;           -- NO anon
alter default privileges for role postgres in schema fitness         revoke execute on functions from public;
alter default privileges for role postgres in schema fitness_private revoke execute on functions from public;

-- shared trigger fn (security invoker, pinned path)
create function fitness_private.tg_sync_row() returns trigger language plpgsql set search_path = '' as $$
begin
  if tg_op = 'UPDATE' then
    if new.client_updated_at < old.client_updated_at then return null; end if;  -- LWW: drop stale write silently
    new.id := old.id; new.user_id := old.user_id; new.created_at := old.created_at;  -- immutable cols
  end if;
  new.server_updated_at := now();
  return new;
end $$;

-- per user-owned MUTABLE table T
grant select, insert, update on fitness.T to authenticated; grant all on fitness.T to service_role;
alter table fitness.T enable row level security;
create policy T_sel on fitness.T for select to authenticated using (user_id = (select auth.uid()));
create policy T_ins on fitness.T for insert to authenticated with check (user_id = (select auth.uid()));
create policy T_upd on fitness.T for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
-- no delete grant/policy: soft delete via deleted_at
create trigger T_sync before insert or update on fitness.T for each row execute function fitness_private.tg_sync_row();
create index on fitness.T (user_id, server_updated_at);   -- pull cursor + RLS column

-- per APPEND-ONLY table A (session_events, recommendations, challenge_rule_events)
grant select, insert on fitness.A to authenticated;  -- update/delete impossible at grant level
(policies _sel, _ins only)

-- per GLOBAL catalog G (muscle_groups, muscles, reason_codes, metric_defs, algorithm_versions)
grant select on fitness.G to authenticated; policy G_sel for select to authenticated using ((select auth.uid()) is not null);
-- MIXED global+user tables (exercises, exercise_muscles, challenge_definitions)
using (owner_user_id is null or owner_user_id = (select auth.uid())) for select;
with check (owner_user_id = (select auth.uid())) for insert/update.

-- children: composite FK
alter table fitness.workout_sessions add unique (id, user_id);
set_logs: foreign key (session_id, user_id) references fitness.workout_sessions(id, user_id) on delete cascade;

-- review views
create view fitness_review.v_x with (security_invoker = true) as ...;  -- no grants; queried by Claude Code as postgres via MCP
-- after migration: get_advisors(security), get_advisors(performance) -> expect 0 new fitness lints; then probe PGRST106 hint lists fitness.
CONST: Roles granted: authenticated, service_role (anon none). search_path='' on every function. One policy per command (avoids lint 0006). Hard delete only via auth.users cascade.
LOG: Advisor output per migration; PostgREST 42501/PGRST106 errors in client logs (table sync_errors in local SQLite, uploaded as session_events type 'sync_error').

### S1 Offline-first sync (outbox push + cursor pull, row-level LWW)
PURPOSE: Gym use without signal; deterministic conflict resolution; no third-party sync service.
IN: Local SQLite tables mirroring fitness.*, outbox(seq, table, row_id, op, payload, attempts, next_try_at); per-table cursor; device_id (random UUID at install).
OUT: Server rows converged; local rows converged; tombstones via deleted_at.
PSEUDO:
ON local write(row):
  row.client_updated_at = now_utc_ms(); row.device_id = DEVICE; write local; outbox.append(table,row.id)
PUSH loop (on connectivity, every 30 s while app foreground, and on session finish):
  for table in DEPENDENCY_ORDER [profiles, gyms, gym_equipment, exercises(custom), exercise_muscles, user_exercise_prefs, rep_preferences, muscle_goals, user_algo_params, challenge_enrollments, calendar_events, workout_sessions, session_exercises, set_logs, metric_observations, wellness_logs, recommendations, challenge_rule_events, session_events, exercise_model_state]:
    batch = outbox rows of table (latest version per row_id), limit 200
    POST /rest/v1/<table>?on_conflict=id  Prefer: resolution=merge-duplicates,return=minimal  (append-only tables: Prefer: resolution=ignore-duplicates)
    2xx -> delete batch from outbox
    409/23503 FK -> leave; retry after parent pushed
    401 -> refresh session, retry; 42501 -> mark fatal, surface to user
    5xx/timeout -> attempts++, next_try_at = now + min(300, 2^attempts) s
PULL (after push):
  for table: since = cursor[table] - OVERLAP
    GET /rest/v1/<table>?server_updated_at=gte.<since>&order=server_updated_at.asc,id.asc&limit=PAGE  (repeat while page full)
    for r in rows: local = find(r.id)
       if local is null or (r.client_updated_at, r.device_id) >= (local.client_updated_at, local.device_id) and no pending outbox for r.id: upsert local
    cursor[table] = max(server_updated_at seen)
Server trigger tg_sync_row drops updates whose client_updated_at < stored (S0).
CONST: OVERLAP = 120 s (covers commit-order skew of now() = txn start); PAGE = 500 (< max_rows 1000); push batch 200; backoff base 2 s, cap 300 s; foreground push interval 30 s. Tie-break: lexical device_id. Sets are mutable rows with LWW + soft delete. Every edit is also an append-only session_event, so history is never lost.
LOG: session_events type 'sync_conflict' (table, row_id, local vs server client_updated_at), sync latency (local write -> server_updated_at), outbox depth at session end.

### S2 Recommendation snapshot and deterministic replay
PURPOSE: Every recommendation can later be reproduced and audited by Claude Code; algorithm changes can be compared offline on real history.
IN: Algorithm name, semver version, app_version, effective user_algo_params, canonical inputs (relevant history window, prefs, goals, equipment, calendar, challenge state, bodyweight).
OUT: Row in fitness.recommendations: kind, scope_date, week_start, session_id?, session_exercise_id?, algorithm, algorithm_version, app_version, params_hash, inputs_hash, inputs (jsonb), outputs (jsonb), explanation (jsonb array of {code, params}), warnings (jsonb array of {code, severity, params}), created_at.
PSEUDO:
inputs = build_inputs(ctx)  // only data the algorithm reads; ids + numbers, no free text
canon = JCS(inputs)          // RFC 8785 canonical JSON, numbers as decimal strings rounded to 0.01
inputs_hash = hex(sha256(utf8(canon)))
params = effective_params(user, now)  // latest user_algo_params per key with valid_from <= now, else defaults compiled in algorithm_version
params_hash = hex(sha256(JCS(params)))
out = ALGO[algorithm][version](inputs, params)  // pure function; ties broken by stable sort on (score desc, exercise.slug asc); StrictMath for exp/log; outputs rounded to equipment grid
if last snapshot for (kind, scope key) has same (algorithm_version, params_hash, inputs_hash): reuse it (no new row)
else insert row (insert-only table)
REPLAY (Claude review / tests): for each row: assert ALGO[row.algorithm][row.algorithm_version](row.inputs, params_by_hash[row.params_hash]) == row.outputs
COUNTERFACTUAL: run ALGO[new_version](row.inputs, params') and diff outputs vs actual set_logs.
CONST: Rounding: loads 0.01 kg internally, displayed per equipment grid; reps integers; RIR 0.5 steps. Snapshot inputs cap 16 KB (truncate history to the window the algorithm actually uses). Retention: keep full inputs 730 days; afterwards null inputs, keep hashes + outputs (size guard S6).
LOG: All recommendation rows plus linked set_logs/session_exercises (actual vs planned) and session_events (accepted/overridden/dismissed warnings).

### S3 Load normalization and effective load
PURPOSE: One numeric basis for weighted, bodyweight, assisted, unilateral and duration exercises; machine pins in lb kept as entered.
IN: Entered value + unit (kg | lb | pin step), exercise.load_mode, exercise.bw_fraction, bodyweight at set time (S4), gym_equipment.load_scheme.
OUT: set_logs.actual_load_kg (added external, >=0), actual_assist_kg (counterweight, >=0), load_input_value/unit (raw), bodyweight_kg + bw_fraction snapshots, effective_load_kg (stored generated column).
PSEUDO:
kg(v, unit) = unit=='lb' ? round(v * 0.45359237, 2) : v
external: load_kg = kg(entered); assist = 0; bw_fraction = 0
bodyweight: load_kg = 0; assist = 0; bw_fraction = exercise.bw_fraction
bodyweight_plus_external (dip belt, weighted pull-up): load_kg = kg(belt); bw_fraction = exercise.bw_fraction
assisted (assisted pull-up machine, pin 5-lb steps): assist = kg(pin_lb); bw_fraction = exercise.bw_fraction
duration_only (hangs, holds, planks): loads as above (holds may be assisted); primary measure actual_duration_s
effective_load_kg GENERATED ALWAYS AS (coalesce(bodyweight_kg*bw_fraction,0) + coalesce(actual_load_kg,0) - coalesce(actual_assist_kg,0)) STORED
round_to_grid(target_kg, scheme): stack/dumbbells: nearest value in values[] (convert unit), tie -> lower; plates: bar + 2*sum(greedy plates), tie -> lower; pin_assist: nearest multiple of step within [min,max], tie -> MORE assistance (safer).
CONST: 1 lb = 0.45359237 kg (exact). Default bw_fraction: pull-up/chin-up/dip 1.00 for logging consistency (configurable per exercise; biomechanical 0.90-0.95 can be set later by review), push-up 0.64, inverted row 0.60. Tie rule: round down external load, round up assistance.
LOG: load_input_value/unit vs normalized kg; frequency of 'wrong_weight_available' reason; per-equipment rounding error |prescribed - rounded|.

### S4 Bodyweight at time t
PURPOSE: Bodyweight-dependent loads (OAP 65% BW threshold, effective load) need a defined BW per set/test.
IN: metric_observations where metric_code='bodyweight_kg' (deleted_at null).
OUT: bw(t) and staleness flag stored as set_logs.bodyweight_kg and workout_sessions.bodyweight_kg snapshots.
PSEUDO:
obs = sorted by observed_at
prev = last obs <= t; next = first obs > t
if prev and next and (next.t - prev.t) <= 21 d: bw = linear interpolation (used only in review/replay)
elif prev: bw = prev.value (used live)
else: bw = profile default / ask user
stale = (t - prev.t) > 14 d -> UI prompts weigh-in at session start; snapshot stored regardless.
CONST: Staleness 14 d; interpolation max gap 21 d; values numeric(5,2) kg.
LOG: Weigh-in frequency; BW trend vs strength trend (relative strength for OAP gates).

### S5 Challenge definition versioning and enrollment state machine (data level)
PURPOSE: Challenges are data, not code: toggle in a Challenges tab, auditable rule firing, upgradeable definitions.
IN: challenge_definitions(definition jsonb, definition_schema_version, slug, version, status), challenge_enrollments(state jsonb, state_rev, status, enabled, current_phase, block_index, week_in_block), session results, metric_observations, wellness_logs (pain).
OUT: Updated enrollment state; challenge_rule_events rows; prescriptions for challenge sessions (via S2 kind='challenge_eval').
PSEUDO:
PUBLISH: insert definition (status draft) -> validate (client JSON-Schema; optional server CHECK extensions.jsonb_matches_schema) -> set status='published', trigger sets checksum=encode(sha256(convert_to(definition::text,'UTF8')),'hex'); trigger forbids UPDATE of definition when status in ('published','retired'); new content => new row version+1.
ENROLL: insert enrollment(definition_id=exact version, status 'active', enabled true, state = definition.initial_state, state_rev=0). Partial unique index (user_id, definition_slug) where status in ('active','paused').
EVALUATE (after each relevant session/test/pain log, deterministic order = definition.rules[] order):
  for rule in rules: if eval(rule.when, facts) then effect = apply(rule.then, state); append event {rule_id, kind, inputs(facts subset), effect, state_rev_before, state_rev_after, algorithm_version, definition_checksum}
  write: UPDATE enrollments SET state=:s, state_rev=state_rev+1 WHERE id=:id AND state_rev=:expected  (0 rows -> pull, re-evaluate)
UPGRADE: new definition version -> migration map in definition.upgrade_from[prev_version] (key renames, defaults) -> event kind 'upgrade'.
TOGGLE OFF: enabled=false (status 'paused'); planner frees its weekly slots; state kept.
CONST: definition JSON top-level keys: schema_version, slug, title, sessions_per_week (OAP: 3), weekly_template[] (code A/B/C/TEST, preferred_dow, calendar_title_regex e.g. '(?i)sessie\s+A', exercises[] with load rules), block {weeks:5, build:4, deload:{sets_factor:0.5, load_factor:1.0, hang_factor:0.5}, test_replaces:'B'}, phases[] with gate criteria [{metric, op, value}], rules[] (progression/safety/schedule), tests {metrics[]}, constraints {max_heavy_unilateral_per_week:3}, initial_state.
LOG: challenge_rule_events (which rules fire, how often, effect), test-day metric_observations, phase gate timing, pain events preceding rule 'elbow_pain_protocol'.

### S6 Analysis metrics in fitness_review views
PURPOSE: Give Claude Code fixed, reproducible queries for periodic algorithm review.
IN: set_logs, session_exercises, workout_sessions, recommendations, session_events, exercise_muscles, metric_observations, challenge_rule_events, calendar_events.
OUT: Views: v_set_facts, v_e1rm_history, v_prescription_error, v_rir_calibration, v_override_stats, v_skip_stats, v_weekly_muscle_volume, v_rest_compliance, v_adherence_week, v_recommendation_acceptance, v_challenge_timeline, v_db_size.
PSEUDO:
v_set_facts: working sets (set_kind in working,top,backoff,amrap,test; deleted_at null; actual_reps>0) joined with exercise, session.local_date, iso week (date_trunc('week', local_date)).
rtf = actual_reps + coalesce(rir_reported, 0)                              -- reps to failure
e1rm_epley = effective_load_kg * (1 + rtf/30.0)
e1rm_brzycki = case when rtf < 37 then effective_load_kg * 36.0/(37 - rtf) end
v_e1rm_history: max(e1rm_epley) per (user, exercise, side, local_date)
v_prescription_error per set: rep_err = actual_reps - planned_reps; rir_err = rir_reported - planned_rir; load_ratio = effective_load_kg / nullif(planned_effective_load_kg,0)
  aggregate by (exercise, algorithm_version, 28-day bucket): n, mean(rep_err) as bias, avg(abs(rep_err)) as mae, avg((abs(rir_err)<=1)::int) as rir_hit_rate
v_rir_calibration: predicted_rtf from recommendations.outputs->'pred_rtf'; calib_err = (predicted_rtf - actual_reps) - rir_reported
v_override_stats: count by reason_code x scope (set/exercise/session/timer) x algorithm_version, rate = overrides / prescriptions
v_weekly_muscle_volume: sum(stimulus * case side when 'both' then 1 else 0.5 end) per (user, muscle, iso week), joined to muscle_goals and to the plan's target from recommendations(kind weekly_plan).outputs
v_rest_compliance: rest_before_s vs rest_timer_planned_s; median ratio, share overridden
v_adherence_week: recommended slots vs calendar gym events vs finished sessions per week
v_db_size: pg_total_relation_size per fitness table.
CONST: Working-set filter as above; rtf capped at 30 for Epley (beyond is noise); fractional stimulus defaults 1.0 target / 0.5 synergist; buckets 28 d.
LOG: These views ARE the tuning surface; reviews filter on the owner's user_id (resolve via auth.users by own email only).

### S7 Storage and retention guard
PURPOSE: Stay far below the free-plan 500 MB read-only cliff shared with chess; free plan has no backups.
IN: pg_database_size, per-table sizes, row ages.
OUT: Warnings in review; optional prune job; export.
PSEUDO:
weekly (Claude review or pg_cron 'fitness-size-guard' '23 3 * * 1'): s = pg_database_size(current_database())
if s > 350 MB: flag; prune recommendations.inputs older than 730 d (set inputs=null, keep hashes/outputs); prune session_events payload older than 730 d for types in (timer_tick, sync_ok)
backup: app 'Export all data' -> JSON (all fitness rows of user) to device storage/Drive monthly; dev side `supabase db dump --schema fitness --data-only` before each migration.
CONST: Alert at 350 MB (70% of 500 MB); retention 730 d full fidelity; no pruning of set_logs, metric_observations, challenge_rule_events (core facts, tiny).
LOG: v_db_size trend; export timestamps.

### S8 Calendar event cache and classification (data level)
PURPOSE: List planned gym sessions this week; link plan vs actual; respect challenge sessions found in the calendar.
IN: CalendarContract instances for primary calendar, window [week_start - 7 d, week_start + 14 d]; classification rules from profile settings + active challenge definitions (calendar_title_regex).
OUT: fitness.calendar_events rows (only classified rows synced), linked_session_id.
PSEUDO:
for ev in instances(primary calendar, window):
  key = (provider='android_cp', calendar_id, event_id, instance_start)
  cls = 'ignored'
  for (code, rx) in active_challenge.weekly_template: if rx matches ev.title: cls='challenge_session', matched_template=slug+':'+code
  if cls=='ignored' and title ~* '\bgym\b' or location ilike '%basic-fit%': cls='gym_session'
  if title ~* 'testdag': cls='test_day', matched_template=slug+':TEST'
  if title ~* 'hardlopen': cls='run' (fatigue input, legs)
  if all_day and calendar is not primary: skip  (stale Todoist tasks)
  upsert by key if cls != 'ignored' (store title, location, starts_at, ends_at, status, match_rule, match_score)
  events missing from provider since last fetch -> status='cancelled' (soft)
link: session started within [starts_at - 3 h, ends_at + 3 h] same local_date and compatible template -> linked_session_id.
CONST: Window -7 d..+14 d; link tolerance +-3 h; regexes data-driven (challenge definition / profile), defaults above; Europe/Amsterdam for local_date.
LOG: match_rule hit counts, unlinked planned events (missed sessions), sessions without events (unplanned).

## DESIGN OPTIONS
- Where the Data API boundary lives: [Expose a new schema `fitness` directly (tables + RLS) / Expose only an `fitness_api` schema of views/RPCs over private tables / Edge Functions with secret key (mirror the chess server model)] -> Expose `fitness` tables directly with RLS. Put helpers in `fitness_private` and analysis views in `fitness_review` (both unexposed).. WHY: Native client, offline outbox needs plain upserts; views-over-tables doubles the work, and edge functions add a server and latency for no security gain at single-user scale. The unexposed helper/review schemas give the 'dedicated API schema' benefit.
- How to add `fitness` to exposed schemas: [Dashboard Data API settings / Management API PATCH /v1/projects/{ref}/postgrest {db_schema:'public,graphql_public,fitness'} / supabase config push with [api].schemas / ALTER ROLE authenticator SET pgrst.db_schemas] -> Dashboard (user clicks) or Management API with a user-provided PAT. Verify via the PGRST106 probe.. WHY: config push would also push auth/storage config over the chess app's settings. The role-setting override diverges from dashboard state and is a hidden trap (issue #45904).
- Status/kind fields: Postgres ENUM vs text+CHECK vs lookup table: [CREATE TYPE enums / text + CHECK constraint / lookup table + FK] -> text+CHECK for internal state machines (session status, set_kind, side); lookup tables for user-facing/extensible lists (reason_codes, muscles, muscle_groups, metric_defs, algorithm_versions).. WHY: Enum values cannot be removed or reordered, ADD VALUE has transaction caveats, and there is no Kotlin typegen benefit. Lookup tables let labels (EN/NL), applicability and algorithm signal be data. CHECK constraints are trivially replaced in a migration.
- Stored vs derived: [Store only raw facts; derive everything client-side / Store raw facts + immutable recommendation snapshots + rebuildable estimator cache / Store server-computed aggregates] -> Raw facts (sets, overrides, events, metrics, wellness, calendar classification) + immutable recommendation snapshots (inputs/outputs/explanation/versions/hashes) + exercise_model_state as a cache (PK user, exercise, side, algorithm_version; never authoritative).. WHY: Snapshots make every recommendation replayable and auditable for Claude reviews. The cache lets a fresh install resume instantly and shows what the model believed. Server aggregates would duplicate algorithm code outside the Kotlin source of truth.
- Set rows: mutable vs strictly append-only: [Mutable rows with LWW + soft delete + append-only session_events audit / Append-only sets with supersedes_id correction chain] -> Mutable set_logs (LWW, deleted_at), with every create/edit/delete also emitted as a session_event.. WHY: Simple queries and UI editing (typos). The full history is still preserved in the events log for review.
- Sync mechanism: [Custom outbox + PostgREST upsert + cursor pull / PowerSync / Online-only] -> Custom outbox (S1).. WHY: Gym connectivity is unreliable. PowerSync needs a BYPASSRLS replication role on a shared free project plus a third-party service. Data volume is tiny.
- Applying migrations: [Supabase MCP apply_migration from Claude Code / supabase db push from the FitnessApp repo / Raw psql] -> MCP apply_migration, with repo files as source of truth (renamed to the assigned version). No db push from this repo.. WHY: db push fails on the 56 chess versions missing locally, and repairing them would damage the chess history. MCP keeps one shared history table consistent.
- Per-user algorithm tuning channel: [Only via new app releases / Table fitness.user_algo_params (key, value jsonb, valid_from, source default|fitted|review, note) read at sync] -> Add user_algo_params. Claude reviews write rows (as postgres via MCP after user approval) and the app applies them from valid_from.. WHY: Lets periodic reviews adjust constants (e.g. the user's flatter rep curve) without rebuilding the APK. params_hash in snapshots keeps replay exact.
- Bodyweight and test results storage: [Separate tables per metric / Generic metric_observations(metric_code FK metric_defs, value, unit, side, observed_at, source, session_id, enrollment_id)] -> Generic metric_observations (bodyweight_kg, wpu_1rm_kg, wpu_3rm_kg, max_reps_bw, one_arm_hang_s, top_hold_s, least_assist_kg, negative_s ...).. WHY: Challenge definitions reference metrics by code, so new challenges need no migration.
- Proposed table list (schema fitness): [GLOBAL/MIXED: muscle_groups(id text, region push|pull|legs|core|other), muscles(id text, group_id), reason_codes(code pk, applies_to text[], direction easier|harder|neutral|skip, algo_signal, label_en, label_nl, requires_note, sort, active), metric_defs(code, unit, side_mode, higher_is_better), algorithm_versions(algorithm, version, released_at, git_sha, constants jsonb), exercises(id, owner_user_id null=global, slug, name_en, name_nl, aliases[], movement_pattern, mechanics, equipment_kind, load_mode external|bodyweight|bodyweight_plus_external|assisted|duration_only, bw_fraction, laterality, measure reps|duration|reps_and_duration|attempt, skill_level, systemic_fatigue, axial_load, setup_s, default_rest_s, default_rep_min/max, tags[]; unique(owner_user_id, slug) nulls not distinct), exercise_muscles(exercise_id, muscle_id, owner_user_id, role target|synergist|stabilizer, stimulus 0..1), challenge_definitions(id, owner_user_id, slug, version, status, definition jsonb, definition_schema_version, checksum) / PER-USER CONFIG: profiles(user_id pk, timezone 'Europe/Amsterdam', week_start_dow 1, display_unit, experience, training_age_months, sessions_per_week_target, session_minutes_target, availability jsonb, default_gym_id, pref_enforcement advisory_warn|respect, rest_timer settings), rep_preferences(scope_kind global|mechanics|muscle_group|exercise, scope_ref, sets, rep_min, rep_max, target_rir), muscle_goals(target_kind group|muscle, target_id, goal improve|maintain|none, weight), user_exercise_prefs(pk user+exercise, favorite, rating 1..5, status normal|avoid|banned, status_reason, note), gyms(name, hours jsonb, is_default), gym_equipment(gym_id null=portable e.g. dip belt, equipment_key, load_scheme jsonb {type stack|plates|dumbbells|pin_assist|bodyweight, unit, values[]|bar_kg+plates_kg[]|step,min,max}, available, busy_factor), user_algo_params / FACTS: workout_sessions(local_date, timezone, status planned|in_progress|finished|abandoned|skipped, split, focus_tags[], source, enrollment_id, challenge_session_code, is_test_day, calendar_event_id, plan_/prescription_recommendation_id, gym_id, planned_start_at, started_at, finished_at, bodyweight_kg, pre_energy/sleep/soreness/stress 1-5, session_rpe 0-10, time_budget_min, end_reason_code, notes), session_exercises(session_id, exercise_id, position numeric, origin plan|challenge|user_added|substitute|live_algo, substitutes_id, status pending|active|done|skipped|removed, status_reason_code, planned_sets/rep_min/rep_max/load_kg/assist_kg/duration_s/rir/rest_s, side_mode, user_override, override_reason_code, override_fields jsonb, superset_group, recommendation_id), set_logs(see S3: planned_* vs actual_*, side both|L|R, set_kind, eccentric_s, rir_reported 0..10 step .5, rir_source, reached_failure, success, deviation_reason_code, pain_flag, technique_flag, rest_before_s, rest_timer_planned_s, rest_timer_overridden, started_at, completed_at; unique(session_exercise_id,set_index,side) where deleted_at is null), metric_observations, wellness_logs(kind readiness|pain|sleep|illness, body_region, side, severity 0-10, symptom_codes[], session_id), calendar_events (S8), challenge_enrollments, challenge_rule_events, recommendations (S2), session_events(event_type, payload jsonb, occurred_at, received_at, session_id, session_exercise_id, set_id, app_version, algorithm_version), exercise_model_state (cache) / STANDARD COLUMNS on syncable tables: id uuid pk (client UUIDv7, default gen_random_uuid()), user_id uuid not null default auth.uid() references auth.users(id) on delete cascade, created_at, client_updated_at not null, server_updated_at not null default now(), deleted_at, device_id, app_version. INDEXES: (user_id, server_updated_at); every FK; workout_sessions(user_id, local_date desc); set_logs(user_id, exercise_id, completed_at desc) where deleted_at is null; recommendations(user_id, kind, scope_date desc); session_events(user_id, occurred_at); metric_observations(user_id, metric_code, observed_at desc).] -> Adopt as the v1 schema (≈27 tables), with migrations split as: 001 schemas+grants+trigger fns, 002 reference tables+seed, 003 user config, 004 sessions/sets/events, 005 challenges+calendar+recommendations, 006 review views, 007 OAP challenge template data.. WHY: Covers every requirement (prefs, goals, catalog ratings with 'avoid' vs 'banned', overrides with reasons, RIR per set, add/remove/skip, rest timers, challenges, calendar, versioned snapshots) with simple per-user RLS and composite FKs. Every row the algorithms consume is in a queryable fact table for review.

## RISKS
- Running `supabase config push` from the fitness repo would overwrite the shared project's auth/API config (site URL, redirect URLs, email settings) used by the chess app.
- Running `supabase db push` from the fitness repo fails on 56 unknown remote (chess) migrations. 'Fixing' it with `migration repair` corrupts the chess migration history.
- Exposed-schema changes through the dashboard have a known propagation bug (supabase/supabase#45904). Always verify with the PGRST106 probe. The PATCH db_schema value replaces the whole list, so dropping 'public' or 'graphql_public' could break chess clients if they ever use the Data API.
- New functions in a new schema are EXECUTE-able by PUBLIC by default. A SECURITY DEFINER helper left callable would bypass RLS (lints 0028/0029). Revoke via default privileges and use `set search_path = ''`.
- FK checks bypass RLS: without composite (parent_id, user_id) FKs a client could attach rows to another user's parent row if it knew the UUID.
- pg-safeupdate is preloaded for the authenticator role: client UPDATE/DELETE without a filter fails. Bulk operations need filters or RPCs.
- Free plan: no backups, read-only above 500 MB (chess already uses 95 MB), pause after 7 days of low activity, no branching or PITR. Fitness needs local-first storage and its own export/backup.
- The legacy anon JWT key is disabled. Client libraries/tutorials that assume the anon key fail. Use the publishable key and a client version that supports it.
- LWW relies on device clocks. A badly skewed phone clock could make stale edits win. Mitigation: single primary device, server_updated_at for cursors, events log for forensic recovery.
- Determinism pitfalls: Kotlin double math with platform Math vs StrictMath, map iteration order, locale-dependent formatting. jsonb canonical text ≠ RFC 8785 JCS, so client and server hashes must never be compared.
- Adding another trigger on auth.users (for profile creation) risks breaking chess sign-ups. Create fitness.profiles lazily from the app.
- Other existing chess users (6 accounts) could sign into the fitness app if they got the APK. RLS isolates their data but they could create rows (tiny size impact). An allowlist would need an extra policy predicate.
- Storing raw calendar titles/descriptions of non-gym events in the cloud is unnecessary personal data. Store only classified gym/challenge/test events.
- Challenge definitions as jsonb can drift in shape. Mitigate with definition_schema_version, a client JSON-Schema validator, an optional pg_jsonschema CHECK, and immutable published versions with checksum.
- Kotlin has no Supabase typegen. Hand-written DTOs can silently drift from DDL. Mitigate with a committed `supabase gen types --lang typescript --schema fitness` snapshot and a CI/test diff, or by choosing a TS-based client stack.

## QUESTIONS
- Exposing the new `fitness` schema to the Data API needs a project setting change; plain SQL can't do it cleanly. Will you add 'fitness' under Data API > Exposed schemas in the dashboard (keeping public and graphql_public)? Or do you want to provide a Management API access token so Claude Code can PATCH it?
- Is the app single-user (only you), or should friends (e.g. the '2x12' friend) be able to log in with their own accounts? Should the 5 other existing chess accounts be blocked from the fitness app with an allowlist?
- Calendar: is reading your phone's synced calendars on-device (Android Calendar Provider, no Google OAuth setup) acceptable? Should only gym/challenge/test events be stored in Supabase, plus optionally anonymous busy blocks for other events?
- The project is on the Free plan: no automatic backups, read-only above 500 MB (shared with chess), and paused after 7 quiet days. Is a monthly in-app JSON export plus a `db dump` before each migration enough, or would you consider Pro?
- May logged sets be edited or deleted after a session is finished (for example to fix a typo)? Or should they lock after N hours, with corrections only as new rows?
- May Claude Code apply migrations to the shared project via the Supabase MCP (recommended, with a review of each migration first)? This keeps one migration history with the chess app instead of using `supabase db push`.
- Periodic reviews could adjust your personal algorithm constants (e.g. your flatter rep curve) by writing rows to a `user_algo_params` table, with no new APK needed. Should Claude Code ask for approval before every such write?
- Should machine pins be displayed and entered in lb (as on the Basic-Fit machines) while being stored in kg? Should reason/exercise labels be English, Dutch, or both?
- Is the optional server-side JSON-schema validation of challenge definitions wanted? It means installing the pg_jsonschema extension in the shared project's `extensions` schema.
