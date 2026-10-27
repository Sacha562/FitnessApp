# Data model and Supabase schema (v1)

| Field | Value |
|-------|-------|
| Status | Draft for user review (2026-10-07; aligned with the cross-document resolutions the same day). Nothing in this document has been applied to the database. |
| Source of truth | `docs/DECISIONS.md` (D1-D16, F1-F27, C1-C65). Where this document and DECISIONS disagree, DECISIONS wins and this document has a bug. |
| Research inputs | `docs/research/data_model.md` (primary), `tech_stack.md`, `challenge_engine.md`, `load_prescription.md`, `planning.md`, `ux_ui.md`, `exercise_selection.md` (taxonomy and catalog attributes), `critique.md`. |
| Owns | Every table, column, constraint, index, grant, RLS policy, trigger and view in the schemas `fitness`, `fitness_private` and `fitness_review`; the value domains of every stored code (section 8, C38); the telemetry event-type registry (9.8.1, C39); the snapshot output keys that reviews read (10.2, C58); the sync protocol between Room and Supabase; the migration plan; retention, backups and privacy rules for stored data. |
| Does not own | Algorithm semantics (they live in the ALGORITHMS documents and in the params JSON). This document only fixes where their inputs and outputs are stored and which JSON keys the review views read (section 10). The Room schema (local tables, timestamp representation, outbox payload policy) is owned by ANDROID.md section 8; section 12 summarises it (C40). Platform constants (timers, notifications, reminders, sync backoff) live in `PlatformConfig` and are owned by ANDROID.md section 25 (C54). |

---

## 1. Scope and how to read this document

This document is written for two readers:

1. **The user**, who reviews each migration before it is applied (F3). Sections 2-6, 13, 15, 16 and 17 explain what will change in the shared Supabase project and why it is safe for the chess app.
2. **Future Claude Code sessions**, which implement the `:data` module, write the SQL migrations and run periodic algorithm reviews. Sections 7-12, 14, 18, 19 and 21 are the implementation contract; section 20 lists the open issues that still block parts of it.

Conventions used throughout:

- Every database object is schema-qualified (`fitness.set_logs`, never `set_logs`) (F3).
- "Client" means the Android app (`:app` + `:data`). "Engine" means the pure `:algo` module. "Owner" means the allowlisted user who owns a row.
- Evidence grades for constants follow the project vocabulary: **strong / moderate / weak / expert-opinion / engineering-fact**. Most constants in this document are engineering choices; they are graded **expert-opinion** and marked **tunable** with the logged data that tunes them (section 18).
- Module ids (for example `sse`, `challenge.eca`, `ui.prompts`) are the registry ids from the project brief. Every module is versioned `id@semver` and starts at `1.0.0` (C30).
- Canonical numeric grids (C22): mass in kg to 0.01, durations in seconds to 0.1, ratios to 1e-4. The database column types are chosen so that a stored value can only exist on that grid (`numeric(_,2)` for kg, `numeric(_,1)` for seconds, `numeric(_,4)` for ratios). C42 adds two grids that appear only inside snapshot JSON (stored reps are whole numbers): fractional reps (for example `n_eff`) to 0.01, and internal statistics (variances, covariances) to 12 significant digits.

---

## 2. Live project facts (verified 2026-10-07)

All facts below were checked on 2026-10-07 with read-only `SELECT` queries through the Supabase MCP (no DDL, no DML, no other users' data printed), or come from the research probe of the same day where marked.

| Fact | Value | Consequence for this design |
|------|-------|-----------------------------|
| Project | `nuvjdvpqibqeyafzgaye` ("ChessAnalysis"), region eu-west-1, PostgreSQL 17.6, org plan **Free** | Shared with the chess app. No backups, read-only mode above 500 MB, pause after 7 quiet days (F4). |
| Database size | 95 MB of the 500 MB Free limit | Fitness budget and size guard in section 15. |
| Non-system schemas | `assistant`, `public` (chess), plus Supabase system schemas (`auth`, `cron`, `extensions`, `graphql`, `graphql_public`, `realtime`, `storage`, `supabase_migrations`, `vault`). No schema named `fitness*` exists yet. | New schemas `fitness`, `fitness_private`, `fitness_review` (F1) do not collide with anything. |
| Exposed schemas (Data API) | `public, graphql_public` (research PGRST106 probe, 2026-10-07) | The user changes the setting to `public,graphql_public,fitness` in the dashboard (F2, C25). Re-probe before and after. |
| Keys | Legacy JWT anon key disabled; only the publishable key (`sb_publishable_...`) is active (research, `get_publishable_keys`) | The app ships the publishable key only, never a secret or service-role key. |
| Auth | 6 users in `auth.users`, email provider only; the owner's account already exists | The owner signs in with existing credentials. Other chess users are blocked by the allowlist (D3). |
| Trigger on `auth.users` | `on_auth_user_created -> public.handle_new_user` (chess) | **Never add a second trigger on `auth.users`.** `fitness.profiles` is created lazily by the app (upsert on first login). |
| Role settings | `authenticator`: `session_preload_libraries=safeupdate`, `statement_timeout=8s`, `lock_timeout=8s`; `authenticated`: `statement_timeout=8s`; `anon`: `statement_timeout=3s`; `postgres`: `search_path="$user", public, extensions` | PostgREST `UPDATE`/`DELETE` without a filter is rejected (safeupdate). Sync uses upserts (inserts) and filtered `GET`s only. Every client query must finish well under 8 s (pagination, indexes). |
| `postgres` role | `rolbypassrls = true`, member of `authenticated` | Claude Code reviews (as `postgres` via MCP) see all rows: review queries must filter by the owner's `user_id`. `SET LOCAL ROLE authenticated` works for RLS tests. |
| `auth.uid()` | `STABLE` SQL function reading `request.jwt.claim.sub`, else `request.jwt.claims->>'sub'` | RLS tests set `request.jwt.claims` locally. Policies wrap it as `(select auth.uid())`. |
| Extensions installed | `pgcrypto`, `uuid-ossp`, `pg_stat_statements`, `hypopg`, `index_advisor` (all in `extensions`), `ltree` (in `public`), `pg_cron`, `plpgsql`, `supabase_vault` | `extensions.uuid_generate_v5` is available for deterministic seed ids. Built-in `sha256(bytea)` (PG 11+) is used for server checksums. |
| Extensions available, not installed | `pg_jsonschema`, `moddatetime` | `pg_jsonschema` is optional (F5, ask once at M0). `moddatetime` is not used (our sync trigger sets timestamps). |
| `uuidv7()` | Not present (PostgreSQL 17) | UUIDv7 ids are generated client-side; server default `gen_random_uuid()` is only a fallback. |
| Migration history | 55 rows in `supabase_migrations.schema_migrations`, all chess, latest `20261005083742` (the research note said 56; the live count today is 55) | Never run `supabase db push` or `migration repair` from this repo (F2/F3). Apply fitness migrations through MCP `apply_migration` only. |
| pg_cron | 3 chess jobs | No fitness cron jobs in v1 (section 15). |
| Event triggers | `pgrst_ddl_watch`, `pgrst_drop_watch` (plus Supabase graphql/cron/net access triggers) | PostgREST reloads its schema cache automatically after each migration. |
| Default privileges | Per-schema default ACLs exist for `public`, `storage`, `graphql*`, `auth`, `realtime`, `extensions`, `cron`. In `public`, objects created by `postgres` are granted to `postgres` and `service_role` only (chess migration `restrict_data_api_roles`). There is **no** global default ACL. | New `fitness*` schemas start with no per-schema defaults. Tables get no grants unless a migration grants them. Functions, however, still get the **built-in `EXECUTE` to `PUBLIC`** (see 4.3). |

---

## 3. Design principles

1. **Local-first.** Room on the phone is the source of truth (F4). Supabase is a synchronised replica used for backup, multi-device continuity and Claude Code reviews. The app works fully offline in the gym; a paused Free project only delays sync.
2. **Three kinds of stored data.**
   - **Facts**: what happened (sets, sessions, wellness answers, metric observations, calendar classifications, challenge input events, UI telemetry). Facts are never derived from other facts in SQL.
   - **Snapshots**: immutable records of what the engine decided and why (`recommendations`, `challenge_rule_events`, `params_snapshots`), each carrying module id@version, `ENGINE_VERSION`, params hash, catalog version, challenge definition hash and inputs hash (C24, C30). Any recommendation can be replayed.
   - **Caches**: rebuildable state (`exercise_model_state`, the cached fields of `challenge_enrollments`). Caches are never authoritative.
3. **One producer per value.** A value is computed in exactly one place. The engine computes loads, effective loads, e1RM, fractional volume and gate values; the database stores them and checks consistency but never recomputes them (C1, C3, C6). Review views read engine-logged values.
4. **Text + CHECK, no PostgreSQL enums.** Internal state machines and fixed vocabularies use `text` with a `CHECK` (section 8). User-facing, extensible lists (muscle groups, regions, reason codes, metric definitions, body regions, symptoms, equipment types) are lookup tables. Reason: enum values cannot be removed or reordered and give no Kotlin type-generation benefit.
5. **Per-user isolation at three levels**: grants (no `anon`, least privilege per table class), RLS (one policy per command, `to authenticated`, allowlist + owner predicate) and composite foreign keys `(parent_id, user_id)` so that a child row can only attach to a parent of the same user (foreign-key checks bypass RLS).
6. **Soft delete only.** No `DELETE` grant for clients. Rows are tombstoned with `deleted_at`. Hard deletion happens only through `ON DELETE CASCADE` from `auth.users`.
7. **Append-only where history matters.** Telemetry, snapshots, challenge input events and rule audits are insert-only at the grant level.
8. **Determinism.** Client ids are UUIDv7 (time-ordered) or UUIDv5 from a natural key (section 6.4). Every stored number is on the C22 grid. Client and server hashes have one producer each and are never compared (RFC 8785 JCS + SHA-256 on the client; `jsonb::text` + SHA-256 on the server).
9. **Privacy by default.** Only classified calendar events are stored in Supabase (section 16). No free text in telemetry payloads. No email or other PII in tables or in the repository.
10. **Nothing in the shared project changes for chess.** No objects in `public`, no trigger on `auth.users`, no global default-privilege changes, no `config push`, no cron jobs.

---

## 4. Schemas, roles and grants

### 4.1 Schemas (F1)

| Schema | Exposed to Data API | Purpose | Grants |
|--------|---------------------|---------|--------|
| `fitness` | Yes, after the user adds it (F2, C25) | All app tables. No functions, no views (nothing callable through `/rpc`). | `USAGE` to `authenticated`, `service_role`. Table grants per class (6.1). Never `anon`. |
| `fitness_private` | No | Allowlist table, the `is_allowed()` helper, trigger functions, validation helpers. | `USAGE` to `authenticated`, `service_role` (required so RLS policies can call `fitness_private.is_allowed()`; the schema is not exposed, so nothing in it is reachable over HTTP). No table grants to `authenticated`. |
| `fitness_review` | No | `security_invoker` analysis views for Claude Code reviews (section 14). | None. Queried as `postgres` through MCP only. |

`REVOKE ALL ON SCHEMA ... FROM PUBLIC` is issued for all three schemas, so `anon` (which only inherits from `PUBLIC`) has no `USAGE` anywhere in fitness.

### 4.2 Roles

| Role | Used by | Access |
|------|---------|--------|
| `anon` | Requests with only the publishable key | Nothing in any fitness schema. Probing a fitness table returns 401 / `42501`. |
| `authenticated` | The app with a user JWT | Per-table grants (6.1) filtered by RLS (allowlist + owner). |
| `service_role` | Not used by the app. Reserved for future server tooling. | Grants per class, bypasses RLS. The secret key never leaves the user's machine. |
| `postgres` | Migrations and reviews via Supabase MCP | Owner of all fitness objects. Bypasses RLS. Reviews must filter by the owner's `user_id`. |

### 4.3 Function privileges: a correction to the research template

PostgreSQL grants `EXECUTE` on every new function to `PUBLIC` through the built-in global default. The research template's `ALTER DEFAULT PRIVILEGES ... IN SCHEMA fitness REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC` **does nothing**: per-schema default privileges can only add to the global defaults, never remove them (PostgreSQL 17 docs, `ALTER DEFAULT PRIVILEGES`). Changing the global default (without `IN SCHEMA`) would also change the chess app's future functions, which this design forbids.

Rule for every migration that creates or replaces a function:

```sql
revoke all on function fitness_private.<fn>(<args>) from public;
-- then grant only what is needed, for example:
grant execute on function fitness_private.is_allowed() to authenticated, service_role;
```

Trigger functions get no `EXECUTE` grant at all: PostgreSQL does not check `EXECUTE` when a trigger fires, only when the trigger is created (by `postgres`, the owner). The verification checklist (17.4) lists every fitness function whose ACL still contains a `PUBLIC` entry; the expected result is zero rows.

### 4.4 The 2026-10-30 platform change

Supabase stops auto-granting new `public` tables to API roles on 2026-10-30 for existing projects. Custom schemas never had auto-grants, so nothing changes for fitness: every migration grants explicitly anyway.

---

## 5. Access control: allowlist and RLS (D3)

### 5.1 Requirements

- Only allowlisted users can read or write any fitness row, including global reference data. Today the allowlist holds only the owner. Adding a friend later is one row (D3).
- The 5 other chess accounts can authenticate against the shared `auth.users`, but every fitness policy denies them. They see empty results and every write fails with `42501`.
- The allowlist itself is invisible and unmodifiable from the app.
- The helper must be cheap: it runs once per statement, not once per row.

### 5.2 Allowlist table and helper

```sql
create table fitness_private.allowed_users (
  user_id   uuid primary key references auth.users (id) on delete cascade,
  role      text not null default 'member' check (role in ('owner', 'member')),
  added_at  timestamptz not null default now(),
  note      text check (note is null or length(note) <= 200)
);
-- Only one owner.
create unique index allowed_users_one_owner on fitness_private.allowed_users (role) where role = 'owner';
revoke all on table fitness_private.allowed_users from public, anon, authenticated;
grant select on table fitness_private.allowed_users to service_role;

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
```

Notes:

- `security definer` lets the function read `allowed_users` although `authenticated` has no grant on it. `set search_path = ''` and fully qualified names prevent search-path hijacking (advisor lint 0011).
- The function returns only a boolean about the caller, so even a direct call would leak nothing; it is not reachable over HTTP because `fitness_private` is not exposed.
- RLS is not enabled on `allowed_users` (no grants exist for `anon`/`authenticated`, and the schema is unexposed), which avoids an `rls_enabled_no_policy` notice.
- The owner row is inserted by migration `fitness_003_allowlist_owner` using the owner's `auth.users.id` UUID literal. Claude resolves that UUID once with a read-only lookup by the owner's email at M0 and writes **only the UUID** into the migration file. The email address never appears in the repository.

### 5.3 Policy predicate

Every policy on every fitness table starts with the allowlist predicate, wrapped in a scalar sub-select so PostgreSQL evaluates it once per statement (initPlan; research benchmark: security-definer function wrapped in `select` 178 s to 12 ms):

```sql
(select fitness_private.is_allowed())
```

User-owned rows add the owner predicate `user_id = (select auth.uid())`. Global reference rows need only the allowlist predicate (this also satisfies advisor lint 0024, which flags `using (true)`). Mixed tables use `(user_id is null or user_id = (select auth.uid()))` for reads.

### 5.4 What a non-allowlisted user experiences

| Action | Result |
|--------|--------|
| `GET /rest/v1/<any fitness table>` | `200 []` (RLS filters everything, including reference data) |
| `POST` (insert or upsert) | `403` / `42501 new row violates row-level security policy` |
| App behaviour | After the first sync attempt the app shows "This account is not enabled for the fitness app" and keeps working offline. No data is lost; the outbox stays intact. |
| Access check without an RPC | `GET /rest/v1/muscle_groups?select=id&limit=1` with `Accept-Profile: fitness` returns one row for an allowlisted user (reference data is seeded by migration 005) and `[]` for anyone else; `PGRST106` means the schema is not exposed yet. The `fitness` schema has no functions (4.1), so no access-check RPC is created; ANDROID.md 10.4 uses exactly this read probe (open issue 24, resolved). |

---

## 6. Table classes, standard columns and sync classes

### 6.1 Table classes

| Class | Meaning | Grants to `authenticated` | Grants to `service_role` | Policies | Trigger(s) |
|-------|---------|---------------------------|--------------------------|----------|-----------|
| **REF** | Global reference data written only by migrations | `select` | `select, insert, update, delete` | `_select` (allowlist) | `a_touch` (sets `updated_at`) |
| **MIX** | Global rows (`user_id is null`, written by migrations) plus user-owned custom rows | `select, insert, update` (or `select` only where noted) | `select, insert, update, delete` | `_select` (global or own), `_insert` (own), `_update` (own) | `a_sync` plus table-specific |
| **USER** | User-owned mutable rows, last-writer-wins | `select, insert, update` | `select, insert, update, delete` | `_select`, `_insert`, `_update` | `a_sync` plus table-specific |
| **USER-RO** | User-owned rows written server-side (Claude review after approval) | `select` | `select, insert, update, delete` | `_select` | `a_sync` |
| **APPEND** | User-owned insert-only rows | `select, insert` | `select, insert` | `_select`, `_insert` | `a_append` |
| **CACHE** | User-owned rebuildable state, last-writer-wins | `select, insert, update` | `select, insert, update, delete` | `_select`, `_insert`, `_update` | `a_sync` |
| **PRIV** | `fitness_private` tables | none | `select` | none (RLS off) | none |

No class grants `DELETE` to `authenticated`, and no grant of any kind goes to `anon`.

### 6.2 Standard columns

**S-MUT** (classes USER, USER-RO, CACHE; MIX with `user_id` nullable):

| Column | Type | Null | Default | Notes |
|--------|------|------|---------|-------|
| `id` | `uuid` | not null | `gen_random_uuid()` | Primary key. Client supplies a UUIDv7, or a UUIDv5 for natural-key tables (6.4). |
| `user_id` | `uuid` | not null (MIX: null = global) | `auth.uid()` | `references auth.users (id) on delete cascade`. Immutable after insert (trigger). |
| `created_at` | `timestamptz` | not null | `now()` | Client creation instant. Immutable after insert (trigger). |
| `client_updated_at` | `timestamptz` | not null | none | Client wall clock of the last local edit, millisecond precision, strictly increasing per device (`max(now_ms, last_issued_ms + 1)`). LWW key part 1. |
| `server_updated_at` | `timestamptz` | not null | `now()` | Always overwritten by the trigger with `now()`. Pull cursor. Never sent by the client. |
| `deleted_at` | `timestamptz` | null | none | Tombstone. A non-null value hides the row everywhere. |
| `device_id` | `uuid` | not null | none | Random UUID generated at install; LWW key part 2 (tie-break). Migrations and server-side writes use `ffffffff-ffff-ffff-ffff-ffffffffffff` (`SERVER_DEVICE_ID`). |
| `app_version` | `text` | not null | none | `check (length(app_version) between 1 and 40)`, for example `1.0.0+12`, or `migration` for server writes. |

Every S-MUT table has `index (user_id, server_updated_at)` (pull cursor plus the RLS column). Every table that is the parent of a composite foreign key has `unique (id, user_id)`.

**S-APP** (class APPEND):

| Column | Type | Null | Default | Notes |
|--------|------|------|---------|-------|
| `id` | `uuid` | not null | `gen_random_uuid()` | Primary key. UUIDv7 or UUIDv5 (synthesized events). |
| `user_id` | `uuid` | not null | `auth.uid()` | `references auth.users (id) on delete cascade` |
| `created_at` | `timestamptz` | not null | `now()` | Client instant at which the row was created. |
| `server_updated_at` | `timestamptz` | not null | `now()` | Receive time, set by trigger; pull cursor; never changes afterwards. |
| `device_id` | `uuid` | not null | none | |
| `app_version` | `text` | not null | none | Same check as S-MUT. |

**S-REF** (class REF): a natural `text` primary key (`id` or `code`), `active boolean not null default true`, `sort smallint not null default 0`, `updated_at timestamptz not null default now()` (set by trigger `a_touch`; the app pulls reference tables by `updated_at`).

### 6.3 Sync classes

| Sync class | Tables | Push | Pull |
|------------|--------|------|------|
| `REF-PULL` | All REF tables except `session_event_types` | none | Incremental by `updated_at` (120 s overlap) |
| `MIX` | `exercises`, `exercise_stimulus` | Own custom rows: upsert (merge) | Global + own rows, incremental |
| `DEF-PULL` | `challenge_definitions` | none in v1 | Global (+ own) rows, incremental |
| `LWW` | USER and CACHE tables | Upsert `merge-duplicates`, `return=representation` | Incremental |
| `LWW-REV` | `challenge_enrollments` | Like LWW, plus the `state_rev` rule (7.5) | Incremental |
| `SRV-PULL` | `user_algo_params` | none (written by `postgres` after user approval) | Incremental |
| `APPEND` | APPEND tables | Insert with `ignore-duplicates`, `return=minimal` | Incremental, filtered per table (11.6) |
| `LOCAL` | Room-only tables (section 12) | never | never |
| `NONE` | `fitness_private.*`, `session_event_types`, `fitness_review.*` | never | never |

### 6.4 Identifier rules

| Row kind | Id rule | Example |
|----------|---------|---------|
| Ordinary user rows | UUIDv7 generated on the device (48-bit Unix ms + version + 74 random bits from `SecureRandom`; clock and random injected in tests) | set log, session, recommendation |
| Natural-key user rows (one row per natural key) | UUIDv5 in namespace `FITNESS_NS` with name `<table>:<user_id>:<natural key>` so two devices create the same id and the upsert merges instead of failing on a unique index | `user_exercise_prefs` (`user_exercise_prefs:<uid>:<exercise_id>`), `muscle_goals`, `rep_preferences` (scope key), `exercise_model_state`, `calendar_events` (`calendar_events:<uid>:<instance_key>`), `params_snapshots` (`params_snapshots:<uid>:<params_hash>`), synthesized challenge events (`challenge_events:<enrollment_id>:<dedupe_key>`) |
| `profiles` | `id = user_id` (checked) | |
| Global seed rows | UUIDv5 in `FITNESS_NS` with name `<kind>:<slug>[@version]`, computed by the seed generator and identical in the bundled APK catalog | `exercise:chinup_weighted` (a catalog slug, C38; not the definition key `weighted_pullup`), `challenge_definition:oap@1.0.0` |

`FITNESS_NS = 2611a46b-2ad8-5006-88dc-e43030211f8f`, defined as `uuid_generate_v5(<RFC 4122 URL namespace 6ba7b811-9dad-11d1-80b4-00c04fd430c8>, 'https://fitness-app.invalid/ns/v1')` (engineering-fact, verified with `extensions.uuid_generate_v5`). Fixture values are in 19.6.

---

## 7. Template SQL

These snippets are the patterns every migration copies. They are **not** executed by this document. All functions use `set search_path = ''` and fully qualified names.

### 7.1 Sync trigger for mutable rows (C23)

```sql
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

  -- UPDATE (including the DO UPDATE branch of an upsert):
  -- last writer wins on (client_updated_at, device_id).
  -- An equal or older tuple is a duplicate or stale write: keep the stored row silently.
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

-- Per mutable table T:
create trigger a_sync
  before insert or update on fitness.T
  for each row execute function fitness_private.tg_sync_row();
```

Behaviour that the client relies on:

- A stale upsert is dropped without an error, and the row is missing from the `return=representation` response. The client detects the missing ids and re-fetches them (11.4).
- `now()` is the transaction start time, so commit order and `server_updated_at` order can differ by the length of a transaction. The 120 s pull overlap covers this (11.5).
- Triggers fire in name order; `a_sync` always runs first. If it returns `null`, later triggers are skipped (correct: nothing is written).
- PostgreSQL orders `uuid` values byte by byte, which equals the lexicographic order of their lower-case text form, so the client comparator of ANDROID.md 9.6 (`device_id` compared as a plain string) gives the same winner as this trigger.

### 7.2 Append-only trigger

```sql
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
  -- Only the owner role (retention pruning, section 15) may update an append-only row.
  if current_user = 'postgres' then
    return new;
  end if;
  raise exception 'fitness.%: rows are append-only', tg_table_name using errcode = '42501';
end;
$$;
revoke all on function fitness_private.tg_append_only() from public;

create trigger a_append
  before insert or update on fitness.A
  for each row execute function fitness_private.tg_append_only();
```

`authenticated` has no `UPDATE` grant on APPEND tables, so the trigger is defence in depth.

### 7.3 Reference-data touch trigger

```sql
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
-- create trigger a_touch before insert or update on fitness.<ref table> for each row execute function fitness_private.tg_touch_reference();
```

### 7.4 Exercise visibility check (closes the global-catalog FK gap)

`exercises` mixes global rows and user rows, so references to it cannot use a composite foreign key. A plain FK would let user A reference user B's custom exercise if the UUID were known. This invoker-rights trigger runs under the caller's RLS (it can only see global and own exercises) and also checks ownership explicitly for roles that bypass RLS:

```sql
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

-- create trigger b_exercise_visible before insert or update of exercise_id on fitness.<T>
--   for each row execute function fitness_private.tg_check_exercise_visible();
```

Used on `user_exercise_prefs`, `rep_preferences`, `session_exercises`, `set_logs`, `exercise_model_state` and `exercise_stimulus` (user rows).

### 7.5 Enrollment revision trigger (LWW-REV)

```sql
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
```

The client-side optimistic lock is the same rule applied locally: `UPDATE challenge_enrollments SET ..., state_rev = :new WHERE id = :id AND state_rev = :expected`; zero rows updated means another fold won, so the client pulls, refolds and retries.

### 7.6 Challenge definition guard and checksum

```sql
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
```

A computed `GENERATED` column cannot be used for the checksum because `convert_to()` is `STABLE`, not `IMMUTABLE` (research, engineering-fact).

### 7.7 Symptom-code array check

Arrays cannot carry foreign keys, so `wellness_logs.symptom_codes` is validated by a trigger:

```sql
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
```

### 7.8 Policy templates

```sql
-- USER / CACHE table T
alter table fitness.T enable row level security;
grant select, insert, update on table fitness.T to authenticated;
grant select, insert, update, delete on table fitness.T to service_role;

create policy T_select on fitness.T for select to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create policy T_insert on fitness.T for insert to authenticated
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create policy T_update on fitness.T for update to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
-- No delete policy and no delete grant: soft delete via deleted_at.

-- APPEND table A
alter table fitness.A enable row level security;
grant select, insert on table fitness.A to authenticated;
grant select, insert on table fitness.A to service_role;
create policy A_select on fitness.A for select to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create policy A_insert on fitness.A for insert to authenticated
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));

-- REF table R
alter table fitness.R enable row level security;
grant select on table fitness.R to authenticated;
grant select, insert, update, delete on table fitness.R to service_role;
create policy R_select on fitness.R for select to authenticated
  using ((select fitness_private.is_allowed()));

-- MIX table M (global rows have user_id is null)
alter table fitness.M enable row level security;
grant select, insert, update on table fitness.M to authenticated;
grant select, insert, update, delete on table fitness.M to service_role;
create policy M_select on fitness.M for select to authenticated
  using ((select fitness_private.is_allowed())
         and (user_id is null or user_id = (select auth.uid())));
create policy M_insert on fitness.M for insert to authenticated
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
create policy M_update on fitness.M for update to authenticated
  using ((select fitness_private.is_allowed()) and user_id = (select auth.uid()))
  with check ((select fitness_private.is_allowed()) and user_id = (select auth.uid()));
```

Rules: one policy per command (no `FOR ALL`, which would overlap `FOR SELECT` and trigger lint 0006), always `to authenticated`, always the wrapped helper and wrapped `auth.uid()` (lint 0003), and an index whose leading column is `user_id` on every user table.

An upsert whose `id` collides with another user's row fails: the `DO UPDATE` branch must pass the update policy's `USING` clause on the existing row, which raises `42501` instead of silently overwriting.

### 7.9 Composite foreign key template

```sql
-- Parent
alter table fitness.workout_sessions
  add constraint workout_sessions_id_user_uq unique (id, user_id);

-- Child
create table fitness.session_blocks (
  -- standard columns ...
  session_id uuid not null,
  constraint session_blocks_session_fk
    foreign key (session_id, user_id)
    references fitness.workout_sessions (id, user_id)
    on delete cascade
  -- ...
);
create index session_blocks_session_fk_idx on fitness.session_blocks (session_id, user_id);
```

Rules:

- Structural children (`session_blocks`, `session_exercises`, `set_logs`) use `on delete cascade`; plain references use the default `no action` (checked at the end of the statement, so cascades from `auth.users` succeed in any order).
- Optional references are nullable; with `MATCH SIMPLE` a null `parent_id` skips the check.
- Every composite FK gets an index in exactly the FK column order (advisor lint 0001).
- A composite FK can also pin a denormalised column: `set_logs (session_exercise_id, user_id, exercise_id) references session_exercises (id, user_id, exercise_id)` guarantees a set's `exercise_id` equals its parent's, and makes a session exercise's `exercise_id` effectively immutable once sets exist (a change of exercise is a substitution, which creates a new row).

### 7.10 Data-migration pattern for seeds (idempotent)

```sql
insert into fitness.reason_codes (code, label_en, label_nl, ui_contexts, is_safety, sort)
values
  ('too_easy', 'Too easy', 'Te makkelijk', '{easier}', false, 10)
  -- ...
on conflict (code) do update
  set label_en    = excluded.label_en,
      label_nl    = excluded.label_nl,
      ui_contexts = excluded.ui_contexts,
      is_safety   = excluded.is_safety,
      sort        = excluded.sort
  where (fitness.reason_codes.label_en, fitness.reason_codes.label_nl, fitness.reason_codes.ui_contexts,
         fitness.reason_codes.is_safety, fitness.reason_codes.sort)
        is distinct from
        (excluded.label_en, excluded.label_nl, excluded.ui_contexts, excluded.is_safety, excluded.sort);
```

The `WHERE ... IS DISTINCT FROM` clause keeps `updated_at` unchanged when re-running the migration, so clients do not re-pull unchanged rows. Rows are never deleted by seeds; a removed code is set `active = false`. Immutable rows (published challenge definitions, algorithm versions) use `on conflict do nothing` plus an assertion that the stored hash equals the expected hash.

---

## 8. Value domains (text + CHECK)

Each domain below is enforced with a `CHECK (col in (...))` (or a regex) on every column that uses it. Changing a domain is a migration. Domains that challenge definitions reference (set flags, load modes, patterns, metric codes, muscle keys, reason codes, exercise tags) are also validated by the challenge definition validator (`challenge.cds`), which rejects unknown keys (C9, C10).

These domains are canonical for every stored code (C38): challenge definitions, the ALGORITHMS documents, UX.md and ANDROID.md use exactly these spellings, and no document introduces a synonym. Older spellings found in drafts are rejected by the CDS validator, not translated: `bodyweight_plus` is `bodyweight_plus_external`, `assisted_pin` is `assisted_machine`, and camelCase flags (`gripSlipBeforeLast`, `torsoRotation`, `hipRotation`, `handShift`) are the snake_case E6 values. Metric codes are the `metric_defs` rows (9.1.4), reason codes the `reason_codes` rows (9.1.3), and catalog slugs follow E54.

| Id | Domain | Values | Notes |
|----|--------|--------|-------|
| E1 | side | `both`, `L`, `R` | C7: one L+R pair = 1 set; each side row counts 0.5. |
| E2 | set_kind | `warmup`, `working`, `top`, `backoff`, `amrap`, `calibration`, `test`, `attempt` | `calibration` = RIRCAL-1 check or first exposure (C27, F14). `test` = test-day battery set. `attempt` = OAP attempt. |
| E3 | load_mode | `external`, `bodyweight`, `bodyweight_plus_external`, `assisted_machine`, `assisted_ordinal` | `assisted_ordinal` = finger assistance; ordinal only, never in load maths (C2). `assisted_machine` = pin-assisted machine (C2 calibration). Duration is a measure, not a load mode. C38: the only spellings; `bodyweight_plus` and `assisted_pin` are rejected. |
| E4 | input_unit | `kg`, `lb`, `level`, `fingers` | The unit as entered on the equipment (native). |
| E5 | rir_source | `chip`, `edited`, `calibration`, `protocol`, `import`, `missing` | `chip` = tapped as the log action (D9). `protocol` = fixed by the test protocol: a test-day 3RM entered with "Record" is stored with RIR 0 (C63). Warm-up sets logged with "Done" carry no effort and therefore `missing` (C63; they never enter an estimator). Imputed RIR is never stored in facts; it lives in the engine trace. |
| E6 | set flag | `grip_slip_before_last`, `jerky`, `torso_rotation`, `hip_rotation`, `technique_breakdown`, `failed_rep`, `hand_shift`, `used_straps` | C38: challenge definitions (`flagsOffered`, FitExpr identifiers) use exactly these snake_case keys; camelCase spellings are rejected. |
| E7 | session kind | `gym`, `home`, `test` | C34. Home habits are tiny `home` sessions. |
| E8 | session status | `planned`, `in_progress`, `finished`, `abandoned`, `skipped` | |
| E9 | template_source | `challenge_slot`, `family_combo`, `recommended`, `empty`, `habit`, `import`, `repeat` | C35. `repeat` (D49): a general session copied from a past finished general session; the CHECK gains it with the M3.4 migration, and `session_started` carries the source session's id. |
| E10 | block_kind | `challenge`, `general` | C34. |
| E11 | session exercise status | `pending`, `active`, `done`, `skipped`, `removed`, `deferred`, `not_done` | `skipped`/`removed` require a reason (UI-05); `not_done` = still pending when the session finished (no reason). |
| E12 | session exercise origin | `plan`, `challenge`, `user_added`, `substitute`, `live_algo` | |
| E13 | progression_mode | `model`, `double`, `rule`, `fixed`, `none` | C15: RX-1 model / DOUBLE ladder / RULE (challenge items; model in shadow). |
| E14 | rep_scheme | `range`, `fixed`, `amrap`, `hold`, `attempt`, `ladder` | |
| E15 | preference | `never`, `dislike`, `neutral`, `like`, `favorite` | C26 (multipliers live in params, not in the table). |
| E16 | goal | `improve`, `maintain`, `skip` | Plus a separate `focus` boolean for improve (C14). |
| E17 | wellness kind | `readiness`, `elbow_check`, `pain`, `soreness`, `srpe`, `sleep`, `illness` | F15, C33. |
| E18 | readiness_level | `worse`, `normal`, `better` | F15. |
| E19 | soreness_code | `never_sore`, `healed`, `still_sore` | UI-07 chips. Mapping to readiness points is the `readiness.soreness_points` param (ALGORITHMS_PLANNING section 23). |
| E20 | pain_severity | `mild`, `moderate`, `sharp` | UI-05/UI-07 labels. Each chip also writes `pain_score` on the 0-10 scale: mild 2, moderate 5, sharp 8 (C64; stop threshold 4). |
| E21 | calendar classified_kind | `challenge_session`, `planned_gym`, `run`, `ignored` | C20. `busy` is never stored on the server; `ignored` is stored only as a user override without title. |
| E22 | run_intensity | `easy`, `tempo`, `intervals`, `race`, `long` | F6, RunInterference. |
| E23 | calendar match_method | `marker`, `title_pattern`, `score`, `manual`, `adopted` | |
| E24 | calendar write_state | `none`, `pending_create`, `created`, `pending_update`, `updated`, `pending_delete`, `deleted`, `conflict` | D11. |
| E25 | enrollment status | `onboarding`, `active`, `paused`, `completed`, `abandoned` | |
| E26 | enrollment mode | `normal`, `deload`, `test_pending`, `extra_deload`, `injury` | Cached from the fold. |
| E27 | definition status | `draft`, `published`, `retired` | |
| E28 | rule stage | `safety`, `data`, `structural`, `progression`, `in_session` | C21 stage order safety < data < structural < progression. |
| E29 | rule outcome | `applied`, `suppressed_first_writer`, `suppressed_lock`, `suppressed_injury`, `no_effect`, `error` | |
| E30 | metric derivation | `measured` or regex `^calculated:[a-z0-9_]+$` | For example `calculated:personal_curve`, `calculated:multiplier3rm`, `calculated:assist_kg`. |
| E31 | metric source | `manual`, `test_day`, `session_derived`, `import`, `health_connect` | |
| E32 | user param source | `default`, `fitted`, `review`, `user` | |
| E33 | load scheme type | `plates`, `belt`, `dumbbells`, `stack`, `pin_assist`, `bodyweight`, `fixed` | Section 9.3.4. |
| E34 | calibration_source | `default`, `user_estimate`, `measured` | The assisted machine's 6.5 kg offset is a user estimate (C2, critique). |
| E35 | evidence_mode | `warn_only`, `auto_adjust_with_warning` | D10: default `warn_only`. |
| E36 | exercise pattern | `squat`, `squat_machine`, `lunge`, `hinge`, `hip_ext`, `knee_ext`, `knee_flex`, `hip_abd`, `hip_add`, `calf`, `horiz_push`, `incline_push`, `vert_push`, `dip`, `chest_fly`, `lateral_raise`, `rear_delt_fly`, `horiz_pull`, `vert_pull`, `straight_arm_pull`, `shrug`, `elbow_flex`, `elbow_ext`, `elbow_ext_overhead`, `wrist_flex`, `wrist_ext`, `forearm_rot`, `grip_hold`, `ext_rotation`, `core_flex`, `core_anti_ext`, `core_anti_rot`, `hang`, `skill_static`, `carry`, `conditioning` | 35 CAT-1 patterns plus `conditioning` (warm-up cardio such as the rower; zero stimulus). Contract pattern references must use these values (C38). |
| E37 | laterality | `bilateral`, `unilateral`, `alternating` | |
| E38 | measure | `reps`, `duration`, `attempt`, `checklist` | |
| E39 | contraction | `dynamic`, `eccentric_only`, `isometric`, `hang` | C8: only `dynamic` sets with load modes other than `assisted_ordinal` may enter SSE-1. |
| E40 | curve_class | `upper_compound`, `lower_machine_compound`, `lower_free_compound`, `upper_isolation`, `lower_isolation` | C3 class priors (values live in params). |
| E41 | rest_class | `strength_heavy`, `compound_lower`, `compound_upper`, `isolation`, `hold`, `core` | C18 table keys (values live in params). |
| E42 | safety_class | `machine`, `cable`, `isolation`, `free_compound`, `bodyweight`, `high_risk` | `high_risk` = weighted/one-arm pulls, loaded dips, loaded free compounds: no failure (D10), never a calibration set (F14). |
| E43 | grip | `supinated`, `neutral`, `pronated`, `mixed`, `none` | |
| E44 | rest_end_reason | `elapsed`, `skipped`, `next_set_early`, `elapsed_while_dead`, `session_finished`, `none` | `session_finished` (the session ended while a rest was running, ANDROID.md 12.3) appears only in the `rest_ended` telemetry: the rest after the last set has no following `set_logs` row. |
| E45 | equipment class | `barbell`, `dumbbell`, `machine`, `cable`, `bodyweight`, `smith`, `band`, `kettlebell`, `other` | |
| E46 | muscle family | `push`, `pull`, `legs`, `core`, `other` | Group family for presets and the push/pull/legs vocabulary. |
| E47 | recommendation kind | regex `^[a-z]+(\.[a-z_]+)+$`; registry in 10.1 | |
| E48 | event_type | regex `^[a-z][a-z0-9]*(_[a-z0-9]+)+$`; registry in 9.8.1 | snake_case `<area>_<event>` (C39). No FK (see 9.1.8). |
| E49 | module id | regex `^[a-z]+(\.[a-z]+)*$` | Registry ids. |
| E50 | semver | regex `^(0\|[1-9][0-9]*)\.(0\|[1-9][0-9]*)\.(0\|[1-9][0-9]*)$` | |
| E51 | hex sha256 | regex `^[0-9a-f]{64}$` | |
| E52 | iso week | regex `^[0-9]{4}-W(0[1-9]\|[1-4][0-9]\|5[0-3])$` | Computed by the `clock` module (C31). |
| E53 | params key | regex `^[a-z]+(\.[a-z0-9_]+)+$` | `<module>.<constant>` with a registry module id and a lower snake_case constant name, for example `rx.cap_up_compound` or `ui.todaycard.close_rel` (C41); the module part may contain dots, so keys are parsed by longest registered module prefix. |
| E54 | slug / code | regex `^[a-z][a-z0-9_]{0,63}$` | Catalog slugs are snake_case (C38). |
| E55 | hold_class | `grip`, `elbow`, `shoulder`, `trunk`, `knee`, `ankle` | C43: catalog attribute of every hold exercise; selects the HOLD-1 exponent. |
| E56 | rest_source | `measured`, `estimated` | How `set_logs.rest_before_s` was obtained: measured (explicit hold start) or estimated from the log time (UX.md 19.8). |
| E57 | proposal decision | `approved`, `declined` | D8, C48: the decision on one `P` proposal. Used by `challenge_enrollments.rule_decisions`, the `rules.approval_changed` payload (9.7.1) and the `challenge_proposal_decided` telemetry (9.8.1); `challenge_rule_events.rule_decision` adds `not_required` for rules that belong to no proposal. An undecided proposal has no value (it is inactive). |

---

## 9. Table-by-table specification (v1)

### 9.0 Overview

| # | Table | Schema | Class | Sync class | Mutability | Migration | Main writers / readers |
|---|-------|--------|-------|-----------|------------|-----------|------------------------|
| 1 | `allowed_users` | fitness_private | PRIV | NONE | Migration / manual | 002, 003 | RLS helper |
| 2 | `muscle_groups` | fitness | REF | REF-PULL | Seed | 004, 005 | catalog, targets, ui.volumebars |
| 3 | `muscle_regions` | fitness | REF | REF-PULL | Seed | 004, 005 | catalog, select, rotate, challenge.cds |
| 4 | `reason_codes` | fitness | REF | REF-PULL | Seed | 004, 005 | reason, ui.reasonchips |
| 5 | `metric_defs` | fitness | REF | REF-PULL | Seed | 004, 005 | challenge.cds, challenge.eca |
| 6 | `body_regions` | fitness | REF | REF-PULL | Seed | 004, 005 | safety, injury |
| 7 | `symptom_codes` | fitness | REF | REF-PULL | Seed | 004, 005 | safety |
| 8 | `equipment_types` | fitness | REF | REF-PULL | Seed | 004, 005 | elig, load, onboarding |
| 9 | `session_event_types` | fitness | REF | NONE | Seed | 004, 005 | Reviews only |
| 10 | `algorithm_versions` | fitness | REF | REF-PULL | Seed per release | 004, 005 | Every snapshot |
| 11 | `algo_params` | fitness | REF | REF-PULL | Seed per release | 004, 005 | Params resolver |
| 12 | `catalog_releases` | fitness | REF | REF-PULL | Seed per release | 008, 009 | catalog |
| 13 | `exercises` | fitness | MIX | MIX | Seed + user custom | 008, 009 | catalog, elig, select, rx, load |
| 14 | `exercise_stimulus` | fitness | MIX | MIX | Seed + user custom | 008, 009 | catalog, setcredit |
| 15 | `exercise_transfer_ratios` | fitness | REF | REF-PULL | Seed | 008, 009 | substitute |
| 16 | `profiles` | fitness | USER | LWW | Mutable | 006 | Settings |
| 17 | `gyms` | fitness | USER | LWW | Mutable | 006 | weekplan, calendar.classify |
| 18 | `gym_equipment` | fitness | USER | LWW | Mutable | 010 | load, elig, ui.prefill |
| 19 | `rep_preferences` | fitness | USER | LWW | Mutable | 010 | rx, select, warnings |
| 20 | `muscle_goals` | fitness | USER | LWW | Mutable | 010 | targets, elig |
| 21 | `user_exercise_prefs` | fitness | USER | LWW | Mutable | 010 | pref, elig |
| 22 | `user_algo_params` | fitness | USER-RO | SRV-PULL | Server-written | 010 | Params resolver, tuner |
| 23 | `challenge_definitions` | fitness | MIX (read-only v1) | DEF-PULL | Immutable when published | 011, 016, 018 | challenge.* |
| 24 | `challenge_enrollments` | fitness | USER | LWW-REV | Cached fold | 011 | challenge.enrollment |
| 25 | `params_snapshots` | fitness | APPEND | APPEND | Insert-only | 012 | Replay |
| 26 | `recommendations` | fitness | APPEND | APPEND | Insert-only | 012 | Every engine module |
| 27 | `workout_sessions` | fitness | USER | LWW | Mutable | 013 | Session flow |
| 28 | `session_blocks` | fitness | USER | LWW | Mutable | 013 | C34 blocks |
| 29 | `session_exercises` | fitness | USER | LWW | Mutable | 013 | Session flow |
| 30 | `set_logs` | fitness | USER | LWW | Mutable | 013 | Logger; all estimators |
| 31 | `wellness_logs` | fitness | USER | LWW | Mutable | 013 | safety, readiness, ui.prompts |
| 32 | `metric_observations` | fitness | USER | LWW | Mutable | 013 | bodyweight, challenge gates |
| 33 | `challenge_events` | fitness | APPEND | APPEND | Insert-only | 014 | challenge.enrollment fold |
| 34 | `challenge_rule_events` | fitness | APPEND | APPEND | Insert-only | 014 | challenge.eca audit |
| 35 | `calendar_events` | fitness | USER | LWW | Mutable | 014 | calendar.classify, challenge.calendar |
| 36 | `session_events` | fitness | APPEND | APPEND | Insert-only | 015 | UI-12 telemetry |
| 37 | `exercise_model_state` | fitness | CACHE | LWW | Rebuildable | 015 | Estimator caches |
| 38 | `exports` | fitness | APPEND | APPEND | Insert-only | 015 | F4 export log |

Unless stated otherwise, every USER/CACHE table has the S-MUT columns, the `a_sync` trigger, the USER policy template (7.8), `index (user_id, server_updated_at)` and `unique (id, user_id)`. Every APPEND table has the S-APP columns, the `a_append` trigger, the APPEND template and `index (user_id, server_updated_at)`. Every REF table has the S-REF columns, the `a_touch` trigger and the REF template. These are not repeated in each table below.

Column tables use: **Null** = `null` allowed or `not null`; **Default** = column default; **Check / FK** = constraints on that column.

### 9.1 Global reference tables (class REF)

#### 9.1.1 `fitness.muscle_groups`

The 18 user-facing groups that carry goals and weekly volume (C9), plus the optional `neck`.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `id` | text | not null | | PK, E54 | Canonical group key. |
| `family` | text | not null | | E46 | push / pull / legs / core / other. |
| `label_en` | text | not null | | length 1-40 | |
| `label_nl` | text | null | | length 1-40 | |
| `aliases` | text[] | not null | `'{}'` | each E54 | Authoring-time synonyms (for example `mid_back`). Stored definitions and catalog rows use canonical ids only. |
| `is_optional` | boolean | not null | `false` | | `neck` only. |
| `svg_region_ids` | text[] | not null | `'{}'` | | Element ids in the muscle-map SVG (front/back), drawn from this list (C9). |
| S-REF | | | | | `active`, `sort`, `updated_at`. |

Seed (migration 005):

| id | family | aliases | | id | family | aliases |
|----|--------|---------|-|----|--------|---------|
| `chest` | push | | | `forearms` | pull | `grip` |
| `front_delts` | push | `front_delt` | | `upper_traps` | pull | |
| `side_delts` | push | `side_delt` | | `abs` | core | |
| `triceps` | push | | | `lower_back` | core | |
| `lats` | pull | | | `glutes` | legs | |
| `upper_back` | pull | `mid_back` | | `abductors` | legs | |
| `rear_delts` | pull | `rear_delt` | | `adductors` | legs | |
| `biceps` | pull | `elbow_flexors` | | `quads` | legs | |
| | | | | `hamstrings` | legs | |
| | | | | `calves` | legs | |
| | | | | `neck` | other | (optional, `active = false`) |

Tunable constants per group (readiness τ, F_REF, MRV, improve base) are **not** stored here; they live in the params JSON (C5, C14).

#### 9.1.2 `fitness.muscle_regions`

Region level for variety bonuses and challenge mapping (C9). Every region maps to exactly one group, except three prehab-only regions.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `id` | text | not null | | PK, E54 | |
| `group_id` | text | null | | FK `muscle_groups(id)` | Null only for prehab-only regions. |
| `is_prehab_only` | boolean | not null | `false` | `check ((group_id is null) = is_prehab_only)` | |
| `aliases` | text[] | not null | `'{}'` | each E54 | Keys used by the research drafts (for example the OAP draft), rewritten to canonical ids by the CDS validator. |
| `label_en` / `label_nl` | text | not null / null | | length 1-60 | |
| S-REF | | | | | |

Seed (35 rows; 34 from the exercise-selection lens plus `neck`):

| Region | Group | Aliases | | Region | Group | Aliases |
|--------|-------|---------|-|--------|-------|---------|
| `chest_clavicular` | chest | | | `rectus_abdominis` | abs | |
| `chest_sternal` | chest | | | `obliques` | abs | |
| `delt_anterior` | front_delts | | | `anti_rotation` | abs | `core_antirotation` |
| `delt_lateral` | side_delts | | | `erectors` | lower_back | |
| `delt_posterior` | rear_delts | `rear_delts` | | `glute_max` | glutes | |
| `lats` | lats | | | `glute_med_min` | abductors | |
| `traps_mid` | upper_back | `mid_traps` | | `adductors` | adductors | |
| `traps_lower` | upper_back | `lower_traps` | | `vasti` | quads | |
| `rhomboids` | upper_back | | | `rectus_femoris` | quads | |
| `traps_upper` | upper_traps | | | `hams_biarticular` | hamstrings | |
| `biceps_brachii` | biceps | `biceps` | | `bf_short_head` | hamstrings | |
| `brachialis` | biceps | | | `gastrocnemius` | calves | |
| `brachioradialis` | biceps | | | `soleus` | calves | |
| `triceps_long` | triceps | | | `rotator_cuff_er` | (prehab) | `rotator_cuff_ER` |
| `triceps_lat_med` | triceps | | | `serratus_anterior` | (prehab) | |
| `wrist_flexors_grip` | forearms | `forearm_flexors` | | `tibialis_anterior` | (prehab) | |
| `wrist_extensors` | forearms | | | `neck` | neck | (inactive) |
| `pronators_supinators` | forearms | | | | | |

`brachioradialis` maps to `biceps` because it is an elbow flexor (the group is "biceps (elbow flexors)" in the exercise-selection lens). The research name `rotator_cuff_ER` is lower-cased to satisfy E54. The count is 34 regions plus the optional `neck`, as C44 fixes (superseding the "~32" of C9); `obliques` is a region of the `abs` group (C44).

#### 9.1.3 `fitness.reason_codes`

The 18 canonical override, skip, pause and correction reasons (13 in 005, five more in 021, D34). Semantics (estimator class, session effect, future effect) belong to REASON-1 and its params (C16); this table holds only labels and where each chip may appear. One semantic fact is fixed by DECISIONS: `too_tired` has the same effect as `feeling_bad` (C57).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `code` | text | not null | | PK, E54 | |
| `label_en` / `label_nl` | text | not null / null | | length 1-40 | |
| `ui_contexts` | text[] | not null | | subset of `easier`, `harder`, `skip`, `session_end`, `substitute`, `pause`, `correction` | Where the chip may be offered; order within a context is UI-05's job. `pause` is the pause dialog (UX 14.7), `correction` Correct a value (D34). |
| `is_safety` | boolean | not null | `false` | | `pain` only: opens the pain sheet and the SafetyState path (C33). |
| `implies_flag` | text | null | | E6 | When chosen on a set, this flag must also be present (enforced by a `set_logs` CHECK). |
| S-REF | | | | | |

Seed:

| code | label_en | label_nl | ui_contexts | is_safety | implies_flag |
|------|----------|----------|-------------|-----------|--------------|
| `too_easy` | Too easy | Te makkelijk | easier, correction | false | |
| `too_hard` | Too hard | Te zwaar | harder, correction | false | |
| `feeling_good` | Feeling good | Voel me goed | easier | false | |
| `feeling_bad` | Feeling bad | Voel me slecht | harder, skip | false | |
| `too_tired` | Too tired | Te moe | harder, skip | false | |
| `no_time` | No time | Geen tijd | harder, skip, session_end | false | |
| `occupied` | Occupied | Bezet | harder, skip, substitute | false | |
| `equipment_missing` | Equipment missing | Materiaal ontbreekt | skip, substitute | false | |
| `pain` | Pain | Pijn | harder, skip, session_end | true | |
| `grip_failure` | Grip failed | Grip gaf op | harder | false | |
| `torso_rotation` | Torso rotation | Romp draaide | harder | false | `torso_rotation` |
| `technique_breakdown` | Technique broke down | Techniek brak af | harder | false | `technique_breakdown` |
| `other` | Other | Anders | easier, harder, skip, session_end, pause, correction | false | |
| `holiday` | Holiday | Vakantie | pause | false | |
| `illness` | Illness | Ziek | pause | false | |
| `injury` | Injury | Blessure | pause | false | |
| `loaded_different` | Loaded a different weight | Ander gewicht geladen | correction | false | |
| `value_wrong` | The value was wrong | Waarde klopte niet | correction | false | |

#### 9.1.4 `fitness.metric_defs`

Metric definitions referenced by challenge definitions (by code) and by `metric_observations`.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `code` | text | not null | | PK, E54 | |
| `value_type` | text | not null | | `number`, `int`, `bool` | |
| `unit` | text | not null | | `kg`, `s`, `reps`, `pin_lb`, `bool` | Must equal `metric_observations.unit`. |
| `side_mode` | text | not null | `'none'` | `none`, `LR` | |
| `better` | text | not null | `'none'` | `higher`, `lower`, `true`, `none` | |
| `grid` | numeric(6,2) | null | | > 0 | Canonical resolution (C22): kg 0.01, s 0.1, reps 1, pin 5. |
| `is_derived` | boolean | not null | `false` | | Derived metrics need `derivation = calculated:<method>`. |
| `display_only` | boolean | not null | `false` | `not display_only or is_derived` | Shown for transparency, never read by a gate or progress rule (the definition's `displayOnly`, D7). |
| `introduced_by` | text | not null | `'core'` | `core` or `^challenge:[a-z0-9_]+$` | |
| `protocol_default` | text | null | | | Human-readable default protocol. |
| `label_en` / `label_nl` | text | not null / null | | | |
| S-REF | | | | | |

Seed:

| code | value_type | unit | side_mode | better | grid | derived | introduced_by | Note |
|------|-----------|------|-----------|--------|------|---------|---------------|------|
| `bw` | number | kg | none | none | 0.01 | no | core | Weigh-in. BW(t) EWMA is engine output, not a metric (C32). Gates use the raw test-day weigh-in (C62). |
| `two_arm_hang_best_s` | number | s | none | higher | 0.1 | no | core | Weekly fresh best of the daily hang (Todoist rule, section 4 of DECISIONS). |
| `wpu_3rm_added` | number | kg | none | higher | 0.01 | no | challenge:oap | |
| `wpu_1rm_added` | number | kg | none | higher | 0.01 | no | challenge:oap | Measured single only (history; D7 adds no single to test days). |
| `wpu_e1rm_added` | number | kg | none | higher | 0.01 | yes | challenge:oap | D7 gate metric (personal curve; class prior `k` = 30 until RCM-1 has informative windows, C59). |
| `wpu_e1rm_added_m110` | number | kg | none | higher | 0.01 | yes, display only | challenge:oap | The x1.10 value shown next to the gate value (D7). |
| `wpu_e1rm_added_hi` / `wpu_e1rm_added_lo` | number | kg | none | higher | 0.01 | yes, display only | challenge:oap | Upper and lower end of the 80 % interval of the gate value (D7). |
| `bw_max_reps` | int | reps | none | higher | 1 | no | challenge:oap | |
| `oa_hang_s` | number | s | LR | higher | 0.1 | no | challenge:oap | |
| `oa_top_hold_s` | number | s | LR | higher | 0.1 | no | challenge:oap | |
| `oa_least_assist_pin` | number | pin_lb | LR | lower | 5 | no | challenge:oap | |
| `oa_least_assist_kg` | number | kg | LR | lower | 0.01 | no | challenge:oap | Effective assistance; `calculated:assist_kg` from the pin, or measured/recorded (legacy import). |
| `oa_neg5_unassisted` | bool | bool | LR | true | | no | challenge:oap | |
| `oa_one_finger_rep` | bool | bool | LR | true | | no | challenge:oap | |
| `oa_rep` | bool | bool | LR | true | | no | challenge:oap | |

The seed has 16 rows. It is the union of the metric ids of `docs/challenges/oap.challenge.v1.json` (C38) plus `oa_least_assist_kg` (effective assistance for imports and the assisted-kg display); the definition needs no rename for metrics. The definition's metric `type` maps to `unit` as mass -> `kg`, duration -> `s`, int -> `reps`, pin -> `pin_lb`, bool -> `bool`.

#### 9.1.5 `fitness.body_regions`

Pain locations for the pain sheet (UI-05/UI-07) and the PainEvent fact (C33).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `code` | text | not null | | PK, E54 | |
| `joint_keys` | text[] | not null | `'{}'` | subset of `elbow_medial`, `elbow_lateral`, `shoulder_ant`, `shoulder_overhead`, `wrist`, `lumbar`, `knee`, `hip` | INJ-1 joint-stress keys this region flags. Mapping is an **expert-opinion** assumption, tunable from pain logs versus later exercise tolerance. |
| `side_applicable` | boolean | not null | `true` | | |
| `label_en` / `label_nl` | text | | | | |
| S-REF | | | | | |

Seed: `elbow_medial` {elbow_medial}, `elbow_lateral` {elbow_lateral}, `elbow_anterior` {elbow_medial, elbow_lateral} (conservative), `elbow_posterior` {elbow_medial, elbow_lateral} and `forearm` {elbow_medial, elbow_lateral, wrist} (both conservative; migration 032, D45), `shoulder` {shoulder_ant, shoulder_overhead}, `wrist` {wrist}, `hand_fingers` {wrist}, `neck` {}, `upper_back` {}, `lower_back` {lumbar}, `hip` {hip}, `knee` {knee}, `ankle_foot` {}, `other` {}.

#### 9.1.6 `fitness.symptom_codes`

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `code` | text | not null | | PK, E54 | |
| `body_region_codes` | text[] | not null | `'{}'` | | Regions where the chip is offered (empty = everywhere). |
| `label_en` / `label_nl` | text | | | | |
| S-REF | | | | | |

Seed: `medial_elbow_ache_after` {elbow_medial}, `elbow_crease_pain_on_extension` {elbow_anterior}, `ulnar_tingling` {elbow_medial, hand_fingers}, `sharp_pain_during` {}, `dull_ache_during` {}, `ache_after` {}, `stiffness` {}, `swelling` {}, `other` {}. The three OAP stop signals are the first three codes; which codes trigger the challenge pain protocol is challenge data (`safety.elbow.stop` rule), not this table.

#### 9.1.7 `fitness.equipment_types`

Station and implement keys used by `exercises.requires_station` and `gym_equipment.equipment_type` (F7).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `key` | text | not null | | PK, E54 | |
| `category` | text | not null | | `station`, `implement`, `accessory`, `cardio`, `home` | |
| `default_scheme_type` | text | null | | E33 | |
| `default_load_scheme` | jsonb | null | | object | Template copied into `gym_equipment.load_scheme` by the first-run checklist. |
| `portable` | boolean | not null | `false` | | Dip belt, own plates, chalk. |
| `label_en` / `label_nl` | text | | | | |
| S-REF | | | | | |

Seed (Basic-Fit defaults per F7, all editable in the checklist; the machine list at Ierse Pond is unverified): `pullup_bar`, `fixed_neutral_handle`, `assisted_pullup_machine` (pin_assist `{unit lb, step 5, min 0, max null, offset_kg 6.5, ratio 1.0}`), `dip_station`, `dip_belt` (portable, belt), `barbell` (plates), `smith_machine` (plates, bar weight unknown, asked), `dumbbells`, `adjustable_bench`, `cable_station`, `lat_pulldown`, `seated_cable_row`, `chest_supported_row`, `chest_press_machine`, `pec_deck`, `shoulder_press_machine`, `lateral_raise_machine`, `leg_press`, `hack_squat`, `leg_extension`, `leg_curl_seated`, `leg_curl_lying`, `hip_thrust_machine`, `hip_abduction`, `hip_adduction`, `calf_machine`, `back_extension`, `rower_erg` (cardio), `wide_bar` (optional), `rings` (optional), `chalk` (accessory, portable), `home_bar` (home).

#### 9.1.8 `fitness.session_event_types`

A registry of `session_events.event_type` values (9.8.1, canonical by C39), used for documentation, by the client's payload validator and by the data-quality review view. There is deliberately **no foreign key** from `session_events`: a new event type shipped in an app release before its registry row exists must not block the outbox. Not pulled by the app.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `event_type` | text | not null | | PK, E48 | |
| `category` | text | not null | | as `session_events.category` | |
| `payload_keys` | text[] | not null | `'{}'` | | Allowed payload keys. |
| `introduced_in` | text | not null | | app version | |
| `description` | text | not null | | | |
| S-REF | | | | | |

#### 9.1.9 `fitness.algorithm_versions`

Registry of every module id@version (C30). Seeded with all 51 registry modules at `1.0.0`.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `module_id` | text | not null | | PK part, E49 | |
| `version` | text | not null | | PK part, E50 | |
| `layer` | text | not null | | `core`, `challenge`, `accounting`, `estimation`, `targets`, `planning`, `selection`, `prescription`, `ui`, `offline` | |
| `engine_version` | text | not null | | E50 | The `:algo` `ENGINE_VERSION` that first shipped this module version. |
| `status` | text | not null | `'active'` | `active`, `shadow`, `deprecated` | |
| `default_params_version` | text | not null | | FK `algo_params(params_version)` | |
| `constants_hash` | text | not null | | E51 | JCS SHA-256 of this module's slice (`<module>.*` keys, values only) of `default_params_version`. |
| `git_sha` | text | null | | `^[0-9a-f]{7,40}$` | |
| `released_at` | timestamptz | not null | `now()` | | |
| `spec_ref` | text | null | | | Document anchor, for example `docs/ALGORITHMS.md#sse-1`. |
| `description` | text | null | | | |
| `updated_at` | timestamptz | not null | `now()` | | `a_touch`. |

Seed module ids (layer): core: `clock`, `load`, `bodyweight`, `calendar.classify`, `safety`; challenge: `challenge.cds`, `challenge.fitexpr`, `challenge.enrollment`, `challenge.eca`, `challenge.materialize`, `challenge.plan`, `challenge.contract`, `challenge.calendar`; accounting: `setcredit`, `readiness`; estimation: `rircal`, `rcm`, `sse`, `fatigue`, `hold`, `stall`; targets: `targets`, `runs`; planning: `allocator`, `today`, `weekplan`; selection: `catalog`, `pref`, `elig`, `select`, `order`, `substitute`, `rotate`, `time`, `injury`; prescription: `rx`, `warmup`, `rest`, `reason`, `autoreg`, `warnings`; ui: `ui.todaycard`, `ui.prefill`, `ui.reasonchips`, `ui.prompts`, `ui.volumebars`, `ui.warndisplay`, `ui.progress`; offline: `tuner`, `sim`, `replay`.

Release rule: a new module version is registered by a data migration **before** the APK that uses it is installed. `recommendations` has no FK to this table (a missing registration must not block sync); the data-quality view (14.20) lists unregistered versions instead.

#### 9.1.10 `fitness.algo_params`

Versioned default constants for all modules. Keys are `<module>.<constant>` (E53).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `params_version` | text | not null | | PK, E50 | For example `1.0.0`. |
| `status` | text | not null | `'draft'` | `draft`, `active`, `superseded` | `unique (status) where status = 'active'`. |
| `engine_version_range` | text | not null | | | For example `>=1.0.0 <2.0.0`. |
| `params` | jsonb | not null | | `jsonb_typeof = 'object'` | Map key to spec (below). |
| `params_hash` | text | not null | | E51 | JCS SHA-256 of `{key: value}` only (metadata excluded), computed by the generator; the app recomputes on load and refuses a mismatch. |
| `released_at` | timestamptz | null | | | |
| `notes` | text | null | | | |
| `updated_at` | timestamptz | not null | `now()` | | `a_touch`. |

Entry spec, one per key:

```json
"sse.sd_day": {
  "value": 0.035,
  "unit": "ln",
  "min": 0.01,
  "max": 0.10,
  "evidence": "strong",
  "source": "Grgic 2020 Sports Med Open (1RM CV median 4.2 %)",
  "tunable": true,
  "tuned_by": "one-step-ahead first-set n_eff error (v_e1rm_history, v_set_estimates)"
}
```

Params 1.0.1 (migration 019, 2026-10-09) supersedes 1.0.0: only `load.default_plate_inventory` changed (counts 4 -> 20, F7). The content of the 1.0.0 params set is defined by the ALGORITHMS documents; migration 005 inserts it from the repo file `algo/src/main/resources/fitness/params/default-params-1.0.0.json` (C40; the same file is bundled in the APK as the offline fallback). Keys follow C41 (E53). Platform constants (timers, notifications, reminders, sync backoff) are not in this file and not in `params_hash`; they live in `PlatformConfig` (C54). Activation: the app uses the newest `active` version whose range admits its `ENGINE_VERSION`, pins it at session start and never switches mid-session.

#### 9.1.11 `fitness.catalog_releases`

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `version` | text | not null | | PK, E50 | `catalog_version` stored in snapshots (C24). |
| `released_at` | timestamptz | not null | `now()` | | |
| `exercise_count` / `stimulus_count` | int | not null | | >= 0 | Sanity check after seeding. |
| `jcs_sha256` | text | not null | | E51 | Hash of the bundled catalog JSON. |
| `notes` | text | null | | | |
| `updated_at` | timestamptz | not null | `now()` | | `a_touch`. |

### 9.2 Catalog

#### 9.2.1 `fitness.exercises` (MIX)

Global curated catalog (~150 entries, CAT-1) plus user custom exercises. The OAP challenge-local exercises are catalog entries too (C10): every exercise key of a challenge definition is either a `catalogRef` to a catalog slug (directly or through a parameter map, for example the OAP key `weighted_pullup` resolves to `chinup_weighted`, `neutral_grip_pullup_weighted` or `pullup_weighted` by grip) or a challenge-local entry that is seeded as a global row whose slug is the definition key (for example `oap_assisted_machine`). Definition keys that resolve through `catalogRef` are not slugs. The catalog seed contains every slug a published definition references (C38; list in 13.2, migration 007).

Standard columns: S-MUT with `user_id` **nullable** (null = global, written only by migrations with `device_id = SERVER_DEVICE_ID`, `app_version = 'migration'`).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `slug` | text | not null | | E54; `unique nulls not distinct (user_id, slug)` | Global ids are UUIDv5 `exercise:<slug>`. |
| `name_en` | text | not null | | length 1-80 | D12. |
| `name_nl` | text | null | | length 1-80 | |
| `aliases` | text[] | not null | `'{}'` | | Search and calendar-description matching. |
| `pattern` | text | not null | | E36 | |
| `family` | text | not null | | E54 | Substitution family (CAT-1 seed table), for example `vert_pull_family`. |
| `equipment` | text[] | not null | | non-empty subset of E45 | |
| `requires_station` | text[] | not null | `'{}'` | keys of `equipment_types` (validated by CAT-1 and the data-quality view) | F7. |
| `load_mode` | text | not null | | E3; `check (load_mode = any (allowed_load_modes))` | Default mode. |
| `allowed_load_modes` | text[] | not null | | non-empty subset of E3 | LOAD-1: one exercise can hold assisted / bodyweight / weighted modes on one load scale. |
| `bw_fraction` | numeric(5,4) | not null | `0` | 0-1; `> 0` when a bodyweight mode is allowed | C1: pull-up, chin-up, one-arm, dip family 1.0000; push-up 0.6400; knee push-up 0.4900; feet raised 30 cm 0.7000 / 61 cm 0.7400; hands raised 30 cm 0.5500 / 61 cm 0.4100; inverted row 0.6000. |
| `implement_base_kg` | numeric(6,2) | null | | >= 0 | Bar, sled or carriage mass not included in the entered load. Null = unknown (Smith bar: ask, F7). |
| `laterality` | text | not null | | E37 | |
| `measure` | text | not null | | E38 | |
| `contraction` | text | not null | `'dynamic'` | E39 | C8 filter for SSE-1. |
| `hold_class` | text | null | | E55; `check ((hold_class is not null) = (contraction in ('isometric', 'hang')))` | C43: selects the HOLD-1 exponent (for example the OAP straight-arm hold is `shoulder`, dead hangs are `grip`). |
| `ecc_emphasis` | boolean | not null | `false` | | SetCredit fatigue factor. |
| `mechanics` | text | not null | | `compound`, `isolation`, `skill`, `conditioning` | |
| `curve_class` | text | null | | E40 | Null for holds, skill and conditioning. |
| `rest_class` | text | not null | | E41 | |
| `safety_class` | text | not null | | E42 | |
| `grip` | text | not null | `'none'` | E43 | |
| `stretch_emphasis` | smallint | not null | `0` | 0-2 | |
| `stability`, `skill` | smallint | not null | `1` | 1-3 | |
| `axial` | smallint | not null | `0` | 0-3 | |
| `systemic` | smallint | not null | `1` | 1-3 | |
| `sfr` | smallint | not null | `3` | 1-5 | |
| `setup_s` | smallint | not null | `30` | 0-600 | TIME-1 seed value (calibrated per user in `exercise_model_state`, model `time`). |
| `grip_demand` | smallint | not null | `0` | 0-3 | |
| `js_elbow_medial`, `js_elbow_lateral`, `js_shoulder_ant`, `js_shoulder_overhead`, `js_wrist`, `js_lumbar`, `js_knee`, `js_hip` | smallint | not null | `0` | 0-3 each | INJ-1 joint stress. |
| `tags` | text[] | not null | `'{}'` | each E54 | Reserved tags: `challenge`, `prehab`, `skill`, `core`, `hang`, `home`, `pull_heavy`, `grip_heavy`, `elbow_flexor_heavy`, `supinated_curl`, `unilateral`, `unilateral_pull`, `heavy_unilateral`, `eccentric`, `elbow_stress_high`, `axial_heavy`, `overhead_press_heavy`, `unstrapped_deadlift`, `no_grip`. This list is the union of the tags used by the OAP definition (exercise tags, `excludeItemTags`, `forbidExerciseTags`) and by the safety module's forbid-tag table (ALGORITHMS_PLANNING section 2) (C38). C11/C12 contracts reference these. |
| `flags_offered` | text[] | not null | `'{}'` | subset of E6 | C74: set flags the logger offers for this exercise (UX 11.9); challenge items may add flags through the definition overlay. |
| `external_refs` | jsonb | not null | `'{}'` | object | `{fedb_id, wger_uuid}`. |
| `evidence_note` | text | null | | | |
| `prototype_exercise_id` | uuid | null | | FK `exercises(id)` | Custom exercises copied from a prototype. |
| `catalog_version` | text | not null | | FK `catalog_releases(version)` | Version in which the row last changed (custom rows: version they were created under). |
| `retired_at` | timestamptz | null | | | Retired global rows stay referenced by history. |

Constraints and indexes: `unique (id, user_id)` (MATCH SIMPLE parent for user stimulus rows); `index (user_id, server_updated_at)`; `index (pattern)`; `index (prototype_exercise_id)`. Grants: MIX template (authenticated may insert/update **own** rows only).

Group coefficients (`max` over regions in the group) are derived by the `catalog` module, never stored.

#### 9.2.2 `fitness.exercise_stimulus` (MIX)

Region-level stimulus coefficients (CAT-1). Absent row = coefficient 0.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | `user_id` nullable | Global rows: `user_id` null. |
| `exercise_id` | uuid | not null | | FK `exercises(id)`; plus FK `(exercise_id, user_id) -> exercises(id, user_id)` (enforced for user rows) | |
| `region_id` | text | not null | | FK `muscle_regions(id)` | |
| `coeff` | numeric(3,2) | not null | | `in (0.25, 0.50, 0.75, 1.00)` | C10 discrete levels (0 is represented by no row). |
| `evidence` | text | null | | | For example `Kubo 2019`, `expert-opinion`. |
| `note` | text | null | | | |

Constraints: `unique (exercise_id, region_id) where deleted_at is null`; indexes `(exercise_id)`, `(exercise_id, user_id)`, `(region_id)`, `(user_id, server_updated_at)`. Trigger `b_exercise_visible`. Seed validation (CAT-1, CI): at least one region >= 0.75 unless tagged `prehab`, `skill` or `hang`.

#### 9.2.3 `fitness.exercise_transfer_ratios` (REF)

Start-load transfer for substitutions (SUB-1): `load(to) = ratio x load(from)` on the effective-load scale.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `from_exercise_id` | uuid | not null | | PK part, FK `exercises(id)` | Global exercises only. |
| `to_exercise_id` | uuid | not null | | PK part, FK `exercises(id)` | |
| `ratio` | numeric(6,4) | not null | | 0.2000-3.0000 | **expert-opinion**, tunable from first-set residuals after substitutions (`v_set_estimates`). |
| `evidence` | text | not null | `'expert-opinion'` | | |
| `catalog_version` | text | not null | | FK `catalog_releases(version)` | |
| S-REF minus key | | | | | `active`, `updated_at`. |

### 9.3 Per-user configuration (class USER unless stated)

#### 9.3.1 `fitness.profiles`

One row per user, created lazily by the app on first login (never by a trigger on `auth.users`).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | `check (id = user_id)` | |
| `display_name` | text | null | | length 1-60 | |
| `timezone` | text | not null | `'Europe/Amsterdam'` | `^[A-Za-z_]+(/[A-Za-z0-9_+-]+)*$` | IANA zone (F18). Validated against `java.time.ZoneId` on the client. |
| `week_start_dow` | smallint | not null | `1` | `= 1` | ISO Monday (F18). Column exists for future flexibility. |
| `mass_unit` | text | not null | `'kg'` | `kg`, `lb` | Display unit for plates and dumbbells. Pins and stacks always show their native unit. |
| `ui_language` | text | not null | `'en'` | `en`, `nl` | D12: English UI. |
| `decimal_separator` | text | not null | `'.'` | `.`, `,` | D12: decimal point. |
| `sex` | text | null | | `female`, `male`, `other`, `unspecified` | Optional. No default may depend on it (D3). |
| `birth_year` | smallint | null | | 1900-2100 | Optional (maintain rules for age >= 60). |
| `training_age` | text | null | | `novice`, `intermediate`, `advanced` | Asked at onboarding (C14 BASE). Null = not answered; the engine then uses `novice` (C64). |
| `bodyweight_fallback_kg` | numeric(5,2) | null | | 20-300 | Used only when no weigh-in exists (C32). |
| `max_gym_visits_per_week` | smallint | not null | `4` | 1-7 | D5. |
| `default_session_min` | smallint | not null | `75` | 20-240 | F11: budget when no calendar event exists. |
| `changeover_min` | smallint | not null | `5` | 0-60 | F11 as revised by C49: calendar gym events are training time (travel happens before them), so the session budget is the event duration minus this changeover. Replaces the earlier `travel_buffer_min` (15). |
| `availability` | jsonb | not null | `'{}'` | object | `{"mon": [["17:30","21:30"]], ...}`; `{}` means "use gym opening hours". |
| `default_gym_id` | uuid | null | | FK `(default_gym_id, user_id) -> gyms(id, user_id)` | |
| `calendar_read_enabled` | boolean | not null | `true` | | D11 (the device permission is separate state). |
| `calendar_scope` | text | not null | `'primary_only'` | `primary_only`, `selected` | D11. |
| `calendar_selected_keys` | text[] | not null | `'{}'` | each E51 | Hashed calendar keys when `selected`. |
| `calendar_ignore_all_day` | boolean | not null | `true` | | D11. |
| `calendar_ignore_name_patterns` | text[] | not null | `'{"(?i)todoist"}'` | | D11 (Todoist calendar ignored). |
| `calendar_write_enabled` | boolean | not null | `true` | | D11 auto-write of challenge sessions and test days. |
| `calendar_write_weeks` | smallint | not null | `2` | 1-4 | Current and next ISO week (D11). |
| `evidence_mode` | text | not null | `'warn_only'` | E35 | D10. |
| `running_priority` | text | not null | `'low'` | `low`, `medium`, `high` | F6. |
| `deload_sync_with_challenge` | boolean | not null | `true` | | F8. |
| `allow_supersets` | boolean | not null | `false` | | F13. |
| `rir_calibration_opt_in` | boolean | not null | `false` | | F14. |
| `prompt_readiness`, `prompt_elbow_check`, `prompt_srpe`, `prompt_soreness`, `prompt_pain_after` | boolean | not null | `true` | | F15 (the elbow check is never reduced while a challenge pain rule is active; that is app logic). |
| `rest_alarm_mode` | text | not null | `'alarm_clock'` | `alarm_clock`, `exact_idle` | C28. `exact_idle` (the "standard exact" option) is offered only after 30 logged rests with p95 latency <= 2 s (C54, ANDROID.md 25.1). |
| `rest_tone_enabled` | boolean | not null | `false` | | F20 (vibration is always on). |
| `keep_screen_on` | boolean | not null | `true` | | F20. |
| `theme` | text | not null | `'system'` | `system`, `light`, `dark` | Kept but ignored: the app is dark only (D31, was F16). |
| `dynamic_color` | boolean | not null | `false` | | Kept but ignored: no dynamic colour (D31, was F16). |
| `reminders_enabled` | boolean | not null | `true` | | Home-habit reminders (D4). |
| `muscle_goal_preset` | text | null | | `legs_only`, `no_legs`, `pull_focus`, `balanced`, `custom` | D6. |
| `disclaimer_accepted_at` | timestamptz | null | | | C33 "not medical advice". |
| `onboarding_completed_at` | timestamptz | null | | | |

Index: `(default_gym_id, user_id)`.

#### 9.3.2 `fitness.gyms`

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | | |
| `name` | text | not null | | length 1-80 | |
| `location_keywords` | text[] | not null | `'{}'` | | Calendar classifier location match, for example `basic-fit`, `ierse pond`. |
| `address_note` | text | null | | length <= 200 | |
| `opening_hours` | jsonb | not null | `'{}'` | object, same shape as `availability` | Basic-Fit Ierse Pond default: Mon-Fri 07:00-22:30, Sat-Sun 09:00-16:00 (club website, engineering-fact as of 2026-10). |
| `is_default` | boolean | not null | `false` | | `unique (user_id) where is_default and deleted_at is null`. |

#### 9.3.3 `fitness.gym_equipment`

Equipment and load schemes per gym, plus portable equipment (`gym_id` null) such as the user's dip belt and belt plates (F7, C2).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | | |
| `gym_id` | uuid | null | | FK `(gym_id, user_id) -> gyms(id, user_id)` | Null = portable. |
| `equipment_type` | text | not null | | FK `equipment_types(key)` | |
| `label` | text | not null | | length 1-80 | For example "Assisted pull-up machine". |
| `available` | boolean | not null | `true` | | |
| `scheme_type` | text | not null | | E33; `check (load_scheme->>'type' = scheme_type)` | |
| `load_scheme` | jsonb | not null | | object | Shapes below. |
| `calibration_rev` | int | not null | `1` | >= 1 | Incremented by the client whenever a numeric calibration field changes; `set_logs.equipment_calibration_rev` snapshots it, and a `session_events` row `equipment_calibration_changed` stores old and new schemes so historic assist kg can be recomputed. |
| `calibration_source` | text | not null | `'default'` | E34 | |
| `confirmed_at` | timestamptz | null | | | First-run checklist confirmation (F7). |

Indexes: `(gym_id, user_id)`, `(equipment_type)`.

Load scheme shapes (validated by the `load` module and, optionally, by `pg_jsonschema`):

| type | Shape | Example / defaults |
|------|-------|--------------------|
| `plates` | `{type, unit:"kg", bar_kg, plates:[{kg, count}]}` (`count` = total plates owned; pairs are formed by the loader) | Barbell 20 kg, plates 25/20/15/10/5/2.5/1.25 x 20, i.e. unlimited (LOAD-1 default, params 1.0.1). The F7 checklist writes `count` 20 per size: gym plates are treated as unlimited |
| `belt` | `{type, unit:"kg", plates:[{kg, count}], max_kg, max_items, dumbbells_on_belt}` | User's own belt plates (unknown, checklist); `max_kg 60`, `max_items 4` (**expert-opinion**, tunable by the user) |
| `dumbbells` | `{type, unit, values:[...], per:"hand"}` | Values from the checklist |
| `stack` | `{type, unit:"kg"\|"lb"\|"level", start, step, max, add_on_steps:[...], ratio, level_kg?}` | F7 checklist default `unit lb, step 10`, `max` the kg cap converted to lb and floored to the step. `ratio 1.0` unless calibrated. `level_kg` (optional, only for `unit = "level"`): kg per level. Without it a level stack is ordinal: DOUBLE mode on the level, excluded from SSE-1 (C43). |
| `pin_assist` | `{type, unit:"lb", step, min, max, offset_kg, ratio}` | C2/F7: `{unit lb, step 5, min 0, max null, offset_kg 6.5, ratio 1.0}`; `max` asked at the checklist |
| `bodyweight` | `{type}` | |
| `fixed` | `{type, unit:"kg", kg}` | Fixed implements |

C2: `assistKg(pin) = pin_native x unit_kg x ratio + offset_kg`, with `unit_kg = 0.45359237` for lb; rounding ties go to more assistance. The 6.5 kg offset is the user's estimate ("6-7 kg"), `calibration_source = 'user_estimate'`; it is tunable from a bathroom-scale check or from first-set residuals after pin changes.

#### 9.3.4 `fitness.rep_preferences`

Set/rep preferences at several scopes; the engine resolves the most specific scope (exercise > muscle_group > mechanics > goal > global). Deviations from evidence produce warnings, never silent changes (D10).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | id = UUIDv5 `rep_preferences:<uid>:<scope_key>` | |
| `scope_kind` | text | not null | | `global`, `goal`, `mechanics`, `muscle_group`, `exercise` | |
| `scope_key` | text | not null | | `global` \| `goal:<hypertrophy\|strength>` \| `mechanics:<compound\|isolation>` \| `muscle_group:<id>` \| `exercise:<uuid>`; prefix must equal `scope_kind` | `unique (user_id, scope_key) where deleted_at is null`. |
| `goal_kind` | text | null | | `hypertrophy`, `strength` | Set iff `scope_kind = 'goal'`. |
| `mechanics` | text | null | | `compound`, `isolation` | Set iff `scope_kind = 'mechanics'`. |
| `muscle_group_id` | text | null | | FK `muscle_groups(id)` | Set iff `scope_kind = 'muscle_group'`. |
| `exercise_id` | uuid | null | | FK `exercises(id)`, trigger `b_exercise_visible` | Set iff `scope_kind = 'exercise'`. |
| `sets_pref` | smallint | null | | 1-10 | |
| `rep_min`, `rep_max` | smallint | null | | 1-100; `rep_min <= rep_max` | |
| `rir_target` | numeric(3,1) | null | | 0-5, half steps | |
| `rep_scheme` | text | null | | `range`, `fixed` | |
| `rest_pref_s` | smallint | null | | 30-600 | Rest in seconds; the editor offers it for an exercise override (D50). |
| `rest_pref_mult` | numeric(3,2) | null | | 0.50-2.00 | Multiplier on REST-1's class base; the editor offers it for the broader scopes (D50). |

Check: at least one of `sets_pref`, `rep_min`, `rir_target`, `rep_scheme`, `rest_pref_s`, `rest_pref_mult` is non-null; at most one of `rest_pref_s` and `rest_pref_mult` (`rep_preferences_rest_one_ck`, D50). Indexes: `(muscle_group_id)`, `(exercise_id)`.

#### 9.3.5 `fitness.muscle_goals`

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | id = UUIDv5 `muscle_goals:<uid>:<group>` | |
| `muscle_group_id` | text | not null | | FK `muscle_groups(id)`; `unique (user_id, muscle_group_id) where deleted_at is null` | |
| `goal` | text | not null | | E16 | D6, C14. |
| `focus` | boolean | not null | `false` | `check (not focus or goal = 'improve')` | Improve focus (+4 sets, C14). |
| `region_emphasis` | text[] | not null | `'{}'` | region ids | Soft variety preference. |
| `source` | text | not null | `'user'` | `onboarding`, `preset`, `user` | |
| `preset_code` | text | null | | as `profiles.muscle_goal_preset` | |

"Covered by challenge" is computed from the challenge contract (D6), never stored here.

#### 9.3.6 `fitness.user_exercise_prefs`

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | id = UUIDv5 `user_exercise_prefs:<uid>:<exercise_id>` | |
| `exercise_id` | uuid | not null | | FK `exercises(id)`, trigger `b_exercise_visible`; `unique (user_id, exercise_id) where deleted_at is null` | |
| `preference` | text | not null | `'neutral'` | E15 | C26 5-state enum. Multipliers 0 / 0.6 / 1.0 / 1.15 / 1.495 live in `pref.*` params. |
| `unavailable_until` | date | null | | | PREF-1: hidden until this date; not a preference. |
| `note` | text | null | | length <= 200 | |

Index: `(exercise_id)`.

#### 9.3.7 `fitness.user_algo_params` (USER-RO)

Per-user constant overrides proposed by the offline ModelTuner or a Claude review and **approved by the user**. Written by `postgres` through MCP; read-only for the app.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | | `device_id = SERVER_DEVICE_ID` for review writes. |
| `param_key` | text | not null | | E53 | Must exist in the active `algo_params` (checked by the review tooling). |
| `module_id` | text | not null | | E49; must be the prefix of `param_key` | |
| `value` | jsonb | not null | | | |
| `valid_from` | timestamptz | not null | | `unique (user_id, param_key, valid_from) where deleted_at is null` | Applied from the next session that starts at or after this instant. |
| `source` | text | not null | | E32 | |
| `engine_version_range` | text | null | | | Restrict to engine versions if the meaning changed. |
| `review_ref` | text | null | | | For example `docs/reviews/2026-11-22.md#rcm-k`. |
| `approved_at` | timestamptz | null | | `check (source not in ('review', 'fitted') or approved_at is not null)` | User approval time. F26: after a review the user approves the proposed change list as one batch (each change listed with its reason); every row of that batch carries the same `approved_at` and `review_ref`. Nothing is written without that approval. |
| `note` | text | null | | | |

Effective params at session start = active `algo_params` values, overridden by the latest non-deleted `user_algo_params` row per key with `valid_from <= session start`; the resolved map is stored once in `params_snapshots` and referenced by hash.

### 9.4 Challenges

#### 9.4.1 `fitness.challenge_definitions`

Immutable, versioned challenge definitions (CDS v1). v1 contains only global rows published by migrations; the app reads them (D4, C21).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| `id` | uuid | not null | | PK | UUIDv5 `challenge_definition:<slug>@<version>`. |
| `user_id` | uuid | null | | FK `auth.users(id) on delete cascade` | Null = global. Reserved for future user-authored drafts. |
| `slug` | text | not null | | E54 | `oap`. |
| `version` | text | not null | | E50; `unique nulls not distinct (user_id, slug, version)` | |
| `status` | text | not null | `'draft'` | E27 | |
| `schema_version` | text | not null | | `^fitapp\.challenge/[0-9]+$` | `fitapp.challenge/1`. |
| `engine_range` | text | not null | | | For example `>=1.0.0 <2.0.0`. |
| `title_en` / `title_nl` | text | not null / null | | | |
| `definition` | jsonb | not null | | object; optional `extensions.jsonb_matches_schema(...)` CHECK (F5, migration 018) | The full CDS JSON (authored in the repo). |
| `jcs_sha256` | text | not null | | E51 | RFC 8785 JCS SHA-256, computed by the authoring tool and recomputed by the app; this is the hash stored in snapshots (C24). |
| `server_checksum` | text | not null | | E51 | Set by trigger (7.6) from `definition::text`; detects tampering. Never compared with `jcs_sha256`. |
| `supersedes_id` | uuid | null | | FK `challenge_definitions(id)` | Previous version. |
| `published_at`, `retired_at` | timestamptz | null | | | Set by trigger. |
| `created_at`, `client_updated_at`, `server_updated_at`, `device_id`, `app_version` | | | | | As S-MUT (no `deleted_at`: retire instead). |

Constraints: `unique (id, slug, version, jcs_sha256)` (target of the enrollment consistency FK); `index (slug, status)`; `index (server_updated_at)`. Trigger `a_guard` (7.6) instead of `a_sync`. Grants: `authenticated` **select only**; `service_role` select, insert, update. Policy `challenge_definitions_select`: `(select fitness_private.is_allowed()) and (user_id is null or user_id = (select auth.uid())) and (status <> 'draft' or user_id = (select auth.uid()))`.

#### 9.4.2 `fitness.challenge_enrollments` (LWW-REV)

One row per user enrollment. Its cached columns are a deterministic fold over `challenge_events` (C21, event-sourced enrollment); the row is never the source of truth for state.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | | Trigger `a_rev` (7.5) instead of `a_sync`. |
| `definition_id` | uuid | not null | | FK `(definition_id, definition_slug, definition_version, definition_jcs_sha256) -> challenge_definitions(id, slug, version, jcs_sha256)` | Pins the exact version. |
| `definition_slug` | text | not null | | | Denormalised for the partial unique index. |
| `definition_version` | text | not null | | E50 | |
| `definition_jcs_sha256` | text | not null | | E51 | |
| `status` | text | not null | `'onboarding'` | E25 | `unique (user_id, definition_slug) where status in ('onboarding','active','paused') and deleted_at is null`. |
| `mode` | text | null | | E26 | Cached. |
| `phase` | smallint | null | | 1-20 | Cached. |
| `block_index` | smallint | null | | >= 1 | Cached. |
| `block_week` | smallint | null | | 1-10 | Cached (repeated or extra weeks allowed). |
| `week_ordinal` | int | null | | >= 0 | Cached calendar progress. |
| `params` | jsonb | not null | `'{}'` | object | Enrollment parameters chosen at setup (grip, completion arms, schedule preferences, habit times). |
| `rule_decisions` | jsonb | not null | `'{}'` | object; keys match `^P[0-9]{2,3}$` (app validator) | D8 and C48: keyed by **proposal id**, the unit the user approves (one proposal may switch several rules): `{"P05": {"decision": "approved"\|"declined", "decided_at": <timestamp>}}` (decision values E57). Cached from `rules.approval_changed` events. An `A` rule runs unless the `P` proposal replacing it is approved; an undecided proposal (no key) is inactive. |
| `state` | jsonb | not null | `'{}'` | object, <= 64 KB | Cached EnrollmentState: vars, per-block snapshots, planned test, flags. |
| `state_rev` | int | not null | `0` | >= 0 | Number of events folded; optimistic lock (7.5). |
| `state_hash` | text | null | | E51 | JCS SHA-256 of `state`. |
| `fold_generation` | int | not null | `0` | >= 0 | Incremented on a full refold (late or amended events); audit rows of older generations are superseded. |
| `last_folded_event_id` | uuid | null | | | |
| `engine_version` | text | not null | | E50 | Engine that produced the cache. |
| `started_on` | date | null | | | |
| `paused_at`, `completed_at`, `abandoned_at` | timestamptz | null | | | |

Constraints and indexes: `unique (id, user_id)`; `index (definition_id, definition_slug, definition_version, definition_jcs_sha256)`; `index (user_id, server_updated_at)`.

### 9.5 Snapshots (class APPEND)

#### 9.5.1 `fitness.params_snapshots`

Content-addressed store of resolved effective params, so every snapshot can be replayed exactly.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-APP | | | | id = UUIDv5 `params_snapshots:<uid>:<params_hash>` | |
| `params_hash` | text | not null | | E51; `unique (user_id, params_hash)` | JCS SHA-256 of `params`. |
| `params_version` | text | not null | | FK `algo_params(params_version)` | Defaults version. |
| `params` | jsonb | not null | | object | Resolved `{key: value}` map. |
| `user_param_ids` | uuid[] | not null | `'{}'` | | `user_algo_params` rows applied. |

New rows appear only when the effective params change (rare).

#### 9.5.2 `fitness.recommendations`

The C24 snapshot envelope for every engine decision. Module-specific `inputs`, `outputs` and `trace` schemas are defined by the ALGORITHMS documents; section 10 lists the keys this document depends on.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-APP | | | | | `unique (id, user_id)`. |
| `kind` | text | not null | | E47, registry 10.1 | |
| `module_id` | text | not null | | E49 | |
| `module_version` | text | not null | | E50 | |
| `engine_version` | text | not null | | E50 | `ENGINE_VERSION` of `:algo`. |
| `params_hash` | text | not null | | E51; FK `(user_id, params_hash) -> params_snapshots(user_id, params_hash)` | |
| `catalog_version` | text | not null | | E50 | |
| `challenge_def_sha256` | text[] | not null | `'{}'` | each E51, sorted | JCS hashes of the active challenge definitions (C24; an array so a second challenge needs no migration). |
| `inputs_hash` | text | not null | | E51 | RFC 8785 JCS + SHA-256 of `inputs`. |
| `inputs` | jsonb | null | | `octet_length(inputs::text) <= 65536` | Null after retention pruning (section 15). Engine target: <= 16 KB. |
| `inputs_bytes` | int | not null | | >= 0 | Size of the JCS inputs at creation. |
| `outputs` | jsonb | not null | | <= 65536 bytes | |
| `trace` | jsonb | not null | `'[]'` | <= 65536 bytes | DecisionTrace: rule ids, candidate scores, reason codes (F19). |
| `warnings` | jsonb | not null | `'[]'` | array | WarningEngine results `{rule_id, severity, entity, magnitude, evidence_grade}`. |
| `scope_key` | text | not null | | length 1-120 | For example `day:2026-10-07`, `week:2026-W41`, `session:<uuid>`, `session_exercise:<uuid>`, `enrollment:<uuid>`. |
| `scope_date` | date | null | | | |
| `iso_week` | text | null | | E52 | |
| `session_id`, `session_exercise_id`, `set_log_id`, `enrollment_id` | uuid | null | | soft references (no FK) | Soft so a snapshot can be stored before its subject syncs and no FK cycle exists. |
| `supersedes_id` | uuid | null | | soft | Previous snapshot for the same scope. |
| `inputs_pruned_at` | timestamptz | null | | | Set by retention pruning. |

Indexes: `(user_id, server_updated_at)`; `(user_id, kind, scope_key, created_at desc)`; `(user_id, session_id) where session_id is not null`; `(user_id, module_id, module_version)`; `(user_id, params_hash)`.

Reuse rule (S2): if the latest snapshot for `(kind, scope_key)` has the same `(module_version, params_hash, inputs_hash)`, the client reuses it and inserts nothing. The estimation snapshots (M3.2) go one step further: their id is UUIDv5 `recommendations:<uid>:<kind>:<scope_key>:<session_id>:<module_version>:<params_hash>:<inputs_hash>`, because the phone rebuilds them by replaying every finished session; a replay of unchanged facts produces the ids that already exist (locally or on the server after a reinstall), an edited past session adds new rows whose `supersedes_id` names the session's previous snapshot.

### 9.6 Session facts

#### 9.6.1 `fitness.workout_sessions`

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | | |
| `kind` | text | not null | `'gym'` | E7 | C34. |
| `status` | text | not null | `'in_progress'` | E8 | `unique (user_id) where status = 'in_progress' and deleted_at is null`. |
| `template_source` | text | not null | | E9 | C35. `habit` requires `habit_id`. |
| `families` | text[] | not null | `'{}'` | subset of `push`, `pull`, `legs`, `core`, `full` | |
| `enrollment_id` | uuid | null | | FK `(enrollment_id, user_id) -> challenge_enrollments(id, user_id)` | |
| `challenge_slot` | text | null | | `^[A-Z][A-Z0-9_]{0,15}$`; requires `enrollment_id` | `A`, `B`, `C`, `TEST`. Challenge `session.completed` fires on this slot (C35). |
| `challenge_template_id` | text | null | | | Resolved template, for example `C1`, `T1`. |
| `habit_id` | text | null | | E54 | For example `daily_hang`. |
| `phase`, `block_index`, `block_week` | smallint | null | | | Challenge snapshot at start. |
| `enrollment_mode` | text | null | | E26 | Snapshot at start. |
| `mesocycle_week` | smallint | null | | 1-10 | C31. |
| `is_deload` | boolean | not null | `false` | | F8 / C13. |
| `local_date` | date | not null | | | Local date in `timezone` at start (or planned start) (C31). |
| `timezone` | text | not null | | as `profiles.timezone` | |
| `iso_week` | text | not null | | E52 | From the `clock` module. |
| `gym_id` | uuid | null | | FK `(gym_id, user_id) -> gyms` | |
| `planned_start_at`, `started_at`, `finished_at` | timestamptz | null | | `finished_at >= started_at` | |
| `bodyweight_kg` | numeric(5,2) | null | | 20-300 | BW(t) snapshot (C32). |
| `bodyweight_source` | text | null | | `ewma`, `test_weighin`, `fallback`, `none` | C32: EWMA for prescriptions, raw weigh-in on test days. |
| `bodyweight_stale` | boolean | not null | `false` | | Last weigh-in older than the `bodyweight` staleness param (14 d). |
| `time_budget_min` | smallint | null | | 10-300 | Null = no limit (90+ chip). |
| `time_budget_source` | text | null | | `calendar`, `chip`, `default` | F11/C49: `calendar` = event duration minus `profiles.changeover_min` (5); `default` = `profiles.default_session_min` (75). |
| `engine_version` | text | not null | | E50 | Pinned at start. |
| `params_hash` | text | not null | | E51 | Pinned at start (critique gap: params pinned per session). |
| `catalog_version` | text | not null | | E50 | |
| `plan_recommendation_id` | uuid | null | | FK `(plan_recommendation_id, user_id) -> recommendations(id, user_id)` | The `today.options` or `session.plan` snapshot accepted at start. |
| `completion_ratio` | numeric(5,4) | null | | 0-1 | Engine-computed at finish (challenge `completionRatio`). |
| `end_reason_code` | text | null | | FK `reason_codes(code)` | Abandon reason. |
| `notes` | text | null | | length <= 2000 | Never copied into telemetry. |

Checks: `status = 'in_progress'` requires `started_at`; `status = 'finished'` requires `started_at` and `finished_at`; `challenge_slot is null or enrollment_id is not null`; `template_source <> 'habit' or habit_id is not null`. Indexes: `(user_id, local_date desc)`, `(enrollment_id, user_id)`, `(gym_id, user_id)`, `(plan_recommendation_id, user_id)`.

The calendar link lives on `calendar_events.linked_session_id` (one direction only, no FK cycle).

#### 9.6.2 `fitness.session_blocks`

C34: a session has one or more blocks. A general or add-on block is built by SEL-1 with its own budget (TIME-1 add-on capacity) and its own snapshot.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | `unique (id, user_id, session_id)` | |
| `session_id` | uuid | not null | | FK `(session_id, user_id) -> workout_sessions(id, user_id) on delete cascade` | |
| `position` | smallint | not null | | >= 0; `unique (session_id, position) where deleted_at is null` | |
| `block_kind` | text | not null | | E10 | |
| `origin` | text | not null | | `challenge_template`, `addon`, `standalone`, `user_built` | |
| `enrollment_id` | uuid | null | | FK `(enrollment_id, user_id) -> challenge_enrollments`; required when `block_kind = 'challenge'` | |
| `challenge_template_id` | text | null | | | |
| `budget_min` | smallint | null | | 0-300 | Add-on capacity or block budget. |
| `status` | text | not null | `'planned'` | `planned`, `active`, `done`, `skipped`, `trimmed` | |
| `recommendation_id` | uuid | null | | FK `(recommendation_id, user_id) -> recommendations` | `session.plan` or `challenge.session_plan` snapshot. |

Indexes: `(session_id, user_id)`, `(enrollment_id, user_id)`, `(recommendation_id, user_id)`.

#### 9.6.3 `fitness.session_exercises`

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | `unique (id, user_id, session_id, exercise_id)` | Target of the `set_logs` FK. |
| `session_id` | uuid | not null | | via the block FK below | |
| `block_id` | uuid | not null | | FK `(block_id, user_id, session_id) -> session_blocks(id, user_id, session_id) on delete cascade` | Also proves the block belongs to the same session. |
| `exercise_id` | uuid | not null | | FK `exercises(id)`, trigger `b_exercise_visible` | Effectively immutable once sets exist (7.9). |
| `challenge_item_key` | text | null | | E54 | Template item key (`wpu`, `oaa`, ...) used by `ex.<key>` in FitExpr. |
| `position` | numeric(9,4) | not null | | | Fractional ordering; reorder without renumbering. |
| `origin` | text | not null | | E12 | |
| `substitutes_id` | uuid | null | | FK `(substitutes_id, user_id) -> session_exercises(id, user_id)` | The exercise this one replaced (SUB-1). Requires `unique (id, user_id)`. |
| `substitution_reason_code` | text | null | | FK `reason_codes(code)` | |
| `status` | text | not null | `'pending'` | E11; `status not in ('skipped','removed') or status_reason_code is not null` | |
| `status_reason_code` | text | null | | FK `reason_codes(code)` | |
| `defer_count` | smallint | not null | `0` | 0-10 | UI-05 "do later". |
| `load_mode` | text | not null | | E3; must be in the exercise's `allowed_load_modes` (app check) | Mode used today. |
| `side_mode` | text | not null | `'bilateral'` | `bilateral`, `unilateral_alternating`, `unilateral_sequential` | |
| `side_order` | text | null | | `LR`, `RL` | Challenge `sideOrder`. |
| `equipment_id` | uuid | null | | FK `(equipment_id, user_id) -> gym_equipment(id, user_id)` | Station or machine used. |
| `progression_mode` | text | not null | | E13 | C15. |
| `rep_scheme` | text | not null | | E14 | |
| `planned_sets` | smallint | null | | 0-20 | Summary; per-set plans live on `set_logs`. |
| `planned_rep_min`, `planned_rep_max` | smallint | null | | 0-100 | |
| `planned_rir` | numeric(3,1) | null | | 0-10 | |
| `planned_load_input` | numeric(7,2) | null | | | Native value (kg, lb pin, level, fingers). |
| `planned_load_unit` | text | null | | E4 | |
| `planned_effective_load_kg` | numeric(7,2) | null | | | From the `load` module. |
| `planned_duration_s` | numeric(6,1) | null | | | Holds. |
| `planned_ecc_s` | numeric(4,1) | null | | | Negatives. |
| `planned_rest_s` | smallint | null | | 0-900 | REST-1 base for this exercise. |
| `superset_group` | smallint | null | | 1-20 | F13. |
| `recommendation_id` | uuid | null | | FK `(recommendation_id, user_id) -> recommendations` | `rx.prescription` or `challenge.session_plan`. |
| `user_override` | boolean | not null | `false` | | In-gym override of the prescription. |
| `override_reason_code` | text | null | | FK `reason_codes(code)` | |
| `override_fields` | jsonb | not null | `'{}'` | object | `{field: {from, to}}` (sets, reps, load, rest). |
| `progression_valid` | boolean | null | | | Engine-computed at finish (challenge `valid(key)`: no substitution, no pain, no excluding reason, at least one set). |
| `progression_invalid_reason` | text | null | | | |
| `started_at`, `completed_at` | timestamptz | null | | | First set start / last set log (TIME-1 calibration). |

Indexes: `(block_id, user_id, session_id)`, `(session_id, user_id)`, `(exercise_id)`, `(equipment_id, user_id)`, `(recommendation_id, user_id)`, `(substitutes_id, user_id)`.

#### 9.6.4 `fitness.set_logs`

Every field the algorithms and challenge rules consume, with planned and actual values side by side. One row per set per side (C7).

Identity:

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | `unique (id, user_id)` | |
| `session_id` | uuid | not null | | | |
| `session_exercise_id` | uuid | not null | | FK `(session_exercise_id, user_id, session_id, exercise_id) -> session_exercises(id, user_id, session_id, exercise_id) on delete cascade` | Pins session and exercise consistency. |
| `exercise_id` | uuid | not null | | (via FK above) | Denormalised for indexes. |
| `set_index` | smallint | not null | | 1-100 | L and R rows of one pair share the index. |
| `side` | text | not null | `'both'` | E1 | |
| `set_kind` | text | not null | `'working'` | E2 | |
| `round_index` | smallint | null | | 1-20 | Top-hold rounds (3 rounds x 4 attempts per side). |
| `attempt_index` | smallint | null | | 1-20 | Attempt within a round, test metric or OAP attempt. |

Planned values (written when the set is materialised; updated when the user applies an AUTO-1 suggestion (D10, C16) and when an in-session challenge rule changes the remaining sets, which happens automatically with a visible Undo (D14, C46)):

| Column | Type | Null | Check | Notes |
|--------|------|------|-------|-------|
| `planned_reps` | smallint | null | 0-100 | Target reps for this set. |
| `planned_rep_min`, `planned_rep_max` | smallint | null | 0-100 | Range bounds (UI-06 short-reps rule). |
| `planned_rir` | numeric(3,1) | null | 0-10 | |
| `planned_input_value` | numeric(7,2) | null | | Native value. |
| `planned_input_unit` | text | null | E4 | |
| `planned_added_kg` | numeric(6,2) | null | >= 0 | |
| `planned_assist_native` | numeric(6,2) | null | >= 0 | Planned pin. |
| `planned_assist_ordinal` | smallint | null | 0-10 | Planned fingers. |
| `planned_effective_load_kg` | numeric(7,2) | null | | |
| `planned_duration_s` | numeric(6,1) | null | 0-3600 | |
| `planned_ecc_s` | numeric(4,1) | null | 0-60 | |
| `planned_source` | text | null | `engine`, `challenge_rule`, `autoreg`, `user`, `prefill_last`, `default` | `challenge_rule` = changed by an auto-applied in-session challenge rule (D14); an Undo restores the previous plan, sets `session_exercises.user_override` with the reason (C46) and is logged as `challenge_rule_undone`. |

Actual load (the native input plus everything the single LOAD function needs, C1/C2):

| Column | Type | Null | Default | Check | Notes |
|--------|------|------|---------|-------|-------|
| `load_mode` | text | not null | | E3 | |
| `equipment_id` | uuid | null | | FK `(equipment_id, user_id) -> gym_equipment(id, user_id)` | Machine, belt or rack used. |
| `equipment_calibration_rev` | int | null | | >= 1 | Snapshot of `gym_equipment.calibration_rev`. |
| `input_value` | numeric(7,2) | null | | `(input_value is null) = (input_unit is null)` | Native value as entered (40 for "pin 40 lb", 20 for "belt 20 kg", 22.5 per dumbbell). |
| `input_unit` | text | null | | E4 | |
| `implement_count` | smallint | null | | `in (1, 2)` | Dumbbells: 1 or 2. |
| `load_per_implement_kg` | numeric(6,2) | null | | `(implement_count is null) = (load_per_implement_kg is null)`; when set, `added_kg = implement_count * load_per_implement_kg` | Per-hand convention (critique gap). |
| `implement_base_kg` | numeric(6,2) | not null | `0` | >= 0 | Bar or sled mass not in the entered value. |
| `added_kg` | numeric(6,2) | not null | `0` | >= 0 | Total external load in kg (belt load, plates + bar, both dumbbells). |
| `assist_pin_native` | numeric(6,2) | null | | >= 0 | Raw pin (C2). |
| `assist_unit` | text | null | | `lb`, `kg` | |
| `assist_kg` | numeric(6,2) | null | | >= 0 | Computed by `assistKg()` with the calibration of `equipment_calibration_rev`. |
| `assist_ordinal` | smallint | null | | 0-10 | Finger count; ordinal only (C2). |
| `bodyweight_kg` | numeric(5,2) | null | | 20-300 | BW snapshot (C32). |
| `f_bw` | numeric(5,4) | null | | 0-1 | Snapshot of the exercise's bodyweight fraction (C1). |
| `effective_load_kg` | numeric(7,2) | null | | tolerance CHECK below | System load `w_sys`, written by the engine's single LOAD function. |

Actual performance and feedback:

| Column | Type | Null | Default | Check | Notes |
|--------|------|------|---------|-------|-------|
| `reps` | smallint | null | | 0-100 | |
| `duration_s` | numeric(6,1) | null | | 0-3600 | Holds and hangs. |
| `hold_reserve_s` | numeric(5,1) | null | | 0-60 | Seconds in reserve from the effort chips Max/Hard/OK/Easy (D9; chip-to-seconds mapping is a `hold` param, research default 0/2/5/10). Holds and eccentric-only sets use this column (C66): an eccentric-only set has `rir_reported` null and its effort here; FitExpr `effort` is derived from it. No CHECK requires RIR on a working set. |
| `hold_reserve_is_lower_bound` | boolean | not null | `false` | | True for the open-ended chip ("Easy" = 10+ s). |
| `ecc_s_target` | numeric(4,1) | null | | 0-60 | Negatives. |
| `ecc_s_actual` | numeric(4,1) | null | | 0-60 | The shortest lowering among the reps of the set ("Shortest lowering", UX.md 11.7.5), because the challenge rules compare the minimum (C66); FitExpr `ex.<key>.ecc_s` reads it. |
| `rir_reported` | numeric(3,1) | null | | 0-10, half steps (`rir_reported * 2 = floor(rir_reported * 2)`) | Raw as reported (C17: challenge rules use raw RIR). |
| `rir_is_lower_bound` | boolean | not null | `false` | | True for the "5+" chip. |
| `rir_source` | text | not null | `'chip'` | E5 | |
| `calib_extra_reps` | smallint | null | | 0-50; only when `set_kind = 'calibration'` | RIRCAL-1 explicit check: reps beyond the target to technical failure. |
| `flags` | text[] | not null | `'{}'` | subset of E6 | Set flags the challenge rules read. |
| `deviation_reason_code` | text | null | | FK `reason_codes(code)` | |
| `deviation_note` | text | null | | length <= 500 | Free text for `other`. Never copied into telemetry. |
| `pain_flag` | boolean | not null | `false` | | The PainEvent detail is a `wellness_logs` row with `set_log_id` pointing here. |

Rest (C18, C24). These column names are canonical; UX.md and ANDROID.md use them (C55):

| Column | Type | Null | Default | Check | Notes |
|--------|------|------|---------|-------|-------|
| `rest_timer_planned_s` | smallint | null | | 0-900 | Prescribed duration of the timer that preceded this set, after clamping and before user adjustments (REST-1 value incl. modifiers; challenge override). Null when no timer ran (first set of a block). |
| `rest_adjust_s` | smallint | null | | -900 to 900 | Net sum of user adjustments (+/- 15 s taps, long-press steps). |
| `rest_timer_overridden` | boolean | not null | `false` | `rest_timer_overridden or (coalesce(rest_adjust_s, 0) = 0 and rest_end_reason is distinct from 'skipped')` | Any adjustment or skip happened during that rest (an adjustment pair that nets to 0 still counts). |
| `rest_before_s` | numeric(6,1) | null | | 0-7200 | Actual rest from the previous set's `completed_at` to this set's start (formula owned by UX.md 19.8). |
| `rest_source` | text | null | | E56; `(rest_source is null) = (rest_before_s is null)` | `measured` (explicit hold start) or `estimated`. |
| `rest_end_reason` | text | null | | E44 | |

Timestamps: `started_at timestamptz null` (hold start, or set start when known), `completed_at timestamptz not null` (the RIR/hold chip tap, D9); `check (started_at is null or started_at <= completed_at)`.

Table-level checks:

```sql
-- Load-mode consistency
check (
  (load_mode = 'external'                 and assist_kg is null and assist_ordinal is null)
  or (load_mode = 'bodyweight'            and added_kg = 0 and assist_kg is null and assist_ordinal is null
                                          and coalesce(f_bw, 0) > 0)
  or (load_mode = 'bodyweight_plus_external' and assist_kg is null and assist_ordinal is null
                                          and coalesce(f_bw, 0) > 0)
  or (load_mode = 'assisted_machine'      and assist_pin_native is not null and assist_unit is not null
                                          and assist_kg is not null and equipment_id is not null
                                          and assist_ordinal is null)
  or (load_mode = 'assisted_ordinal'      and assist_ordinal is not null and assist_kg is null)
),
-- Effective load: the server verifies, it does not compute (C1)
check (
  case
    when load_mode = 'assisted_ordinal' then effective_load_kg is null
    when effective_load_kg is null then true
    else abs(effective_load_kg
             - (coalesce(bodyweight_kg, 0) * coalesce(f_bw, 0)
                + implement_base_kg + added_kg - coalesce(assist_kg, 0))) <= 0.01
  end
),
-- Something was performed
check (reps is not null or duration_s is not null),
-- One effort scale per set
check (not (rir_reported is not null and hold_reserve_s is not null)),
check ((rir_reported is null and hold_reserve_s is null) = (rir_source = 'missing')),
check (set_kind = 'calibration' or calib_extra_reps is null),
-- Reasons that imply a flag (reason_codes.implies_flag)
check (deviation_reason_code is null
       or deviation_reason_code not in ('torso_rotation', 'technique_breakdown')
       or flags @> array[deviation_reason_code])
```

Why `effective_load_kg` is engine-written and checked rather than a `GENERATED` column: C1 requires one LOAD function used everywhere, and the engine rounds with HALF_EVEN while PostgreSQL `round(numeric)` rounds half away from zero (verified: `round(45.245, 2) = 45.25`, HALF_EVEN gives 45.24). A generated column would be a second implementation that disagrees on ties. The 0.01 kg tolerance accepts exactly one rounding step (see open issue 1).

Indexes: `unique (session_exercise_id, set_index, side, coalesce(round_index, 0), coalesce(attempt_index, 0)) where deleted_at is null` (also enforced in Room, so a double-tap fails locally first); `(session_exercise_id, user_id, session_id, exercise_id)` (FK); `(user_id, exercise_id, completed_at desc) where deleted_at is null`; `(session_id, user_id)`; `(equipment_id, user_id)`; `(user_id, server_updated_at)`.

Every create, edit, delete and undo of a set also emits a `session_events` row (`set_logged`, `set_edited`, `set_deleted`, `set_undone`), so the full edit history survives the LWW overwrite.

#### 9.6.5 `fitness.wellness_logs`

Readiness, elbow check, pain (the PainEvent fact, C33), soreness, session RPE, sleep and illness (F15).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | | Triggers `a_sync`, `b_symptoms` (7.7). |
| `kind` | text | not null | | E17 | |
| `phase` | text | not null | | `pre`, `in_session`, `post`, `standalone` | |
| `observed_at` | timestamptz | not null | | | |
| `local_date` | date | not null | | | |
| `session_id` | uuid | null | | FK `(session_id, user_id) -> workout_sessions` | |
| `session_exercise_id` | uuid | null | | FK `(session_exercise_id, user_id) -> session_exercises(id, user_id)` | |
| `set_log_id` | uuid | null | | FK `(set_log_id, user_id) -> set_logs(id, user_id)` | Pain during this set. |
| `readiness_level` | text | null | | E18 | |
| `elbow_score` | smallint | null | | 0-3 | 0 none, 1 trace, 2 mild, 3 pain. |
| `pain_body_region` | text | null | | FK `body_regions(code)` | |
| `pain_side` | text | null | | `L`, `R`, `both`, `none` | |
| `pain_severity` | text | null | | E20 | |
| `pain_score` | smallint | null | | 0-10 | Written from the severity chip: mild 2, moderate 5, sharp 8 (C64); REASON-1 and the safety rules compare it with the stop threshold 4. |
| `symptom_codes` | text[] | not null | `'{}'` | trigger-validated against `symptom_codes` | |
| `soreness_muscle_group_id` | text | null | | FK `muscle_groups(id)` | |
| `soreness_code` | text | null | | E19 | |
| `srpe` | smallint | null | | 0-10 | Foster sRPE. |
| `sleep_hours` | numeric(3,1) | null | | 0-24 | |
| `note` | text | null | | length <= 1000 | |

Per-kind checks: `readiness` requires `readiness_level`; `elbow_check` requires `elbow_score`; `pain` requires `pain_body_region`, `pain_severity` and `pain_score` (C64); `soreness` requires `soreness_muscle_group_id` and `soreness_code`; `srpe` requires `srpe`; `sleep` requires `sleep_hours`. Indexes: `(user_id, kind, observed_at desc)`, `(session_id, user_id)`, `(session_exercise_id, user_id)`, `(set_log_id, user_id)`.

#### 9.6.6 `fitness.metric_observations`

Bodyweight weigh-ins and test results, including validity and protocol versioning (critique gap).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | | |
| `metric_code` | text | not null | | FK `metric_defs(code)` | |
| `value_num` | numeric(10,3) | null | | `num_nonnulls(value_num, value_bool) = 1` | |
| `value_bool` | boolean | null | | | |
| `unit` | text | not null | | `kg`, `s`, `reps`, `pin_lb`, `bool` | Must match `metric_defs.unit` (app check and data-quality view). |
| `side` | text | not null | `'none'` | `none`, `L`, `R` | |
| `observed_at` | timestamptz | not null | | | |
| `local_date` | date | not null | | | |
| `source` | text | not null | | E31 | |
| `session_id` | uuid | null | | FK `(session_id, user_id) -> workout_sessions` | Context (for example the weigh-in at session start). |
| `test_session_id` | uuid | null | | FK `(test_session_id, user_id) -> workout_sessions` | The test-day session the result belongs to (imported tests get an imported `kind = 'test'` session). |
| `set_log_id` | uuid | null | | FK `(set_log_id, user_id) -> set_logs(id, user_id)` | The set that produced the value. |
| `enrollment_id` | uuid | null | | FK `(enrollment_id, user_id) -> challenge_enrollments` | |
| `equipment_id` | uuid | null | | FK `(equipment_id, user_id) -> gym_equipment` | For example the assisted machine for least-assist tests. |
| `equipment_calibration_rev` | int | null | | >= 1 | |
| `valid_for_gate` | boolean | not null | `false` | `not valid_for_gate or enrollment_id is not null` | |
| `validity_note` | text | null | | length <= 300 | For example "old protocol, not comparable". |
| `protocol_code` | text | null | | `^[a-z0-9_.]{1,80}$` | The definition's `protocolCode`, for example `oa_hang_neutral_rotation_stop_v1`; imported legacy protocols such as `oap.hang.bar_supinated_release.v0`. |
| `derivation` | text | not null | `'measured'` | E30 | |
| `derived_from_ids` | uuid[] | not null | `'{}'` | `derivation = 'measured' or cardinality(derived_from_ids) > 0` | Input observations of a calculated value. |
| `calc_params` | jsonb | not null | `'{}'` | object | D7: `{method, k, m3, multiplier?, bw_kg, alternatives: {multiplier3rm, personal_curve, linear}, ci80: {lo, hi}}`; `m3` is the 3RM added load in kg, `multiplier` the factor of the `multiplier3rm` method (1.10), `bw_kg` the test-day weigh-in (C62). |
| `attempt_count` | smallint | null | | 0-20 | |
| `bodyweight_kg` | numeric(5,2) | null | | 20-300 | BW used for relative values and %BW thresholds (C32). For gate values this is the raw test-day weigh-in (C62); the dashboard's forward threshold at the current BW(t) is a display value and is not stored. |

Cutover import (C59): historical tests are imported as observations with their validity flags; no historical set rows are created for RCM-1, so derived gate values use the class prior `k` = 30 until RCM-1 has informative windows; a least-assistance value recorded without an arm (2026-08-30) is imported once per arm.

Indexes: `(user_id, metric_code, observed_at desc)`, `(enrollment_id, user_id)`, `(test_session_id, user_id)`, `(session_id, user_id)`, `(set_log_id, user_id)`, `(equipment_id, user_id)`.

### 9.7 Challenge events and calendar

#### 9.7.1 `fitness.challenge_events` (APPEND)

Append-only **input** events of an enrollment. The cached enrollment state is `fold(initialState, events ordered by (occurred_at, id))`. Derived events (`phase.changed`, `block.started`, `mode.changed`) are not stored here; they appear in `challenge_rule_events`.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-APP | | | | `unique (id, user_id)` | Synthesized events use UUIDv5 `challenge_events:<enrollment_id>:<dedupe_key>`. |
| `enrollment_id` | uuid | not null | | FK `(enrollment_id, user_id) -> challenge_enrollments(id, user_id)` | |
| `event_type` | text | not null | | see list below | |
| `occurred_at` | timestamptz | not null | | | |
| `local_date` | date | not null | | | |
| `dedupe_key` | text | null | | `unique (enrollment_id, dedupe_key) where dedupe_key is not null` | `day.tick:2026-10-07`, `week.closed:2026-W40`, `week.started:2026-W41`, `test.due:2026-10-11`. |
| `payload` | jsonb | not null | `'{}'` | object, <= 64 KB | Self-contained fact subset needed by the rules (for example all set values of the session, completion ratio, pain kind), plus source ids. |
| `source_session_id` | uuid | null | | FK `(source_session_id, user_id) -> workout_sessions` | |
| `source_wellness_log_id` | uuid | null | | FK `(source_wellness_log_id, user_id) -> wellness_logs(id, user_id)` | |
| `supersedes_event_id` | uuid | null | | FK `(supersedes_event_id, user_id) -> challenge_events(id, user_id)` | `session.amended` replaces the original at its position (refold, `fold_generation + 1`). |
| `definition_version` | text | not null | | E50 | |
| `definition_jcs_sha256` | text | not null | | E51 | |
| `engine_version` | text | not null | | E50 | |

Allowed `event_type` values: `enrollment.created` (params, baseline, imported history; D4 cutover), `rules.approval_changed` (D8, C48: one event per proposal, payload `{proposal_id, decision, previous?}` with `decision` and `previous` from E57), `session.started`, `session.completed`, `session.amended`, `set.logged` (only for items with in-session rules), `habit.completed`, `readiness.checked`, `pain.reported`, `test.due`, `test.completed`, `attempt.logged`, `week.closed`, `week.started`, `day.tick`, `pause`, `resume`, `abandon`, `manual.override`, `definition.migrated`.

Indexes: `(enrollment_id, occurred_at, id)`, `(user_id, server_updated_at)`, `(source_session_id, user_id)`, `(source_wellness_log_id, user_id)`, `(supersedes_event_id, user_id)`.

Stored payload keys are lower snake_case (for example `proposal_id`, `session_id`), and per-set values use the `set_logs` column names (`rir_reported`, `ecc_s_actual`, `assist_pin_native`, ...); coded values use the domains of section 8 (C38). The Kotlin field names of the challenge engine are mapped to these keys at the storage boundary (CHALLENGE_ENGINE.md 6.12).

Tick synthesis is idempotent through the deterministic id and the `dedupe_key`: two devices synthesizing `day.tick:2026-10-25` produce the same id, and the second insert is ignored.

Synthesized boundary events have fixed local times so that the fold order `(occurred_at, id)` is always the intended one (owner: CHALLENGE_ENGINE.md 4.3; times in the profile zone, stored as the corresponding UTC instant):

| Event | `dedupe_key` | `occurred_at` (local) | `local_date` |
|-------|--------------|-----------------------|--------------|
| `day.tick` | `day.tick:<date>` | `<date> 00:00:00.002` | `<date>` |
| `test.due` | `test.due:<date>` | `<date> 00:00:00.003` | `<date>` |
| `week.closed` | `week.closed:<iso week W>` | Monday after W at `challenge.enrollment.week_close_local_time` (params key per C41; default 04:00:00.000) | that Monday |
| `week.started` | `week.started:<iso week W+1>` | the same Monday at 04:00:00.001 | that Monday |

Example: `week.closed:2026-W43` has `occurred_at` 2026-10-26T03:00:00.000Z (04:00 CET, after the DST change) and `local_date` 2026-10-26. No synthesized event ever has an `occurred_at` later than the device clock at synthesis time.

#### 9.7.2 `fitness.challenge_rule_events` (APPEND)

The ECA audit: one row per evaluated rule whose condition was true or errored (C21).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-APP | | | | | |
| `enrollment_id` | uuid | not null | | FK `(enrollment_id, user_id) -> challenge_enrollments` | |
| `challenge_event_id` | uuid | not null | | FK `(challenge_event_id, user_id) -> challenge_events(id, user_id)` | Root input event. |
| `evaluation_id` | uuid | not null | | | Groups the rows of one `evaluate()` call. |
| `fold_generation` | int | not null | `0` | >= 0 | Reviews use only the enrollment's current generation. |
| `rule_id` | text | not null | | `^[A-Za-z][A-Za-z0-9_.+-]{0,79}$` | Exactly as in the definition, for example `prog.B.wpu`, `safety.elbow.stop`. |
| `rule_tag` | text | not null | | `A`, `P`, `A+P`, `D`, `E` | D8. `D` = rules fixed by DECISIONS (for example `data.test.e1rm`, D7; allowed since migration 025). `E` = engine-written rows such as `manual.override` (CHALLENGE_ENGINE.md 5.8). |
| `proposal_id` | text | null | | `^P[0-9]{2,3}$` | The proposal the rule belongs to (`proposal`, `supersededBy` or `uses` in the definition); null for plain `A` and `E` rows (C48). |
| `rule_decision` | text | null | | `approved`, `declined`, `not_required` | Decision in force when evaluated, derived from the decision of `proposal_id` (C48); `not_required` when the rule belongs to no proposal. |
| `stage` | text | not null | | E28 | |
| `prio` | smallint | not null | | | |
| `outcome` | text | not null | | E29 | |
| `cond_value` | boolean | null | | | |
| `eval_error` | text | null | | length <= 500 | For example `missing:t.oa_hang_s`. |
| `bindings` | jsonb | not null | `'{}'` | object | Every identifier read and its value (allows counterfactual threshold checks). |
| `proposals` | jsonb | not null | `'[]'` | array | |
| `applied` | jsonb | not null | `'{}'` | object | `{var: {old, new}}`. |
| `suppressed_by` | text | null | | | Rule id or lock. |
| `state_rev_before`, `state_rev_after` | int | not null | | >= 0 | |
| `state_hash_before`, `state_hash_after` | text | null | | E51 | |
| `cascade_depth` | smallint | not null | `0` | 0-3 | C21 bounded cascade. |
| `derived_event_type` | text | null | | | `phase.changed`, `block.started`, `mode.changed`. |
| `definition_slug`, `definition_version`, `definition_jcs_sha256` | text | not null | | | |
| `module_version` | text | not null | | E50 | `challenge.eca` version. |
| `engine_version` | text | not null | | E50 | |

Indexes: `(enrollment_id, fold_generation, created_at)`, `(challenge_event_id, user_id)`, `(user_id, rule_id)`, `(user_id, server_updated_at)`.

In-session challenge rules (D14) are applied automatically to the remaining sets; when they are audited (from `set.logged` events), their rows have `stage = 'in_session'`. An Undo is not a rule evaluation: it is a fact on the session exercise (`user_override` with a reason, C46) plus the telemetry event `challenge_rule_undone`.

#### 9.7.3 `fitness.calendar_events`

Only **classified** calendar instances are stored (D11, C20, privacy section 16). Busy blocks and unclassified events are never stored on the server: the app reads them from the calendar provider at each refresh (ANDROID.md 16.4) and keeps them only in the local, never-synced Room cache `calendar_instance` (ANDROID.md 8.3, 12.3).

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | id = UUIDv5 `calendar_events:<uid>:<instance_key>` | |
| `provider` | text | not null | `'android_cp'` | `android_cp` | CalendarContract (D11). |
| `instance_key` | text | not null | | E51; `unique (user_id, instance_key) where deleted_at is null` | SHA-256 of `account_type\|account_name\|calendar_sync_id\|event_sync_id (or _ID)\|instance_begin_ms`, computed on the device. Stable across devices for Google-synced events; contains no readable personal data. |
| `series_key` | text | null | | E51 | Same hash without the instance begin (recurring series); used to apply an override to all instances. |
| `source_device_id` | uuid | not null | | | Device whose provider ids are in its local cache. |
| `classified_kind` | text | not null | | E21 | |
| `title` | text | null | | length <= 200; null when `classified_kind = 'ignored'` | Title of gym, challenge, test and run events. |
| `location` | text | null | | length <= 200; null when `ignored` | |
| `starts_at`, `ends_at` | timestamptz | not null | | `ends_at > starts_at` | |
| `all_day` | boolean | not null | `false` | | |
| `local_date` | date | not null | | | Local date of the start (C31). |
| `timezone` | text | not null | | | |
| `iso_week` | text | not null | | E52 | |
| `provider_status` | text | not null | | `confirmed`, `tentative`, `cancelled` | |
| `challenge_slot` | text | null | | required when `classified_kind = 'challenge_session'` | |
| `enrollment_id` | uuid | null | | FK `(enrollment_id, user_id) -> challenge_enrollments` | |
| `planned_families` | text[] | not null | `'{}'` | | `planned_gym(type)`. |
| `run_intensity` | text | null | | E22 | |
| `run_minutes` | smallint | null | | 1-600; required when `classified_kind = 'run'` | F6. |
| `classifier_version` | text | not null | | E50 | `calendar.classify` module version. |
| `match_method` | text | not null | | E23 | |
| `score` | numeric(6,3) | null | | | |
| `features` | text[] | not null | `'{}'` | | Classifier features that fired. |
| `override_kind` | text | null | | E21 | User override (C20). |
| `override_slot` | text | null | | | |
| `override_at` | timestamptz | null | | | |
| `app_written` | boolean | not null | `false` | `not app_written or marker is not null` | D11. |
| `marker` | text | null | | `marker ~ '^\[fitapp:v1 [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12} [A-Za-z0-9_./-]{1,64}\]$'` | C37: the marker line exactly as it ends the event description, `[fitapp:v1 <uuid> <slotKey>]`. `<uuid>` is the enrollment id (challenge sessions and test days, equal to `enrollment_id`) or the recommendation id (accepted general sessions, equal to `recommendation_id`). `<slotKey>` is `<challenge slug>/<slot>/<isoYear>-W<ww>` for challenge events (OAP: `oap/C/2026-W42`, `oap/TEST/2026-W46`) or `gen/<isoYear>-W<ww>/<isoDow>` (for example `gen/2026-W42/5`). The same slot-key pattern is used by the ANDROID parser, UX, ALGORITHMS_PLANNING and the definition's `writeBack`. The uuid part is the version-agnostic pattern that ALGORITHMS_PLANNING 1.4 and CHALLENGE_ENGINE 9.4 also use (the ids are UUIDv7, 6.4); ANDROID.md 16.5's parser uses the same pattern and does not depend on the version digit. |
| `adopted_at` | timestamptz | null | | | User tapped "adopt" on an unmarked event (D11). |
| `write_state` | text | not null | `'none'` | E24 | |
| `attention` | text | null | | `slot_mismatch`, `unmarked_needs_adopt`, `moved_externally`, `edited_externally`, `deleted_externally` | C36: challenge state wins for the slot type. Edit policy (C37, owner ANDROID.md 16.7): a user time edit wins (`moved_externally` is informational); a user-edited title or description stops app updates for that event (`edited_externally`); the app deletes only its own unmodified future events; a user deletion is respected (`deleted_externally`, the slot is not re-created for that week). |
| `linked_session_id` | uuid | null | | FK `(linked_session_id, user_id) -> workout_sessions(id, user_id)` | C35 post-hoc link (same day, +/- 3 h). |
| `link_method` | text | null | | `auto_window`, `manual`, `created_from_plan` | |
| `recommendation_id` | uuid | null | | FK `(recommendation_id, user_id) -> recommendations(id, user_id)` | Accepted "Plan it" recommendation. |
| `last_seen_at` | timestamptz | not null | | | Last time the provider returned the instance. |
| `missing_since` | timestamptz | null | | | Instance disappeared from the provider (soft cancel). |

Indexes: `(user_id, local_date)`, `(linked_session_id, user_id)`, `(enrollment_id, user_id)`, `(recommendation_id, user_id)`, `(user_id, server_updated_at)`.

Mapping of the local `calendar_link.state` (ANDROID.md 16.6) onto the synced columns of an app-written or adopted event (ANDROID OI-26):

| `calendar_link.state` | `write_state` | `attention` |
|-----------------------|---------------|-------------|
| `active` (after a successful insert or update in the batch) | `created` / `updated` | null |
| an op planned but not yet applied (for example while the calendar permission is missing) | `pending_create` / `pending_update` / `pending_delete` | unchanged |
| `user_moved` | unchanged | `moved_externally` |
| `user_edited` | unchanged | `edited_externally` |
| `released` (the user removed the marker line) | unchanged | `edited_externally` (`marker` keeps the last written value; the app never edits the event again) |
| `user_deleted` | unchanged | `deleted_externally` |
| `deleted_by_app` | `deleted` | null |

`conflict` is used only for a C36 slot mismatch on an unmarked event that the user has not adopted or renamed yet (with `attention = slot_mismatch` or `unmarked_needs_adopt`).

Every classification decision (including busy and ignored events) is also logged as a `session_events` row of type `calendar_classified` carrying only the instance key (the same 64-hex `instance_key` as this table), kind, score and features, never the title of an unclassified event (C20).

### 9.8 Telemetry, caches and exports

#### 9.8.1 `fitness.session_events` (APPEND)

The single telemetry stream (C24, UI-12): UI, session lifecycle, rest timer, sync and calendar classification events. Written locally in the same Room transaction as the domain write it describes.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-APP | | | | | |
| `event_type` | text | not null | | E48 | Registry below; no FK (9.1.8). |
| `category` | text | not null | | `lifecycle`, `logging`, `today`, `week`, `autoreg`, `warning`, `prompt`, `rest`, `calendar`, `sync`, `config`, `system`, `challenge`, `export` | |
| `occurred_at` | timestamptz | not null | | | |
| `tz_offset_min` | smallint | not null | | -840 to 840 | Local offset at the event; disambiguates the repeated hour on 2026-10-25. |
| `screen` | text | null | | length <= 40 | |
| `session_id` | uuid | null | | FK `(session_id, user_id) -> workout_sessions` | |
| `entity_type` | text | null | | `set`, `session_exercise`, `session`, `block`, `recommendation`, `calendar_event`, `enrollment`, `exercise`, `gym_equipment`, `wellness_log`, `export`, `none` | |
| `entity_id` | uuid | null | | soft | |
| `recommendation_id` | uuid | null | | soft | The snapshot whose output was shown or acted on. |
| `module_id` | text | null | | E49 | |
| `module_version` | text | null | | E50 | |
| `ui_version` | text | null | | length <= 20 | |
| `payload` | jsonb | not null | `'{}'` | object, `octet_length(payload::text) <= 8192` | No free text (reference note ids instead). |

Indexes: `(user_id, event_type, occurred_at)`, `(session_id, user_id)`, `(user_id, server_updated_at)`.

Event registry. This table is canonical (C39): it is the union of the event names of UX.md section 20 and ANDROID.md (7.10: rest timer, foreground service, notifications, reminders, platform config, calendar writes, sync, crashes), plus the params events of ALGORITHMS.md A8, renamed to the snake_case `<area>_<event>` scheme of E48. Where UX.md 20 or ANDROID.md 7.10 still print an earlier name or payload key, the name below wins (open issue 27). Migration 005 seeds it into `session_event_types` (one row per event type with its category and payload keys); UX.md and ANDROID.md reference this table and add only triggers and value details. Rules for every payload:

- Keys are snake_case and mean the same thing in every event (glossary in UX.md 20.1). A key marked `?` is optional and is omitted rather than sent as null.
- Values are numbers on the C22/C42 grids, booleans, UUIDs, ISO timestamps, epoch milliseconds (keys ending in `_ms`) or codes. Payloads never contain free text (16.3). Lists hold at most 50 items (longer lists are truncated and `truncated: true` is added).
- `instance_key` is always the full 64-hex `calendar_events.instance_key` (9.7.3), so telemetry joins to stored events.
- Platform constants that shaped an event (for example the alarm mode or the timer step) are logged in its payload, because they are not part of `params_hash` (C54).

| Category | event_type | Emitted by (trigger details) | Payload keys |
|----------|-----------|------------------------------|--------------|
| lifecycle | `session_started` | UX 20.2 | `kind`, `template_source`, `challenge_slot?`, `families`, `budget_min?`, `budget_source`, `started_from`, `plan_recommendation_id?`, `enrollment_mode?`, `params_hash`, `engine_version`, `catalog_version`, `source_session_id?` (D49) |
| lifecycle | `session_minimised`, `session_resumed` | UX 20.2 | `via` |
| lifecycle | `session_restored` | UX 20.2 | `rest_state`, `ms_since_last_event` |
| lifecycle | `session_finished` | UX 20.2 | `duration_s`, `working_sets_done`, `working_sets_planned`, `exercises_not_done`, `not_done_reason_codes`, `outbox_depth` |
| lifecycle | `session_abandoned` | UX 20.2 | `working_sets_done`, `reason_code?` |
| lifecycle | `session_abandon_undone`, `session_reopened` | UX 20.2 | `ms_since` / `ms_since_abandon` |
| lifecycle | `session_deleted` | UX 20.2 | `working_sets`, `kind` |
| lifecycle | `session_idle_prompted` | ANDROID 11.5 | `idle_min`, `action` (`posted`, `finish`, `keep_open`) |
| lifecycle | `session_overrun_shown` | UX 20.2 (more than 10 min over budget, C63) | `elapsed_min`, `remaining_est_min`, `budget_min` |
| lifecycle | `session_summary_shown` | UX 20.2 | `prs`, `next_changes_count`, `pipeline_ms` |
| lifecycle | `recalc_report_shown` | UX 20.2 | `lines`, `test_valid` |
| lifecycle | `block_started`, `block_finished` | UX 20.2 | `block_id`, `block_kind`, `origin`, `status?` |
| lifecycle | `block_addon_skipped` | UX 20.2 | `block_id`, `reason_code`, `exercises_skipped` |
| lifecycle | `exercise_focused` | UX 20.2 | `via` |
| logging | `set_logged` | UX 20.3 | `set_id`, `session_exercise_id`, `set_index`, `side`, `set_kind`, `round_index?`, `attempt_index?`, `prefill_source`, `side_cap_applied`, `deviation_direction`, `deviation_fields`, `input_method`, `taps`, `snapped`, `log_control`, `chip_value?`, `ms_from_card_shown`, `ms_from_rest_end?`, `first_touch_at?`, `completed_at` |
| logging | `set_edited` | UX 20.3 (one event per changed field) | `set_id`, `field`, `from`, `to`, `surface`, `ms_since_logged` |
| logging | `set_undone` | UX 20.3 | `set_id`, `ms_since_logged` |
| logging | `set_deleted` | UX 20.3 | `set_id`, `surface` |
| logging | `set_added_later` | UX 20.3 | `set_id`, `hours_after_session` |
| logging | `log_double_tap_dropped` | UX 20.3 | `control`, `gap_ms` |
| logging | `deviation_shown` | UX 20.3 | `context`, `direction`, `fields`, `codes_shown`, `visible_count` |
| logging | `reason_selected` | UX 20.3 | `reason_code`, `context`, `chip_position`, `via_more` |
| logging | `flag_toggled` | UX 20.3 | `flag` (E6), `on` |
| logging | `keypad_used` | UX 20.3 | `field`, `typed`, `snapped_to`, `changed` |
| logging | `hold_started` | UX 20.3 | `mode`, `side`, `target_s?`, `lead_in_s` |
| logging | `hold_lead_in_cancelled` | UX 20.3 | `ms_into_lead_in` |
| logging | `hold_stopped` | UX 20.3 | `elapsed_s`, `via` |
| logging | `hold_corrected` | UX 20.3 | `timer_s`, `logged_s`, `delta_s` |
| logging | `hold_interrupted` | UX 20.3 | `ms_since_go?` |
| logging | `hold_count_aloud_toggled`, `hold_ecc_pacing_started` | UX 20.3 | `on` / `lead_in_s` |
| logging | `warmup_item_done` | UX 20.3 (warm-ups are logged with Done, C63) | `item_key`, `block_id` |
| logging | `exercise_skipped` | UX 20.3 | `reason_code`, `chip_position`, `sets_done` |
| logging | `exercise_deferred` | UX 20.3 | `defer_count`, `from_position`, `to_position` |
| logging | `swap_sheet_shown` | UX 20.3 | `from_exercise_id`, `candidates`, `trigger_reason` |
| logging | `exercise_swapped` | UX 20.3 | `from_exercise_id`, `to_exercise_id`, `rank?`, `similarity?`, `via`, `reason_code?` |
| logging | `exercise_added` | UX 20.3 | `exercise_id`, `source`, `position`, `block_kind`, `rule_override` |
| logging | `exercise_removed` | UX 20.3 | `exercise_id` |
| logging | `exercise_ended` | UX 20.3 | `sets_done`, `sets_planned`, `reason_code` |
| logging | `exercise_reordered` | UX 20.3 | `from_position`, `to_position`, `via`, `left_program_order` |
| logging | `override_applied` | UX 20.3 ("Add a set", C63, and other exercise-level prescription changes) | `field`, `from`, `to`, `reason_code?` |
| logging | `rule_override` | UX 20.3 (an action a hard rule forbids) | `rule_id`, `enrollment_id?`, `action`, `exercise_id?` |
| logging | `action_undone` | UX 20.3 | `undone_event_type`, `undone_event_id`, `ms_since` |
| logging | `habit_toggled` | UX 20.3 | `habit_id`, `done`, `via` |
| logging | `pain_reported` | UX 20.3 | `wellness_log_id`, `body_region`, `side`, `severity`, `pain_score`, `symptom_codes`, `context` |
| logging | `pain_retracted` | UX 20.3 | `wellness_log_id`, `ms_since` |
| logging | `pr_detected` | UX 20.3 | `exercise_id`, `kind`, `side`, `value`, `previous` |
| today | `today_card_shown` | UX 20.4 | `state`, `verdict` (`TRAIN`, `REST_SOFT`, `REST_HARD`, C53), `variant`, `options` (`option_id`, `type`, `score`), `chips` (`code`, `class`), `confidence?`, `primary_option_id?`, `calendar_instance_keys`, `unknown_codes`, `inputs_hash` |
| today | `today_action` | UX 20.4 | `action`, `rank?`, `latency_ms` |
| today | `template_picked` | UX 20.4 | `kind`, `slot_or_type`, `rank?` |
| today | `why_opened` | UX 20.4 (any screen; `screen` is the envelope column) | `subject_kind`, `subject_id?`, `codes` |
| today | `trace_exported` | UX 20.4 | `via` |
| week | `time_budget_selected` | UX 20.4 | `minutes?`, `source`, `trimmed_count`, `core_does_not_fit` |
| week | `session_trimmed` | UX 20.4 | `block_id`, `budget_min`, `trimmed`, `core_does_not_fit` |
| week | `weekplan_shown` | UX 20.4 | `iso_week`, `iso_week_offset`, `days`, `volume_rows` |
| week | `week_day_opened` | UX 20.4 | `date`, `row_kind` |
| week | `plan_it_tapped` | UX 20.4 | `date`, `type`, `recommendation_id` |
| week | `volume_row_opened` | UX 20.4 | `group`, `state` |
| autoreg | `suggestion_shown` | UX 20.5 (AUTO-1 on general items, C16; readiness "Worse" card) | `trigger`, `deltas`, `proposed` (`load_native`, `unit`, `reps`, `sets`), `recommendation_id` |
| autoreg | `suggestion_applied` | UX 20.5 | `recommendation_id`, `latency_ms` |
| autoreg | `suggestion_kept` | UX 20.5 | `implicit` |
| warning | `warning_shown` | UX 20.5 | `rule_id`, `severity`, `entity`, `magnitude`, `surface` |
| warning | `warning_dismissed` | UX 20.5 | `rule_id`, `entity`, `magnitude` |
| warning | `warning_why_opened` | UX 20.5 | `rule_id`, `entity` |
| warning | `warning_use_suggested` | UX 20.5 ("Use suggested", D10) | `rule_id`, `entity`, `field`, `from`, `to` |
| warning | `warning_use_mine_toggled` | UX 20.5 ("Use mine anyway") | `rule_id`, `entity`, `on` |
| warning | `safety_acknowledged` | UX 20.5 | `rule_id`, `entity` |
| prompt | `prompt_shown` | UX 20.5 | `prompt`, `group?`, `phase`, `position`, `budget_used`, `reduced` |
| prompt | `prompt_answered` | UX 20.5 | `prompt`, `group?`, `value_code`, `latency_ms`, `wellness_log_id?`, `observation_id?` |
| prompt | `prompt_skipped` | UX 20.5 | `prompt`, `group?`, `cause` |
| rest | `rest_started` | UX 20.6, ANDROID 12.3 | `timer_id`, `set_id`, `planned_s`, `purpose`, `duration_source`, `alarm_mode` |
| rest | `rest_adjusted` | UX 20.6, ANDROID 12.3 | `timer_id`, `delta_s`, `new_end_wall_ms`, `capped`, `via` |
| rest | `rest_skipped` | UX 20.6, ANDROID 12.3 | `timer_id`, `remaining_ms`, `via` |
| rest | `rest_prewarn` | UX 20.6 (only while the app UI is visible, C72, ANDROID 12.4) | `timer_id` |
| rest | `rest_alarm_fired` | ANDROID 12.7 | `timer_id`, `scheduled_end_wall_ms`, `fired_wall_ms`, `latency_ms`, `alarm_mode`, `app_state`, `screen_interactive`, `device_idle`, `power_save`, `battery_opt_exempt`, `standby_bucket`, `fgs_type`, `sdk_int`, `manufacturer`, `build_display`, `platform_config_hash` |
| rest | `rest_alarm_stale` | ANDROID 12.3 | `timer_id` |
| rest | `rest_alarm_failed` | UX 20.6 | `timer_id`, `error_class` |
| rest | `rest_overtime_reminder` | ANDROID 12.7 | `timer_id`, `overtime_ms` |
| rest | `rest_ended` | UX 20.6, ANDROID 12.3 | `timer_id`, `end_reason` (E44, including `session_finished`), `planned_s`, `adjust_s`, `actual_s`, `rest_source` (E56), `prev_completed_at`, `rest_end_at?`, `first_touch_at?`, `skip_at?`, `completed_at?` |
| rest | `rest_recovered` | ANDROID 12.6 | `timer_id`, `missed_by_ms?` |
| rest | `rest_tone_skipped` | ANDROID 12.7 | `timer_id?`, `reason` |
| rest | `focus_mode_toggled` | UX 20.6 | `on` |
| calendar | `calendar_classified` | ANDROID 16.4 (C20) | `instance_key`, `kind`, `slot?`, `type?`, `score`, `features`, `classifier_version`, `method` (E23) |
| calendar | `calendar_selection_saved` | UX 20.7 | `count_selected`, `primary_selected` |
| calendar | `calendar_correction` | UX 20.7 | `instance_key`, `from`, `to`, `scope` |
| calendar | `calendar_event_written` | ANDROID 16.7-16.8, UX 20.7 (executed ops only) | `op` (`insert`, `update`, `delete`), `kind` (`challenge`, `general`), `instance_key?`, `marker_key` (the C37 slot key), `cause`, `provider_rows` |
| calendar | `calendar_event_adopted` | UX 20.7 | `slot`, `instance_key` |
| calendar | `calendar_conflict_resolved` | UX 20.7 (C36) | `instance_key`, `choice` |
| calendar | `calendar_link_changed` | UX 20.7 (C35) | `from_instance_key?`, `to_instance_key?`, `method` |
| calendar | `calendar_write_failed` | UX 20.7, ANDROID 16.7 (refused or failed ops) | `op`, `error_class` (for example `marker_missing`, `week_guard`, `provider_error`) |
| calendar | `calendar_unavailable` | UX 20.7, ANDROID 16.2 | `reason` |
| sync | `sync_run` | ANDROID 9.8 | `trigger`, `pushed`, `pulled` (row totals), `pushed_by_table`, `pulled_by_table`, `duration_ms`, `outbox_before`, `outbox_after`, `oldest_outbox_age_s`, `errors` (counts by error class), `platform_config_hash` |
| sync | `sync_conflict` | 11.4, ANDROID 9.8 | `table`, `row_id`, `local_tuple`, `server_tuple`, `winner` |
| sync | `sync_error` | 11.8, ANDROID 9.8 | `table`, `row_id?`, `rows?`, `error_class`, `http_status?`, `pg_code?`, `pgrst_code?`, `attempts` |
| sync | `sync_dead_letter` | 11.8 | `table`, `row_id`, `http_status?`, `pg_code?`, `pgrst_code?`, `attempts` |
| sync | `sync_now_tapped` | UX 20.12 | `outbox_depth` |
| config | `onboarding_step_completed` | UX 20.8 | `step`, `duration_ms`, `skipped`, `fields_set` |
| config | `disclaimer_acknowledged` | UX 20.8 (C33) | `version` |
| config | `equipment_confirmed` | UX 20.8 (F7) | `profile_version`, `changed_from_default` |
| config | `equipment_changed` | UX 20.8 | `key`, `from`, `to` |
| config | `equipment_calibration_changed` | UX 20.8, 9.3.3 | `gym_equipment_id`, `from_scheme`, `to_scheme`, `from_rev`, `to_rev` |
| config | `rep_preference_changed` | UX 20.8 | `scope`, `exercise_id?`, `field`, `from`, `to`, `warning_rule_ids` |
| config | `goal_changed` | UX 20.8 | `group`, `from`, `to`, `focus_from`, `focus_to`, `via`, `preset_id?` |
| config | `preference_changed` | UX 20.8 (C26) | `exercise_id`, `from`, `to`, `surface` |
| config | `exercise_custom_created` | UX 20.8 | `exercise_id`, `pattern`, `equipment`, `coefficients_edited` |
| config | `settings_changed` | UX 20.8 | `key`, `from`, `to` |
| config | `params_activated` | UX 20.8 | `params_hash`, `params_version`, `session_id?` |
| config | `params_rejected` | UX 20.8, ALGORITHMS A8.2 | `params_version`, `kind`, `error_class` |
| config | `params_override_rejected` | ALGORITHMS A8.4 (a `user_algo_params` value outside its ParamSpec bounds; the default stays active) | `param_key`, `user_algo_param_id`, `params_version`, `error_class` |
| config | `platform_config_logged` | ANDROID 7.7, 25 (C54: whenever the `PlatformConfig` set changes) | `platform_config_hash` (SHA-256 of the JCS form), `values` (every ANDROID section-25 key and its value) |
| system | `app_opened` | UX 20.9 | `cold_start` |
| system | `app_startup_failed` | ANDROID 7.7 | `step`, `error_class` |
| system | `app_crash` | ANDROID 8.3, 20.2 (summarised at the next start) | `exception_class`, `stack_sha256`, `top_frame`, `crashed_at`, `crash_app_version` |
| system | `screen_viewed` | UX 20.9 | `iso_week_offset?`, `tab_reselected?` |
| system | `signin_failed` | UX 20.9 | `error_class` |
| system | `allowlist_rejected` | UX 20.9 | none (the user id is in the envelope) |
| system | `permission_requested` | UX 20.9 | `permission` |
| system | `permission_result` | UX 20.9 (result of a request) | `permission`, `granted`, `can_ask_again` |
| system | `permission_state` | ANDROID 15.5 (snapshot at each app start) | `permission`, `granted` |
| system | `exact_alarm_status`, `battery_optimization_status` | UX 20.9, ANDROID 7.7 (onboarding and each app start) | `allowed` / `ignoring` |
| system | `oem_settings_opened`, `oem_settings_confirmed` | UX 20.9 | `maker`, `intent_ok?` |
| system | `timer_selftest` | UX 20.9, ANDROID 15.4 | `latency_ms`, `felt`, `screen_off`, `alarm_mode`, `passed`, `platform_config_hash` |
| system | `fgs_started` | ANDROID 11.2 | `fgs_type` (`health`, `special_use`, or `none` for the untyped service below API 34, C72), `sdk_int` |
| system | `fgs_stopped` | ANDROID 11.3 | `reason` |
| system | `fgs_restart_failed` | ANDROID 11.3 | `error_class` |
| system | `notification_posted`, `notification_opened`, `notification_dismissed` | UX 20.9, ANDROID 13.3, 17 | `notification_id` (UX section 18 catalogue id), `channel` (ANDROID 13.1 channel id), `latency_ms?` (scheduled reminders: delivery time minus due time), `platform_config_hash?` (reminder deliveries) |
| system | `notification_action` | UX 20.9, ANDROID 17 | `notification_id`, `channel`, `action` |
| system | `notification_promotion_state` | ANDROID 13.4 | `can_post_promoted`, `flag_promoted` |
| system | `reminder_scheduled` | ANDROID 17 | `kind` (`habit`, `weigh_in`, `test_day`), `count`, `horizon_h` |
| system | `tick_catchup` | CHALLENGE_ENGINE 4.6 (synthesis after a gap) | `enrollment_id`, `from_date`, `to_date`, `ticks` |
| system | `engine_error` | UX 20.9 | `module_id`, `module_version`, `inputs_hash`, `error_class` |
| system | `catalog_searched` | UX 20.9 | `query_length`, `results` |
| system | `catalog_filter_changed` | UX 20.9 | `filter`, `value` |
| system | `exercise_opened` | UX 20.9 | `exercise_id`, `from` |
| system | `exercise_alternatives_opened` | UX 20.9 | `exercise_id`, `count` |
| system | `progress_viewed` | UX 20.9 | `exercise_id`, `metric`, `axis`, `range` |
| system | `progress_axis_toggled` | UX 20.9 | `axis` |
| system | `progress_point_opened` | UX 20.9 | `set_id?`, `kind` |
| system | `history_session_opened` | UX 20.9 | `days_ago` |
| challenge | `challenge_setup_step` | UX 20.10 | `step`, `step_key`, `duration_ms`, `action` |
| challenge | `challenge_cutover_confirmed` | UX 20.10 (D4) | `definition_id`, `version`, `sha256` |
| challenge | `challenge_proposal_viewed` | UX 20.10 | `proposal_id` |
| challenge | `challenge_proposal_decided` | UX 20.10 (D8, C48) | `proposal_id`, `decision` (E57: `approved`, `declined`), `previous?` (E57) |
| challenge | `challenge_rule_viewed` | UX 20.10 | `rule_id` |
| challenge | `challenge_rule_applied` | UX 11.10 (D14, C46: applied automatically, "Rule: ... · Undo") | `rule_id`, `rule_tag`, `proposal_id?`, `session_exercise_id`, `field`, `from`, `to`, `sets_affected` |
| challenge | `challenge_rule_undone` | UX 11.10 (C46: logged as an override with reason) | `rule_id`, `session_exercise_id`, `reason_code`, `ms_since` |
| challenge | `challenge_pause_sheet_shown`, `challenge_resume_sheet_shown`, `challenge_abandon_dialog_shown` | UX 20.10 | `gap_days?` |
| challenge | `test_validity_shown` | UX 20.10 | `valid`, `reason_code?` |
| challenge | `test_step_completed` | UX 20.10 | `step`, `step_key`, `duration_ms`, `skipped` |
| challenge | `test_submitted` | UX 20.10 | `valid`, `steps_completed`, `steps_skipped` |
| challenge | `celebration_shown` | UX 20.10 | `kind`, `phase`, `criterion_id?` |
| export | `export_completed` | UX 20.12, ANDROID 20.4 | `export_id`, `kind` (`monthly_full`, `manual_full`, `review_bundle`), `rows`, `bytes`, `from?`, `to?`, `notes_included?` |

Names that changed in the merge (C39). Every other name of UX.md section 20 and ANDROID.md is unchanged.

| Earlier name (source) | Registry name | Reason |
|-----------------------|---------------|--------|
| `time_budget_chosen`, `exercise_substituted`, `calendar_override`, `calendar_linked` (this document) | `time_budget_selected`, `exercise_swapped`, `calendar_correction`, `calendar_link_changed` | UX names |
| `disclaimer_accepted` (this document, `system`) | `disclaimer_acknowledged` (`config`) | UX name and category |
| `rest_elapsed` (this document) | `rest_alarm_fired` | ANDROID name |
| `alarm_capability` (this document, ANDROID 7.10 with payload `can_schedule_exact`) | `exact_alarm_status` with `allowed` | same content as the UX event, which also fires at each app start |
| `reminder_delivered`, `reminder_action` (this document, UX 20.12) | `notification_posted` (with `latency_ms`), `notification_action` | ANDROID 17 logs reminders as notifications (ANDROID OI-21) |
| `reminder_scheduled {habit_id, due_at}` (this document) | `reminder_scheduled {kind, count, horizon_h}` | ANDROID 17 payload |
| `fgs_started {type}` (this document), `notification_promotion_state {allowed, promoted}` (this document) | `fgs_started {fgs_type}`, `notification_promotion_state {can_post_promoted, flag_promoted}` | ANDROID payloads; `fgs_type` is also the key in `rest_alarm_fired` |
| `app_crash {app_version}` (ANDROID 7.10) | `app_crash {crash_app_version}` | the envelope column `app_version` holds the version that wrote the row at the next start; the payload key must not shadow it |
| `warning_use_mine` (this document), `use_mine_anyway_toggled` (UX) | `warning_use_mine_toggled` | `<area>_<event>`; it is a toggle |
| `use_suggested_tapped` (UX) | `warning_use_suggested` | `<area>_<event>` |
| `rule_suggestion_shown`, `rule_suggestion_applied`, `rule_suggestion_kept` (UX) | `challenge_rule_applied`, `challenge_rule_undone` | D14: in-session challenge rules apply automatically with Undo (C46) |
| `p_rule_viewed {item_id}`, `p_rule_decided {item_id, decision: accept \| decline}` (UX) | `challenge_proposal_viewed {proposal_id}`, `challenge_proposal_decided {proposal_id, decision: approved \| declined}` (E57) | area; decisions are per proposal id with the stored decision values (C48, C38) |
| `cutover_confirmed` (UX) | `challenge_cutover_confirmed` | area |
| `data_exported`, `review_bundle_exported` (UX) | `export_completed` with `kind` | one event per export |
| `calendar_write` (ANDROID 16.7-16.8) | `calendar_event_written` (executed ops, with ANDROID's `provider_rows`) and `calendar_write_failed` (refused or failed ops, with `error_class`) | the UX names; the former `result` key is replaced by the choice of event |
| `reliability_test` (ANDROID 15.4) | `timer_selftest` | the same 20-second test |
| `promoted_notification_state` (ANDROID 13.4) | `notification_promotion_state` | area |
| `session_idle_prompt` (UX 20.12, ANDROID 7.10 and 11.5, category `system`) | `session_idle_prompted` (category `lifecycle`) | event verb; it belongs to the session lifecycle |
| `time_overrun_shown`, `summary_shown`, `addon_block_skipped` (UX) | `session_overrun_shown`, `session_summary_shown`, `block_addon_skipped` | area |
| `double_tap_dropped`, `count_aloud_toggled`, `ecc_pacing_started` (UX) | `log_double_tap_dropped`, `hold_count_aloud_toggled`, `hold_ecc_pacing_started` | area |
| `custom_exercise_created`, `alternatives_opened`, `calendars_selected` (UX) | `exercise_custom_created`, `exercise_alternatives_opened`, `calendar_selection_saved` | area |
| `why_opened {screen, code}` (this document) | `why_opened {subject_kind, subject_id, codes}` | UX payload; `screen` is an envelope column |
| `set_edited {fields, before, after}` (this document) | `set_edited {field, from, to}`, one event per field | UX payload |
| camelCase payload keys in ANDROID (`latencyMs`, `deltaS`, `missedByMs`, `remainingMs`, `sdk`) | `latency_ms`, `delta_s`, `missed_by_ms`, `remaining_ms`, `sdk_int` | snake_case payload rule |

#### 9.8.2 `fitness.exercise_model_state` (CACHE)

Rebuildable estimator state so a fresh install can resume without replaying all history. Never authoritative: the engine can always rebuild it from facts.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-MUT | | | | id = UUIDv5 `exercise_model_state:<uid>:<model>:<exercise_id or ->:<side>:<class_key or ->` | |
| `model` | text | not null | | `sse`, `rcm`, `rircal`, `fatigue`, `hold`, `readiness`, `time`, `rest`, `stall`, `pref` | |
| `exercise_id` | uuid | null | | FK `exercises(id)`, trigger `b_exercise_visible` | Null for user- or class-level state (RIR bias, kappa, FAT c_ref). |
| `side` | text | not null | `'both'` | E1 | |
| `class_key` | text | null | | E54; `check (exercise_id is not null or class_key is not null)` | For example `upper_compound`, `small_upper`, `user`. |
| `module_version` | text | not null | | E50 | |
| `engine_version` | text | not null | | E50 | |
| `params_hash` | text | not null | | E51 | |
| `state` | jsonb | not null | | object | For example SSE-1 `{L, g, P:[[...],[...]], k, t_last}`. |
| `state_hash` | text | not null | | E51 | |
| `n_observations` | int | not null | `0` | >= 0 | |
| `as_of` | timestamptz | not null | | | |
| `as_of_session_id` | uuid | null | | soft | |

Constraint: `unique nulls not distinct (user_id, model, exercise_id, side, class_key) where deleted_at is null` (index form). Index: `(exercise_id)`.

#### 9.8.3 `fitness.exports` (APPEND)

Log of data exports (F4). The export file itself stays on the device or in the user's chosen share target.

| Column | Type | Null | Default | Check / FK | Notes |
|--------|------|------|---------|-----------|-------|
| S-APP | | | | | |
| `export_kind` | text | not null | | `monthly_full`, `manual_full`, `review_bundle` | `review_bundle` = UI-12 "Export review bundle". |
| `format` | text | not null | `'json_gz'` | `json_gz`, `json` | C40: the export container is one gzip-compressed JSON document (`.json.gz`) for every kind; `json` = uncompressed (debug only). |
| `schema_version` | text | not null | | `^fitness-export/[0-9]+$` | |
| `table_row_counts` | jsonb | not null | | object | |
| `bytes` | bigint | not null | | >= 0 | |
| `sha256` | text | not null | | E51 | Of the export file. |
| `destination` | text | not null | | `device_storage`, `share_sheet`, `drive` | |
| `period_start`, `period_end` | timestamptz | null | | | |
| `includes_local_only` | boolean | not null | `true` | | Whether local-only tables (outbox, timers) were included. |
| `engine_version`, `catalog_version` | text | not null | | E50 | |

### 9.9 `fitness_private` tables

Only `fitness_private.allowed_users` (5.2). It is never synced and never exported by the app.

---

## 10. Engine output contracts used by the database and reviews

The ALGORITHMS documents own the full `inputs` / `outputs` / `trace` schemas of every snapshot. This section lists only (a) the snapshot kinds and their scope keys, and (b) the JSON keys that review views read, so that review SQL never recomputes engine values (C3, C6). Changing a listed key is a breaking change for `fitness_review` and needs a view migration.

### 10.1 Snapshot kinds (`recommendations.kind`)

| kind | Module | scope_key | Written at (pipeline layer) |
|------|--------|-----------|-----------------------------|
| `today.options` | `today` | `day:<date>` | Today screen opened or inputs changed (L5) |
| `weekplan.plan` | `weekplan` | `week:<iso_week>` | Replan triggers (L5) |
| `targets.weekly` | `targets` | `week:<iso_week>` | Weekly and on goal change (L4) |
| `accounting.week` | `setcredit` | `week:<iso_week>` | After each session and at week close (L3/L8) |
| `readiness.snapshot` | `readiness` | `session:<uuid>` | Session start (L3) |
| `safety.state` | `safety` | `day:<date>` | On every PainEvent and daily (L1) |
| `challenge.contract` | `challenge.contract` | `week:<iso_week>` | After each challenge state change (L2) |
| `challenge.weekplan` | `challenge.plan` | `week:<iso_week>` | L2 |
| `challenge.session_plan` | `challenge.materialize` | `session:<uuid>` or `block:<uuid>` | Session start (L6) |
| `session.plan` | `select` (with `elig`, `order`, `time`) | `block:<uuid>` | General or add-on block build (L6) |
| `rx.prescription` | `rx` | `session_exercise:<uuid>` | Per exercise (L6); RULE mode records the model's shadow proposal |
| `warmup.sets` | `warmup` | `session_exercise:<uuid>` | L6 |
| `warnings.evaluation` | `warnings` | `session:<uuid>` or `week:<iso_week>` | L6 and weekly |
| `autoreg.suggestion` | `autoreg` | `session_exercise:<uuid>` | When UI-06 triggers (L7) |
| `estimation.sse` | `sse` | `session:<uuid>` | Session finish (L8) |
| `estimation.rcm` | `rcm` | `exercise:<uuid>` | Session finish (L8) |
| `estimation.rircal` | `rircal` | `user` | After a calibration set (L8) |
| `estimation.fatigue` | `fatigue` | `class:<key>` | Nightly personalisation (L8) |
| `estimation.hold` | `hold` | `session:<uuid>` | Session finish (L8) |
| `stall.weekly` | `stall` | `week:<iso_week>` | Weekly (L8) |
| `rotate.mesocycle` | `rotate` | `mesocycle:<n>` | Mesocycle start (F12) |
| `rest.adapt` | `rest` | `class:<key>` | After >= 10 sets per class |
| `time.calibration` | `time` | `user` | After each session (C19) |

The calendar classification is not a snapshot; it is a `session_events` row of type `calendar_classified` (C20).

### 10.2 Keys that review views depend on

This list is canonical (C58): every module writes at least these `outputs` keys, and the review metrics of the ALGORITHMS documents read only these keys and the facts. Keys are lower snake_case; where a module specification prints another spelling (for example `J_min`, `F`, `T_s` or camelCase Kotlin field names), the serialised snapshot uses the name below. A module may write further keys (the full schemas belong to the ALGORITHMS documents); adding a key is not a breaking change, renaming or removing a listed key is.

| kind | Required `outputs` keys |
|------|-------------------------|
| `estimation.sse` | `sets[]: {set_id, q_true, n_eff, n_fresh, fatigue_f, z, s, estimator_class, e1rm_kg, e1rm_added_kg, k_used, residual, n_eff_pred, z_pred_lo, z_pred_hi}`; `states[]: {exercise_id, side, m_kg, ci80_lo_kg, ci80_hi_kg, g_per_week, l_prior, p_ll_prior, huber_applied, lb_applied}`. The one-step-ahead keys (`n_eff_pred`, `z_pred_lo`, `z_pred_hi` per set, `l_prior`, `p_ll_prior` per state) come from the prior state before this session's update (ALGORITHMS B6.5); `huber_applied` and `lb_applied` feed review metric M5. |
| `estimation.rcm` | `k_prev`, `k_hat`, `k_used`, `sd_lnk`, `m`, `n_sets`, `rate_limited`, `pooled`, `j_min`, `j_k0`; `sets[]: {set_id, w_sys, n_eff, fatigue_f, n_fresh, window, residual}` (ALGORITHMS B5.1) |
| `estimation.rircal` | `b_before`, `b_after`, `s_q_before`, `s_q_after`, `e`, `type` (`explicit`, `implicit`, `reason_hook`), `class`, `n_c` (ALGORITHMS B4.6) |
| `estimation.fatigue` | `c_hat`, `c_used`, `d_hat`, `d_used`, `m`, `resid_by_rest_bucket: {<bucket>: mean}` (buckets `lt75`, `b75_105`, `b105_150`, `b150_210`, `gt210` s), `resid_by_rir_bucket: {<bucket>: mean}` (`q0_1`, `q2_3`, `q4_plus`), `resid_by_set_index: {<bucket>: mean}` (`s2`, `s3`, `s4`, `s5_plus`) (ALGORITHMS B7.5; the curve class is the scope key `class:<curve_class>`) |
| `estimation.hold` | `sets[]: {set_id, t_done, s_res, fatigue_f, w_sys, z, sigma, estimator_class}`; `states[]: {exercise_id, side, hold_class, t_s, ci80_lo_s, ci80_hi_s, g_per_week, beta, w_ref_kg}` (ALGORITHMS B8.4) |
| `stall.weekly` | `exercises[]: {exercise_id, side, status, g, sd_g, p_prog, n_exposures, event, tier_action}`; `fail_counters[]: {exercise_id, side, count}`; `global: {triggers[], recommended, planned_deload_within_7_days}` (ALGORITHMS B14.3) |
| `safety.state` | `as_of`, `trigger_event_id`, `joint_flags[]: {joint, severity, since, until, source_event_ids[]}`, `forbid_session_tags[]`, `forbid_exercise_tags[]`, `challenge_modes[]`, `fingerprint` (ALGORITHMS_PLANNING 2.3, 2.6) |
| `autoreg.suggestion` | `trigger_rule`, `deltas[]`, `delta_ex`, `kind` (`load`, `reps`, `stop`), `proposed: {load_point, reps[]}`, `rir_next_cur`, `rir_next_new` (ALGORITHMS B13.6) |
| `warmup.sets` | `tier`, `p_w`, `sets[]: {load_point, reps, rest_s}` (ALGORITHMS B10) |
| `rotate.mesocycle` | `mesocycle`, `anchors[]: {exercise_id, reason}`, `keep_scores: {<exercise_id>: score}`, `rotated[]: {from_exercise_id, to_exercise_id, reason}` (ALGORITHMS_PLANNING 16) |
| `rest.adapt` | `rest_class`, `base_old_s`, `base_new_s`, `early_share`, `resid_mean`, `n_sets` (ALGORITHMS B11.4) |
| `time.calibration` | `setup_s: {<exercise_id>: s}`, `tempo_s_per_rep: {<rest_class>: s}`, `rest_ratio`, `transition_s`, `n_exposures: {<exercise_id>: n}` (ALGORITHMS_PLANNING 17.4) |
| `accounting.week` | `groups: {<group_id>: {vhyp_done, vstr_done, fatigue_done, committed}}`; `sets[]: {set_id, credits: {<group_id>: number}}` |
| `targets.weekly` | `groups: {<group_id>: {goal, target, mrv, cap_s, covered_by_challenge, reentry, deload}}` |
| `weekplan.plan` | `days[]: {date, kind, type, est_minutes}`; `recommended_n` |
| `session.plan`, `challenge.session_plan` | `est_min`, `budget_min`, `exercises[]: {exercise_id, sets, est_s}` |
| `rx.prescription` | `mode`, `sets[]: {set_index, side, target_reps, rir, effective_load_kg}` (`rir` is the target RIR), `caps_applied[]: {type, limit_w, unclamped_w}` (`type` includes `double_one_step` and `rule_excess`, C45), `rir_pred[]`, `scheme: {kind, r_lo, r_hi, r_star, q_star}`, `probe` (ALGORITHMS B9.14, B9.15) |
| `warnings.evaluation` | `warnings[]: {rule_id, severity, entity, magnitude, evidence_grade, enforced}` (the same list is copied into the envelope column `warnings`) |
| `today.options` | `options[]: {option_id, session_type, score}`, `rest_advice`, `verdict` (`TRAIN`, `REST_SOFT`, `REST_HARD`), `reasons[]: {code, class}` (C53; spec in ALGORITHMS_PLANNING 9) |

The rows of the ALGORITHMS.md modules (`sse`, `rcm`, `rircal`, `fatigue`, `hold`, `stall`, `rx`, `autoreg`, `warmup`, `rest`, `warnings`) equal that document's D5 review-key table together with the base keys it already took from here (ALGORITHMS OI-A26); the rows of the ALGORITHMS_PLANNING modules (`safety`, `rotate`, `time`, `setcredit`, `targets`, `weekplan`, `select`, `challenge.materialize`, `today`) are defined here and referenced by that document (ALGORITHMS_PLANNING 0.2, C58). The review views read these keys with `->` / `->>` and `jsonb_array_elements`; they do not reimplement any formula. v1 ships review views only for the kinds that section 14 uses; the keys of the other kinds are read by ad-hoc review queries until a view migration adds a view (14.24).

---

## 11. Sync protocol (C23)

### 11.1 Summary

Room is authoritative. Every local write appends to an outbox in the same Room transaction. A push loop sends outbox rows table by table in dependency order with plain PostgREST upserts: mutable tables merge, the server `BEFORE UPDATE` trigger drops stale rows by `(client_updated_at, device_id)` (7.1); append-only tables ignore duplicates. A pull loop fetches each table by a `server_updated_at` cursor minus a 120 s overlap and applies the same last-writer-wins rule locally. No third-party sync service, no RPC, no realtime.

### 11.2 Local write path

The local write path and the outbox table are owned by ANDROID.md 8.3-8.4 (C40). In summary:

```
fun localWrite(table, row):                                // one Room transaction (ANDROID.md 8.4)
  room.withTransaction {
    row.client_updated_at = clock.nextClientTimestampMs()  // epoch ms, max(now_ms, last_issued_ms + 1): strictly increasing per device (6.2)
    row.device_id = DEVICE_ID
    row.app_version = APP_VERSION
    dao.upsert(table, row)
    if (event != null) dao.insert("session_events", event) // same transaction (UI-12)
    outbox.enqueue(table, row.id, op = if (isAppend(table)) INSERT_IGNORE else UPSERT)
                                                           // INSERT OR IGNORE on unique (table_name, row_id), then gen = gen + 1: one entry per row
  }
```

The outbox stores no payload: the push reads the current entity when it builds the batch, which coalesces many edits of one row into one push (ANDROID.md 8.3, C40). The sync protocol requires one guard from that design, and ANDROID.md 8.3 provides it: every enqueue of an already queued row increments the entry's `gen` counter, and after a successful push an outbox entry is deleted only if its `(seq, gen)` still equals the value read for the batch; otherwise the entry stays and the newer version is pushed in the next batch (open issue 23, resolved).

The client never sends `server_updated_at` or `server_checksum`; the request lists the writable columns explicitly (`columns=` parameter), so a missing key can never null a column.

### 11.3 Push

Order (parents before children; any table not listed is never pushed):

| Step | Table | Mode |
|------|-------|------|
| 1 | `gyms` | merge |
| 2 | `gym_equipment` | merge |
| 3 | `profiles` | merge |
| 4 | `exercises` (own custom rows) | merge |
| 5 | `exercise_stimulus` (own rows) | merge |
| 6 | `user_exercise_prefs` | merge |
| 7 | `rep_preferences` | merge |
| 8 | `muscle_goals` | merge |
| 9 | `challenge_enrollments` | merge (LWW-REV) |
| 10 | `params_snapshots` | ignore-duplicates |
| 11 | `recommendations` | ignore-duplicates |
| 12 | `workout_sessions` | merge |
| 13 | `session_blocks` | merge |
| 14 | `session_exercises` | merge |
| 15 | `set_logs` | merge |
| 16 | `wellness_logs` | merge |
| 17 | `metric_observations` | merge |
| 18 | `calendar_events` | merge |
| 19 | `challenge_events` | ignore-duplicates |
| 20 | `challenge_rule_events` | ignore-duplicates |
| 21 | `session_events` | ignore-duplicates |
| 22 | `exercise_model_state` | merge |
| 23 | `exports` | ignore-duplicates |

Within a table, rows are sent in outbox `seq` order, so a self-referencing child (`session_exercises.substitutes_id`, `challenge_events.supersedes_event_id`) never precedes its parent; inside one multi-row insert PostgreSQL checks non-deferred foreign keys at the end of the statement, so parent and child may share a batch.

Request for a mutable table:

```
POST https://nuvjdvpqibqeyafzgaye.supabase.co/rest/v1/set_logs?on_conflict=id&columns=<writable columns>&select=id,client_updated_at,device_id,server_updated_at
apikey: <publishable key>
Authorization: Bearer <user access token>
Content-Profile: fitness
Content-Type: application/json
Prefer: resolution=merge-duplicates,return=representation
<JSON array of up to 200 rows>
```

Request for an append-only table:

```
POST .../rest/v1/session_events?on_conflict=id&columns=<columns>
Content-Profile: fitness
Prefer: resolution=ignore-duplicates,return=minimal
```

Upserts are `INSERT ... ON CONFLICT`, so pg-safeupdate (which rejects unfiltered `UPDATE`/`DELETE`) never fires. `merge-duplicates` needs `INSERT` + `UPDATE` grants and policies and, with `return=representation`, the `SELECT` policy; `ignore-duplicates` needs only `INSERT`.

### 11.4 Stale-write detection

```
response_ids = ids in the representation
stale = batch_ids - response_ids          // rows the server trigger dropped (equal or older tuple)
if stale not empty:
  rows = GET /rest/v1/<table>?id=in.(<stale ids>)&select=*
  for r in rows: applyPulled(table, r)    // 11.5: the newer server version wins locally
  log session_events 'sync_conflict' per row (local tuple, server tuple, winner = server)
for each sent row: delete its outbox entry unless the entity changed after it was read for this batch (11.2)
```

A re-push of an already stored version is also "dropped" (equal tuple); the follow-up GET returns the identical tuple and the client simply marks it synced.

### 11.5 Pull

Order: reference tables (`muscle_groups`, `muscle_regions`, `reason_codes`, `metric_defs`, `body_regions`, `symptom_codes`, `equipment_types`, `algorithm_versions`, `algo_params`, `catalog_releases`), then `exercises`, `exercise_stimulus`, `exercise_transfer_ratios`, `challenge_definitions`, `user_algo_params`, then the user tables in push order.

```
fun pullTable(table):
  since = cursor[table] - OVERLAP                 // OVERLAP = 120 s (C23)
  page = GET /rest/v1/<table>?select=*&<owner filter>&<device filter>
             &<cursor column>=gte.<since>&order=<cursor column>.asc,id.asc&limit=PAGE   // PAGE = 500 (< max_rows 1000)
  maxSeen = cursor[table]
  loop:
    room.withTransaction { for r in page: applyPulled(table, r) }
    maxSeen = max(maxSeen, last(page).<cursor column>)
    if page.size < PAGE: break
    page = GET ... &or=(<col>.gt.<lastTs>,and(<col>.eq.<lastTs>,id.gt.<lastId>)) ...   // keyset pagination
  cursor[table] = maxSeen                          // overlap is applied at the next pull, not stored

fun applyPulled(table, r):
  local = find(table, r.id)
  when (syncClass(table)) {
    REF-PULL, DEF-PULL, SRV-PULL, MIX-global -> upsert local (server authoritative)
    APPEND -> insert if absent
    LWW, LWW-REV, CACHE, MIX-own ->
      if (local == null) insert r as synced
      else when (compare(tuple(r), tuple(local))) {        // tuple = (client_updated_at, device_id); LWW-REV: (state_rev, client_updated_at, device_id)
        > 0  -> overwrite local; drop pending outbox entries of r.id; log 'sync_conflict' if one was pending
        == 0 -> mark synced
        < 0  -> keep local; ensure an outbox entry exists (re-enqueue)
      }
  }
```

| Parameter | Value |
|-----------|-------|
| Cursor column | `server_updated_at` (user, MIX, DEF tables); `updated_at` (REF tables) |
| Owner filter | User tables: `user_id=eq.<uid>` (RLS would filter anyway; the explicit filter lets PostgreSQL use the index, research: 171 ms to 9 ms). MIX: `or=(user_id.is.null,user_id.eq.<uid>)`. |
| Device filter | User tables only: `device_id=neq.<this device>`. Rows this device pushed are skipped (they come back only if another device or a server-side edit changed them, which changes `device_id`). |
| Timestamp format | ISO 8601 UTC with microseconds, URL-encoded (`2026-10-07T10:00:00.123456Z`). |

Why 120 s: `server_updated_at = now()` is the transaction start time, so a long transaction can commit after a later-starting one and appear "in the past" relative to the cursor. 120 s covers far more than the 8 s statement timeout plus lock wait; duplicates from the overlap are harmless because `applyPulled` is idempotent.

### 11.6 Pull scope per table

| Tables | Bootstrap (new device, reinstall) | Incremental |
|--------|-----------------------------------|-------------|
| REF, MIX, DEF, SRV-PULL | Full | Cursor |
| USER and CACHE tables | Full | Cursor |
| `challenge_events`, `challenge_rule_events`, `params_snapshots`, `exports` | Full | Cursor |
| `recommendations` | Last 90 days (`created_at=gte.`) | Cursor, same window |
| `session_events` | Last 365 days, only types that rebuild local caches: `warning_*`, `prompt_*`, `equipment_calibration_changed`, `params_activated` | Cursor, same filter |
| `session_event_types` | Never | Never |

Incremental pulls of the append-only challenge tables are required for multi-device convergence (11.13); ANDROID.md 9.6 pulls them with one cursor per table, not only at bootstrap (C67).

After a bootstrap the client refolds every enrollment from its events and compares the result with the pulled cached state (`state_hash`); a mismatch means the engine changed, the local refold wins and is pushed with a higher `client_updated_at`, and a `sync_conflict` event records it.

### 11.7 Tombstones

- Deleting anything sets `deleted_at` (and a new `client_updated_at`); the tombstone syncs like any update. LWW allows an undelete by a newer edit.
- The server keeps tombstones indefinitely (tiny). No hard deletes from the app; `authenticated` has no `DELETE` grant.
- Room may physically purge a tombstone 30 days after it was confirmed synced (no pending outbox entry). An old tombstone can never be re-pulled because its `server_updated_at` is far older than the cursor overlap.

### 11.8 Error handling

| Condition | Detection | Action |
|-----------|-----------|--------|
| Success | 2xx | Remove the sent outbox entries, except rows changed since they were read (11.2). Run 11.4 for mutable tables. |
| Expired session | 401 (`PGRST301`/`PGRST303`) | Refresh the session once and retry. If refresh fails, stop syncing and ask the user to sign in; the outbox is kept. |
| Not allowlisted / RLS / missing grant | 401/403 with `42501` | If the very first push of a profile fails this way, show "This account is not enabled for the fitness app" and stop syncing. Otherwise move the row to the dead-letter table (it is a bug) and continue. |
| Schema not exposed | `PGRST106` | Stop syncing; banner "Server not configured: the fitness schema is not exposed" (F2 not done yet); retry at each app start. |
| App newer than database | `PGRST204` or `42703` (unknown column) / `PGRST205` (unknown table) | Stop syncing that table and every later table that depends on it; banner "Server update needed"; retry hourly. |
| Parent not on the server yet | `23503` | Keep in the outbox, `attempts += 1`, next attempt after `2^attempts` minutes; dead-letter after `sync.fkRetryAttempts` (5) attempts (ANDROID.md 9.5). |
| Natural-key collision | `23505` | Should not occur (UUIDv5 natural ids); dead-letter, surface in the debug screen. |
| Invalid data | `23514`, `22P02`, `22003`, `23502` | Dead-letter the row immediately; the rest of the batch is retried without it (binary split of the batch to isolate the row). |
| Payload too large | 413 | Halve the batch size for this table and retry. |
| Rate limited | 429 | Wait `Retry-After`, else back off. |
| Transient | 5xx, `57014` statement timeout, network error, paused project | Retry with the WorkManager exponential backoff starting at `sync.backoffInitialS` (30 s; ANDROID.md 9.4); keep table order (do not push children of a failed parent table in this cycle). After `sync.unreachableBannerH` (24 h) of continuous failure show "Server unreachable (paused?)". |
| Server read-only (Free plan above 500 MB) | `25006` (`read_only_sql_transaction`) | Stop pushing, keep every outbox row, keep pulling, show the storage banner and retry every `sync.periodicH` (6 h) (15.2). ANDROID.md 9.5 lists the same row; nothing is dead-lettered (open issue 11, resolved). |

Every error is logged as a `session_events` row (`sync_error` or `sync_dead_letter`) with codes only, never payload contents. The retry and backoff constants named here are platform constants: they live in `PlatformConfig` with the values of ANDROID.md 25.4 (C54); this table fixes only which error class gets which treatment.

### 11.9 Scheduling

Triggers and their constants are owned by ANDROID.md 9.3 (C54). In summary:

| Trigger | Push | Pull |
|---------|------|------|
| Any committed local write that enqueued outbox rows | WorkManager unique work `sync` after `sync.debounceS` (3 s), `NetworkType.CONNECTED`, policy `KEEP`; a running sync keeps draining the outbox | after push |
| App comes to the foreground | yes | yes |
| Session finished | yes (expedited) | yes |
| Connectivity regained | yes (pending work has a `CONNECTED` constraint) | yes |
| User taps "Sync now" | yes | yes |
| Periodic | WorkManager every `sync.periodicH` (6 h) | yes |

Sync never blocks the UI and never runs on the main thread. A visible sync chip shows: synced / pending N / offline / error. The `sandbox` build flavour never syncs and never writes to the calendar (F27).

### 11.10 Outbox safety (sign-out, reinstall, updates)

- **Sign-out** is disabled while the outbox or the dead-letter table is not empty. The screen shows the count with "Sync now" and "Export, then sign out".
- **Uninstall cannot be blocked by an app.** Mitigations: (1) the stable release keystore (F17) means updates install in place (`adb install -r`) and keep the database; (2) debug builds write `files/sync_status.json` `{outbox_depth, oldest_enqueued_at, dead_letters}` on every change, and the developer install script refuses `adb uninstall` or a signature-changing reinstall while `outbox_depth > 0` (it reads the file with `adb shell run-as dev.fitnessapp cat files/sync_status.json`; application id per F22); (3) a persistent "unsynced data" chip; (4) the monthly JSON export (F4).
- **App updates** must ship Room migrations for every local schema change; `fallbackToDestructiveMigration` is forbidden (test `RoomMigrationTest`).

### 11.11 Params and definitions

New `algo_params`, `user_algo_params` and `challenge_definitions` rows are pulled at any time but activated only at the next session start (critique gap: params pinned per session). The session row stores `engine_version`, `params_hash` and `catalog_version` at start. A pulled definition whose recomputed JCS hash differs from `jcs_sha256` is rejected and logged.

### 11.12 Clocks

- `client_updated_at` comes from the device wall clock, made strictly increasing per device.
- Clock skew is measured on every push from returned rows (`server_updated_at - client_updated_at`). If the absolute skew exceeds 10 minutes (tunable), the app shows "Your phone clock looks wrong" and logs `sync_error` with code `clock_skew`. LWW correctness depends on roughly correct clocks; the append-only event log keeps a full forensic history if a skewed device ever wins wrongly.
- Server-side edits (review writes as `postgres`) use `client_updated_at = now()` and `device_id = SERVER_DEVICE_ID` (`ffffffff-...`), which wins ties.

### 11.13 Multi-device

Convergence works for any number of devices (LWW on mutable rows, idempotent inserts on append-only rows, deterministic ids for natural keys and synthesized ticks, deterministic challenge folds ordered by `(occurred_at, id)`). v1 still assumes one primary phone (D2). If events from another device arrive with an `occurred_at` earlier than the last folded event, the enrollment is refolded and `fold_generation` increments.

---

## 12. Room local schema (summary of ANDROID.md section 8)

ANDROID.md section 8 owns the Room schema: the database file, the column representation, the local-only tables, the local write path and Room migrations (C40). This section summarises what the data layer relies on. Where the two documents differ, ANDROID.md section 8 wins for the local schema, and this document wins for the server schema and the sync protocol (section 11).

### 12.1 Type mapping

| PostgreSQL | SQLite (Room) | Kotlin | Note |
|------------|---------------|--------|------|
| `uuid` | `TEXT` (36 chars, lower-case) | `Uuid` value class | Client ids are UUIDv7 (ANDROID.md 8.2) or UUIDv5 (6.4). |
| `text` | `TEXT` | `String` | |
| `text[]` | `TEXT` (JSON array) | `List<String>` via TypeConverter | |
| `jsonb` | `TEXT` (canonical JSON) | typed DTO or `JsonObject` | |
| `boolean` | `INTEGER` 0/1 | `Boolean` | |
| `smallint`, `int` | `INTEGER` | `Int` | |
| `bigint` | `INTEGER` | `Long` | |
| `numeric(_,2)` kg | `INTEGER` hundredths (kg x 100) | `Kg` | Exact C22 grid; no `REAL` columns for stored quantities (ANDROID.md 8.2). |
| `numeric(_,1)` seconds, RIR | `INTEGER` tenths (x 10) | `Tenths` | |
| `numeric(_,4)` ratio | `INTEGER` 1e-4 units (x 10 000) | `Ratio` | |
| `numeric(10,3)` metric value | `INTEGER` thousandths | `Milli` | The same scaled-integer rule applied to `metric_observations.value_num`. |
| `timestamptz` written by the client (`created_at`, `client_updated_at`, `started_at`, `completed_at`, ...) | `INTEGER` epoch milliseconds UTC | `Instant` | ANDROID.md 8.2. `client_updated_at` has millisecond precision on both sides (6.2), so the last-writer-wins comparison is exact. |
| `server_updated_at`, and `updated_at` of REF tables (the pull cursors) | `TEXT` exactly as returned by PostgREST (microsecond precision) | `String` | No precision loss in the pull cursor (ANDROID.md 8.2). |
| `date` | `TEXT` `YYYY-MM-DD` | `LocalDate` | |

DTOs (kotlinx.serialization) write numbers as exact decimal JSON numbers from the scaled integers (never through `Double`).

### 12.2 Mirrored tables

Every `fitness` table except `session_event_types` has a Room entity with the same table name and the same column names (ANDROID.md 8.1), so DTO mapping is mechanical. The data layer relies on these properties of the entities: the same unique indexes as the server (including the `set_logs` unique set key, the single `in_progress` session and every natural-key unique index), so constraint violations surface locally first; foreign keys at least for the structural chain `workout_sessions -> session_blocks -> session_exercises -> set_logs`, declared `deferred = true` so a pulled page can be applied in one transaction regardless of row order; and every CHECK constraint of section 9 re-implemented as a Kotlin validator in `:data` (one validator per entity, unit-tested against the same fixtures as the SQL).

### 12.3 Local-only tables (never synced)

ANDROID.md 8.3 defines them: `outbox`, `sync_cursor`, `dead_letter`, `rest_timer` (ANDROID.md 12.2), `calendar_link` (ANDROID.md 16.6), `calendar_instance`, `scheduled_alarm`, `params_cache`, `warning_dismissal`, `prompt_state`, `exercise_fts`, `device` and `crash_log`. Local-only table names carry no `local_` prefix and none collides with a `fitness` table name. What the data layer needs from them:

| Need | Provided by |
|------|-------------|
| One outbox entry per row, pushed in dependency order (11.3), with the payload read from the entity at push time, and no edit lost while a push is in flight | `outbox` with its `(seq, gen)` guard (ANDROID.md 8.3; 11.2) |
| Pull cursor per table with microsecond precision and keyset tie-break | `sync_cursor` (`server_updated_at` as text, `last_id`) |
| Rows the server rejected permanently, kept for retry or export and never deleted automatically | `dead_letter` |
| The random `device_id` used as the LWW tie-break, new on every install | `device` |
| Rest-timer state that survives process death; outcomes copied to the `set_logs` rest fields (C55) and to `session_events` | `rest_timer` |
| Mapping between app-written calendar events and slots, rebuildable from the markers (C37) | `calendar_link` |
| Busy and unclassified calendar events never reach the outbox or the server (16.2) | `calendar_instance`: the local cache of the read window (ANDROID.md 16.4), including busy and ignored instances with their titles; never synced and not part of any export (15.5, 16.1), rows older than 60 days purged (ANDROID.md 8.3) |
| Effective params before the first pull | `params_cache` (bundled or pulled `algo_params`, verified by `params_hash`) |
| UI-07 and UI-10 state (prompt skip streaks, warning dismissals) | `prompt_state`, `warning_dismissal`; both rebuildable from `session_events` |

The local tables of the earlier draft of this section (`local_outbox` with a payload copy, `local_calendar_instance`, `local_calendar_write_queue`, `local_sync_log` and others) are superseded by ANDROID.md 8.3: the outbox has no payload copy, the calendar cache is `calendar_instance`, a write plan is applied in one `applyBatch` (no write queue), and the sync log is the `sync_run` telemetry (no ring buffer).

### 12.4 Bundled fallbacks

The APK bundles the reference data, the catalog (with its UUIDv5 ids), the default params JSON and the published OAP definition from `algo/src/main/resources/fitness/` (params under `fitness/params/`, C40; ANDROID.md 4). They are loaded into Room (the params into `params_cache`) on first launch so the app works before the first sync (critique gap: first login needs network), and are replaced by pulled rows when the server versions are newer.

### 12.5 Local retention

Facts are kept forever. `recommendations` and `session_events` are kept locally for at least 365 days; older rows are pruned only if they are synced and contained in a completed monthly export. Tombstones are purged 30 days after confirmed sync. Android Auto Backup is off (D16, F21), so a reinstall never restores an old copy of the database; a new install bootstraps from Supabase (11.6) or, later, from an export file (15.4).

---

## 13. Migration plan

### 13.1 Process (F3)

1. Claude writes the migration as `supabase/migrations/pending/fitness_NNN_<name>.sql`. Every object is schema-qualified; grants, RLS and policies are in the same file as the table; every created function is followed by its `revoke ... from public`.
2. The user reviews the file (D-list action 4).
3. Once real data exists: run the in-app export and `supabase db dump --schema fitness --data-only` (the DB connection string is provided by the user through an environment variable and never committed). Claude never runs `supabase db push`, `supabase config push`, `migration repair` or `alter role authenticator set pgrst.db_schemas` (F2/F3).
4. Claude applies it with the Supabase MCP `apply_migration` (`name = 'fitness_NNN_<name>'`), which records the version in the shared `supabase_migrations.schema_migrations` next to the chess history.
5. Claude renames the file to `supabase/migrations/<assigned version>_fitness_NNN_<name>.sql`.
6. Claude runs the verification checklist (section 17) and records the result in the migration's review notes.
7. Data migrations are idempotent (`on conflict ... do update ... where ... is distinct from ...`), so re-running one is harmless.

No automatic down migrations exist. Each file ends with a commented manual rollback (drops in reverse order), applied only with explicit user approval.

### 13.2 Ordered migration list

| # | File | Milestone | Contents | Specific checks |
|---|------|-----------|----------|-----------------|
| 001 | `fitness_001_schemas_and_roles` | M0 | Create `fitness`, `fitness_private`, `fitness_review`; `revoke all on schema ... from public`; `grant usage on schema fitness, fitness_private to authenticated, service_role`; schema comments. No tables. | `has_schema_privilege('anon','fitness','USAGE') = false`. |
| 002 | `fitness_002_private_helpers` | M0 | `fitness_private.allowed_users`; `is_allowed()`; trigger functions `tg_sync_row`, `tg_append_only`, `tg_touch_reference`, `tg_enrollment_rev`, `tg_challenge_definition_guard`, `tg_check_exercise_visible`, `tg_check_symptom_codes`; all `revoke ... from public`; `grant execute on is_allowed() to authenticated, service_role`. | No fitness function with a `PUBLIC` ACL entry; all have `search_path=""`. |
| 003 | `fitness_003_allowlist_owner` (data) | M0 | `insert into fitness_private.allowed_users (user_id, role, note) values ('<owner uuid>', 'owner', 'owner') on conflict do nothing`. The UUID is resolved beforehand by a read-only lookup; no email in the file. | Exactly one owner row. |
| 004 | `fitness_004_reference_tables` | M0 | REF tables: `muscle_groups`, `muscle_regions`, `reason_codes`, `metric_defs`, `body_regions`, `symptom_codes`, `equipment_types`, `session_event_types`, `algo_params`, `algorithm_versions`, `catalog_releases`; RLS, grants, `a_touch` triggers. | RLS on; `authenticated` has only `SELECT`. |
| 005 | `fitness_005_reference_seed` (data; applied 2026-10-08 as `_part1` reference rows and `_part2`-`_part7` params chunks, because one MCP call cannot carry the 133 KB params set: the chunks build a `draft` row that the last part activates after key-count and hash checks) | M0 | 19 groups (18 + inactive `neck`), 35 regions (34 + inactive `neck`, C44), 13 reason codes, 16 metric defs (9.1.4), body regions, symptom codes, equipment types, the 9.8.1 event-type registry (C39), `algo_params` 1.0.0 (from `algo/src/main/resources/fitness/params/default-params-1.0.0.json`, C40; status `active`), 51 `algorithm_versions` rows at 1.0.0 with `constants_hash`. | Row counts; `params_hash` recomputed by the seed generator matches. |
| 006 | `fitness_006_profiles_gyms` | M0 | `profiles`, `gyms` (9.3.1, 9.3.2); USER template. Pulled forward from the user-config migration (2026-10-08) so that M0 proves the write path: the app upserts the owner's profile on first login. | Two-user RLS test for both tables; LWW trigger test on `profiles`. |
| 007 | `fitness_007_jsonschema_extension` | M0 (user approved F5 on 2026-10-08) | `create extension if not exists pg_jsonschema with schema extensions`. The CHECK that uses it ships in 018. | `list_extensions` shows `pg_jsonschema`; advisors. |
| 008 | `fitness_008_catalog_tables` | M1 | `exercises`, `exercise_stimulus`, `exercise_transfer_ratios`; MIX policies; visibility trigger on `exercise_stimulus`. | MIX policy test (global readable, own writable, others invisible). |
| 009 | `fitness_009_catalog_seed` (data) | M1 | `catalog_releases` 1.0.0; ~150 curated exercises, stimulus coefficients (C10 levels), transfer ratios. Generated from `catalog/catalog-1.0.0.json` (the same file the APK bundles). The seed contains every slug a published challenge definition references (C38); for `oap.challenge.v1.json` that union is 29 slugs: the 15 catalog slugs reached through `catalogRef` (`chinup_weighted`, `neutral_grip_pullup_weighted`, `pullup_weighted` and `chinup_bw`, `neutral_grip_pullup_bw`, `pullup_bw` through the grip map of the definition keys `weighted_pullup` and `pullup_bw`; `dip_neutral`, `barbell_row_pronated`, `seated_cable_row_wide_pronated`, `chest_supported_row_pronated`, `reverse_wrist_curl_db`, `db_pronation_supination`, `face_pull_cable`, `rower_erg`, `scap_pull_bar`) and the 14 challenge-local entries seeded as global rows under their definition key (`weighted_negative_pullup`, `oap_assisted_machine`, `oap_finger_assisted`, `oa_negative`, `oa_top_hold`, `oa_hold90_machine`, `oa_hold_angle`, `oa_straight_arm_hold`, `oa_scap_pull`, `oa_dead_hang`, `oap_attempt`, `typewriter_pullup`, `one_arm_plank`, `two_arm_dead_hang`). A definition key that resolves through `catalogRef` is not itself a slug (9.2.1): `weighted_pullup` has no catalog row, and the key `pullup_bw` only happens to equal the slug of its pronated variant. | CAT-1 validator passes in CI before the file is generated; counts equal `catalog_releases`; the seed generator fails if a slug referenced by a published definition is missing. |
| 010 | `fitness_010_user_config` | M1 | `gym_equipment`, `rep_preferences`, `muscle_goals`, `user_exercise_prefs`, `user_algo_params`; USER / USER-RO templates. | Two-user RLS test (17.6). |
| 011 | `fitness_011_challenge_core` | M1 | `challenge_definitions` (guard trigger), `challenge_enrollments` (rev trigger). | Immutability test; rev-trigger test. |
| 012 | `fitness_012_snapshots` | M1 | `params_snapshots`, `recommendations`. | Append-only test (no update for `authenticated`). |
| 013 | `fitness_013_sessions_sets` | M1 | `workout_sessions`, `session_blocks`, `session_exercises`, `set_logs`, `wellness_logs`, `metric_observations`, all composite FKs and checks. | Composite-FK cross-user test; effective-load and load-mode checks. |
| 014 | `fitness_014_challenge_events_calendar` | M1 | `challenge_events`, `challenge_rule_events`, `calendar_events`. | Dedupe-key idempotency; ignored-row title check. |
| 015 | `fitness_015_telemetry_cache_exports` | M1 | `session_events`, `exercise_model_state`, `exports`. | Payload size checks. |
| 019 | `fitness_019_algo_params_1_0_1` (data; applied 2026-10-09, numbered after the reserved 016-018) | M1a | `algo_params` 1.0.1 = 1.0.0 with `load.default_plate_inventory` counts 20 (F7); 1.0.0 set to `superseded`. | Key count 621; only that key differs from 1.0.0; `params_hash` pinned in ParamsFileTest. |
| 020 | `fitness_020_catalog_1_0_1` (data; applied 2026-10-09) | M1a | Four `stack` stations in `equipment_types` (ab crunch, preacher curl, triceps extension, dip machine); `catalog_releases` 1.0.1; the four matching global exercises require them and move to `catalog_version` 1.0.1. | Exactly 4 global rows at 1.0.1, each with a stack station; 158 global exercises. |
| 016 | `fitness_016_oap_definition_v1` (data; applied 2026-10-20 in 5 parts, versions 20261020161521-20261020162006) | M2b | Insert the OAP definition 1.0.0 (id UUIDv5 `challenge_definition:oap@1.0.0` = `b2afe9c6-816e-51a4-9cdb-8a529fd54615`) as `published`, with `jcs_sha256` from the authoring tool; `on conflict (id) do nothing`; then assert the stored `jcs_sha256` equals the file's. Written only after the CHALLENGE spec is final and the definition passes the CDS validator with the section 8 domains (C38; open issue 3, resolved). | Guard trigger set `server_checksum`; an `update` of `definition` fails with `55000`. |
| 017 | `fitness_017_review_views` (applied 2026-10-11, version 20261011163142) | M1b (needed by the first review after 4-6 weeks) | All `fitness_review` views (section 14), `security_invoker = true`, no grants. | Views compile; `v_db_size` returns rows. |
| 018 | `fitness_018_jsonschema_constraint` (optional; deferred by D33) | later (a second challenge or in-app publishing) | `alter table fitness.challenge_definitions add constraint challenge_definitions_schema_ck check (extensions.jsonb_matches_schema('<CDS v1 JSON Schema>'::json, definition))` (the extension itself is installed by 007). | Advisors; a malformed definition insert fails. |

| 021 | `fitness_021_reason_codes_pause_correction` (data and CHECK; applied 2026-10-20, version 20261020212943) | M2b (D34) | The `ui_contexts` CHECK gains `pause` and `correction`; codes `holiday`, `illness`, `injury`, `loaded_different`, `value_wrong`; `other`, `too_hard`, `too_easy` join the new contexts. | `select code, ui_contexts from fitness.reason_codes order by sort` shows 18 rows. |
| 022 | `fitness_022_oap_definition_v1_0_1` (data; applied 2026-10-21 in 5 parts, versions 20261021172555-20261021173602, plus `part2_fix` 20261021173315, which repaired a mistyped tail of the staged part 2 text before part 5 checked the whole definition) | M2b (D35) | Insert the OAP definition 1.0.1 (id UUIDv5 `challenge_definition:oap@1.0.1` = `e56b2826-8ae2-58ca-981c-5a39284eea87`) as `published`: a text-only patch of 1.0.0 (shorter, plain user-facing texts; `migrations["1.0.0"]` with an empty `varMap`). Same staging and assertions as 016. 1.0.0 stays published for the enrollments that pin it. | `select version, jcs_sha256 from fitness.challenge_definitions where slug = 'oap'` shows 1.0.0 `e454a479…` and 1.0.1 `8f1b6522…`. |
| 023 | `fitness_023_oap_definition_v1_1_0` (data; applied 2026-10-21, version 20261021194917) | M2b.4 (D36) | Insert the OAP definition `oap@1.1.0`: the published 1.0.1 row's jsonb patched with P21's five protocol params, its `affects.params`, `stepsIfApproved` on the T1 items `str` and `assist` and the migration notes. The guard trigger's `server_checksum` must equal the checksum of `docs/challenges/oap.challenge.v1.json` rendered as jsonb text, which proves the stored row is byte-identical to the file (no 100 KB restaging). | Checks inside the migration (status, `jcs_sha256`, `server_checksum`); advisors. |
| 024 | `fitness_024_algorithm_versions_engine_1_1_0` (data; applied 2026-10-21, version 20261021194928) | M2b.4 (D36) | Register `challenge.cds@1.1.0` and `challenge.materialize@1.1.0` (engine 1.1.0, params 1.0.1, the empty-slice `constants_hash`) before the APK that uses them is installed (9.1.9 release rule). | Count check inside the migration. |
| 025 | `fitness_025_rule_tag_d` (schema; applied 2026-10-21, version 20261021202305) | M2b.4 | `challenge_rule_events.rule_tag` also accepts `D`: the first test day's `data.test.e1rm` audit row (tag D, D7) was rejected on sync by the old check. No data change. | Advisors. |
| 026 | `fitness_026_oap_definition_v1_2_0` (data; applied 2026-10-21, version 20261021210120) | M2b (D37) | Insert `oap@1.2.0` by patching the published 1.1.0 row like 023: the gate e1RM init from the entered 3RM, `anchorFraction` no longer static, param `firstAnchorRir`, the rules `data.test.first`, `data.test.first.neg`, `data.test.first.pins` and three guards. The server checksum proves the row equals the file. | Checks inside the migration; advisors. |
| 027 | `fitness_027_oap_v1_3_0_engine_1_2_0` (data; applied 2026-10-22, version 20261021221251) | M2b (D38) | Insert `oap@1.3.0` by patching the 1.2.0 row (no Sunday-only test, `schedule.order.first` for the test while there are no loads; the server checksum proves the row equals the file) and register `challenge.cds@1.2.0` and `challenge.plan@1.1.0` for engine 1.2.0. | Checks inside the migration; advisors. |
| 028 | `fitness_028_oap_v1_4_0_engine_1_3_0` (data; applied 2026-10-22, version 20261022170000) | M2b.6 (D40) | Insert `oap@1.4.0` by patching the 1.3.0 row with 54 generated jsonb operations (the Pallof press in A, B and C, P28, P29 and the re-entry week, the `cable` rounding profile, the two-day plank rule; the server checksum proves the row equals the file) and register `challenge.enrollment@1.1.0` for engine 1.3.0 (setup decisions fold with the creation). | Checks inside the migration; advisors. |
| 029 | `fitness_029_engine_1_4_0` (data; version 20261023090000) | M2b.6 (D41) | Register `challenge.enrollment@1.2.0` for engine 1.4.0 (the comparison baseline `pt` per measurement); no definition change. | Check inside the migration; advisors. |
| 030 | `fitness_030_engine_1_5_0` (data; applied 2026-10-25, version 20261025195106) | M2b.6 (D42) | Register `challenge.plan@1.2.0` for engine 1.5.0 (train now tries every slot not done yet, at any time of day); no definition change. | Check inside the migration; advisors. |
| 031 | `fitness_031_oap_v1_5_0_engine_1_6_0` (data; applied 2026-10-25, version 20261025201207) | M2b.6 (D44) | Insert `oap@1.5.0` by patching the 1.4.0 row with 5 jsonb operations (the warm-up loses `wu_row`, the `rower_erg` equipment and exercise entries go, `migrations.1.4.0` added); register `challenge.enrollment@1.3.0` for engine 1.6.0 (`definition.migrated` folds later events under the new version and rewrites the enrollment row's `definition_id`, which needs this row first). | `server_checksum` and `jcs_sha256` checks inside the migration; advisors. |
| 032 | `fitness_032_body_regions_elbow_back_forearm` (data; applied 2026-10-25, version 20261025202623) | M2b.6 (D45) | Two body regions: `elbow_posterior` {elbow_medial, elbow_lateral} and `forearm` {elbow_medial, elbow_lateral, wrist}; equal to the bundled `reference/body_regions.json`. | Check inside the migration; advisors. |
| 033 | `fitness_033_oap_v1_6_0` (data; applied 2026-10-25, version 20261025202641) | M2b.6 (D45) | `oap@1.6.0`: 6 jsonb operations on the 1.5.0 row (version, `safety.elbow.traceFromPain` on the two new regions, the per-side Pallof press in A1, B1 and C1, the empty migration from 1.5.0); proven by `server_checksum`. No engine change. | Checks inside the migration (032 first, the 1.5.0 checksum, the new row's `jcs_sha256` and `server_checksum`); advisors. |
| 034 | `fitness_034_oap_v1_7_0` (data; applied 2026-10-25, version 20261025204534) | M2b.6 (D47) | `oap@1.7.0`: 3 jsonb operations on the 1.6.0 row (version, `structure.modes.deload.exemptRoles` = `warmup`, `prehab`, the empty migration from 1.6.0); proven by `server_checksum`. No engine change. | Checks inside the migration (the 1.6.0 checksum, the new row's `jcs_sha256` and `server_checksum`); advisors. |
| 035 | `fitness_035_engine_1_7_0` (data; applied 2026-10-25, version 20261025220432) | M2b.6 (D48) | Register `challenge.enrollment@1.4.0` for engine 1.7.0 (a superseding `enrollment.created` may correct the imported earlier tests); no definition change. | Check inside the migration; advisors. |
| 036 | `fitness_036_params_1_0_2_engine_1_8_0` (data and one CHECK; applied 2026-10-27, version 20261027152030) | M3.4 (D49) | `algo_params` 1.0.2 (1.0.1 plus `reason.pain_stop_js_min`, hash `6298d121...`, 1.0.1 superseded); `rest@1.1.0` for engine 1.8.0 (unchanged slice hash); the `workout_sessions.template_source` CHECK gains `repeat` (E9); the `session_started` registry row gains `source_session_id`. Applied before the M3.4 APK is installed (9.1.9 release rule). | Checks inside the migration (622 keys, only the new key differs, one module for 1.8.0, the registry key); advisors. |
| 037 | `fitness_037_rest_multiplier_engine_1_9_0` (DDL and data; applied 2026-10-27, version 20261027205903) | D50 | `rep_preferences.rest_pref_mult` numeric(3,2) 0.50-2.00; `rep_preferences_something_ck` counts it; new `rep_preferences_rest_one_ck` (at most one rest form); `rest@1.2.0` and `rx@1.1.0` for engine 1.9.0 (unchanged slice hashes); the `rep_preference_changed` registry row gains `preset_id`. Applied before the APK that writes the column is installed (9.1.9 release rule). | Checks inside the migration (column, both CHECKs, two modules for 1.9.0, the registry key); advisors. |
| 038 | `fitness_038_oap_v1_8_0` (data; applied 2026-10-28, version 20261027232211) | D51 | `oap@1.8.0`: 4 jsonb operations on the 1.7.0 row (version, `habits[0]` (`daily_hang`) `skipIf` on every gym slot A, B, C and TEST, its `source`, the empty migration from 1.7.0); proven by `server_checksum`. No engine change. | Checks inside the migration (the 1.7.0 checksum, `habits[0]` is `daily_hang`, the new row's `jcs_sha256` and `server_checksum`); advisors. |
| 039 | `fitness_039_oap_v1_9_0` (data; applied 2026-10-28, version 20261027233603) | D52 | `oap@1.9.0`: 13 jsonb operations on the 1.8.0 row (version, `inSessionRules[6]` `ins.rotationStop` replaced by six per-item rules, message `rotation_stop` replaced by four, the empty migration from 1.8.0); proven by `server_checksum`. No engine change. | Checks inside the migration (the 1.8.0 checksum, `inSessionRules[6]` is `ins.rotationStop` of 8 rules, the new row's `jcs_sha256` and `server_checksum`); advisors. |

The user exposes the schema (F2, action 3) after 004 so the app can read reference data; the exposure can happen any time before the first app sync. Later migrations continue at 019 with the same process.

### 13.3 Seed generation

A small Kotlin tool in `:sim` (`seedgen`) reads the repo JSON files (reference data, catalog, params, OAP definition), validates them with the same `:algo` validators the app uses (CAT-1, CDS, params schema), computes UUIDv5 ids and JCS hashes, and emits the data migration SQL. The generated SQL and the bundled APK assets therefore always agree.

---

## 14. Review views (`fitness_review`)

The `fitness_review` schema holds the read-only views that Claude Code queries through the Supabase MCP during periodic algorithm reviews (milestone M5; the first review after 4-6 weeks of real logs). They are the fixed, reproducible tuning surface proposed by the research (data-model lens S6), reduced to what the canonical resolutions allow: every engine-derived number (e1RM, the rep-curve parameter `k`, fractional volume, hard-set credit, readiness, gate values) is **read from what the engine logged** and never recomputed in SQL (C3, C6, design principle 3). All views are created by migration 017 (13.2).

### 14.1 Rules for every review view

| Rule | Detail | Reason |
|------|--------|--------|
| R-V1 Invoker rights | `create view fitness_review.<name> with (security_invoker = true) as ...` | A view never widens access (advisor lint 0010 `security_definer_view`). |
| R-V2 No grants | No grant to `anon`, `authenticated` or `service_role`; `USAGE` on `fitness_review` is revoked from `PUBLIC` (4.1). Only `postgres` (the owner) can query them, through the MCP. The schema is never added to the exposed schemas. | F1. |
| R-V3 Always `user_id` | Every user-scoped view exposes `user_id` as its first column. Only `v_db_size` and `v_db_size_summary` have no `user_id`. | `postgres` bypasses RLS (section 2), so owner filtering is the reviewer's job (14.2). |
| R-V4 No recomputation | Views read (a) the `recommendations.outputs` keys listed in 10.2, (b) engine-written columns (`effective_load_kg`, `assist_kg`, `completion_ratio`, `progression_valid`, `metric_observations` rows with `derivation like 'calculated:%'`) and (c) facts. Allowed SQL arithmetic: differences and ratios of two logged values (planned versus actual), counts, sums and percentiles of logged values, date bucketing. Forbidden: any e1RM formula (Epley, Brzycki, RCM-1), the SetCredit RIR tables, readiness decay, the BW(t) EWMA, pin-to-kg conversion, ISO-week logic other than `to_char(..., 'IYYY-"W"IW')` on a logged local timestamp. | C3: "Review SQL views never recompute e1RM; they read the engine-logged per-set values." The research's fixed-Epley/Brzycki view formulas are dropped. |
| R-V5 Live rows only | `deleted_at is null` on every S-MUT source; challenge rule events only for the enrollment's current `fold_generation`. | Tombstones and superseded folds are not facts. |
| R-V6 Newest snapshot | When several snapshots exist for one subject, a view picks the newest by `(created_at desc, id desc)` with `distinct on`. | Deterministic selection; the reuse rule (S2, 9.5.2) keeps duplicates rare. |
| R-V7 No personal free text | Views never select `workout_sessions.notes`, `set_logs.deviation_note`, `wellness_logs.note`, `user_exercise_prefs.note`, `gyms.address_note`, `profiles.display_name` or `user_algo_params.note`. Two named exceptions: `calendar_events.title`/`location` in `v_calendar_events` (classified events only, needed to tune the classifier) and `metric_observations.validity_note` in `v_challenge_tests` (protocol annotations such as "old protocol, not comparable"). | Privacy (section 16): review results end up in `docs/reviews/`. |
| R-V8 Cheap | No materialized views. Expected volume (15.1) is about 15 000 sets and 50 000 telemetry events per year; every view must answer a one-user, one-quarter query in well under a second. Review queries always add `where user_id = ...` and a date range. | Keeps reviews interactive. |
| R-V9 Stable contract | A view's columns and their meaning are versioned with its migration. A meaning change is `drop view` + `create view` in a new migration, never a silent `create or replace`. A breaking change of a 10.2 key ships the view migration in the same release as the engine change. | Reviews compare months of data. |

### 14.2 Owner resolution and the review query pattern

Reviews analyse only the data of the person who asked for the review, identified by **their own email address** (D3; the allowlist currently holds only the owner).

1. The email comes from the review session's context (the signed-in user's own address). It is typed into the query at run time and never written into the repository, a migration, a review document or a view definition.
2. The lookup selects only `auth.users.id`, must return exactly one row, and that row must be in `fitness_private.allowed_users`. Zero or several rows: stop the review and ask the user.
3. No other column of `auth.users` and no other user is ever read. The chess app's tables are never queried.
4. Every later query binds the resolved UUID, either as a literal (`where user_id = '<uuid>'`) or by joining the `me` CTE below. A review of another allowlisted user (a friend, D3) happens only at that user's own request, resolving their id from their own email in the same way.

```sql
-- Review query template (run as postgres through the Supabase MCP).
with me as (
  select u.id as user_id
  from auth.users u
  join fitness_private.allowed_users a on a.user_id = u.id
  where lower(u.email) = lower('<own email, typed at run time>')
)
select h.*
from fitness_review.v_e1rm_history h
join me using (user_id)
where h.local_date >= date '2026-10-01'
order by h.exercise_slug, h.side, h.local_date;
```

Review sessions start with `v_db_size_summary` (15.2) and `v_data_quality` (14.20) before any module-specific view, so storage or data problems are found before conclusions are drawn from the data.

### 14.3 Catalogue

| § | View | Grain (one row per) | Main sources | Purpose / modules reviewed |
|---|------|--------------------|--------------|----------------------------|
| 14.4 | `v_set_facts` | live set | `set_logs`, `session_exercises`, `session_blocks`, `workout_sessions`, `exercises` | Base fact view with session and exercise context; every other set-level view builds on it. |
| 14.5 | `v_set_estimates` | set that entered SSE-1 | `estimation.sse` outputs `sets[]` + `v_set_facts` | Per-set engine estimates (`q_true`, `n_eff`, `e1rm_kg`, `k_used`, residual): `sse`, `rircal`, `rcm`, `fatigue`. |
| 14.6 | `v_e1rm_history` | user, exercise, side, local date | `v_set_estimates`, `estimation.sse` outputs `states[]` | Strength trend: best per-set e1RM, logged state M with CI80, weekly slope, last `k_used`. |
| 14.7 | `v_prescription_error` | non-warm-up set | `v_set_facts`, `rx.prescription` | Planned versus actual reps, RIR and load per RX-1 mode and version: `rx`, `autoreg`. |
| 14.8 | `v_rir_calibration` | RIRCAL-1 update | `estimation.rircal`, `v_set_facts` | RIR bias `b` trajectory (C17): `rircal`. |
| 14.9 | `v_override_stats` | user, ISO week, scope, reason, module version | `session_exercises`, `v_prescription_error` | Override, substitution, deviation, rest-adjust and autoreg rates: `rx`, `reason`, `autoreg`, `substitute`, `rest`. |
| 14.10 | `v_skip_stats` | user, ISO week, exercise, status, reason, preference | `session_exercises`, `user_exercise_prefs` | Skip/remove/defer behaviour per preference state: `pref`, `elig`, `select`. |
| 14.11 | `v_weekly_muscle_volume` | user, ISO week, muscle group | `accounting.week`, `targets.weekly` | Done versus target per group (C14): `setcredit`, `targets`, `allocator`, `weekplan`, `ui.volumebars`. |
| 14.12 | `v_rest_compliance`, `v_rest_alarm_latency` | set; user, week, app state, alarm mode | `v_set_facts`, `session_events` | Rest taken versus timer (C18); alarm delivery latency (C28): `rest`. |
| 14.13 | `v_adherence_week` | user, ISO week | `weekplan.plan`, `calendar_events`, `workout_sessions` | Recommended versus planned versus done: `weekplan`, `today`, `calendar.classify`. |
| 14.14 | `v_recommendation_acceptance` | user, week, family, subject, module version | `session_events` | Acceptance of Today options, autoreg suggestions, warnings and prompts: `today`, `autoreg`, `warnings`, `ui.*`. |
| 14.15 | `v_session_summary`, `v_exercise_time` | session; done session exercise | `workout_sessions`, `session_blocks`, `set_logs`, `wellness_logs`, plan snapshots | Session outcome and TIME-1 estimate versus actual duration: `time`, `select`. |
| 14.16 | `v_challenge_timeline`, `v_challenge_var_changes` | input or rule event; changed variable | `challenge_events`, `challenge_rule_events` | ECA audit of the current fold: `challenge.eca`, `challenge.enrollment`. |
| 14.17 | `v_challenge_tests` | metric observation of a challenge or test | `metric_observations` | Test history, validity, derived gate values with their logged `k` (D7, C4). |
| 14.18 | `v_wellness_safety` | wellness answer | `wellness_logs` | Pain, elbow, readiness, soreness, sRPE: `safety`, `injury`, `readiness`, `ui.prompts`. |
| 14.19 | `v_calendar_events`, `v_calendar_classification` | stored classified event; user, week, kind, method | `calendar_events`, `session_events` | Classifier quality and overrides (C20): `calendar.classify`, `challenge.calendar`. |
| 14.20 | `v_data_quality` | finding | all tables | Data-quality checks, including unregistered module versions. |
| 14.21 | `v_sync_health`, `v_upload_latency` | user, local date, event type, code; user, local date | `session_events`, `set_logs` | Sync errors, conflicts, outbox depth, upload delay. |
| 14.22 | `v_db_size`, `v_db_size_summary` | table; one row | catalog functions | Size guard (15.2). |
| 14.23 | `v_replay_index` | recommendation | `recommendations`, `params_snapshots` | Selects snapshots for `:sim` replay and counterfactuals. |

Views are created in the order of this table (later views select from `v_set_facts`, `v_set_estimates` and `v_prescription_error`).

### 14.4 `v_set_facts`

One row per live set with its session, block and exercise context. It contains only facts and engine-written columns; it is the base of every set-level view.

```sql
create view fitness_review.v_set_facts with (security_invoker = true) as
select
  s.user_id,
  s.id                    as set_id,
  s.session_id,
  s.session_exercise_id,
  se.block_id,
  sb.block_kind,
  s.exercise_id,
  e.slug                  as exercise_slug,
  e.pattern, e.mechanics, e.contraction, e.curve_class, e.rest_class, e.safety_class,
  ws.kind                 as session_kind,
  ws.status               as session_status,
  ws.challenge_slot,
  se.challenge_item_key,
  ws.is_deload,
  ws.mesocycle_week,
  ws.local_date,
  ws.iso_week,
  ws.engine_version,
  ws.params_hash,
  se.origin               as exercise_origin,
  se.status               as exercise_status,
  se.progression_mode,
  se.user_override,
  s.set_index, s.side, s.set_kind, s.round_index, s.attempt_index,
  (s.set_kind in ('working', 'top', 'backoff', 'amrap')) as is_work_set,
  s.load_mode, s.equipment_id, s.equipment_calibration_rev,
  s.input_value, s.input_unit, s.implement_count, s.load_per_implement_kg, s.implement_base_kg,
  s.added_kg, s.assist_pin_native, s.assist_unit, s.assist_kg, s.assist_ordinal,
  s.bodyweight_kg, s.f_bw, s.effective_load_kg,
  s.reps, s.duration_s, s.hold_reserve_s, s.hold_reserve_is_lower_bound,
  s.ecc_s_target, s.ecc_s_actual,
  s.rir_reported, s.rir_is_lower_bound, s.rir_source, s.calib_extra_reps,
  s.flags, s.deviation_reason_code, s.pain_flag,
  s.planned_reps, s.planned_rep_min, s.planned_rep_max, s.planned_rir,
  s.planned_input_value, s.planned_input_unit, s.planned_effective_load_kg,
  s.planned_duration_s, s.planned_source,
  s.rest_timer_planned_s, s.rest_adjust_s, s.rest_timer_overridden, s.rest_before_s, s.rest_source, s.rest_end_reason,
  s.started_at, s.completed_at
from fitness.set_logs s
join fitness.session_exercises se on se.id = s.session_exercise_id and se.user_id = s.user_id
join fitness.session_blocks sb    on sb.id = se.block_id and sb.user_id = s.user_id
join fitness.workout_sessions ws  on ws.id = s.session_id and ws.user_id = s.user_id
join fitness.exercises e          on e.id = s.exercise_id
where s.deleted_at is null
  and se.deleted_at is null
  and sb.deleted_at is null
  and ws.deleted_at is null;
```

`is_work_set` classifies a logged code; it is not the SetCredit "hard set" (RIR <= 4, C6), which only the engine computes and which reviews read from `accounting.week`.

### 14.5 `v_set_estimates`

One row per set that SSE-1 ingested (C8: eccentric-only, isometric and finger-assisted sets never enter SSE-1 and therefore have no row). Values come from the newest `estimation.sse` snapshot that lists the set.

```sql
create view fitness_review.v_set_estimates with (security_invoker = true) as
with est as (
  select distinct on (r.user_id, (x.value->>'set_id')::uuid)
    r.user_id,
    (x.value->>'set_id')::uuid as set_id,
    r.id             as sse_recommendation_id,
    r.module_version as sse_version,
    r.engine_version,
    r.params_hash    as sse_params_hash,
    r.created_at     as estimated_at,
    x.value          as est
  from fitness.recommendations r
  cross join lateral jsonb_array_elements(r.outputs->'sets') as x(value)
  where r.kind = 'estimation.sse'
  order by r.user_id, (x.value->>'set_id')::uuid, r.created_at desc, r.id desc
)
select
  f.user_id, f.set_id, f.session_id, f.exercise_id, f.exercise_slug, f.side, f.set_kind,
  f.local_date, f.iso_week, f.load_mode, f.effective_load_kg, f.bodyweight_kg,
  f.reps, f.rir_reported, f.rir_is_lower_bound, f.rir_source,
  est.sse_recommendation_id, est.sse_version, est.engine_version, est.sse_params_hash, est.estimated_at,
  est.est->>'estimator_class'                          as estimator_class,
  (est.est->>'q_true')::numeric                        as q_true,
  (est.est->>'q_true')::numeric - f.rir_reported       as q_minus_reported,
  (est.est->>'n_eff')::numeric                         as n_eff,
  (est.est->>'n_fresh')::numeric                       as n_fresh,
  (est.est->>'fatigue_f')::numeric                     as fatigue_f,
  (est.est->>'z')::numeric                             as z,
  (est.est->>'s')::numeric                             as s,
  (est.est->>'e1rm_kg')::numeric                       as e1rm_kg,
  (est.est->>'e1rm_added_kg')::numeric                 as e1rm_added_kg,
  (est.est->>'k_used')::numeric                        as k_used,
  (est.est->>'residual')::numeric                      as residual
from est
join fitness_review.v_set_facts f on f.set_id = est.set_id and f.user_id = est.user_id;
```

`q_minus_reported` is the difference between the engine's bias-corrected or imputed RIR (C17) and the raw chip value; both numbers are logged, nothing is derived. `e1rm_kg` is the system-load e1RM and `e1rm_added_kg` the external-load equivalent, both exactly as the engine computed them with the `k_used` of that set (C3).

### 14.6 `v_e1rm_history`

One row per user, exercise, side and local date with at least one SSE-1 estimate.

```sql
create view fitness_review.v_e1rm_history with (security_invoker = true) as
with per_day as (
  select user_id, exercise_id, exercise_slug, side, local_date, iso_week,
         count(*)                                                        as n_sets,
         max(e1rm_kg)                                                    as best_set_e1rm_kg,
         max(e1rm_added_kg)                                              as best_set_e1rm_added_kg,
         (array_agg(k_used      order by estimated_at desc, set_id desc))[1] as k_used_last,
         (array_agg(sse_version order by estimated_at desc, set_id desc))[1] as sse_version
  from fitness_review.v_set_estimates
  group by user_id, exercise_id, exercise_slug, side, local_date, iso_week
),
state as (
  select distinct on (r.user_id, ws.local_date, (st.value->>'exercise_id')::uuid, st.value->>'side')
    r.user_id,
    ws.local_date,
    (st.value->>'exercise_id')::uuid      as exercise_id,
    st.value->>'side'                     as side,
    (st.value->>'m_kg')::numeric          as state_m_kg,
    (st.value->>'ci80_lo_kg')::numeric    as state_ci80_lo_kg,
    (st.value->>'ci80_hi_kg')::numeric    as state_ci80_hi_kg,
    (st.value->>'g_per_week')::numeric    as state_g_per_week
  from fitness.recommendations r
  join fitness.workout_sessions ws on ws.id = r.session_id and ws.user_id = r.user_id and ws.deleted_at is null
  cross join lateral jsonb_array_elements(r.outputs->'states') as st(value)
  where r.kind = 'estimation.sse'
  order by r.user_id, ws.local_date, (st.value->>'exercise_id')::uuid, st.value->>'side', r.created_at desc, r.id desc
)
select d.*, s.state_m_kg, s.state_ci80_lo_kg, s.state_ci80_hi_kg, s.state_g_per_week
from per_day d
left join state s
  on s.user_id = d.user_id and s.exercise_id = d.exercise_id and s.side = d.side and s.local_date = d.local_date;
```

The challenge gate value for the weighted pull-up (D7, `wpu_e1rm_added`) is deliberately not mixed into this view; C4 requires the two numbers to be shown side by side and labelled, so a review joins `v_e1rm_history` with `v_challenge_tests` on `local_date` when it compares them.

### 14.7 `v_prescription_error`

One row per non-warm-up set, with the RX-1 snapshot of its session exercise (`rx.prescription`, found by its scope key, so RULE-mode challenge items show the model's shadow proposal, C15) next to the planned and the actual values.

```sql
create view fitness_review.v_prescription_error with (security_invoker = true) as
select
  f.user_id, f.set_id, f.session_id, f.session_exercise_id, f.exercise_id, f.exercise_slug,
  f.local_date, f.iso_week, f.set_index, f.side, f.set_kind, f.is_work_set,
  f.progression_mode, f.challenge_item_key, f.user_override,
  rx.id                                       as rx_recommendation_id,
  rx.module_id                                as rx_module_id,
  rx.module_version                           as rx_module_version,
  rx.params_hash                              as rx_params_hash,
  rx.outputs->>'mode'                         as rx_mode,
  rx.outputs->'caps_applied'                  as rx_caps_applied,
  (rs.value->>'target_reps')::numeric         as rx_target_reps,
  (rs.value->>'rir')::numeric                 as rx_rir,
  (rs.value->>'effective_load_kg')::numeric   as rx_effective_load_kg,
  f.planned_source,
  f.planned_reps, f.planned_rep_min, f.planned_rep_max, f.planned_rir, f.planned_effective_load_kg,
  f.reps, f.rir_reported, f.rir_is_lower_bound, f.effective_load_kg,
  f.reps - f.planned_reps                                    as rep_err,
  f.rir_reported - f.planned_rir                             as rir_err,
  f.effective_load_kg / nullif(f.planned_effective_load_kg, 0) as load_ratio,
  f.deviation_reason_code, f.rest_adjust_s
from fitness_review.v_set_facts f
left join lateral (
  select r.*
  from fitness.recommendations r
  where r.user_id = f.user_id
    and r.kind = 'rx.prescription'
    and r.scope_key = 'session_exercise:' || f.session_exercise_id::text
  order by r.created_at desc, r.id desc
  limit 1
) rx on true
left join lateral (
  select x.value
  from jsonb_array_elements(rx.outputs->'sets') as x(value)
  where (x.value->>'set_index')::int = f.set_index and x.value->>'side' = f.side
  limit 1
) rs on true
where f.set_kind <> 'warmup';
```

Interpretation notes: when `rir_is_lower_bound` is true (the "5+" chip) `rir_err` is a lower bound; `planned_source = 'autoreg'` marks sets whose plan an accepted AUTO-1 suggestion changed (C16), so the RX-1 error and the AUTO-1 effect can be separated. Standard aggregate (research S6, 28-day buckets):

```sql
select rx_module_version, exercise_slug,
       date_bin('28 days', local_date::timestamp, timestamp '2026-10-05') as bucket,
       count(*)                                         as n,
       avg(rep_err)                                     as rep_bias,
       avg(abs(rep_err))                                as rep_mae,
       avg((abs(rir_err) <= 1)::int)                    as rir_hit_rate,
       avg(load_ratio)                                  as mean_load_ratio
from fitness_review.v_prescription_error
where user_id = '<owner uuid>' and is_work_set and rx_mode is not null
group by 1, 2, 3
order by 1, 2, 3;
```

### 14.8 `v_rir_calibration`

One row per RIRCAL-1 update (C17: bias `b`, init 0.5, clamp -1..3).

```sql
create view fitness_review.v_rir_calibration with (security_invoker = true) as
select
  r.user_id,
  r.id               as recommendation_id,
  r.module_version   as rircal_version,
  r.params_hash,
  r.created_at,
  r.set_log_id,
  f.exercise_slug, f.set_kind, f.challenge_item_key, f.safety_class, f.local_date,
  f.reps, f.rir_reported, f.calib_extra_reps, f.effective_load_kg,
  (r.outputs->>'b_before')::numeric    as b_before,
  (r.outputs->>'b_after')::numeric     as b_after,
  (r.outputs->>'s_q_before')::numeric  as s_q_before,
  (r.outputs->>'s_q_after')::numeric   as s_q_after,
  (r.outputs->>'e')::numeric           as e
from fitness.recommendations r
left join fitness_review.v_set_facts f on f.set_id = r.set_log_id and f.user_id = r.user_id
where r.kind = 'estimation.rircal';
```

A review checks that `b_after` stays inside the clamp, that calibration sets occur at most once per week and only on machine, cable or isolation exercises and never on challenge lifts (F14: `safety_class in ('machine', 'cable', 'isolation')` and `challenge_item_key is null`), and compares the trajectory of `b` with `q_minus_reported` in `v_set_estimates`.

### 14.9 `v_override_stats`

Override rates per ISO week, scope, reason code and the version of the module whose plan was overridden. The denominator (`basis_n`) is the number of opportunities of the same scope and module version in that week, so rates are comparable across versions.

| scope | Hit when | Basis | Module attributed |
|-------|----------|-------|-------------------|
| `exercise_override` | `session_exercises.user_override` | live, not removed session exercises | snapshot of `session_exercises.recommendation_id` |
| `substitution` | `origin = 'substitute'` (reason = `substitution_reason_code`) | same | same |
| `set_deviation` | `deviation_reason_code is not null` | work sets | `rx.prescription` of the set |
| `rest_adjusted` | `rest_adjust_s <> 0` | work sets | `rx.prescription` of the set |
| `autoreg_applied` | `planned_source = 'autoreg'` | work sets | `rx.prescription` of the set |

```sql
create view fitness_review.v_override_stats with (security_invoker = true) as
with ex as (
  select se.user_id, ws.iso_week, se.user_override, se.override_reason_code, se.origin,
         se.substitution_reason_code,
         coalesce(r.module_id, 'none')    as module_id,
         coalesce(r.module_version, '-')  as module_version
  from fitness.session_exercises se
  join fitness.workout_sessions ws
    on ws.id = se.session_id and ws.user_id = se.user_id and ws.deleted_at is null
  left join fitness.recommendations r
    on r.id = se.recommendation_id and r.user_id = se.user_id
  where se.deleted_at is null and se.status <> 'removed'
),
st as (
  select user_id, iso_week, deviation_reason_code, planned_source, rest_adjust_s,
         coalesce(rx_module_id, 'none')   as module_id,
         coalesce(rx_module_version, '-') as module_version
  from fitness_review.v_prescription_error
  where is_work_set
),
x as (
  select user_id, iso_week, 'exercise_override' as scope, override_reason_code as reason_code,
         module_id, module_version, user_override as hit from ex
  union all
  select user_id, iso_week, 'substitution', substitution_reason_code,
         module_id, module_version, origin = 'substitute' from ex
  union all
  select user_id, iso_week, 'set_deviation', deviation_reason_code,
         module_id, module_version, deviation_reason_code is not null from st
  union all
  select user_id, iso_week, 'rest_adjusted', null,
         module_id, module_version, coalesce(rest_adjust_s, 0) <> 0 from st
  union all
  select user_id, iso_week, 'autoreg_applied', null,
         module_id, module_version, coalesce(planned_source = 'autoreg', false) from st
),
basis as (
  select user_id, iso_week, scope, module_id, module_version, count(*) as basis_n
  from x group by 1, 2, 3, 4, 5
),
hits as (
  select user_id, iso_week, scope, reason_code, module_id, module_version, count(*) as n
  from x where hit group by 1, 2, 3, 4, 5, 6
)
select h.user_id, h.iso_week, h.scope, h.reason_code, h.module_id, h.module_version,
       h.n, b.basis_n, round(h.n::numeric / b.basis_n, 4) as rate
from hits h
join basis b using (user_id, iso_week, scope, module_id, module_version);
```

### 14.10 `v_skip_stats`

Status counts of session exercises per week, exercise, reason and the exercise's **current** preference (the preference at the time of the skip can be reconstructed from `preference_changed` telemetry when a review needs it).

```sql
create view fitness_review.v_skip_stats with (security_invoker = true) as
select
  se.user_id, ws.iso_week, se.exercise_id, e.slug as exercise_slug, se.origin, se.status,
  se.status_reason_code,
  coalesce(p.preference, 'neutral') as preference_now,
  count(*)            as n,
  sum(se.defer_count) as defers
from fitness.session_exercises se
join fitness.workout_sessions ws
  on ws.id = se.session_id and ws.user_id = se.user_id and ws.deleted_at is null
join fitness.exercises e on e.id = se.exercise_id
left join fitness.user_exercise_prefs p
  on p.user_id = se.user_id and p.exercise_id = se.exercise_id and p.deleted_at is null
where se.deleted_at is null
group by 1, 2, 3, 4, 5, 6, 7, 8;
```

Used to check the disliked-necessity gate (C26): a `dislike` exercise that is planned but skipped repeatedly is a signal for SUB-1/ROT-1, and a `never` exercise must never appear with `origin` `plan` or `live_algo`.

### 14.11 `v_weekly_muscle_volume`

One row per user, ISO week and muscle group, combining the newest `accounting.week` snapshot (done) with the newest `targets.weekly` snapshot (target) of that week. No set credit is computed here (C6, C14).

```sql
create view fitness_review.v_weekly_muscle_volume with (security_invoker = true) as
with acc as (
  select distinct on (r.user_id, r.iso_week)
         r.user_id, r.iso_week, r.id as accounting_id, r.module_version as setcredit_version,
         r.outputs->'groups' as groups
  from fitness.recommendations r
  where r.kind = 'accounting.week' and r.iso_week is not null
  order by r.user_id, r.iso_week, r.created_at desc, r.id desc
),
tgt as (
  select distinct on (r.user_id, r.iso_week)
         r.user_id, r.iso_week, r.id as targets_id, r.module_version as targets_version,
         r.outputs->'groups' as groups
  from fitness.recommendations r
  where r.kind = 'targets.weekly' and r.iso_week is not null
  order by r.user_id, r.iso_week, r.created_at desc, r.id desc
),
wk as (
  select coalesce(a.user_id, t.user_id) as user_id,
         coalesce(a.iso_week, t.iso_week) as iso_week,
         a.accounting_id, a.setcredit_version, coalesce(a.groups, '{}'::jsonb) as acc_groups,
         t.targets_id, t.targets_version, coalesce(t.groups, '{}'::jsonb) as tgt_groups
  from acc a
  full join tgt t on t.user_id = a.user_id and t.iso_week = a.iso_week
)
select
  wk.user_id, wk.iso_week, g.group_id, mg.family,
  (wk.acc_groups->g.group_id->>'vhyp_done')::numeric    as vhyp_done,
  (wk.acc_groups->g.group_id->>'vstr_done')::numeric    as vstr_done,
  (wk.acc_groups->g.group_id->>'fatigue_done')::numeric as fatigue_done,
  (wk.acc_groups->g.group_id->>'committed')::numeric    as committed,
  wk.tgt_groups->g.group_id->>'goal'                    as goal,
  (wk.tgt_groups->g.group_id->>'target')::numeric       as target,
  (wk.tgt_groups->g.group_id->>'mrv')::numeric          as mrv,
  (wk.tgt_groups->g.group_id->>'cap_s')::numeric        as cap_s,
  wk.tgt_groups->g.group_id->'covered_by_challenge'     as covered_by_challenge,
  wk.tgt_groups->g.group_id->'reentry'                  as reentry,
  wk.tgt_groups->g.group_id->'deload'                   as deload,
  wk.setcredit_version, wk.targets_version, wk.accounting_id, wk.targets_id
from wk
cross join lateral (
  select k as group_id from jsonb_object_keys(wk.acc_groups) as k
  union
  select k from jsonb_object_keys(wk.tgt_groups) as k
) g
left join fitness.muscle_groups mg on mg.id = g.group_id;
```

`covered_by_challenge`, `reentry` and `deload` stay `jsonb` because their exact shape belongs to the targets spec. A review compares `vhyp_done` with `target` and the WarningEngine thresholds (C14: improve < 10 INFO, < 6 WARN, < 3 STRONG) and reads the warnings actually shown from `v_recommendation_acceptance`, never re-deriving them.

### 14.12 `v_rest_compliance` and `v_rest_alarm_latency`

```sql
create view fitness_review.v_rest_compliance with (security_invoker = true) as
select
  f.user_id, f.set_id, f.session_id, f.exercise_id, f.exercise_slug, f.rest_class, f.set_kind,
  f.local_date, f.iso_week, f.challenge_item_key,
  f.rest_timer_planned_s, f.rest_adjust_s, f.rest_timer_overridden,
  f.rest_timer_planned_s + coalesce(f.rest_adjust_s, 0)                                 as rest_timer_s,
  f.rest_before_s, f.rest_source, f.rest_end_reason,
  f.rest_before_s / nullif(f.rest_timer_planned_s + coalesce(f.rest_adjust_s, 0), 0)     as rest_ratio,
  f.reps, f.planned_reps, f.rir_reported, f.planned_rir
from fitness_review.v_set_facts f
where f.rest_timer_planned_s is not null;

create view fitness_review.v_rest_alarm_latency with (security_invoker = true) as
select
  e.user_id,
  to_char((e.occurred_at at time zone 'UTC') + make_interval(mins => e.tz_offset_min), 'IYYY-"W"IW') as iso_week,
  e.payload->>'app_state'                     as app_state,
  (e.payload->>'screen_interactive')::boolean as screen_interactive,
  e.payload->>'alarm_mode'                    as alarm_mode,
  e.payload->>'platform_config_hash'          as platform_config_hash,
  count(*)                                                                         as n,
  percentile_cont(0.50) within group (order by (e.payload->>'latency_ms')::numeric) as p50_ms,
  percentile_cont(0.95) within group (order by (e.payload->>'latency_ms')::numeric) as p95_ms,
  max((e.payload->>'latency_ms')::numeric)                                          as max_ms
from fitness.session_events e
where e.event_type = 'rest_alarm_fired' and e.payload ? 'latency_ms'
group by 1, 2, 3, 4, 5, 6;
```

`rest_ratio` describes behaviour, not a hypertrophy requirement (C18, ACSM 2026). `rest_source = 'estimated'` rows carry the estimate of UX.md 19.8 and are less precise than `measured` ones. `v_rest_alarm_latency` is the evidence for C28 and C54: the "standard exact" option (`setExactAndAllowWhileIdle`) is offered only after `timer.exactIdleMinSamples` (30) logged rests and only while `p95_ms <= 2000` with the screen off (`screen_interactive = false`); the platform constants that produced each alarm are identified by `platform_config_hash` (ANDROID.md 12.7, 25.1).

### 14.13 `v_adherence_week`

Recommended versus planned versus done per ISO week.

```sql
create view fitness_review.v_adherence_week with (security_invoker = true) as
with plan as (
  select distinct on (r.user_id, r.iso_week)
         r.user_id, r.iso_week, r.module_version as weekplan_version,
         (r.outputs->>'recommended_n')::int as recommended_n
  from fitness.recommendations r
  where r.kind = 'weekplan.plan' and r.iso_week is not null
  order by r.user_id, r.iso_week, r.created_at desc, r.id desc
),
cal as (
  select c.user_id, c.iso_week,
         count(*) filter (where k.kind in ('challenge_session', 'planned_gym')
                            and c.provider_status <> 'cancelled' and c.missing_since is null)        as planned_events,
         count(*) filter (where k.kind in ('challenge_session', 'planned_gym')
                            and c.linked_session_id is not null)                                      as linked_events,
         count(*) filter (where k.kind in ('challenge_session', 'planned_gym')
                            and c.provider_status <> 'cancelled' and c.missing_since is null
                            and c.ends_at < now() and c.linked_session_id is null)                    as missed_events,
         count(*) filter (where k.kind = 'run')                                                       as runs,
         sum(c.run_minutes) filter (where k.kind = 'run')                                             as run_minutes
  from fitness.calendar_events c
  cross join lateral (select coalesce(c.override_kind, c.classified_kind) as kind) k
  where c.deleted_at is null
  group by 1, 2
),
ses as (
  select s.user_id, s.iso_week,
         count(*) filter (where s.status = 'finished' and s.kind in ('gym', 'test'))                  as gym_done,
         count(*) filter (where s.status = 'finished' and s.challenge_slot is not null)               as challenge_done,
         count(*) filter (where s.status = 'finished' and s.kind = 'home')                            as home_done,
         count(*) filter (where s.status in ('abandoned', 'skipped'))                                 as not_done,
         count(*) filter (where s.status = 'finished' and s.kind in ('gym', 'test')
                            and not exists (select 1 from fitness.calendar_events c
                                            where c.user_id = s.user_id and c.linked_session_id = s.id
                                              and c.deleted_at is null))                              as unplanned_done
  from fitness.workout_sessions s
  where s.deleted_at is null
  group by 1, 2
)
select coalesce(p.user_id, c.user_id, s.user_id)    as user_id,
       coalesce(p.iso_week, c.iso_week, s.iso_week) as iso_week,
       p.weekplan_version, p.recommended_n,
       c.planned_events, c.linked_events, c.missed_events, c.runs, c.run_minutes,
       s.gym_done, s.challenge_done, s.home_done, s.not_done, s.unplanned_done
from plan p
full join cal c on c.user_id = p.user_id and c.iso_week = p.iso_week
full join ses s on s.user_id = coalesce(p.user_id, c.user_id) and s.iso_week = coalesce(p.iso_week, c.iso_week);
```

### 14.14 `v_recommendation_acceptance`

Acceptance of everything the engine suggests, from telemetry (UI-01, UI-06, UI-07, UI-10), plus the Undo rate of auto-applied challenge in-session rules (D14, C46). The event names are those of the 9.8.1 registry (canonical, C39). The ISO week is taken from the local timestamp of each event (`occurred_at` plus the logged `tz_offset_min`), which is correct across the DST change of 2026-10-25.

```sql
create view fitness_review.v_recommendation_acceptance with (security_invoker = true) as
with ev as (
  select e.user_id,
         to_char((e.occurred_at at time zone 'UTC') + make_interval(mins => e.tz_offset_min), 'IYYY-"W"IW') as iso_week,
         e.event_type, e.module_id, e.module_version, e.payload,
         case when e.event_type in ('today_card_shown', 'today_action', 'why_opened') then 'today'
              when e.event_type like 'suggestion\_%' then 'autoreg'
              when e.event_type like 'warning\_%'    then 'warning'
              when e.event_type like 'prompt\_%'     then 'prompt'
              when e.event_type in ('challenge_rule_applied', 'challenge_rule_undone') then 'challenge_rule'
         end as family
  from fitness.session_events e
  where e.category in ('today', 'autoreg', 'warning', 'prompt', 'challenge')
)
select
  user_id, iso_week, family,
  case family when 'today' then '-'
              else coalesce(payload->>'rule_id', payload->>'trigger', payload->>'prompt', '-') end as subject,
  case family when 'warning' then payload->>'severity' end                                         as severity,
  module_id, module_version,
  count(*) filter (where event_type in ('today_card_shown', 'suggestion_shown', 'warning_shown', 'prompt_shown',
                                        'challenge_rule_applied'))                                as shown,
  count(*) filter (where event_type in ('suggestion_applied', 'warning_use_suggested', 'prompt_answered')
                      or (event_type = 'today_action' and payload->>'rank' = '1'))               as accepted,
  count(*) filter (where event_type = 'today_action' and coalesce(payload->>'rank', '1') <> '1') as chose_alternative,
  count(*) filter (where event_type in ('suggestion_kept', 'warning_dismissed', 'prompt_skipped', 'challenge_rule_undone')
                      or (event_type = 'warning_use_mine_toggled' and payload->>'on' = 'true')) as declined,
  count(*) filter (where event_type in ('warning_why_opened', 'why_opened'))                      as why_opened
from ev
where family is not null
group by 1, 2, 3, 4, 5, 6, 7;
```

For the `challenge_rule` family, `shown` counts rules applied automatically to the remaining sets and `declined` counts their Undos (D14, C46); a high Undo rate for one `rule_id` is a review finding for the challenge definition, never a reason to change it without the user (CLAUDE.md, F26). For `warning`, "Use mine anyway" counts as declined only when it is switched on.

### 14.15 `v_session_summary` and `v_exercise_time`

```sql
create view fitness_review.v_session_summary with (security_invoker = true) as
select
  ws.user_id, ws.id as session_id, ws.local_date, ws.iso_week, ws.kind, ws.status, ws.template_source,
  ws.challenge_slot, ws.challenge_template_id, ws.enrollment_mode, ws.is_deload, ws.mesocycle_week,
  ws.families, ws.time_budget_min, ws.time_budget_source,
  ws.started_at, ws.finished_at,
  round(extract(epoch from (ws.finished_at - ws.started_at)) / 60.0, 1) as actual_min,
  pl.est_min_total, pl.budget_min_total,
  ws.completion_ratio, ws.bodyweight_kg, ws.bodyweight_source, ws.bodyweight_stale,
  ws.engine_version, ws.params_hash, ws.catalog_version, ws.end_reason_code,
  st.n_sets, st.n_work_sets, st.n_pain_flags,
  w.srpe, w.readiness_level, w.elbow_score
from fitness.workout_sessions ws
left join lateral (
  select sum((r.outputs->>'est_min')::numeric)    as est_min_total,
         sum((r.outputs->>'budget_min')::numeric) as budget_min_total
  from fitness.session_blocks b
  join fitness.recommendations r on r.id = b.recommendation_id and r.user_id = b.user_id
  where b.session_id = ws.id and b.user_id = ws.user_id and b.deleted_at is null
) pl on true
left join lateral (
  select count(*)                                                                    as n_sets,
         count(*) filter (where s.set_kind in ('working', 'top', 'backoff', 'amrap')) as n_work_sets,
         count(*) filter (where s.pain_flag)                                          as n_pain_flags
  from fitness.set_logs s
  where s.session_id = ws.id and s.user_id = ws.user_id and s.deleted_at is null
) st on true
left join lateral (
  select max(wl.srpe)            filter (where wl.kind = 'srpe')        as srpe,
         max(wl.readiness_level) filter (where wl.kind = 'readiness')   as readiness_level,
         max(wl.elbow_score)     filter (where wl.kind = 'elbow_check') as elbow_score
  from fitness.wellness_logs wl
  where wl.session_id = ws.id and wl.user_id = ws.user_id and wl.deleted_at is null
) w on true
where ws.deleted_at is null;

create view fitness_review.v_exercise_time with (security_invoker = true) as
select
  se.user_id, se.session_id, se.id as session_exercise_id, se.exercise_id, e.slug as exercise_slug,
  e.mechanics, ws.local_date, b.block_kind,
  r.kind                                           as plan_kind,
  r.module_version                                 as plan_version,
  (pe.value->>'sets')::int                         as plan_sets,
  (pe.value->>'est_s')::numeric                    as est_s,
  extract(epoch from (se.completed_at - se.started_at)) as actual_s,
  (select count(*) from fitness.set_logs s
    where s.session_exercise_id = se.id and s.user_id = se.user_id and s.deleted_at is null) as n_sets
from fitness.session_exercises se
join fitness.session_blocks b    on b.id = se.block_id and b.user_id = se.user_id and b.deleted_at is null
join fitness.workout_sessions ws on ws.id = se.session_id and ws.user_id = se.user_id and ws.deleted_at is null
join fitness.exercises e         on e.id = se.exercise_id
left join fitness.recommendations r on r.id = b.recommendation_id and r.user_id = b.user_id
left join lateral (
  select x.value from jsonb_array_elements(r.outputs->'exercises') as x(value)
  where (x.value->>'exercise_id')::uuid = se.exercise_id
  limit 1
) pe on true
where se.deleted_at is null and se.status = 'done';
```

`actual_s` runs from the first set's start to the last set's log and therefore excludes the transition after the last set; TIME-1's own EWMA calibration is logged as `time.calibration` snapshots and is the authoritative comparison (C19). `est_min_total` sums the block plans (`session.plan`, `challenge.session_plan`), which is how C34 budgets a session.

### 14.16 `v_challenge_timeline` and `v_challenge_var_changes`

The ECA audit of the **current** fold of each enrollment (rule events of older `fold_generation`s are superseded, 9.7.2). Input events and rule evaluations are interleaved in fold order.

```sql
create view fitness_review.v_challenge_timeline with (security_invoker = true) as
select
  ce.user_id, ce.enrollment_id, en.definition_slug, en.definition_version,
  ce.occurred_at, ce.local_date, ce.id as challenge_event_id,
  'input'::text      as row_kind,
  ce.event_type      as type,
  ce.dedupe_key, ce.supersedes_event_id, ce.source_session_id,
  null::text as rule_id, null::text as rule_tag, null::text as rule_decision, null::text as stage,
  null::smallint as prio, null::text as outcome, null::boolean as cond_value, null::text as eval_error,
  null::jsonb as applied, null::text as suppressed_by, null::smallint as cascade_depth,
  null::text as derived_event_type, null::int as state_rev_after,
  ce.created_at as logged_at
from fitness.challenge_events ce
join fitness.challenge_enrollments en on en.id = ce.enrollment_id and en.user_id = ce.user_id and en.deleted_at is null
union all
select
  re.user_id, re.enrollment_id, re.definition_slug, re.definition_version,
  ce.occurred_at, ce.local_date, re.challenge_event_id,
  'rule', coalesce(re.derived_event_type, 'rule'),
  null, null, null,
  re.rule_id, re.rule_tag, re.rule_decision, re.stage,
  re.prio, re.outcome, re.cond_value, re.eval_error,
  re.applied, re.suppressed_by, re.cascade_depth,
  re.derived_event_type, re.state_rev_after,
  re.created_at
from fitness.challenge_rule_events re
join fitness.challenge_enrollments en
  on en.id = re.enrollment_id and en.user_id = re.user_id and en.deleted_at is null
 and re.fold_generation = en.fold_generation
join fitness.challenge_events ce on ce.id = re.challenge_event_id and ce.user_id = re.user_id;

create view fitness_review.v_challenge_var_changes with (security_invoker = true) as
select
  t.user_id, t.enrollment_id, t.occurred_at, t.local_date, t.rule_id, t.rule_tag, t.stage,
  v.key                as var,
  v.value->'old'       as old_value,
  v.value->'new'       as new_value,
  t.state_rev_after
from fitness_review.v_challenge_timeline t
cross join lateral jsonb_each(t.applied) as v(key, value)
where t.row_kind = 'rule' and t.applied is not null and t.applied <> '{}'::jsonb;
```

Typical use: per-variable trajectories (`wpuA`, `wpuB`, `wpuC`, `negB`, `pinA`, `pinC`, `pinHold90`, `plankS`), fire counts per rule id, P-rule suppressions (`rule_decision = 'declined'`), and evaluation errors (`outcome = 'error'`).

### 14.17 `v_challenge_tests`

Test history with validity and derivation metadata. Derived values (for example the D7 gate value `wpu_e1rm_added`) carry the engine's own `k`, multiplier and CI in `calc_params`; the view only unpacks them.

```sql
create view fitness_review.v_challenge_tests with (security_invoker = true) as
select
  mo.user_id, mo.id as observation_id, mo.enrollment_id, mo.test_session_id, mo.session_id, mo.set_log_id,
  mo.metric_code, md.better, mo.side, mo.value_num, mo.value_bool, mo.unit,
  mo.observed_at, mo.local_date, mo.source,
  mo.valid_for_gate, mo.validity_note, mo.protocol_code,
  mo.derivation, mo.derived_from_ids,
  mo.calc_params->>'method'                    as calc_method,
  (mo.calc_params->>'k')::numeric              as calc_k,
  (mo.calc_params->>'m3')::numeric             as calc_m3,
  (mo.calc_params->>'multiplier')::numeric     as calc_multiplier,
  (mo.calc_params->>'bw_kg')::numeric          as calc_bw_kg,
  (mo.calc_params->'ci80'->>'lo')::numeric     as ci80_lo,
  (mo.calc_params->'ci80'->>'hi')::numeric     as ci80_hi,
  mo.calc_params->'alternatives'               as alternatives,
  mo.bodyweight_kg, mo.equipment_id, mo.equipment_calibration_rev, mo.attempt_count
from fitness.metric_observations mo
join fitness.metric_defs md on md.code = mo.metric_code
where mo.deleted_at is null
  and (mo.enrollment_id is not null or mo.test_session_id is not null or md.introduced_by like 'challenge:%');
```

C4 and D7 require the UI to show the SSE-1 estimate and the challenge gate value side by side; a review does the same by joining this view (`metric_code = 'wpu_e1rm_added'`) with `v_e1rm_history` on `local_date`, and checks that `calc_k` equals the `k_used` that SSE-1 logged for the weighted pull-up near that date (D7: the gate uses RCM-1 `k` of `weighted_pullup`; C59: the class prior k = 30 until RCM-1 has informative windows, so imported tests carry `calc_k` 30). Gate rows use the test-day weigh-in in `bodyweight_kg`; the forward threshold at the current BW(t) is a display value and is not stored (C62).

### 14.18 `v_wellness_safety`

```sql
create view fitness_review.v_wellness_safety with (security_invoker = true) as
select
  wl.user_id, wl.id as wellness_log_id, wl.kind, wl.phase, wl.observed_at, wl.local_date,
  wl.session_id, wl.session_exercise_id, wl.set_log_id, e.slug as exercise_slug,
  wl.readiness_level, wl.elbow_score,
  wl.pain_body_region, wl.pain_side, wl.pain_severity, wl.pain_score, wl.symptom_codes,
  wl.soreness_muscle_group_id, wl.soreness_code, wl.srpe, wl.sleep_hours
from fitness.wellness_logs wl
left join fitness.session_exercises se
  on se.id = wl.session_exercise_id and se.user_id = wl.user_id
left join fitness.exercises e on e.id = se.exercise_id
where wl.deleted_at is null;
```

The free-text `note` is excluded (R-V7). The SafetyState itself is a `safety.state` snapshot; its output keys are listed in 10.2 (C58). v1 ships no view for it, so reviews read those keys with ad-hoc queries until a view migration adds one (14.24).

### 14.19 `v_calendar_events` and `v_calendar_classification`

`v_calendar_events` lists the stored (classified) events; `v_calendar_classification` aggregates the `calendar_classified` telemetry, which also covers busy and ignored events (instance key, kind, score and features only, never titles; C20, section 16).

```sql
create view fitness_review.v_calendar_events with (security_invoker = true) as
select
  c.user_id, c.id as calendar_event_id, c.instance_key, c.series_key, c.local_date, c.iso_week,
  c.starts_at, c.ends_at, round(extract(epoch from (c.ends_at - c.starts_at)) / 60.0) as minutes,
  c.classified_kind, c.override_kind, coalesce(c.override_kind, c.classified_kind) as effective_kind,
  c.challenge_slot, c.override_slot, coalesce(c.override_slot, c.challenge_slot) as effective_slot,
  c.title, c.location,
  c.match_method, c.score, c.features, c.classifier_version,
  c.provider_status, c.app_written, (c.marker is not null) as has_marker, c.adopted_at,
  c.write_state, c.attention, c.linked_session_id, c.link_method, c.missing_since
from fitness.calendar_events c
where c.deleted_at is null;

create view fitness_review.v_calendar_classification with (security_invoker = true) as
select
  e.user_id,
  to_char((e.occurred_at at time zone 'UTC') + make_interval(mins => e.tz_offset_min), 'IYYY-"W"IW') as iso_week,
  e.payload->>'classifier_version' as classifier_version,
  e.payload->>'kind'               as kind,
  e.payload->>'method'             as method,
  count(*)                                   as n,
  count(distinct e.payload->>'instance_key') as n_instances,
  avg((e.payload->>'score')::numeric)        as mean_score,
  min((e.payload->>'score')::numeric)        as min_score
from fitness.session_events e
where e.event_type = 'calendar_classified'
group by 1, 2, 3, 4, 5;
```

Override rate per kind (`override_kind is not null`), confirmation needs (scores below the classifier's confirm threshold) and runs mis-typed as gym sessions are the main review questions (C20, F6).

### 14.20 `v_data_quality`

One row per finding. `severity` is `info`, `warn` or `error`. Findings are aggregated where a per-row listing would be noisy.

| Code | Severity | Finding | Predicate (summary) |
|------|----------|---------|---------------------|
| DQ01 | warn | Snapshot from an unregistered module version (9.1.9 release rule) | `recommendations (module_id, module_version)` not in `algorithm_versions`; aggregated per module@version with count, first and last seen. The same check runs for `exercise_model_state.model/module_version`. |
| DQ02 | info | Telemetry event type missing from the registry | `session_events.event_type` not in `session_event_types`; aggregated per type. |
| DQ03 | error | Metric unit differs from its definition | `metric_observations.unit <> metric_defs.unit`. |
| DQ04 | warn | Exercise requires an unknown station key | element of `exercises.requires_station` not in `equipment_types.key`. |
| DQ05 | info | Dynamic work set logged without effort | `rir_source = 'missing'` on a dynamic work set; aggregated per ISO week (D9 makes this rare; imputation then widens SSE-1 variance). |
| DQ06 | error | Engine did not write the effective load | `effective_load_kg is null` and `load_mode <> 'assisted_ordinal'` and `reps is not null`. |
| DQ07 | info | Finished session without any live set | `workout_sessions.status = 'finished'`, `template_source <> 'import'` (imported test sessions carry only metric observations) and no live `set_logs`. |
| DQ08 | warn | Session left in progress | `status = 'in_progress'` and `started_at < now() - interval '12 hours'`. |
| DQ09 | info | Enrollment fold count differs from the stored events | `challenge_enrollments.state_rev <> count(challenge_events)` of that enrollment (can be transient while sync is pending; amended events make it informative only). |
| DQ10 | warn | Session params snapshot missing (replay impossible) | `workout_sessions.params_hash` not in `params_snapshots` of the same user. |
| DQ11 | error | Snapshot inputs pruned too early | `recommendations.inputs is null` and `created_at > now() - interval '730 days'` (retention rule 15.3). |
| DQ12 | warn | SSE-1 snapshot references an unknown set | an `outputs.sets[].set_id` of `estimation.sse` has no `set_logs` row. |
| DQ13 | error | Possible free text in telemetry | a string value in `session_events.payload` contains whitespace or is 65 characters or longer (privacy rule 16.3). |
| DQ14 | warn | Set logged outside its session window | `completed_at < started_at - 1 h` or `completed_at > finished_at + 1 h` of the session. |
| DQ15 | error | Per-user override for an unknown param key | `user_algo_params.param_key` not a key of the active `algo_params.params`. |
| DQ16 | info | Bodyweight snapshot stale at session start | `workout_sessions.bodyweight_stale` (C32 weigh-in prompt compliance); aggregated per ISO week. |
| DQ17 | warn | Classifier feature that is not a code | an element of `calendar_events.features` that does not match `^[a-z0-9_.:]{1,60}$` (privacy rule 16.2). |

```sql
create view fitness_review.v_data_quality with (security_invoker = true) as
-- DQ01: unregistered module versions
select 'DQ01'::text as check_code, 'warn'::text as severity, r.user_id,
       'module_version'::text as entity, r.module_id || '@' || r.module_version as entity_id,
       jsonb_build_object('count', count(*), 'first_seen', min(r.created_at), 'last_seen', max(r.created_at)) as detail
from fitness.recommendations r
where not exists (select 1 from fitness.algorithm_versions v
                  where v.module_id = r.module_id and v.version = r.module_version)
group by r.user_id, r.module_id, r.module_version
union all
select 'DQ01', 'warn', m.user_id, 'module_version', m.model || '@' || m.module_version,
       jsonb_build_object('count', count(*), 'source', 'exercise_model_state')
from fitness.exercise_model_state m
where m.deleted_at is null
  and not exists (select 1 from fitness.algorithm_versions v
                  where v.module_id = m.model and v.version = m.module_version)
group by m.user_id, m.model, m.module_version
union all
-- DQ02: unregistered telemetry types
select 'DQ02', 'info', e.user_id, 'event_type', e.event_type, jsonb_build_object('count', count(*))
from fitness.session_events e
where not exists (select 1 from fitness.session_event_types t where t.event_type = e.event_type)
group by e.user_id, e.event_type
union all
-- DQ03: metric unit mismatch
select 'DQ03', 'error', mo.user_id, 'metric_observation', mo.id::text,
       jsonb_build_object('metric', mo.metric_code, 'unit', mo.unit, 'expected', md.unit)
from fitness.metric_observations mo
join fitness.metric_defs md on md.code = mo.metric_code
where mo.deleted_at is null and mo.unit <> md.unit
union all
-- DQ04: unknown station keys
select 'DQ04', 'warn', ex.user_id, 'exercise', ex.slug, jsonb_build_object('station', st.key)
from fitness.exercises ex
cross join lateral unnest(ex.requires_station) as st(key)
where ex.deleted_at is null
  and not exists (select 1 from fitness.equipment_types t where t.key = st.key)
union all
-- DQ05: dynamic work sets without effort, per week
select 'DQ05', 'info', f.user_id, 'iso_week', f.iso_week, jsonb_build_object('count', count(*))
from fitness_review.v_set_facts f
where f.is_work_set and f.contraction = 'dynamic' and f.rir_source = 'missing'
group by f.user_id, f.iso_week
union all
-- DQ06: missing engine-written effective load
select 'DQ06', 'error', s.user_id, 'set', s.id::text, jsonb_build_object('load_mode', s.load_mode)
from fitness.set_logs s
where s.deleted_at is null and s.effective_load_kg is null
  and s.load_mode <> 'assisted_ordinal' and s.reps is not null
union all
-- DQ07: finished session without sets
select 'DQ07', 'info', ws.user_id, 'session', ws.id::text, jsonb_build_object('local_date', ws.local_date)
from fitness.workout_sessions ws
where ws.deleted_at is null and ws.status = 'finished' and ws.template_source <> 'import'
  and not exists (select 1 from fitness.set_logs s
                  where s.session_id = ws.id and s.user_id = ws.user_id and s.deleted_at is null)
union all
-- DQ08: stale in-progress session
select 'DQ08', 'warn', ws.user_id, 'session', ws.id::text, jsonb_build_object('started_at', ws.started_at)
from fitness.workout_sessions ws
where ws.deleted_at is null and ws.status = 'in_progress' and ws.started_at < now() - interval '12 hours'
union all
-- DQ09: fold count versus stored events
select 'DQ09', 'info', en.user_id, 'enrollment', en.id::text,
       jsonb_build_object('state_rev', en.state_rev, 'events', n.cnt)
from fitness.challenge_enrollments en
cross join lateral (select count(*) as cnt from fitness.challenge_events ce
                    where ce.enrollment_id = en.id and ce.user_id = en.user_id) n
where en.deleted_at is null and en.state_rev <> n.cnt
union all
-- DQ10: missing params snapshot for a session
select 'DQ10', 'warn', ws.user_id, 'session', ws.id::text, jsonb_build_object('params_hash', ws.params_hash)
from fitness.workout_sessions ws
where ws.deleted_at is null
  and not exists (select 1 from fitness.params_snapshots p
                  where p.user_id = ws.user_id and p.params_hash = ws.params_hash)
union all
-- DQ11: inputs pruned before the retention age
select 'DQ11', 'error', r.user_id, 'recommendation', r.id::text,
       jsonb_build_object('created_at', r.created_at, 'pruned_at', r.inputs_pruned_at)
from fitness.recommendations r
where r.inputs is null and r.created_at > now() - interval '730 days'
union all
-- DQ12: SSE-1 snapshot referencing an unknown set
select 'DQ12', 'warn', r.user_id, 'recommendation', r.id::text, jsonb_build_object('set_id', x.value->>'set_id')
from fitness.recommendations r
cross join lateral jsonb_array_elements(r.outputs->'sets') as x(value)
where r.kind = 'estimation.sse'
  and not exists (select 1 from fitness.set_logs s
                  where s.id = (x.value->>'set_id')::uuid and s.user_id = r.user_id)
union all
-- DQ13: possible free text in telemetry payloads
select 'DQ13', 'error', e.user_id, 'session_event', e.id::text, jsonb_build_object('event_type', e.event_type)
from fitness.session_events e
where jsonb_path_exists(e.payload, '$.** ? (@.type() == "string" && @ like_regex "[[:space:]]|.{65}")')
union all
-- DQ14: set outside its session window
select 'DQ14', 'warn', s.user_id, 'set', s.id::text,
       jsonb_build_object('completed_at', s.completed_at, 'started_at', ws.started_at, 'finished_at', ws.finished_at)
from fitness.set_logs s
join fitness.workout_sessions ws on ws.id = s.session_id and ws.user_id = s.user_id
where s.deleted_at is null and ws.deleted_at is null
  and (s.completed_at < ws.started_at - interval '1 hour' or s.completed_at > ws.finished_at + interval '1 hour')
union all
-- DQ15: per-user override for an unknown key
select 'DQ15', 'error', u.user_id, 'user_algo_param', u.param_key, jsonb_build_object('valid_from', u.valid_from)
from fitness.user_algo_params u
where u.deleted_at is null
  and not exists (select 1 from fitness.algo_params a where a.status = 'active' and a.params ? u.param_key)
union all
-- DQ16: stale bodyweight at session start, per week
select 'DQ16', 'info', ws.user_id, 'iso_week', ws.iso_week, jsonb_build_object('sessions', count(*))
from fitness.workout_sessions ws
where ws.deleted_at is null and ws.bodyweight_stale
group by ws.user_id, ws.iso_week
union all
-- DQ17: classifier features that are not codes
select 'DQ17', 'warn', c.user_id, 'calendar_event', c.id::text, jsonb_build_object('feature_length', length(f.feature))
from fitness.calendar_events c
cross join lateral unnest(c.features) as f(feature)
where c.deleted_at is null and f.feature !~ '^[a-z0-9_.:]{1,60}$';
```

DQ04 rows for global exercises have `user_id is null`; a review includes them with `where user_id = '<owner>' or user_id is null`. DQ17 deliberately reports only the length of the offending feature, never its text.

Migration 017 selects `user_id` first in every branch, so the view's columns are `user_id, check_code, severity, entity, entity_id, detail` (R-V3); the SQL above shows the predicates and keeps the earlier column order for readability.

### 14.21 `v_sync_health` and `v_upload_latency`

```sql
create view fitness_review.v_sync_health with (security_invoker = true) as
select
  e.user_id,
  ((e.occurred_at at time zone 'UTC') + make_interval(mins => e.tz_offset_min))::date as local_date,
  e.event_type,
  coalesce(e.payload->>'pg_code', e.payload->>'pgrst_code', e.payload->>'http_status',
           e.payload->>'trigger', '-')                                             as code,
  count(*)                                                                         as n,
  sum((e.payload->>'pushed')::int)                                                 as pushed,
  sum((e.payload->>'pulled')::int)                                                 as pulled,
  max((e.payload->>'outbox_after')::int)                                           as max_outbox_after,
  percentile_cont(0.95) within group (order by (e.payload->>'duration_ms')::numeric) as p95_duration_ms
from fitness.session_events e
where e.category = 'sync'
group by 1, 2, 3, 4;

create view fitness_review.v_upload_latency with (security_invoker = true) as
select
  s.user_id,
  (s.completed_at at time zone 'Europe/Amsterdam')::date                                         as local_date_ams,
  count(*)                                                                                        as n_sets,
  percentile_cont(0.50) within group (order by extract(epoch from (s.server_updated_at - s.client_updated_at))) as p50_s,
  percentile_cont(0.95) within group (order by extract(epoch from (s.server_updated_at - s.client_updated_at))) as p95_s,
  min(extract(epoch from (s.server_updated_at - s.client_updated_at)))                            as min_s
from fitness.set_logs s
where s.deleted_at is null
group by 1, 2;
```

`v_upload_latency` measures the delay from the last local edit to the server write; a negative `min_s` reveals a phone clock ahead of the server (11.12). `local_date_ams` uses the project zone only for bucketing this diagnostic; it is not a training date (C31).

### 14.22 `v_db_size` and `v_db_size_summary`

The size guard of section 15. These two views have no `user_id`; they read catalog functions only.

```sql
create view fitness_review.v_db_size with (security_invoker = true) as
select
  n.nspname                                           as schema_name,
  c.relname                                           as table_name,
  pg_total_relation_size(c.oid)                       as total_bytes,
  pg_relation_size(c.oid)                             as heap_bytes,
  pg_indexes_size(c.oid)                              as index_bytes,
  coalesce(pg_total_relation_size(nullif(c.reltoastrelid, 0)), 0) as toast_bytes,
  nullif(c.reltuples, -1)::bigint                     as est_rows
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname in ('fitness', 'fitness_private') and c.relkind in ('r', 'p');

create view fitness_review.v_db_size_summary with (security_invoker = true) as
with s as (
  select pg_database_size(current_database())                       as db_bytes,
         (select coalesce(sum(total_bytes), 0) from fitness_review.v_db_size) as fitness_bytes
)
select
  round(db_bytes / 1048576.0, 1)                       as db_mb,
  round(fitness_bytes / 1048576.0, 1)                  as fitness_mb,
  round((db_bytes - fitness_bytes) / 1048576.0, 1)     as other_mb,
  round(100.0 * db_bytes / (500 * 1048576.0), 1)       as pct_of_free_limit,
  case when db_bytes      >= 425 * 1048576.0 then 'critical'
       when db_bytes      >= 350 * 1048576.0 then 'warn'
       when fitness_bytes >= 150 * 1048576.0 then 'warn'
       else 'ok' end                                   as status,
  now()                                                as measured_at
from s;
```

The thresholds 500 / 425 / 350 / 150 MB are the size-guard constants of 15.2 (written as literals because views cannot read params). Growth per 30 days is computed by the review from the `measured_at` values it records in each review document.

### 14.23 `v_replay_index`

Selects snapshots for the `:sim` replay CLI (S2): exact replay needs the inputs (unless pruned), the params snapshot and a registered module version.

```sql
create view fitness_review.v_replay_index with (security_invoker = true) as
select
  r.user_id, r.id as recommendation_id, r.kind, r.module_id, r.module_version, r.engine_version,
  r.params_hash, (p.id is not null) as params_available,
  r.catalog_version, r.challenge_def_sha256, r.inputs_hash,
  (r.inputs is not null) as inputs_available, r.inputs_bytes, r.inputs_pruned_at,
  r.scope_key, r.scope_date, r.iso_week, r.session_id, r.session_exercise_id, r.enrollment_id,
  r.supersedes_id, r.created_at,
  exists (select 1 from fitness.algorithm_versions v
          where v.module_id = r.module_id and v.version = r.module_version) as version_registered
from fitness.recommendations r
left join fitness.params_snapshots p on p.user_id = r.user_id and p.params_hash = r.params_hash;
```

The replay run itself reads `inputs`, `outputs` and the params map for the selected ids (through the MCP or from an "Export review bundle" file, 15.5) and asserts `ALGO[module@version](inputs, params) == outputs`; a counterfactual run executes a candidate version on the same inputs and diffs the outputs against the actual `set_logs` (S2).

### 14.24 Maintenance rules for the views

- All views live in migration 017 and later view migrations; the same SQL is never copied into review notes. Review queries that are reused are stored as files in `supabase/review/queries/` (21) with a `:owner_id` placeholder.
- A view migration is required when (a) a 10.2 key changes, (b) a source column is renamed or dropped, or (c) a snapshot kind whose 10.2 keys have no v1 view (for example `estimation.hold`, `stall.weekly`, `safety.state`) needs a reusable review view (C58). Views are dropped in reverse creation order and recreated in catalogue order (14.3).
- Every view migration runs the verification checklist (section 17), including 17.4 item V (all views `security_invoker = true`, no grants) and the sentinel tests of 19.9.

---

## 15. Retention, size guard, backups and exports (F4, research S7)

The project is on the Supabase Free plan (F4): no backups, read-only above 500 MB of database size, paused after 7 quiet days, and shared with the chess app, which already uses 95 MB (section 2). The phone database is the source of truth (design principle 1); everything in this section keeps the server replica small and makes sure that losing either side loses nothing.

### 15.1 Expected volume

| Table | Rows per year (estimate) | Bytes per row incl. index share (estimate) | MB per year | Basis |
|-------|--------------------------|---------------------------------------------|-------------|-------|
| `set_logs` | 15 000 | 700 | 10.5 | 4 sessions/week x about 70 rows (per-side rows, warm-ups) x 52 weeks; 6 indexes |
| `session_exercises`, `session_blocks`, `workout_sessions` | 3 000 | 600 | 1.8 | about 6 exercises and 1-2 blocks per session; plus home-habit sessions |
| `wellness_logs` | 2 000 | 400 | 0.8 | F15 prompts (at most a few per session) |
| `metric_observations` | 300 | 500 | 0.2 | weekly weigh-in, 10 test values per block |
| `recommendations` | 5 000 | 8 000 | 40.0 | per session: plan, about 6 `rx.prescription` + `warmup.sets`, estimators; per day/week: today, weekplan, targets, accounting; inputs target 16 KB before TOAST compression |
| `session_events` | 50 000 | 450 | 22.5 | about 900 events per week (sets, rest, prompts, sync, today) |
| `challenge_events` | 600 | 2 000 | 1.2 | sessions, ticks, tests |
| `challenge_rule_events` | 3 000 | 1 500 | 4.5 | about 5 true rules per input event |
| `calendar_events` | 400 | 600 | 0.2 | classified events only |
| Reference, catalog, config, caches | | | 1.0 | |
| **Total** | | | **about 80** | upper estimate; the research estimated < 60 MB per year |

Grade: **expert-opinion**, tunable. Tuned by: the monthly `v_db_size` measurements recorded in review documents (actual MB per table per 30 days). At 80 MB per year and a static chess footprint, the 350 MB warning level is about 3 years away; the guard exists because chess growth is outside this project's control.

### 15.2 Size guard

| Constant | Default | Bounds | Grade | Source | Tuned by |
|----------|---------|--------|-------|--------|----------|
| Free-plan read-only limit | 500 MB | fixed | engineering-fact | Supabase database-size docs (research) | n/a |
| `size.db_warn_mb` | 350 MB (70 %) | 250-450 | expert-opinion | research S7 | growth rate from `v_db_size_summary` history |
| `size.db_critical_mb` | 425 MB (85 %) | 350-480 | expert-opinion | this document | same |
| `size.fitness_budget_mb` | 150 MB | 50-300 | expert-opinion | about two years of 15.1 growth | actual fitness growth |
| `size.fitness_growth_warn_mb_30d` | 15 MB | 5-50 | expert-opinion | about 2x the expected monthly growth (80 / 12 = 6.7 MB) | actual growth |

**Who checks.** There is no fitness cron job in v1 (a scheduled job would be persistent configuration inside the shared chess project), and the app cannot query the database size (the `fitness` schema has no RPC functions, by design). The guard therefore runs:

1. at the start of every Claude Code review (14.2: `v_db_size_summary` first),
2. before every migration (17.1),
3. whenever the user asks.

The app shows an informational **local estimate** of its own server footprint in You > Data and sync: the number of synced rows per table multiplied by the per-row sizes of 15.1. It never claims to know the database total. The gap this leaves (nobody reviews for months while chess grows) is open issue 20.

**Ladder.**

| Status | Condition | Action |
|--------|-----------|--------|
| ok | database < 350 MB, fitness < 150 MB, fitness growth < 15 MB per 30 days | None. |
| watch | fitness growth >= 15 MB per 30 days | Find the table (`v_db_size`); check `recommendations.inputs_bytes` (engine target <= 16 KB) and that the snapshot reuse rule (9.5.2) works. |
| warn | database >= 350 MB or fitness >= 150 MB | Tell the user. Propose pruning P1 and P2 (15.3); each needs the user's approval. |
| critical | database >= 425 MB | The user decides between further pruning (P1 with 365 days), `vacuum full` of the pruned tables, asking for chess-side pruning, or a paid plan. |
| read-only | database >= 500 MB | Supabase switches the database to read-only. Writes fail with SQLSTATE `25006` (`read_only_sql_transaction`). The app keeps every outbox row, stops pushing, keeps pulling, shows "Server storage is full (read-only). Your data is safe on this phone." and retries every 6 hours. Nothing is lost because the phone is authoritative. (11.8 and ANDROID.md 9.5 list this error class.) |

### 15.3 Retention

| Data | Server retention | Pruning (only after user approval, only when the size guard says warn or higher) |
|------|------------------|--------------------------------------------------------------------------------|
| Facts: `set_logs`, `workout_sessions`, `session_blocks`, `session_exercises`, `wellness_logs`, `metric_observations`, `calendar_events` | Forever | Never. Core facts are small. |
| Challenge history: `challenge_events`, `challenge_rule_events`, `challenge_enrollments` | Forever | Never; the fold must stay replayable. |
| Configuration, caches, `params_snapshots`, `exports` | Forever | Never (`params_snapshots` is needed for every replay). |
| Tombstones (`deleted_at`) | Forever | Never on the server (11.7). |
| `recommendations` | Full rows for at least `retention.recommendation_inputs_days` = 730 days | **P1**: set `inputs = null` and `inputs_pruned_at = now()` for rows older than 730 days that are covered by a completed full export. `inputs_hash`, `outputs`, `trace` and `warnings` are kept, so reviews of outcomes still work; only exact replay of those rows needs the export file. |
| `session_events` | Full rows for at least `retention.session_events_payload_days` = 730 days | **P2**: replace `payload` with `{"pruned": true}` for rows older than 730 days of the low-value types `sync_run`, `app_opened`, `permission_state`, `exact_alarm_status`, `calendar_classified`, `today_card_shown`, `weekplan_shown`, covered by a completed full export. Rows are kept, so counts in the review views stay correct. |

Constants: `retention.recommendation_inputs_days` 730 (bounds 365-3650) and `retention.session_events_payload_days` 730 (bounds 365-3650); grade **expert-opinion**, source research S2/S7 (the tech-stack lens proposed 365 days; 730 keeps two full years of exact replay); tuned by how far back reviews actually replay (`v_replay_index` queries in review documents) and by size pressure.

P1 as run by `postgres` through the MCP, after the user approved it in chat (the append-only trigger 7.2 permits updates by `postgres`; `server_updated_at` is not touched, so devices do not re-pull the pruned rows and keep their full local copies):

```sql
begin;
update fitness.recommendations r
set    inputs = null,
       inputs_pruned_at = now()
where  r.user_id = '<owner uuid>'
  and  r.inputs is not null
  and  r.created_at < now() - interval '730 days'
  and  exists (select 1
               from fitness.exports x
               where x.user_id = r.user_id
                 and x.export_kind in ('monthly_full', 'manual_full')
                 and x.created_at > r.created_at);
-- Inspect the row count, then commit (or roll back).
commit;
vacuum (analyze) fitness.recommendations;
```

P2 is the same pattern on `session_events` with `payload = '{"pruned": true}'::jsonb` and `event_type = any (array[...])`. Plain `vacuum` makes the space reusable but does not shrink the files; if the database size itself must drop, `vacuum full fitness.recommendations` (exclusive lock for seconds at this size; the app sees transient errors and retries) is run only with the user's approval. Local retention on the phone is defined in 12.5 and is independent of server pruning.

### 15.4 Backups and restore

| Layer | What | When | Where | Owner |
|-------|------|------|-------|-------|
| L1 Phone database | Room `fitness.db` (authoritative) plus pre-migration copies (ANDROID.md 8.5: newest 3 kept) | Continuous | Phone, app-private storage | App |
| L2 Supabase replica | Every synced table (section 9) | Continuous via sync | Supabase (no backups, no PITR on Free) | App |
| L3 Full export | Gzip-compressed JSON (`.json.gz`, C40) of every synced table and the local-only tables listed in 15.5 | Monthly reminder after `export.reminderDays` = 30 days (F4; ANDROID.md 25.3) and on demand | A location the user chooses through the system file picker (no automatic upload) | User |
| L4 Pre-migration dump | `supabase db dump --db-url "$FITNESS_DB_URL" --schema fitness --data-only -f <dir>/fitness-data-<yyyyMMdd-HHmm>.sql` (F3); if the CLI cannot run its container, the equivalent `pg_dump "$FITNESS_DB_URL" --data-only --schema=fitness --no-owner --no-privileges -f ...` | Before every migration once real data exists | The user's machine outside the repository (default `%USERPROFILE%\FitnessBackups\`); the newest 10 dumps are kept | Claude Code runs the command; the user provides `FITNESS_DB_URL` as an environment variable, so the database password never appears in the repository, a command line in the transcript, or chat |
| L5 Android Auto Backup | **Off** (D16, F21): `android:allowBackup="false"` and data-extraction rules that exclude everything (ANDROID.md 20.3) | n/a | n/a | No training or health data goes to the user's Google backup, and a reinstall never restores a stale database that would conflict with sync; L2 and L3 are the backups (open issue 17, resolved) |

`fitness_private.allowed_users` is not dumped: migration 003 recreates it. Dumps and exports are never committed (21: `.gitignore` patterns).

Restore scenarios:

| # | Situation | Restore path | Data loss |
|---|-----------|--------------|-----------|
| R1 | Server data lost or corrupted, phone intact | Re-apply the fitness migrations (001-016) if the schema is gone; then the app's **full re-push**: every local row of every pushed table is re-enqueued in push order (11.3). Ids are stable, so upserts recreate exactly the same rows; append-only rows are re-inserted with `ignore-duplicates`. ANDROID.md proposes a user-triggered "Re-push all data" action in Sync status and defers automatic detection (ANDROID OI-25); an automatic offer when a pull returns zero rows for a table that has synced rows locally would be a later addition (open issue 12). | None. |
| R2 | Phone lost or reset, server intact | New install, sign in, bootstrap pull (11.6). Older `recommendations` (> 90 days) and most telemetry stay on the server and in the exports; engine caches are rebuilt from facts; enrollments are refolded from `challenge_events`. | None (older snapshots remain on the server). |
| R3 | Phone and server both lost | Restore from the newest L3 export file. "Restore from file" in the app is a later milestone (ANDROID.md 20.4); until then Claude Code converts the export JSON into Room rows or SQL inserts with a `:sim` tool (`ExportRestore`), with the user's approval. | Everything since the export (at most about 30 days with the reminder). Open issue 13. |
| R4 | Project paused after 7 quiet days | The user restores the project in the dashboard (the research notes a long restore window; verify at the time, open issue 19). Meanwhile the app works offline and the outbox waits (11.8: "Server unreachable (paused?)" after 24 h). | None. |
| R5 | A migration damaged fitness data | Roll back with the commented manual rollback of that migration (user approval), then restore the L4 dump into the emptied tables, or use R1 if the phone is more recent than the dump. | None if R1 is possible. |

### 15.5 Exports

**Kinds** (`exports.export_kind`, 9.8.3):

| Kind | Content | Free text | Trigger |
|------|---------|-----------|---------|
| `monthly_full` | Every synced table of the user, including tombstones, plus the local-only tables of ANDROID.md 8.3 except the auth session, the outbox internals, the derived search index `exercise_fts` and `calendar_instance` (busy and unclassified calendar events never leave the phone, 16.1; the cache is rebuilt from the calendar provider); outbox depth and dead-letter count as metadata | Included (it is the user's own backup) | Monthly reminder (`export.reminderDays`, 30 days) |
| `manual_full` | Same as `monthly_full` | Included | "Export all data" |
| `review_bundle` | For a chosen date range: `recommendations`, `session_events`, sessions, sets, metric observations, wellness and pain events, preferences, goals, equipment and calibration versions, `challenge_events`, `challenge_rule_events`, params snapshots and algorithm versions (UX.md 15.10) | Excluded unless the user ticks "Include notes" | "Export review bundle" (F19 trace export) |

**Format.** One JSON document in a gzip container (`.json.gz`, C40; default name `fitnessapp-export-<yyyy-MM-dd>.json.gz`, ANDROID.md 20.4), the same container for every kind; `exports.format = 'json_gz'`. The JSON is canonical (RFC 8785 JCS) so the same data always produces the same bytes before compression:

```json
{
  "schema_version": "fitness-export/1",
  "export_kind": "monthly_full",
  "generated_at": "2026-11-01T09:00:00.000000Z",
  "user_id": "<owner uuid>",
  "app_version": "1.0.0+12",
  "engine_version": "1.0.0",
  "catalog_version": "1.0.0",
  "params_versions": ["1.0.0"],
  "room_schema_version": 1,
  "period": { "start": null, "end": "2026-11-01T09:00:00.000000Z" },
  "includes_local_only": true,
  "includes_notes": true,
  "outbox_depth": 0,
  "dead_letters": 0,
  "tables": { "gyms": [], "gym_equipment": [], "...": [] },
  "local_only": { "rest_timer": [], "...": [] }
}
```

Rules: JCS sorts object keys, so the tables inside `tables` and `local_only` appear in alphabetical order (the example above is not in canonical order); an importer applies them in push order (11.3). Rows inside a table are sorted by `id` (local-only tables by their primary key). Global reference data and the global catalog are not exported; `catalog_version` and `params_versions` identify them, while the user's own custom exercises and stimulus rows are exported. Each row uses exactly the sync DTO of that table (same column names and C22 grid as the server), so timestamps are written as the DTO writes them: ISO 8601 UTC with milliseconds for client-written values (`generated_at` and `period.end` too) and `server_updated_at` exactly as PostgREST returned it (DECISIONS changelog 2026-10-11, M1b); no email, no auth tokens, no device secrets. The M1b export contains `local_only` = `crash_log`, `dead_letter`, `device` (one row: `id`, `device_id`, `last_client_updated_at_ms`), `rest_timer` and `scheduled_alarm`; `outbox` and `sync_cursor` are the outbox internals and are left out (only `outbox_depth` and `dead_letters` are reported). The gzip header carries no timestamp, so the same data gives the same file bytes. The `exports` row records kind, format, schema version, row counts per table, byte size and the SHA-256 of the file as written; the file itself never goes to Supabase. The container is settled by C40 (open issue 8, resolved); UX.md 15.10 still says "JSON zip" for review bundles (open issue 27).

A full export is a precondition for server pruning (15.3) and for local pruning of telemetry and snapshots older than 365 days (12.5).

---

## 16. Privacy

Training logs, bodyweight, pain reports and calendar data are health-related personal data, even though they belong to the user and live in the user's own project. The rules below apply to every table, view, export and log line. They complement ANDROID.md section 20 (keys, transport, logcat, local encryption).

### 16.1 Where each category of data may appear

| Category | Examples | Room (phone) | Supabase `fitness` | Telemetry payloads (`session_events`) | Snapshot `inputs` / `trace` | Review views | Full export | Review bundle |
|----------|----------|--------------|--------------------|---------------------------------------|----------------------------|--------------|-------------|---------------|
| Training facts | sets, loads, RIR, sessions | yes | yes | ids and numbers only | ids and numbers | yes | yes | yes |
| Health signals | pain region, severity, symptom codes, elbow score, readiness, sRPE, sleep hours, bodyweight | yes | yes | ids only (`wellness_log_id`) | codes and numbers | yes (`v_wellness_safety`) | yes | yes |
| Personal free text | session notes, deviation notes (`other`), wellness notes, preference notes, gym address note, display name, custom exercise names | yes | yes (owner-only RLS) | **never** | **never** (exercise ids, never names) | **never** (R-V7) | yes | only if "Include notes" is ticked |
| Calendar: classified events | title, location, start/end of gym, challenge, test and run events | yes | yes (`calendar_events`) | instance key, kind, slot, score, feature codes | kinds, slots, times; never titles | `v_calendar_events` only | yes | yes |
| Calendar: busy or unclassified events | everything else in the calendar | the local cache `calendar_instance` only (ANDROID.md 8.3: read window from 7 days before to 14 days after the Monday of the current ISO week, rows purged after 60 days) | **never** | instance key, kind (`busy`/`ignored`), score, feature codes | busy intervals as times only | aggregate counts only | no | no |
| Identity | email, auth tokens | auth session (supabase-kt storage) | **never** (only `auth.users`, owned by Supabase Auth) | never | never | never | never | never |
| Device | `device_id` (random UUID per install), app version | yes | yes (S-MUT/S-APP columns) | yes | no | yes | yes | yes |

### 16.2 Calendar (D11, C20)

1. The app reads the phone's synced calendars through `CalendarContract`, primary calendar only by default (D11). Event descriptions are read only to find the app's own marker line; attendees are never read.
2. Only classified events (`challenge_session`, `planned_gym`, `run`) are stored in `fitness.calendar_events` (9.7.3). An `ignored` row is stored only as a user override and then without title and location (CHECK). `busy` is never stored on the server (E21).
3. `instance_key` and `series_key` are SHA-256 hashes computed on the device; the provider's row ids and the account name never leave the phone in readable form. Selected calendars are stored as hashed keys (`profiles.calendar_selected_keys`).
4. Classifier `features` are codes from a fixed vocabulary owned by `calendar.classify` (for example `title_pattern:sessie_c`, `location:gym_keyword`, `all_day`); they never contain substrings of a title. The client validator enforces `^[a-z0-9_.:]{1,60}$`; DQ17 (14.20) detects violations on the server (a database CHECK is open issue 16).
5. The `calendar_classified` telemetry event carries `instance_key`, `kind`, `slot`, `score`, `features`, `classifier_version` and `method` for every event, including busy and ignored ones, and never a title (C20).
6. App-written events contain the title (the challenge definition's title template for challenge events, an English label for general sessions, F25), the gym location, English exercise lines with their planned loads, a human-readable line and, as the last line, the C37 marker `[fitapp:v1 <uuid> <slotKey>]` (9.7.3; ANDROID.md 16.5, 16.7). No pain, wellness, bodyweight or note data is written into the user's calendar.

### 16.3 Telemetry and snapshot payloads

1. Payload keys per event type are fixed by the registry (`session_event_types.payload_keys`, 9.8.1). Unknown keys are dropped by the client before the row is written.
2. Allowed payload values: numbers, booleans, UUIDs, ISO timestamps and **codes**. A string that is not a UUID or timestamp must match `^[A-Za-z0-9_.:/+-]{0,64}$` (`/` is allowed for marker slot keys such as `oap/C/2026-W42`, C72). Free text is replaced by a reference (for example a `wellness_log_id`), never copied (UX.md: "never copied into telemetry payloads").
3. Size limits: `session_events.payload` <= 8 KB, `recommendations.inputs` <= 64 KB (engine target 16 KB), `outputs` and `trace` <= 64 KB (CHECKs in section 9).
4. Engine inputs contain only what the algorithm reads: ids, codes, numbers and times (research S2: "ids + numbers, no free text"). Custom exercise names, notes, calendar titles and the display name are never engine inputs.
5. Sync and error telemetry carries codes (`http_status`, `pg_code`, `pgrst_code`), never request or response bodies (11.8).
6. Crash records carry the exception class, the top frame and a stack hash only (ANDROID.md 20.2).
7. Verification: the Kotlin test `TelemetryPayloadPrivacyTest` (21) checks every registered event type against these rules, and DQ13 (14.20) scans the server data.

### 16.4 Identity and access

- No email address or other direct identifier is stored in any fitness table or committed to the repository. The owner's UUID appears in exactly one migration (003). Reviews resolve the UUID from the user's own email at run time (14.2).
- `profiles.sex`, `profiles.birth_year` and `profiles.display_name` are optional, and no default depends on sex or age (D3).
- `device_id` is a random UUID generated at install, not a hardware identifier. No location, contacts or advertising identifiers are collected (ANDROID.md section 19).
- Access paths: the app (user JWT, allowlist plus owner RLS, section 5); Claude Code reviews (as `postgres` through the MCP, which bypasses RLS, so reviews filter by the owner's UUID, 14.2); nothing else. No `service_role` key exists in the app or the repository.
- IP addresses and request metadata appear only in Supabase's platform logs, which are outside the fitness schemas and outside this design's control.

### 16.5 Review outputs

Review documents (`docs/reviews/<date>.md`) contain aggregates, parameter values and anonymous ids at most. They never contain notes, calendar titles, the email address or raw telemetry payloads. When a review needs to quote a calendar title to explain a misclassification, it shows the classifier features instead.

### 16.6 Erasing fitness data

The app has no hard-delete path (no `DELETE` grant, design principle 6), and deleting the user in `auth.users` would also delete the user's **chess** account, because both apps share it. Erasing all fitness data of one user is therefore a script that Claude Code prepares on request and the user runs (or explicitly approves) in the SQL editor, in one transaction, after a final export:

```sql
begin;
-- :uid = the user's UUID, resolved from the user's own email (14.2)
delete from fitness.challenge_rule_events where user_id = :uid;
delete from fitness.challenge_events      where user_id = :uid;
delete from fitness.calendar_events       where user_id = :uid;
delete from fitness.metric_observations   where user_id = :uid;
delete from fitness.wellness_logs         where user_id = :uid;
delete from fitness.session_events        where user_id = :uid;
delete from fitness.set_logs              where user_id = :uid;
delete from fitness.session_exercises     where user_id = :uid;
delete from fitness.session_blocks        where user_id = :uid;
delete from fitness.workout_sessions      where user_id = :uid;
delete from fitness.recommendations       where user_id = :uid;
delete from fitness.params_snapshots      where user_id = :uid;
delete from fitness.exercise_model_state  where user_id = :uid;
delete from fitness.exports               where user_id = :uid;
delete from fitness.challenge_enrollments where user_id = :uid;
delete from fitness.user_algo_params      where user_id = :uid;
delete from fitness.user_exercise_prefs   where user_id = :uid;
delete from fitness.rep_preferences       where user_id = :uid;
delete from fitness.muscle_goals          where user_id = :uid;
delete from fitness.exercise_stimulus     where user_id = :uid;
delete from fitness.exercises             where user_id = :uid;
delete from fitness.profiles              where user_id = :uid;
delete from fitness.gym_equipment         where user_id = :uid;
delete from fitness.gyms                  where user_id = :uid;
delete from fitness_private.allowed_users where user_id = :uid;
-- Expect zero rows for :uid in every fitness table (query v_db_size and a per-table count), then commit.
commit;
```

`:uid` stands for the UUID literal (the Supabase SQL editor has no psql variables). The order deletes referencing rows before their targets (plain foreign keys use `no action`). Whether the app should offer this as a user-facing action is open issue 15. Removing only the allowlist row blocks access without deleting anything.

### 16.7 Files outside the database

Export files, review bundles and database dumps contain the user's data and are never committed. The repository `.gitignore` excludes `*.json.gz`, `*.export.json`, `fitnessapp-export-*`, `review-bundle-*`, `supabase/.temp/`, `backups/` and `*.dump.sql`. Replay fixtures derived from real data (`:sim` `replay/`, ANDROID.md section 4) are anonymised before they are committed: user ids replaced by the fixture owner id (19.6), free-text fields removed, calendar titles replaced by their classified kind.

---

## 17. Verification checklist after each migration

Every migration (13.2) is followed by this checklist. Claude Code runs it, records the results in the migration's review notes (`supabase/migrations/notes/<version>_fitness_NNN.md`, 21) and stops at the first failure: no further migration is applied until the failure is understood and the user has decided how to proceed.

### 17.0 Overview

| Step | When | Check | Pass criterion |
|------|------|-------|----------------|
| 17.1 | Before | Pre-flight | All items true |
| 17.2 | Apply | MCP `apply_migration` | Success; the version appears in `list_migrations` after the chess history |
| 17.3 | After | Advisors (security + performance) | No new finding about a `fitness*` object, except the allowed list |
| 17.4 | After | Grants, function ACLs, policies, FK indexes, views, shared-project invariants | Every query returns the expected result |
| 17.5 | Before and after exposing, then after every migration | PGRST106 and anon probes | Expected HTTP status, code and hint |
| 17.6 | After every migration from 008 on, and before the first app install | RLS tests with two allowlisted users and one non-allowlisted user (19.3) | `FITNESS_DB_TESTS_PASSED` |
| 17.7 | After | safeupdate implications | Client write paths are upserts or filtered |
| 17.8 | After data migrations | Counts and hashes | Equal to the generator's expectations |
| 17.9 | After exposure (once) and after schema changes the app uses | App smoke test (user taps "Server check") | Pull and push succeed |

### 17.1 Pre-flight (before applying)

1. The user has read the migration file and approved it in chat (F3; DECISIONS section 5, action 4).
2. If real data exists: a fresh in-app full export (15.5) and an L4 dump (15.4) were made today.
3. `v_db_size_summary` (once migration 017 exists; before that `select pg_database_size(current_database())`) is below the warn level (15.2).
4. The PGRST106 probe (17.5) has been run and its result recorded as the "before" state.
5. `list_migrations` has been read; the latest version and the count are recorded (2026-10-07: 55 chess rows, latest `20261005083742`).
6. Static checks on the file (scripted, 21): every `create`/`alter`/`grant`/`revoke`/`insert` names a schema-qualified object; no object in `public`; no `alter default privileges` (fitness never uses default privileges); no trigger on `auth.users`; no `alter role`; no `create extension` except in migration 007 (`pg_jsonschema`, F5 approved 2026-10-08); every `create function` is followed by `revoke all on function ... from public`; every `create table` in `fitness` has `enable row level security` and explicit grants in the same file.

### 17.2 Apply

`apply_migration(name = 'fitness_NNN_<name>', query = <file content>)` through the Supabase MCP, nothing else (never `supabase db push`, `supabase config push`, `migration repair` or `alter role authenticator set pgrst.db_schemas`; F2, F3). Then rename the file to its assigned version (13.1 step 5).

### 17.3 Advisors

Run `get_advisors(type = 'security')` and `get_advisors(type = 'performance')` and compare with the baseline recorded before the first fitness migration (research, 2026-10-07: security 14 INFO `rls_enabled_no_policy` on chess tables, WARN `extension_in_public` for `ltree`, WARN leaked-password protection disabled; performance 3 unindexed foreign keys on chess tables).

Pass: no new finding whose object is in `fitness`, `fitness_private` or `fitness_review`, except the two exceptions that C60 allows (they replace the earlier reading of F3's "zero new fitness findings", open issue 22):

| Finding | Allowed when | Reason |
|---------|--------------|--------|
| INFO `unused_index` on a new fitness index | During the first 8 weeks after the index was created (C60) | No queries have run yet; reviewed at the first algorithm review. After 8 weeks an unused fitness index is a finding like any other. |
| A security-definer lint (for example 0028/0029) on `fitness_private.is_allowed()` | Always (C60) | Documented exception (5.2): the schema is not exposed, the function returns only the caller's own allowlist boolean, and RLS needs it. `anon` must never be able to execute it (17.4 item F). |

Everything else must be zero (C60): any other new fitness finding fails the step.

### 17.4 Grants, ACLs, policies, indexes and invariants

Run as `postgres` through the MCP. The expected matrix for item T is generated from table 9.0 (class and sync class) by the migration lint script (21), so the check is mechanical.

**T. Table privileges and RLS**

```sql
select c.relname,
       c.relrowsecurity                                        as rls_on,
       has_table_privilege('anon',          c.oid, 'SELECT')   as anon_select,
       has_table_privilege('authenticated', c.oid, 'SELECT')   as auth_select,
       has_table_privilege('authenticated', c.oid, 'INSERT')   as auth_insert,
       has_table_privilege('authenticated', c.oid, 'UPDATE')   as auth_update,
       has_table_privilege('authenticated', c.oid, 'DELETE')   as auth_delete,
       has_table_privilege('authenticated', c.oid, 'TRUNCATE') as auth_truncate,
       has_table_privilege('authenticated', c.oid, 'REFERENCES') or
       has_table_privilege('authenticated', c.oid, 'TRIGGER') as auth_other,
       has_table_privilege('service_role',  c.oid, 'DELETE')   as service_delete
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'fitness' and c.relkind in ('r', 'p')
order by c.relname;
```

Expected: `rls_on` true for every table; `anon_select`, `auth_delete`, `auth_truncate`, `auth_other` false for every table; REF and `challenge_definitions` and `user_algo_params`: only `auth_select`; APPEND: `auth_select` and `auth_insert` only, `service_delete` false; USER, CACHE and MIX: select, insert, update. In `fitness_private`: `allowed_users` has no privilege for `anon` or `authenticated` and `rls_on` false (5.2).

**S. Schema privileges**

```sql
select n.nspname, r.rolname,
       has_schema_privilege(r.rolname, n.nspname, 'USAGE')  as usage,
       has_schema_privilege(r.rolname, n.nspname, 'CREATE') as create_priv
from pg_namespace n
cross join pg_roles r
where n.nspname like 'fitness%' and r.rolname in ('anon', 'authenticated', 'service_role')
order by 1, 2;
```

Expected: `fitness` and `fitness_private`: usage true for `authenticated` and `service_role`, false for `anon`; `fitness_review`: usage false for all three; `create_priv` false everywhere.

**F. Functions**

```sql
-- F1: functions still executable by PUBLIC (proacl null means the built-in default, which includes PUBLIC)
--     or without a pinned empty search_path. Expected: zero rows.
select p.oid::regprocedure as fn, p.prosecdef, p.proconfig, p.proacl
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname like 'fitness%'
  and (   p.proacl is null
       or exists (select 1 from aclexplode(p.proacl) a
                  where a.grantee = 0 and a.privilege_type = 'EXECUTE')
       or not coalesce(p.proconfig @> array['search_path=""'], false));

-- F2: security definer functions. Expected: exactly fitness_private.is_allowed().
select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid = p.pronamespace
where n.nspname like 'fitness%' and p.prosecdef;

-- F3: who may execute is_allowed(). Expected: anon false, authenticated true, service_role true.
select r.rolname, has_function_privilege(r.rolname, 'fitness_private.is_allowed()', 'EXECUTE')
from pg_roles r where r.rolname in ('anon', 'authenticated', 'service_role');
```

**P. Policies**

```sql
select tablename, policyname, cmd, roles, qual, with_check
from pg_policies
where schemaname = 'fitness'
order by tablename, policyname;
```

Expected: no `cmd = 'ALL'`; `roles = {authenticated}` everywhere; every `qual` and `with_check` contains `fitness_private.is_allowed()` wrapped in a sub-select and, on user-owned tables, `( SELECT auth.uid() AS uid)` (the printed form of the wrapped call, lint 0003); policy count per table equals its class template (USER and CACHE 3, MIX 3, APPEND 2, REF 1, `challenge_definitions` 1, `user_algo_params` 1); no `using (true)` (lint 0024).

**I. Foreign-key indexes**

```sql
-- Expected: zero rows (every FK has an index whose leading columns equal the FK columns in order).
select c.conrelid::regclass as tbl, c.conname, c.conkey
from pg_constraint c
join pg_namespace n on n.oid = c.connamespace
where c.contype = 'f' and n.nspname = 'fitness'
  and not exists (
    select 1 from pg_index i
    where i.indrelid = c.conrelid
      and (string_to_array(i.indkey::text, ' ')::int2[])[1:cardinality(c.conkey)] = c.conkey
  );
```

**V. Review views**

```sql
-- Expected: zero rows (every view is security_invoker).
select c.relname, c.reloptions
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'fitness_review' and c.relkind = 'v'
  and not coalesce(c.reloptions @> array['security_invoker=true'], false);

-- Expected: zero rows (no grants on review views to anyone but the owner).
select table_name, grantee, privilege_type
from information_schema.role_table_grants
where table_schema = 'fitness_review' and grantee <> 'postgres';
```

**X. Shared-project invariants** (each compared with the value recorded in 17.1)

| Check | Query | Expected |
|-------|-------|----------|
| Triggers on `auth.users` | `select count(*) from pg_trigger where tgrelid = 'auth.users'::regclass and not tgisinternal` | 1 (`on_auth_user_created`, chess) |
| Objects in `public` | `select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public'` and the same for `pg_proc` | Unchanged |
| Role settings | `select rolname, rolconfig from pg_roles where rolname in ('authenticator', 'authenticated', 'anon')` | Unchanged (`safeupdate`, statement and lock timeouts) |
| Default privileges | `select count(*) from pg_default_acl d left join pg_namespace n on n.oid = d.defaclnamespace where d.defaclnamespace = 0 or n.nspname like 'fitness%'` | 0 |
| Cron jobs | `select count(*) from cron.job where jobname ilike '%fitness%'` | 0 |
| Migration history | `list_migrations` | Previous rows unchanged, one new `fitness_NNN_*` row |
| Extensions | `list_extensions` | Unchanged, except `pg_jsonschema` after migration 007 |

### 17.5 PGRST106 and anon probes (C25, F2)

The exposed-schema setting is changed only by the user in the dashboard (F2), from `public, graphql_public` to `public, graphql_public, fitness` (C25). The probes use the publishable key (read with the MCP `get_publishable_keys`, held in an environment variable, never committed) and no user token. Claude never signs in as the user to probe; requests with the owner's token are made by the app (17.9).

```bash
BASE="https://nuvjdvpqibqeyafzgaye.supabase.co/rest/v1"
# P1: a schema that does not exist; the hint lists the exposed schemas.
curl -s -o - -w '\n%{http_code}\n' "$BASE/probe?select=id&limit=1" \
     -H "apikey: $SUPABASE_PUBLISHABLE_KEY" -H "Accept-Profile: fitness_probe_schema"
# P2: the fitness schema as anon.
curl -s -o - -w '\n%{http_code}\n' "$BASE/muscle_groups?select=id&limit=1" \
     -H "apikey: $SUPABASE_PUBLISHABLE_KEY" -H "Accept-Profile: fitness"
# P3: chess baseline, unchanged by fitness.
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/profiles?select=id&limit=1" \
     -H "apikey: $SUPABASE_PUBLISHABLE_KEY"
```

| Probe | Before the user exposes `fitness` | After exposing |
|-------|-----------------------------------|----------------|
| P1 | HTTP 406, `code: PGRST106`, hint "Only the following schemas are exposed: public, graphql_public" | HTTP 406, `PGRST106`, hint lists `public, graphql_public, fitness` |
| P2 | HTTP 406, `PGRST106` (same hint) | HTTP 401, `code: 42501` (permission denied for schema `fitness`: `anon` has no `USAGE`) |
| P3 | HTTP 401 (chess tables grant nothing to `anon`) | HTTP 401, unchanged |

If the hint does not list `fitness` a few minutes after the dashboard change (known propagation issue supabase/supabase#45904), the user re-saves the setting; the SQL override is never used (F2). P1-P3 run before and after every migration as well, because a failing probe after a migration usually means the PostgREST schema cache did not reload (it reloads automatically through the `pgrst_ddl_watch` event trigger, section 2). P2 returning 200 would mean `anon` gained access: stop immediately.

### 17.6 RLS tests with two allowlisted users and one non-allowlisted user

The tests of 19.3 run as one forced-rollback block (19.2) with three actors:

| Actor | Allowlisted | Local Docker stack (preferred when available) | Live shared project |
|-------|-------------|-----------------------------------------------|---------------------|
| A, owner | yes (`owner`) | fixture user `fixture-user:owner` (19.6) | the real owner from `allowed_users` (the user's own id, read into a variable, never printed) |
| B, second allowlisted user | yes (`member`, inserted inside the rolled-back transaction) | fixture user `fixture-user:member` (19.6), created in the local stack's own `auth.users` by the test setup | **not run** (see below) |
| C, outsider | no | fixture user `fixture-user:outsider` | a synthetic UUID (`gen_random_uuid()`) that exists only in the JWT claims of the rolled-back transaction (an outsider never owns a row, and RLS rejects its writes before any foreign-key check, so no `auth.users` row is needed) |

C61: RLS tests use synthetic UUIDs inside rolled-back transactions and never the real id of another user of the shared project. On the live project a synthetic B cannot be allowlisted or own rows, because `allowed_users.user_id` and every `user_id` column reference `auth.users`, and the live `auth.users` is never written (inserting a user would also fire the chess trigger `on_auth_user_created`, section 2; CLAUDE.md allows only the three fitness schemas). The live run therefore covers the actors A and C: every outsider, grant, append-only, trigger, LWW and CHECK test. The tests whose actor column in 19.3 or 19.5 names B (isolation between two allowlisted users and cross-user composite foreign keys: RLS-02 to RLS-06, RLS-16 to RLS-18, the B part of RLS-22, RLS-23, FK-01, FK-05, FK-06) run only on a local Docker stack (`supabase start`, `supabase db reset` with the fitness migrations only), which is the preferred environment whenever Docker is available (open issue 14).

Pass: the block ends with the exception message `FITNESS_DB_TESTS_PASSED <n> (<env>)` where `<env>` is `live` or `local` and `<n>` equals the number of test ids in 19.3-19.5 and 19.9 that apply to the migrations applied so far and to that environment (the live run skips the B tests). Any other error message is a failure and names the failing test id.

### 17.7 safeupdate implications

- The `authenticator` role preloads `safeupdate` (section 2): an `UPDATE` or `DELETE` without a `WHERE` clause sent through PostgREST fails (SQLSTATE `21000`, "UPDATE requires a WHERE clause").
- The app never sends `PATCH` or `DELETE`: every write is a `POST` upsert (`INSERT ... ON CONFLICT`), which safeupdate does not intercept (11.3). A unit test in `:data` (`NoUnfilteredWriteTest`, 21) asserts that the Postgrest client wrapper exposes only `upsert` and filtered `select`.
- If a future feature needs a `PATCH`, it must carry an `id=eq.` filter; the verification adds a smoke test of that call.
- Sessions of `postgres` through the MCP (pruning 15.3, review writes to `user_algo_params`, erasure 16.6) are not affected by safeupdate, but by convention every maintenance `UPDATE`/`DELETE` still has a `WHERE user_id = ...` clause and runs inside an explicit transaction with a row-count check before `commit`.
- A migration must never change `authenticator`'s `session_preload_libraries` (17.4 item X).

### 17.8 Data migrations

For seed migrations (005, 009, 016): row counts per table equal the counts the seed generator printed; `algo_params.params_hash`, `catalog_releases.jcs_sha256` and `challenge_definitions.jcs_sha256` equal the generator's values; re-running the migration changes no `updated_at` (the `is distinct from` guard, 7.10); for 014, `server_checksum` is set and an `update` of `definition` fails with `55000`.

### 17.9 App smoke test

After the user exposes the schema, and after any migration that changes a table the app uses, the user opens the debug screen and taps "Server check". The app (with the owner's own session) pulls every reference table, upserts its `profiles` row with an unchanged payload, reads it back, and reports pass or the first error code (PGRST106, PGRST204/205, 42501). Claude Code reads the result from the `session_events` row (`sync_run` with `trigger = 'server_check'`) or from the user.

---

## 18. Constants register (data layer)

This register lists every numeric constant that this document introduces or relies on. Algorithm constants (SSE-1 noise, RIR tables, readiness tau, warning thresholds) are **not** listed here: they live in the versioned params JSON under `<module>.<constant>` keys (9.1.10) and are specified by the ALGORITHMS documents. Data-layer constants live in one of four places:

- **SQL**: CHECK constraints, triggers and view SQL (changed only by a migration);
- **PlatformConfig**: the `:app` object owned by ANDROID.md section 25 (C54). Timer, notification, reminder, calendar-window and sync-backoff constants live there, outside the params JSON and `params_hash`, and are logged in event payloads (`platform_config_logged`, 9.8.1). ANDROID.md 25 is authoritative for their values; the rows below that name a PlatformConfig key only mirror it;
- **SyncConfig**: a Kotlin object in `:data` (`data/sync/SyncConfig.kt`, with `RetentionConfig`) for the data-layer constants that ANDROID.md 25 does not own (clock-skew warning, bootstrap windows, local retention); not in the params JSON because no registry module owns sync and because sync must work before any params are loaded;
- **Profile**: user settings in `fitness.profiles` (the user changes them);
- **Review**: the review procedure in this document (sections 14, 15, 17).

| Constant | Default | Bounds | Unit | Lives in | Grade | Source | Tunable / tuned by |
|----------|---------|--------|------|----------|-------|--------|--------------------|
| Pull overlap (`OVERLAP`) | 120 | fixed | s | PlatformConfig `sync.overlapS` | canonical | C23; `now()` is the transaction start | Not tunable (C23); `sync_conflict` and duplicate-pull counts in `v_sync_health` would show a problem |
| Pull page size (`PAGE`) | 500 | <= 1000 | rows | PlatformConfig `sync.pullPageRows` | engineering-fact (bound) | PostgREST `max_rows` 1000 | Tunable: `sync_run.duration_ms` |
| Push batch size | 200 | 50-500 | rows | PlatformConfig `sync.pushBatchRows` | expert-opinion | research; PostgREST statement timeout 8 s | Tunable: 413 frequency, `duration_ms` |
| Backoff initial delay | 30 | 10-300 | s | PlatformConfig `sync.backoffInitialS` (WorkManager exponential, capped by WorkManager at 5 h) | assumption | ANDROID.md 9.4 | Tunable: time to drain after outages |
| Push debounce after a local write | 3 | 0-30 | s | PlatformConfig `sync.debounceS` | assumption | ANDROID.md 9.3 (replaces the earlier 30 s foreground push interval) | Tunable: runs per session, upload latency (`v_upload_latency`) |
| Periodic background sync | 6 | 1-24 | h | PlatformConfig `sync.periodicH` | assumption | ANDROID.md 9.3 | Tunable: `sync_run` triggers |
| FK-retry attempts before dead letter | 5 | 1-10 | attempts | PlatformConfig `sync.fkRetryAttempts` | assumption | ANDROID.md 9.5 | Tunable: `sync_dead_letter` with `pg_code 23503` |
| Server-unreachable banner after | 24 | 6-72 | h | PlatformConfig `sync.unreachableBannerH` | assumption | F4 pause risk | Tunable: user feedback |
| Read-only (25006) retry interval | 6 | 1-24 | h | PlatformConfig `sync.periodicH` | assumption | 15.2, ANDROID.md 9.5 | Not tuned (rare event) |
| Clock-skew warning | 10 | 1-60 | min | SyncConfig | expert-opinion | 11.12 | Tunable: `v_upload_latency.min_s` |
| `client_updated_at` monotonic step | 1 | fixed | ms | SyncConfig | engineering-fact | 6.2 | Not tunable |
| Local tombstone purge | 30 | 7-365 | days | SyncConfig (`RetentionConfig`) | expert-opinion | 11.7 | Tunable: undelete frequency |
| Bootstrap pull window, `recommendations` | 90 | 30-730 | days | SyncConfig | expert-opinion | 11.6 | Tunable: what estimators need after reinstall |
| Bootstrap pull window, `session_events` | 365 | 90-730 | days | SyncConfig | expert-opinion | 11.6 | Tunable: rebuild needs of UI-07/UI-10 state |
| Calendar read window around the Monday of the current ISO week | -7 / +14 | 0-14 / 14-28 | days | PlatformConfig `calendar.readWindowBeforeDays` / `calendar.readWindowAfterDays` | expert-opinion | research S8, ANDROID.md 16.4 | Tunable: link misses for late-logged sessions |
| Local calendar cache purge (`calendar_instance`) | 60 | fixed | days | ANDROID.md 8.3 | expert-opinion | 12.3 | Not tuned |
| Local snapshot/telemetry retention | 365 | 180-3650 | days | RetentionConfig | expert-opinion | 12.5 | Tunable: phone storage, export cadence |
| `recommendations.inputs` hard cap / engine target | 65 536 / 16 384 | fixed / 4-64 KB | bytes | SQL / engine | engineering choice (expert-opinion) | 9.5.2, research S2 | Target tunable: `inputs_bytes` distribution |
| `outputs`, `trace` cap | 65 536 | fixed | bytes | SQL | expert-opinion | 9.5.2 | Raise by migration if `23514` appears |
| `session_events.payload` cap | 8 192 | fixed | bytes | SQL | expert-opinion | 9.8.1 | Same |
| `challenge_events.payload`, enrollment `state` caps | 65 536 | fixed | bytes | SQL | expert-opinion | 9.4.2, 9.7.1 | Same |
| Telemetry string value limit | 64 | fixed | chars | Client validator, DQ13 | expert-opinion | 16.3 | Not tuned (privacy rule) |
| Calendar feature code pattern length | 60 | fixed | chars | Client validator, DQ17 | expert-opinion | 16.2 | Not tuned |
| Effective-load CHECK tolerance | 0.01 | fixed | kg | SQL | engineering-fact | C1, C22 (one rounding step) | Not tunable (open issue 1) |
| lb to kg | 0.45359237 | exact | kg/lb | engine `load` | engineering-fact | definition of the pound | Not tunable |
| Assisted machine offset | 6.5 | 0-15 | kg | `gym_equipment.load_scheme` | weak (user estimate "6-7 kg") | C2, F7 | Tunable per machine: bathroom-scale check, first-set residuals after pin changes (`v_set_estimates.residual`) |
| Assisted machine ratio / step | 1.0 / 5 | 0.5-2.0 / fixed | - / lb | `gym_equipment.load_scheme` | expert-opinion / engineering-fact | C2, F7 | Ratio tunable as above |
| Belt `max_kg` / `max_items` | 60 / 4 | 20-100 / 1-8 | kg / items | `gym_equipment.load_scheme` | expert-opinion | 9.3.3 | Set by the user |
| Calendar link window | +/- 3 | fixed | h | engine `calendar.classify` | expert-opinion | C35 | Decided (C35); `v_calendar_events.link_method` shows misses |
| Bodyweight staleness | 14 | fixed | days | engine `bodyweight` | expert-opinion | C32 | Decided (C32); DQ16 counts stale sessions |
| Calendar write horizon | 2 | 1-4 | ISO weeks | Profile `calendar_write_weeks` | user decision | D11 | User setting |
| Max gym visits per week | 4 | 1-7 | visits | Profile | user decision | D5 | User setting |
| Default session length / changeover | 75 / 5 | 20-240 / 0-60 | min | Profile `default_session_min` / `changeover_min` | default | F11 as revised by C49 (budget = calendar event duration - 5 min; without an event 75 min) | User setting |
| Export reminder | 30 | 7-90 | days | ANDROID.md 25.3 `export.reminderDays` | default | F4 | User setting |
| Pre-migration dumps kept | 10 | 3-50 | files | Review | expert-opinion | 15.4 | Disk space |
| Server retention before P1/P2 pruning | 730 | 365-3650 | days | Review | expert-opinion | research S2/S7 | Review replay depth, size pressure |
| Size guard levels | 350 / 425 / 150 / 15 per 30 d | see 15.2 | MB | Review, `v_db_size_summary` | expert-opinion | 15.2 | `v_db_size_summary` history |
| Free-plan read-only limit | 500 | fixed | MB | platform | engineering-fact | Supabase docs | n/a |
| Stale in-progress session (DQ08) | 12 | 4-48 | h | View SQL | expert-opinion | 14.20 | False-positive rate in reviews |
| Set outside session window (DQ14) | 1 | 0.25-6 | h | View SQL | expert-opinion | 14.20 | Same |
| Prescription-error bucket | 28 | 7-56 | days | Review query | expert-opinion | research S6 | Review needs |
| Exact-idle alarm gate | p95 <= 2 after at least 30 logged rests | fixed / 10-100 | s / rests | PlatformConfig `timer.exactIdleP95MaxMs`, `timer.exactIdleMinSamples` (ANDROID.md 25.1); checked in reviews with `v_rest_alarm_latency` | canonical / assumption | C28, C54 | Decided (C28, C54) |
| Allowed `unused_index` period | 8 | fixed | weeks | Review (17.3) | canonical | C60 | Decided (C60) |

---

## 19. Golden fixtures and database tests

### 19.1 Layers and environments

| Layer | Runs where | What | When |
|-------|-----------|------|------|
| SQL tests (19.2-19.5, 19.9) | Preferred: local Supabase Docker stack (`supabase start`, fitness migrations only). Otherwise: the live project inside one forced-rollback block (19.2), as `postgres` through the MCP, with the actors A and C only (synthetic UUIDs, C61; 17.6). | RLS and grants, LWW triggers, composite foreign keys, CHECKs, view sentinels | After every migration (17.6); before the first app install; after every view migration |
| Privilege checks (17.4) | Live project | Catalog queries, no data | After every migration |
| Kotlin `:data` tests (19.10) | JVM (Robolectric for Room) | Validators mirroring every CHECK, DTO round trips, LWW comparator, pull overlap, export determinism, privacy rules, the same fixtures | Every build |
| Fixture files | `fixtures/db/real-week-2026-W41.json`, `fixtures/db/dst-2026-10-25.json` | One source for SQL inserts (generated by `:sim` `fixturegen`) and Kotlin tests | Shared |

pgTAP is not installed on the shared project and is not added for tests (no extension changes beyond F5). The tests use PL/pgSQL `assert` and expected-exception blocks, which gives the same "plan, assert, report" structure.

### 19.2 Forced-rollback harness

The whole suite is one `do` block that **always ends by raising an exception**, so nothing can commit even if the caller runs in autocommit mode. Success is the specific message `FITNESS_DB_TESTS_PASSED <n> (<env>)` (17.6); any other message is a failure that names the test id.

```sql
-- supabase/tests/fitness_db_tests.sql  (run as postgres; session_user must be postgres)
do $tests$
declare
  v_a     uuid;                                                        -- actor A, owner
  v_b     constant uuid := '2c87d2df-3ae7-5148-b047-860b3515151d';     -- actor B, fixture member (19.6); local stack only
  v_c     uuid := gen_random_uuid();                                   -- actor C, synthetic outsider (claims only, C61)
  v_dev   constant uuid := '00000000-0000-4000-8000-0000000000d1';     -- fixture device id
  v_sess  constant uuid := '01a11c9e-5160-7000-8000-000000000001';     -- fixture session A (19.6)
  v_local boolean;                                                     -- true on the local Docker stack
  v_n     int;
  v_pass  int := 0;
begin
  -- Actors (17.6). On the local stack these are the fixture users of 19.6.
  select a.user_id into strict v_a from fitness_private.allowed_users a where a.role = 'owner';
  -- B exists only where the local test setup created the fixture member in the local auth.users; that
  -- runner sets "set fitness_tests.env = 'local'" before the block. The live run leaves it unset, so the
  -- block never reads or writes auth.users on the shared project (C61).
  v_local := coalesce(current_setting('fitness_tests.env', true), 'live') = 'local';
  if v_local then
    insert into fitness_private.allowed_users (user_id, role, note) values (v_b, 'member', 'db-test, rolled back');
  end if;

  -- Fixture rows, inserted as postgres (RLS bypassed); the full set is generated from 19.7.
  insert into fitness.workout_sessions
    (id, user_id, client_updated_at, device_id, app_version, kind, status, template_source,
     local_date, timezone, iso_week, engine_version, params_hash, catalog_version, started_at, finished_at)
  values
    (v_sess, v_a, '2026-10-08T19:05:00Z', v_dev, '1.0.0+1', 'gym', 'finished', 'empty',
     '2026-10-08', 'Europe/Amsterdam', '2026-W41', '1.0.0', repeat('0', 64), '1.0.0',
     '2026-10-08T17:50:00Z', '2026-10-08T19:05:00Z');

  if v_local then                             -- tests whose actor is B (17.6)
    -- RLS-02: B does not see A's session.
    perform set_config('request.jwt.claims', json_build_object('sub', v_b, 'role', 'authenticated')::text, true);
    set local role authenticated;
    select count(*) into v_n from fitness.workout_sessions where id = v_sess;
    assert v_n = 0, 'RLS-02 FAILED: member sees the owner''s session';
    v_pass := v_pass + 1;

    -- RLS-03: B cannot insert a row owned by A.
    begin
      insert into fitness.workout_sessions
        (id, user_id, client_updated_at, device_id, app_version, kind, status, template_source,
         local_date, timezone, iso_week, engine_version, params_hash, catalog_version)
      values
        (gen_random_uuid(), v_a, now(), v_dev, '1.0.0+1', 'gym', 'planned', 'empty',
         '2026-10-09', 'Europe/Amsterdam', '2026-W41', '1.0.0', repeat('0', 64), '1.0.0');
      raise exception 'RLS-03 FAILED: insert with a foreign user_id succeeded';
    exception when insufficient_privilege then
      v_pass := v_pass + 1;
    end;

    -- RLS-04: B's update of A's row affects nothing.
    update fitness.workout_sessions set status = 'abandoned', client_updated_at = now() + interval '1 day'
     where id = v_sess;
    get diagnostics v_n = row_count;
    assert v_n = 0, 'RLS-04 FAILED: member updated the owner''s row';
    v_pass := v_pass + 1;

    set local role postgres;                  -- back to the session user between actors
  end if;
  -- ... remaining tests of 19.3-19.5 and 19.9, each ending with v_pass := v_pass + 1;
  --     the B tests sit inside "if v_local then ... end if" blocks like the one above ...

  raise exception 'FITNESS_DB_TESTS_PASSED % (%)', v_pass, case when v_local then 'local' else 'live' end;
end
$tests$;
```

Conventions:

- Switching actors: `set_config('request.jwt.claims', '{"sub": ..., "role": "authenticated"}', true)` plus `set local role authenticated`; `set local role postgres` returns to the session user (allowed because `postgres` is the session user and a member of the API roles, section 2). `auth.uid()` reads these claims (section 2).
- Expected errors use a nested `begin ... exception when <condition> then` block. The `raise exception '... FAILED ...'` inside it has SQLSTATE `P0001`, which the handler does not catch, so an unexpected success fails the suite. Conditions: `insufficient_privilege` (42501, RLS and grants), `foreign_key_violation` (23503), `check_violation` (23514), `unique_violation` (23505), `object_not_in_prerequisite_state` (55000, definition guard).
- `now()` is constant inside the block (one transaction); tests compare `server_updated_at = now()` instead of "later than before".
- The anon test (RLS-10) needs `set local role anon`; if the session user is not a member of `anon` on the live project, RLS-10 runs only on the local stack and the live run covers it with probe P2 (17.5).
- The block contains only fixed UUIDs from 19.6 and synthetic UUIDs (C61). The only real id it reads is the owner's, into a variable that is never printed; no other user's id is ever read or used.

### 19.3 RLS and grant tests

| Id | Actor | Action | Expected |
|----|-------|--------|----------|
| RLS-01 | A | Select the fixture rows of every user table by id | All visible |
| RLS-02 | B | Select A's rows by id | 0 rows |
| RLS-03 | B | Insert a row with `user_id = A` | 42501 |
| RLS-04 | B | Update A's row by id | 0 rows affected; A's row unchanged |
| RLS-05 | B | Upsert (`insert ... on conflict (id) do update`) with the id of A's row | 42501 (the `DO UPDATE` branch fails the update policy, 7.8) |
| RLS-06 | B | Insert an own row (`user_id` defaulted from `auth.uid()`) | Success; then as A: 0 rows for that id |
| RLS-07 | C | Select `fitness.muscle_groups` | 0 rows (reference data is allowlist-only) |
| RLS-08 | C | Select every user table | 0 rows |
| RLS-09 | C | Insert an own `profiles` row | 42501 |
| RLS-10 | anon | Select `fitness.muscle_groups` | 42501 (no `USAGE` on the schema) |
| RLS-11 | A | Delete an own `set_logs` row | 42501 (no `DELETE` grant) |
| RLS-12 | A | Update an own `session_events` row | 42501 (no `UPDATE` grant on APPEND) |
| RLS-13 | A | Insert into `fitness.muscle_groups` | 42501 |
| RLS-14 | A | Insert into `fitness.user_algo_params` | 42501 (USER-RO) |
| RLS-15 | A | Insert or update `fitness.challenge_definitions` | 42501 |
| RLS-16 | B | Select a `draft` challenge definition owned by A (fixture row) | 0 rows |
| RLS-17 | A and B | Select global exercises; B selects A's custom exercise | Global rows visible to both; A's custom row invisible to B |
| RLS-18 | B | Insert `user_exercise_prefs` referencing A's custom exercise | 23503 (visibility trigger, 7.4) |
| RLS-19 | A | Select `fitness_private.allowed_users` | 42501 |
| RLS-20 | A, then C | `select fitness_private.is_allowed()` | A: true; C: false |
| RLS-21 | A | Select `fitness_review.v_set_facts` | 42501 (no `USAGE` on `fitness_review`) |
| RLS-22 | A | Update an own row with a newer tuple and `user_id = B` | Row keeps `user_id = A` (trigger resets immutable columns, 7.1); B still sees nothing |
| RLS-23 | B after `postgres` deletes B's allowlist row inside the block | Select B's own row from RLS-06 | 0 rows (the allowlist is evaluated on every statement) |

### 19.4 LWW and trigger tests

All run as A (`authenticated`), using the exact statement shape PostgREST generates for `Prefer: resolution=merge-duplicates`:

```sql
insert into fitness.muscle_goals as t (id, user_id, client_updated_at, device_id, app_version, muscle_group_id, goal, focus)
values ($1, $2, $3, $4, $5, $6, $7, $8)
on conflict (id) do update
  set client_updated_at = excluded.client_updated_at, device_id = excluded.device_id,
      app_version = excluded.app_version, muscle_group_id = excluded.muscle_group_id,
      goal = excluded.goal, focus = excluded.focus
returning t.id, t.client_updated_at, t.device_id, t.server_updated_at;
```

Base row: `muscle_goals` id `d33481ab-5f28-5337-9ebf-f42732cb9e01` (UUIDv5 `muscle_goals:<fixture owner>:quads`; on the live project the id is recomputed from the real owner id), goal `maintain`, `client_updated_at = 2026-10-07T10:00:00.000Z`, `device_id = 00000000-0000-4000-8000-0000000000d1`.

| Id | Action | Expected |
|----|--------|----------|
| L-01 | Insert with `server_updated_at = '2000-01-01'` in the payload | Stored `server_updated_at = now()` |
| L-02 | Upsert goal `improve`, `client_updated_at` + 1 ms, same device | Row updated; `returning` yields 1 row |
| L-03 | Upsert goal `skip`, `client_updated_at` - 1 ms | Row unchanged; `returning` yields 0 rows (`get diagnostics row_count = 0`) |
| L-04 | Equal `client_updated_at`, `device_id = 00000000-0000-4000-8000-0000000000d2` (greater) | Applied |
| L-05 | Equal `client_updated_at`, `device_id = 00000000-0000-4000-8000-0000000000d0` (smaller) | Dropped |
| L-06 | Re-push of the identical stored tuple | Dropped (0 rows), row unchanged (idempotent retry) |
| L-07 | Newer tuple that also sets another `user_id` and `created_at` | `user_id` and `created_at` keep their old values |
| L-08 | Equal `client_updated_at` with `SERVER_DEVICE_ID` (`ffffffff-ffff-ffff-ffff-ffffffffffff`) as `postgres` | Applied (wins every tie) |
| L-09 | Newer tuple with `deleted_at` set, then a newer tuple with `deleted_at = null` | Tombstoned, then undeleted (11.7) |
| L-10 | `challenge_enrollments`: `state_rev` 2 to 1 with a newer `client_updated_at` | Dropped (7.5) |
| L-11 | Same `state_rev`, older tuple; then same `state_rev`, newer tuple | Dropped; then applied |
| L-12 | Higher `state_rev` with an older `client_updated_at` | Applied (the revision dominates) |
| L-13 | APPEND: insert a duplicate `session_events` id with `on conflict do nothing` | 0 rows, no error |
| L-14 | APPEND: `postgres` updates a payload (pruning path, 15.3) | Allowed; `server_updated_at` unchanged |
| L-15 | REF: re-run a seed upsert with identical values; then with a changed label (as `postgres`) | `updated_at` unchanged; then `updated_at = now()` |
| L-16 | Definition guard: update `definition` of a published row; retire it; set `published` again | 55000; success with `retired_at` set; 55000 |
| L-17 | Definition checksum | `server_checksum = encode(sha256(convert_to(definition::text, 'UTF8')), 'hex')` |
| L-18 | Two writes in one transaction | Identical `server_updated_at` (documents why the 120 s pull overlap exists, 11.5) |

### 19.5 Composite foreign keys and CHECK constraints

| Id | Actor | Action | Expected |
|----|-------|--------|----------|
| FK-01 | B | Insert `session_blocks` with `session_id` of A's session | 23503 |
| FK-02 | A | Insert `session_exercises` whose block belongs to session S1 while `session_id = S2` | 23503 |
| FK-03 | A | Insert `set_logs` whose `exercise_id` differs from its session exercise's | 23503 |
| FK-04 | A | Change `session_exercises.exercise_id` while a set exists | 23503 (a change of exercise is a substitution, 7.9) |
| FK-05 | B | Insert `wellness_logs.set_log_id` of A's set | 23503 |
| FK-06 | B | Insert `calendar_events.linked_session_id` of A's session | 23503 |
| FK-07 | A | Insert `recommendations` with a `params_hash` not in `params_snapshots` | 23503 |
| FK-08 | A | Insert `challenge_enrollments` with a wrong `definition_jcs_sha256` | 23503 |
| FK-09 | A | Insert `wellness_logs` with symptom code `made_up` | 23503 (7.7) |
| FK-10 | postgres | Delete a `workout_sessions` row whose set is referenced by a `wellness_logs` row | 23503 (documents the erase order of 16.6) |
| CK-01 | A | Weighted pull-up set C: `bodyweight_plus_external`, BW 70.70, `f_bw` 1.0000, added 20.00, effective 90.70 | Accepted |
| CK-02 | A | Same with effective 90.72 | 23514 (tolerance 0.01 kg) |
| CK-03 | A | Assisted one-arm set, pin 40 lb, `assist_kg` 24.64, effective 46.06 | Accepted (C2: 40 x 0.45359237 x 1.0 + 6.5 = 24.6437, HALF_EVEN 24.64; 70.70 - 24.64) |
| CK-04 | A | `assisted_machine` without `equipment_id` | 23514 |
| CK-05 | A | `assisted_ordinal` with a non-null `effective_load_kg` | 23514 |
| CK-06 | A | `rir_reported = 1.3` | 23514 (half steps only) |
| CK-07 | A | Both `rir_reported` and `hold_reserve_s` set | 23514 |
| CK-08 | A | `deviation_reason_code = 'torso_rotation'` without the flag | 23514 |
| CK-09 | A | Second live set with the same `(session_exercise_id, set_index, side)` | 23505 |
| CK-10 | A | Second `in_progress` session | 23505 |
| CK-11 | A | `calendar_events` row with `classified_kind = 'ignored'` and a title | 23514 |
| CK-12 | A | `metric_observations.valid_for_gate = true` without `enrollment_id` | 23514 |
| CK-13 | A | Weighted negative set: `bodyweight_plus_external`, added 47.50, effective 118.20 | Accepted |
| CK-14 | A | 90-degree hold, pin 35 lb, `assist_kg` 22.38, effective 48.32, `duration_s` 6.0, `hold_reserve_s` 2.0 | Accepted |
| CK-15 | A | `profiles` row with `id <> user_id` | 23514 |
| CK-16 | A | Assisted set, pin 50 lb, `assist_kg` 29.18, effective 41.52 (session C pin) | Accepted |
| CK-17 | A | Assisted set, pin 40 lb, `assist_kg` 18.14 (no offset), effective 52.56 | Accepted by the database (the CHECK verifies arithmetic, not the calibration); caught by the Kotlin `LoadCalibrationFixtureTest`, which recomputes `assistKg()` with the stored calibration revision (C2: the offset must be applied) |

### 19.6 Fixture identifiers

All values are computed with `uuid_generate_v5(FITNESS_NS, name)` (equivalently Python `uuid.uuid5`) or the fixture UUIDv7 rule `FIX7(t, n)`: UUIDv7 with the Unix-millisecond timestamp of local time `t` (Europe/Amsterdam), `rand_a = 0` and `rand_b = n`. They are verified by `UuidV5FixtureTest` and `UuidV7FixtureTest` (19.10).

| Name | Rule | Value |
|------|------|-------|
| `FITNESS_NS` | `uuid_generate_v5('6ba7b811-9dad-11d1-80b4-00c04fd430c8', 'https://fitness-app.invalid/ns/v1')` | `2611a46b-2ad8-5006-88dc-e43030211f8f` |
| Fixture owner | v5 `fixture-user:owner` | `63750a4b-f9b8-5269-a927-91957167fe62` |
| Fixture member | v5 `fixture-user:member` | `2c87d2df-3ae7-5148-b047-860b3515151d` |
| Fixture outsider | v5 `fixture-user:outsider` | `22499716-1c0e-538f-b5db-120450ae2d61` |
| OAP definition 1.0.0 | v5 `challenge_definition:oap@1.0.0` | `b2afe9c6-816e-51a4-9cdb-8a529fd54615` |
| Exercise `chinup_weighted` (the catalog slug that the OAP definition key `weighted_pullup` resolves to with the grip parameter `supinated`, C38; `weighted_pullup` itself has no catalog row and no id) | v5 `exercise:chinup_weighted` | `1bf2755f-6a6e-5998-9327-79648dda4205` |
| Exercise `weighted_negative_pullup` | v5 | `a4da7574-a867-594d-8ab1-b7c704973ee3` |
| Exercise `oap_assisted_machine` | v5 | `bb7197a7-c5d4-57f7-b2b6-80c6e3f28947` |
| Exercise `oa_hold90_machine` | v5 | `51162cb8-f880-59c1-8f80-6b16d68de934` |
| Exercise `oa_straight_arm_hold` | v5 | `22278c9c-201a-5e2a-b9e0-463b6e696b6f` |
| Exercise `oa_negative` | v5 | `5c4c444c-56f8-540a-b036-d47218bc502e` |
| Exercise `two_arm_dead_hang` | v5 | `637a638c-b2cb-5f42-a874-c7f0066cfbb1` |
| Fixture device | fixed | `00000000-0000-4000-8000-0000000000d1` |
| Fixture params hash | SHA-256 of the JCS text `{"fixture.constant":1}` | `4ccac5e674e1d05ff38a16474dcc390a7d015f499157bc143151430e158f1307` |
| Params snapshot | v5 `params_snapshots:<fixture owner>:<fixture params hash>` | `8774b870-0621-50a5-9747-19b95bc01cfd` |
| Muscle goal quads | v5 `muscle_goals:<fixture owner>:quads` | `d33481ab-5f28-5337-9ebf-f42732cb9e01` |
| Preference `oap_assisted_machine` | v5 `user_exercise_prefs:<fixture owner>:<exercise id>` | `b7b272d2-329c-5aab-81ee-b70602c71e64` |
| Gym (created 2026-10-07 11:00) | `FIX7(2026-10-07 11:00, 1)` | `01a11597-4e80-7000-8000-000000000001` |
| Equipment: assisted machine / dip belt | `FIX7(2026-10-07 11:00, 2 / 3)` | `01a11597-4e80-7000-8000-000000000002` / `...-000000000003` |
| Weigh-in 2026-10-07 08:00 | `FIX7(2026-10-07 08:00, 1)` | `01a114f2-8300-7000-8000-000000000001` |
| Enrollment (cutover 2026-10-07 12:00) | `FIX7(2026-10-07 12:00, 1)` | `01a115ce-3d00-7000-8000-000000000001` |
| Event `enrollment.created` | `FIX7(2026-10-07 12:00, 2)` | `01a115ce-3d00-7000-8000-000000000002` |
| Event `day.tick:2026-10-07` | v5 `challenge_events:<enrollment>:day.tick:2026-10-07` | `04be1ba1-431a-53a4-9ffa-a91036fefb5a` |
| Event `test.due:2026-10-11` | v5 `challenge_events:<enrollment>:test.due:2026-10-11` | `c6db4711-5fbf-5fb9-a175-bd04c18be990` |
| Event `week.closed:2026-W41` | v5 | `db91998c-c236-5cdb-b94b-5baa2c72ba07` |
| Event `day.tick:2026-10-25` (DST) | v5 | `3ea186d6-d068-5a95-9b67-de983d3da5d0` |
| Event `week.closed:2026-W43` (DST) | v5 | `59e9e220-fade-5afa-8190-a04d5713e1ef` |
| Imported test sessions 2026-08-30 / 09-19 / 09-29 (12:00) | `FIX7(<date> 12:00, 1)`; their observations `n = 2, 3, ...` | `01a0521c-9500-7000-8000-000000000001` / `01a0b91b-c500-7000-8000-000000000001` / `01a0ec9b-5d00-7000-8000-000000000001` |
| Session A (2026-10-08 19:45) and its children | `FIX7(2026-10-08 19:45, n)`, n = 1 session, 2 block, 3-6 session exercises, 7-11 sets | `01a11c9e-5160-7000-8000-000000000001` ... `...-00000000000b` |
| Session C (2026-10-06 19:45), TEST (2026-10-11 13:00) | `FIX7(..., 1)` | `01a11251-9960-7000-8000-000000000001`, `01a12a9e-9b80-7000-8000-000000000001` |
| Calendar instance keys | SHA-256 of `com.google\|fixture-account\|fixture-primary\|fixture-<slot>\|<begin ms>` | C: `8d7b7c4e437545c1b5670b54cbbd76caf40df822a92b9696d00b32a42a37d340`; A: `ee0b6d17fedc1a8f42131ef4cb7632945afd140f8adb11454efdfdd23f959503`; TEST: `8df4fb1a63f686511bba7d9b6b6acbb9cb101a299ad49b208234715b209f4231` |
| Calendar event ids | v5 `calendar_events:<fixture owner>:<instance key>` | C: `f6c896a7-405e-5e1e-a640-076d9d996a8a`; A: `eb963157-0875-5edf-a5cb-2e2aa476a569`; TEST: `9af28a75-33da-55a0-a3e8-8d3a597e9e41` |

Begin instants (milliseconds since the epoch): C 2026-10-06 19:45 CEST = `1791308700000` (17:45Z); A 2026-10-08 19:45 CEST = `1791481500000` (17:45Z); TEST 2026-10-11 13:00 CEST = `1791716400000` (11:00Z). On the live project, every id that embeds the owner (v5 names containing `<fixture owner>`) is recomputed from the real owner id at run time.

### 19.7 Real-week fixture (`fixtures/db/real-week-2026-W41.json`)

Context: today is Wednesday 2026-10-07 (ISO week 2026-W41, Monday 2026-10-05 to Sunday 2026-10-11). The OAP challenge is in its deload week, block 1 week 5; the calendar holds C on Tuesday 2026-10-06 19:45, A on Thursday 2026-10-08 19:45 and the test day on Sunday 2026-10-11 13:00 (DECISIONS section 4). Values marked *(assumption)* are fixture choices, not facts from the user's program (open issue 18); values marked *(illustrative)* exercise the constraints and are not the challenge engine's materialisation. Imported values are a subset of OAP_CHALLENGE.md 11.1 and 11.2 (the owner of the import values) and must be confirmed at the D4 cutover (open issue 21). The OAP grip parameter is `supinated` *(assumption, matching the supinated test-day 3RM)*, so the definition key `weighted_pullup` resolves to the catalog slug `chinup_weighted`.

**Configuration**

| Row | Key values |
|-----|------------|
| `profiles` (id = user_id = fixture owner) | `timezone` Europe/Amsterdam, `mass_unit` kg, `ui_language` en, `decimal_separator` `.`, `max_gym_visits_per_week` 4, `default_session_min` 75, `changeover_min` 5, `calendar_write_weeks` 2, `evidence_mode` warn_only, `rest_alarm_mode` alarm_clock, `keep_screen_on` true, `default_gym_id` = gym |
| `gyms` | "Basic-Fit Ierse Pond", `is_default` true, `location_keywords` {basic-fit, ierse pond}, opening hours Mon-Fri 07:00-22:30, Sat-Sun 09:00-16:00 |
| `gym_equipment` assisted machine | `equipment_type` assisted_pullup_machine, `scheme_type` pin_assist, `load_scheme` `{"type":"pin_assist","unit":"lb","step":5,"min":0,"max":null,"offset_kg":6.5,"ratio":1.0}`, `calibration_rev` 1, `calibration_source` user_estimate |
| `gym_equipment` dip belt (portable, `gym_id` null) | `scheme_type` belt, `load_scheme` `{"type":"belt","unit":"kg","plates":[{"kg":20,"count":2},{"kg":10,"count":1},{"kg":5,"count":1},{"kg":2.5,"count":2}],"max_kg":60,"max_items":4,"dumbbells_on_belt":false}` *(assumption: plate inventory; it makes 20, 22.5, 27.5 and 47.5 kg loadable)* |
| `metric_observations` weigh-in | `bw` 70.70 kg, observed 2026-10-07 08:00 local (06:00Z), source manual *(assumption: date)*; BW(t) EWMA of a single weigh-in = 70.70 (C32) |
| `params_snapshots` | fixture params hash (19.6), `params_version` 1.0.0, `params` `{"fixture.constant":1}` |

**Challenge state (D4 cutover import, 2026-10-07)**

| Row | Key values |
|-----|------------|
| `challenge_definitions` | OAP 1.0.0, `status` published (inserted by the fixture loader as `postgres`) |
| `challenge_enrollments` | id `01a115ce-...-0001`, `status` active, `mode` test_pending, `phase` 1, `block_index` 1, `block_week` 5, `started_on` 2026-10-07 *(assumption: the in-app start; earlier history is imported)*, `state_rev` 2, `state` vars `{wpuC: 20, wpuA: 22.5, wpuB: 27.5, negB: 47.5, negReset: 45, pinA: 40, pinC: 50, pinHold90: 35, plankS: 20, oaHangS: 10, e1rmAdded: 39.38, e1rmAddedM110: 42.82, kgPerRep: 2.1667, anchorFraction: 0.8239, needsCleanSession: true, plannedTestDate: "2026-10-11"}` (OAP_CHALLENGE.md 11.1: the gate e1RM with the personal curve and the class prior k = 30, C59, display only; the x1.10 value for transparency, D7) |
| `challenge_events` | `enrollment.created` at 2026-10-07T10:00:00Z with the import payload; `day.tick:2026-10-07` (dedupe key, v5 id) |

**Imported test history** (each test belongs to an imported `kind = 'test'`, `template_source = 'import'`, `status = 'finished'` session; validity flags exactly as imported)

| Session (local date, ISO week) | Observation | Value | `valid_for_gate` | Other |
|--------------------------------|-------------|-------|------------------|-------|
| 2026-08-30 (2026-W35) | `bw_max_reps` | 18 reps | true | hang values of that day are not imported in the fixture ("old protocol, not comparable") |
| 2026-09-19 (2026-W38) | `wpu_3rm_added` | 32.50 kg | false | `validity_note` "3RM only", `bodyweight_kg` 70.70 |
| 2026-09-19 | `wpu_e1rm_added` | 39.38 kg | false | `derivation` calculated:personal_curve, `derived_from_ids` = the 3RM row, `calc_params` `{"method":"personal_curve","k":30,"m3":32.50,"bw_kg":70.70,"alternatives":{"multiplier3rm":42.82}}` (D7, C59: class prior k = 30; (70.70 + 32.50) x (1 + 2/30) - 70.70 = 39.38), `source` import |
| 2026-09-19 | `wpu_e1rm_added_m110` | 42.82 kg | false | display only (D7), `derivation` calculated:multiplier3rm, `derived_from_ids` = the 3RM row, `calc_params` `{"method":"multiplier3rm","m3":32.50,"multiplier":1.10,"bw_kg":70.70}`; (70.70 + 32.50) x 1.10 - 70.70 = 42.82 (the program recorded it as 42.8) |
| 2026-09-29 (2026-W40) | `oa_hang_s` L / R | 15.0 s / 20.0 s | false | `validity_note` "not a test day", `protocol_code` `oa_hang_neutral_rotation_stop_v1` |

**Calendar** (`calendar_events`, all three `classified_kind` challenge_session with slots C, A and TEST, `provider` android_cp, `classifier_version` 1.0.0, `match_method` title_pattern, `app_written` false, `marker` null, `write_state` none)

| Event | Title *(assumption)* | `starts_at` (UTC) | `ends_at` *(assumption)* | `local_date` / `iso_week` | Slot | `attention` | Link |
|-------|----------------------|-------------------|--------------------------|---------------------------|------|-------------|------|
| C | "sessie C" | 2026-10-06T17:45:00Z | +90 min | 2026-10-06 / 2026-W41 | C | `unmarked_needs_adopt` | none (before cutover) |
| A | "sessie A" | 2026-10-08T17:45:00Z | +90 min | 2026-10-08 / 2026-W41 | A | `unmarked_needs_adopt` | session A, `auto_window` (scenario) |
| TEST | "testdag" | 2026-10-11T11:00:00Z | +120 min | 2026-10-11 / 2026-W41 | TEST | `unmarked_needs_adopt` | none (future) |

The three events were created by hand before the app existed, so they carry no marker and need the one-time "adopt" (D11). A calendar gym event is training time (travel happens before it), so the 90-minute event gives an 85-minute time budget (F11 as revised by C49: event duration minus the 5-minute changeover).

**Session A scenario** ("after A has been logged on Thursday"; *(illustrative)* set values)

| Row | Key values |
|-----|------------|
| `workout_sessions` `...-0001` | `kind` gym, `status` finished, `template_source` challenge_slot, `challenge_slot` A, `enrollment_id` fixture enrollment, `block_index` 1, `block_week` 5, `enrollment_mode` test_pending, `is_deload` true (F8), `local_date` 2026-10-08, `iso_week` 2026-W41, `planned_start_at` 17:45Z, `started_at` 17:50Z, `finished_at` 19:05Z, `bodyweight_kg` 70.70 (`ewma`), `time_budget_min` 85 (`calendar`), `engine_version` 1.0.0, `params_hash` fixture hash, `catalog_version` 1.0.0 |
| `session_blocks` `...-0002` | `block_kind` challenge, `origin` challenge_template, `challenge_template_id` A1 *(assumption)*, `position` 0 |
| `session_exercises` | `...-0003` wpu (exercise `chinup_weighted`, the slug of the definition key `weighted_pullup`; `bodyweight_plus_external`, `progression_mode` rule); `...-0004` oaa (`oap_assisted_machine`, `assisted_machine`, `equipment_id` assisted machine, `side_mode` unilateral_sequential, `side_order` LR); `...-0005` h90 (`oa_hold90_machine`, `assisted_machine`, hold); `...-0006` cal (`oa_negative`, `bodyweight`, eccentric) |
| `set_logs` `...-0007` | wpu set 1 `both`: BW 70.70, `f_bw` 1.0000, `input_value` 22.50 kg, `added_kg` 22.50, `effective_load_kg` 93.20, `reps` 4, `rir_reported` 2.0 (`chip`), `rest_timer_planned_s` 180 (after the specific warm-up, whose "Done" rows are not part of the fixture), `rest_adjust_s` 0, `rest_timer_overridden` false, `rest_before_s` 182.4, `rest_source` estimated, `rest_end_reason` elapsed |
| `set_logs` `...-0008` / `...-0009` | oaa set 1 `L` / `R`: `assist_pin_native` 40, `assist_unit` lb, `assist_kg` 24.64, `effective_load_kg` 46.06, `equipment_calibration_rev` 1, `reps` 3, `rir_reported` 1.0 |
| `set_logs` `...-000a` | h90 set 1 `L`: pin 35 lb, `assist_kg` 22.38, `effective_load_kg` 48.32, `duration_s` 6.0, `hold_reserve_s` 2.0 (the "Hard" chip, D9) |
| `set_logs` `...-000b` | cal set 1 `L`: `bodyweight`, `effective_load_kg` 70.70, `reps` 1, `ecc_s_target` 5.0, `ecc_s_actual` 5.4, `rir_reported` null, `hold_reserve_s` 5.0 (effort chip OK; C66: eccentric-only sets carry an effort value, never an RIR) |

Other loads of the user's program appear as enrollment variables and in constraint tests: C anchor 20 kg (CK-01, effective 90.70), B 27.5 kg (effective 98.20), negatives 47.5 kg (CK-13, effective 118.20), C pin 50 lb (CK-16, effective 41.52).

**Planned test session**: `workout_sessions` `01a12a9e-...-0001`, `kind` test, `status` planned, `challenge_slot` TEST, `planned_start_at` 2026-10-11T11:00:00Z, `local_date` 2026-10-11, `iso_week` 2026-W41.

**Expected results on this fixture** (before the sentinel rows of 19.9 are added)

| Check | Expected |
|-------|----------|
| `v_set_facts` for the owner | 5 rows (the A sets) |
| `v_adherence_week` 2026-W41 | `planned_events` 3, `linked_events` 1, `gym_done` 1, `challenge_done` 1, `unplanned_done` 0, `recommended_n` null (no `weekplan.plan` snapshot in the fixture); `missed_events` 1 (C: ended, unlinked) when the suite runs before 2026-10-11 15:00 local, 2 afterwards (TEST has no result in the fixture). The view uses `now()`, so the test asserts the value for its own run time. |
| `v_challenge_tests` | 6 rows (18 reps, 32.50 kg, 39.38 kg with `calc_k` 30, 42.82 kg with `calc_multiplier` 1.10, 15.0 s, 20.0 s); only the 2026-08-30 row has `valid_for_gate`; the weigh-in is not included |
| `v_data_quality` | No rows: every dynamic work set has effort, the eccentric and hold sets are not dynamic, imported test sessions are exempt from DQ07, every session's params hash has a snapshot, the enrollment's `state_rev` (2) equals its event count (2), and all module versions are registered |
| `v_calendar_events` | 3 rows; `effective_kind` challenge_session and `effective_slot` C, A and TEST; `has_marker` false; `attention` unmarked_needs_adopt on all three |

### 19.8 DST fixture (`fixtures/db/dst-2026-10-25.json`)

Europe/Amsterdam leaves summer time on Sunday 2026-10-25: 03:00 CEST becomes 02:00 CET at 01:00Z, so local 02:00-03:00 happens twice, the day has 25 hours and ISO week 2026-W43 (Monday 2026-10-19 00:00 CEST = 2026-10-18T22:00Z to Monday 2026-10-26 00:00 CET = 2026-10-25T23:00Z) lasts 169 hours (F18, C31).

| Row | Values | Expected |
|-----|--------|----------|
| `challenge_events` `day.tick:2026-10-25` | `occurred_at` 2026-10-24T22:00:00.002Z (local 00:00:00.002 CEST, 9.7.1), `local_date` 2026-10-25 | Unique by dedupe key; a second device inserting the same tick (same v5 id) is ignored (L-13 style) |
| `session_events` `rest_alarm_fired` #1 | `occurred_at` 2026-10-25T00:30:00Z, `tz_offset_min` 120, `latency_ms` 800 | Local 02:30 CEST, week 2026-W43 |
| `session_events` `rest_alarm_fired` #2 | `occurred_at` 2026-10-25T01:30:00Z, `tz_offset_min` 60, `latency_ms` 1200 | Local 02:30 CET, week 2026-W43; the two rows are distinct and ordered by `occurred_at`. Both carry the same `alarm_mode` (`alarm_clock`), `app_state` (`fgs_background`), `screen_interactive` (false) and `platform_config_hash` |
| `workout_sessions` (home habit) | `local_date` 2026-10-25, `started_at` 2026-10-25T16:15:00Z (17:15 CET), `iso_week` 2026-W43 | Grouped in W43 |
| `challenge_events` `week.closed:2026-W43` | `occurred_at` 2026-10-26T03:00:00Z (Monday 04:00 CET, `challenge.enrollment.week_close_local_time`; CHALLENGE_ENGINE 4.3, GF-CE-12), `local_date` 2026-10-26 | Dedupe key names the closed week; a session finished on Sunday evening after midnight still folds before it |
| `challenge_events` `week.started:2026-W44` | `occurred_at` 2026-10-26T03:00:00.001Z, `local_date` 2026-10-26 | Strictly after `week.closed:2026-W43` |
| `workout_sessions` | `local_date` 2026-10-26, `iso_week` 2026-W44 | Next week |

`v_rest_alarm_latency` for W43 must return n = 2 with p50 1000 ms, and `v_recommendation_acceptance` and `v_calendar_classification` must bucket events from the repeated hour into W43. The CHECK `tz_offset_min between -840 and 840` accepts both offsets.

### 19.9 View sentinel tests

The fixture loader inserts one `estimation.sse` snapshot for session A whose `outputs.sets[]` entry for the wpu set has deliberately implausible values: `e1rm_kg` 123.45, `e1rm_added_kg` 52.75, `k_used` 31.7, `q_true` 2.5. The tests assert:

| Id | Assertion | Proves |
|----|-----------|--------|
| V-01 | `v_set_estimates` returns exactly 123.45, 52.75, 31.7 and `q_minus_reported` 0.5 for that set | The view reads the logged values and recomputes nothing (C3, R-V4) |
| V-02 | `v_e1rm_history` for 2026-10-08 returns `best_set_e1rm_kg` 123.45 and `k_used_last` 31.7 | Same, through aggregation |
| V-03 | A second, newer `estimation.sse` snapshot listing the same set with `e1rm_kg` 124.00 makes `v_set_estimates` return 124.00 | Newest-snapshot rule (R-V6) |
| V-04 | Tombstoning the set removes it from `v_set_facts`, `v_set_estimates` and `v_e1rm_history` | Live rows only (R-V5) |
| V-05 | An `accounting.week` snapshot for 2026-W41 with `groups.lats.vhyp_done` 7.25 and a `targets.weekly` snapshot with `groups.lats.target` 0 and `covered_by_challenge` true appear unchanged in `v_weekly_muscle_volume` | No set-credit recomputation (C6) |
| V-06 | A `session_events` row with payload `{"note":"felt bad today"}` produces a DQ13 finding; a row with only codes does not | Privacy scan (16.3) |
| V-07 | A `recommendations` row with module version `9.9.9` produces DQ01 | Release rule (9.1.9) |
| V-08 | `v_data_quality` selects no free-text column; `v_set_facts` has no `deviation_note` column (`information_schema.columns`) | R-V7 |
| V-09 | Exactly the 26 views of the 14.3 catalogue exist in `fitness_review`, nothing else | Migration 017 complete (14.24) |
| V-10 | Every review view has `security_invoker = true` | R-V1, 17.4 item V |
| V-11 | No ACL entry for anyone but the owner on any review view; no API role can read one | R-V2, 17.4 item V |
| V-12 | `user_id` is the first column of every user-scoped view; the two size views have none | R-V3 |
| V-13 | Every view answers over all rows the project holds | R-V8 (catches runtime casts and joins) |
| V-14 | `v_db_size` lists `fitness.set_logs` and at least 38 tables; `v_db_size_summary` returns one valid row | 13.2 check of migration 017 |
| V-15 | `v_set_facts` returns the sentinel session's sets on 2026-10-25 (the DST day) with their context | Base fact view |
| V-16 | `v_session_summary` returns the sentinel session (actual 65.0 min, plan estimate 42.5 min, 2 sets, 1 work set, sRPE 7) | Session outcome view |
| V-17 | `v_exercise_time` returns the sentinel session exercise | TIME-1 comparison view |
| V-18 | `v_rest_alarm_latency` aggregates two alarms in the repeated hour of 2026-10-25 into one 2026-W43 row (n 2, p95 1180, max 1200) | Local-time bucketing across DST (C31) |
| V-19 | `v_data_quality` reports exactly one finding for the sentinel session (DQ06 on its warm-up set without effective load) | Data-quality view on real shapes |
| V-20 | Tombstoning the sentinel session removes it from the views | R-V5 at session level |

V-09 to V-20 were added with migration 017 (M1b, 2026-10-11). Their fixtures use only fixture ids, a fixture-only `platform_config_hash` and module version `9.9.9`, so the owner's real rows on the live project never change a result. On the live project the file must run after migration 017 (V-09 fails on purpose otherwise).

### 19.10 Kotlin tests that share the fixtures

| Test | Module | Checks |
|------|--------|--------|
| `UuidV5FixtureTest`, `UuidV7FixtureTest` | `:data` | Every value of 19.6 |
| `RealWeekFixtureValidatorTest` | `:data` | Every fixture row passes the Kotlin validators that mirror section 9's CHECKs; every negative case of 19.5 (CK-*) fails the same validator with the same constraint name |
| `LoadCalibrationFixtureTest` | `:algo` + `:data` | `assistKg()` with the fixture calibration gives 24.64 (40 lb), 29.18 (50 lb), 22.38 (35 lb); effective loads 46.06, 41.52, 48.32, 90.70, 93.20, 98.20, 118.20 |
| `LwwComparatorParityTest` | `:data` | The local `applyPulled` comparator gives the same winner as the SQL trigger for every case L-01 to L-12 |
| `PullOverlapTest` | `:data` | Rows committed out of order within the 120 s window are pulled exactly once into Room (idempotent apply) |
| `DstFixtureLocalDateTest` | `:algo` (`clock`) + `:data` | Local dates, ISO weeks and `tz_offset_min` of 19.8 |
| `ExportFormatDeterminismTest` | `:data` | Exporting the real-week fixture twice produces byte-identical JSON before compression; the row counts equal the `exports.table_row_counts` |
| `TelemetryPayloadPrivacyTest` | `:data` | Every event type in the registry rejects free text (16.3) |
| `CalendarPrivacyTest` | `:data` | Busy and unclassified instances never enter the outbox; classifier features match the code pattern (16.2) |
| `ServerReadOnlyErrorTest` | `:data` | A 25006 response stops pushing, keeps the outbox and raises the storage banner (15.2) |
| `RoomMigrationTest` | `:data` | No destructive fallback (11.10) |

---

## 20. Open issues

None of these is decided silently in this document. Each names what it blocks and a proposal; the user (or the owner of the named document) decides, and DECISIONS.md is updated first when a decision results. Items closed by the 2026-10-07 decisions (D13-D16, F21-F27, C37-C65) are kept for traceability and marked "Resolved by ...".

| # | Status | Issue | Blocks | Proposal |
|---|--------|-------|--------|----------|
| 1 | Open | **Effective-load CHECK tolerance.** The `set_logs` CHECK (9.6.4) accepts a 0.01 kg difference between `effective_load_kg` and `bodyweight_kg x f_bw + implement_base_kg + added_kg - assist_kg`, assuming the LOAD spec rounds each stored value once, HALF_EVEN, to 0.01 kg (C22), and computes from the same snapshot columns. If the LOAD spec rounds the system load to an equipment grid, or uses an unrounded BW(t), the CHECK rejects valid rows. PostgreSQL's `round()` is half-away-from-zero, so the server must never recompute. | Migration 013 | Keep 0.01 kg; the ALGORITHMS `load` spec confirms the rounding order; `RealWeekFixtureValidatorTest` and CK-01..CK-17 lock it. |
| 2 | Resolved by C44 | **Region count.** 34 regions plus the optional `neck` (35 seed rows) supersede C9's "about 32". | — | — |
| 3 | Resolved by C38 | **OAP definition vocabulary.** The definition now uses the section 8 codes (snake_case E6 flags, E3 load modes, E36 patterns, E55 hold classes, the metric codes of 9.1.4), and migration 009's slug list is the union of its 15 catalog slugs and 14 challenge-local slugs (13.2); the fixtures use `chinup_weighted` (19.6, 19.7). | — | — |
| 4 | Resolved by C64 | **Numeric pain score.** Every pain chip writes `pain_score` (mild 2, moderate 5, sharp 8; stop threshold 4), so the column is required for `kind = 'pain'` (9.6.5). | — | — |
| 5 | Resolved by ALGORITHMS_PLANNING 23 | **Soreness mapping.** `readiness.soreness_points` = `{never_sore: 0, healed: 1, still_sore: 3}` (owner: ALGORITHMS_PLANNING; E19 references it). | — | — |
| 6 | Resolved by C40 | **Room schema and paths.** ANDROID.md section 8 owns the Room schema (local table names, epoch-ms timestamps with `server_updated_at` as text, read-at-push outbox); section 12 summarises it; the bundled params live at `algo/src/main/resources/fitness/params/` (9.1.10, 13.2). | — | — |
| 7 | Resolved by F26 | **Approval policy for `user_algo_params`.** The user approves a review's change list as one batch, each change listed with its reason; every row of the batch carries the same `approved_at` and `review_ref` (9.3.7). | — | — |
| 8 | Resolved by C40 | **Export container.** One `.json.gz` file for every kind; `exports.format` allows `json_gz` and `json` (9.8.3, 15.5). UX.md 15.10 still says "JSON zip" (issue 27). | — | — |
| 9 | Resolved by C39 | **Telemetry event names.** 9.8.1 is the canonical merged registry (UX.md 20, ANDROID.md 7.10, ALGORITHMS A8); views 14.12 and 14.14 use its names. The names other documents still print are issue 27. | — | — |
| 10 | Resolved by C58 | **Snapshot keys for reviews.** 10.2 lists the output keys of every module, including the `sse` one-step-ahead keys, the ALGORITHMS D5 keys of hold, stall, rcm, fatigue, autoreg, warmup and rest, and the keys of safety, rotate and time. v1 still ships views only for the kinds section 14 uses (14.24). | — | — |
| 11 | Resolved (ANDROID.md 9.5) | **Server read-only error.** SQLSTATE `25006` is in 11.8 and in ANDROID.md 9.5 with the behaviour of 15.2. | — | — |
| 12 | Resolved by D17 | **Full re-push action.** Restore scenario R1 (15.4) needs a "re-upload everything" action. ANDROID OI-25 proposes a user-triggered "Re-push all data" in Sync status and defers automatic detection; UX.md has no entry point yet. | Disaster recovery | Adopt ANDROID's user-triggered action for v1; add the automatic offer (a pull returns zero rows for a table with synced local rows) later. |
| 13 | Open | **Restore from an export file** is a later milestone (ANDROID.md 20.4). Until then scenario R3 (phone and server both lost) depends on a Claude-run `:sim` `ExportRestore` tool. | R3 | Accept until M6; the user may move it earlier. |
| 14 | Resolved by C61; Docker part resolved 2026-10-08 (Docker Desktop installed, DECISIONS changelog) | **Actors for RLS tests.** Synthetic UUIDs only, never another user's real id; the live run covers A and C, and the tests that need a second allowlisted user B run only on a local Docker stack (17.6, 19.2). Still open: Docker availability on this machine is not verified, so until it is available the B tests do not run. | 17.6 B tests | Install Docker Desktop (user decision) before the first app install, or accept the reduced live suite until then. |
| 15 | Open | **Fitness-only erasure.** There is no user-facing "erase my server data"; 16.6 is a script the user runs, because deleting the auth user would delete the chess account too. | Privacy UX | Keep the script for v1; add an app action only if a second user joins (D3). |
| 16 | Open | **Database CHECK for classifier feature codes.** The code pattern of `calendar_events.features` (16.2) is enforced by the client and detected by DQ17, not by a CHECK (array elements need a trigger or an `array_to_string` regex). | Privacy defence in depth | Add `check (array_to_string(features, ' ') ~ '^([a-z0-9_.:]{1,60}( \|$))*$')` in a later migration. |
| 17 | Resolved by D16 / F21 | **Android Auto Backup** is off (15.4 L5, 12.5). | — | — |
| 18 | Open | **Fixture assumptions.** Event end times (+90/+120 min, giving the 85-minute budget of F11/C49), event titles, the gym location, the belt plate inventory, the weigh-in date, the enrollment `started_on`, the template id `A1`, the OAP grip parameter `supinated` and the illustrative rest values in 19.7 are fixture choices. | Fixture realism, not correctness | Replace with real values at the D4 cutover. |
| 19 | Open | **Paused-project restore window.** The research notes a long restore window for paused Free projects; the figure is not verified. | R4 | Verify in the dashboard when it matters. |
| 20 | Open | **No automatic size guard.** Without a cron job (persistent configuration in the shared chess project) and without an RPC (the `fitness` schema has none by design), the size guard runs only during reviews and migrations. If reviews lapse while chess grows, the 500 MB read-only limit could be reached unnoticed. | F4 size guard | Accept: the phone is authoritative, read-only mode loses nothing (15.2), and every review and migration checks the size. Alternatives (a read-only RPC or a pg_cron job) need a DECISIONS change. |
| 21 | Open | **Imported test values.** The fixture is a subset of OAP_CHALLENGE.md 11.1-11.2 (18 reps on 2026-08-30; 3RM +32.5 kg on 2026-09-19 with the personal-curve value +39.38 kg and the x1.10 value +42.82 kg, C59; hang L 15 s / R 20 s on 2026-09-29). The other 2026-08-30 values are not in the fixture. | D4 cutover import | Confirm every value and validity flag against the artifact at cutover (owner: OAP_CHALLENGE.md 11). |
| 22 | Resolved by C60 | **Advisor exceptions.** INFO `unused_index` on new fitness indexes during their first 8 weeks and a security-definer lint on `fitness_private.is_allowed()` are allowed; everything else must be zero (17.3). | — | — |
| 23 | Resolved by C40 (ANDROID.md 8.3) | **Outbox edits during a push.** The read-at-push outbox needs a guard so that an edit made while a batch is in flight is not lost; ANDROID.md 8.3 deletes an entry only if its `(seq, gen)` is unchanged (11.2). | — | — |
| 24 | Resolved (ANDROID.md 10.4) | **Access check without an RPC.** ANDROID.md 10.4 uses the read probe on `muscle_groups` of 5.4; no function is added to `fitness`. | — | — |
| 25 | Resolved by C37 (ANDROID.md 16.5) | **Marker uuid sub-pattern.** ANDROID.md 16.5 now uses the version-agnostic uuid pattern shared with the 9.7.3 CHECK. | — | — |
| 26 | Resolved by C67 | **Incremental pull of append-only tables.** ANDROID.md 9.6 now pulls them incrementally per 11.6. | — | — |
| 27 | Open (new) | **Other documents still print earlier names for items this document owns.** UX.md 20 (and its 20.13 table) still uses `time_overrun_shown`, `summary_shown`, `addon_block_skipped`, `double_tap_dropped`, `count_aloud_toggled`, `ecc_pacing_started`, `use_suggested_tapped`, `use_mine_anyway_toggled`, `rule_suggestion_*`, `p_rule_viewed` / `p_rule_decided` (`item_id`, `accept` / `decline`), `cutover_confirmed`, `calendars_selected`, `custom_exercise_created`, `alternatives_opened`, `data_exported` and `review_bundle_exported`, and UX.md 20.11 shows `rules.approval_changed {item_id, decision: accept}`. ANDROID.md 7.10 still uses `session_idle_prompt`, `alarm_capability {can_schedule_exact}` and `app_crash {app_version}`, and ANDROID.md 20.4 includes every local-only table in a full export, while 15.5 excludes `calendar_instance` and `exercise_fts`. UX.md 15.10 says "JSON zip". | UI and platform code must emit the registry names (the migration 005 seed is unaffected because the registry wins) | UX.md and ANDROID.md use the 9.8.1 names and payload keys, the E57 decision values, the `proposal_id` key, the 15.5 export content and the C40 container (C38, C39, C40, C48). |
| 28 | Resolved by C72 | **`instance_key` definition.** The full 64-hex key of 9.7.3 everywhere; UX.md 20 references it. | — | — |
| 29 | Resolved by C66 | **Meaning of `set_logs.ecc_s_actual`.** The shortest lowering of the set; FitExpr `ex.<key>.ecc_s` reads it. | — | — |
| 30 | Open (new) | **Per-exercise goal kind.** ALGORITHMS OI-A10: RX-1 needs strength versus hypertrophy per exercise; the data model has `rep_preferences.goal_kind` only for the `goal` scope. | RX-1 defaults, WE-P5, rest classes | Keep the ALGORITHMS interim derivation (strength for RULE-mode challenge items and for an exercise-scope preference with `rep_scheme = fixed` and `rep_max <= 6`) without a new column in v1; add a column only if the user wants to set it explicitly. |
| 31 | Resolved (ANDROID.md 16.6) | **`calendar_link.state` to `write_state` / `attention`.** ANDROID.md 16.6 references the 9.7.3 mapping table and closed its OI-26. | — | — |

---

## 21. Implementation notes for Claude Code

**Files** (package root `dev.fitnessapp`, ANDROID.md section 4):

```
supabase/
  migrations/pending/fitness_NNN_<name>.sql        # written by Claude, reviewed by the user (13.1)
  migrations/<version>_fitness_NNN_<name>.sql      # renamed after apply_migration
  migrations/notes/<version>_fitness_NNN.md        # results of the section 17 checklist
  review/queries/*.sql                             # reusable review queries with a :owner_id placeholder
  tests/fitness_db_tests.sql                       # 19.2-19.5 and 19.9 (forced rollback; the local runner sets fitness_tests.env = 'local')
  tests/privilege_checks.sql                       # 17.4 items T, S, F, P, I, V, X
  tests/probe.sh                                   # 17.5 P1-P3; reads SUPABASE_PUBLISHABLE_KEY from the environment
  snapshots/fitness.types.ts                       # `supabase gen types` output for the DTO drift test
fixtures/db/real-week-2026-W41.json                # 19.7
fixtures/db/dst-2026-10-25.json                    # 19.8
sim/src/main/kotlin/dev/fitnessapp/sim/seed/SeedGen.kt        # 13.3 data migrations
sim/src/main/kotlin/dev/fitnessapp/sim/seed/FixtureGen.kt     # SQL inserts from fixtures/db
sim/src/main/kotlin/dev/fitnessapp/sim/seed/MigrationLint.kt  # 17.1 item 6 and the 17.4 T matrix
sim/src/main/kotlin/dev/fitnessapp/sim/restore/ExportRestore.kt  # R3 (open issue 13)
data/src/main/kotlin/dev/fitnessapp/data/sync/      # SyncConfig (data-layer constants of section 18), Outbox, PushEngine, PullEngine, ErrorClassifier;
                                                    #   timer, calendar and sync-backoff constants come from :app PlatformConfig (ANDROID.md 25, C54)
data/src/main/kotlin/dev/fitnessapp/data/export/    # ExportWriter, ExportFormat (15.5)
data/src/main/kotlin/dev/fitnessapp/data/validate/  # one validator per entity, mirroring section 9 CHECKs
data/src/main/kotlin/dev/fitnessapp/data/privacy/   # TelemetryPayloadValidator, CalendarFeatureValidator
```

**Order of work.** M0: migrations 001-007, then 17.1-17.5, then the user exposes the schema, then the P1-P3 probes again. M1: 008-015, the full section 17 checklist after each, `fitness_db_tests.sql` from 006 on, then 017 (views) with the sentinel tests. M2: 016 once the final OAP definition passes the CDS validator with the section 8 domains (C38). The Room v1 schema follows ANDROID.md section 8 (C40); migration 005 seeds the 9.8.1 registry (C39) and migration 015 creates `exports` with `json_gz` (C40). Before UI or platform code emits telemetry, check open issue 27 so that only registry names are used.

**Test names.** SQL: `RLS-01..23`, `L-01..18`, `FK-01..10`, `CK-01..17`, `V-01..08` inside `fitness_db_tests.sql`. Kotlin: `UuidV5FixtureTest`, `UuidV7FixtureTest`, `RealWeekFixtureValidatorTest`, `LoadCalibrationFixtureTest`, `LwwComparatorParityTest`, `PullOverlapTest`, `DstFixtureLocalDateTest`, `ExportFormatDeterminismTest`, `TelemetryPayloadPrivacyTest`, `CalendarPrivacyTest`, `ServerReadOnlyErrorTest`, `NoUnfilteredWriteTest`, `RoomMigrationTest`, `DtoSchemaDriftTest` (DTO fields versus `snapshots/fitness.types.ts`).

**Never** (shared chess project, F2, F3): `supabase db push`, `supabase config push`, `supabase migration repair`, `alter role authenticator set pgrst.db_schemas`, triggers on `auth.users`, writes to `auth.users` (also not inside rolled-back tests, C61), objects in `public`, global `alter default privileges`, cron jobs, grants to `anon`, using or printing other users' ids or data, committing an email address, a secret key, an export or a dump. A local `supabase/config.toml` created for the Docker test stack is never pushed.

**Review session routine.** Resolve the owner by the user's own email (14.2); run `v_db_size_summary` and `v_data_quality`; then the module views relevant to the review question; write `docs/reviews/<date>.md` with aggregates only (16.5); propose the `user_algo_params` changes as one list, each change with its reason, and write them only after the user has approved that list as a batch (F26; 9.3.7). Never apply parameter or definition changes without that approval (CLAUDE.md).
