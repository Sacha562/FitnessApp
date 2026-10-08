-- fitness_001_schemas_and_roles (M0) — DATA_MODEL.md 4.1, 13.2
--
-- Creates the three fitness schemas in the shared project. Nothing in public, assistant or any chess
-- object is touched. No tables yet.
--
--   fitness          app tables; exposed to the Data API later by the user (F2, C25)
--   fitness_private  allowlist + helper and trigger functions; never exposed
--   fitness_review   analysis views for Claude Code reviews; never exposed, no grants
--
-- anon only inherits from PUBLIC, so revoking PUBLIC leaves anon with no USAGE anywhere in fitness.

create schema if not exists fitness;
create schema if not exists fitness_private;
create schema if not exists fitness_review;

revoke all on schema fitness from public;
revoke all on schema fitness_private from public;
revoke all on schema fitness_review from public;

-- fitness_private needs USAGE for authenticated so RLS policies can call fitness_private.is_allowed();
-- the schema is not exposed, so nothing in it is reachable over HTTP.
grant usage on schema fitness to authenticated, service_role;
grant usage on schema fitness_private to authenticated, service_role;

comment on schema fitness is
  'FitnessApp tables (exposed to the Data API). RLS: allowlist + owner. Never grant anything to anon.';
comment on schema fitness_private is
  'FitnessApp allowlist and helper/trigger functions. Not exposed to the Data API.';
comment on schema fitness_review is
  'FitnessApp security_invoker analysis views for reviews via MCP. Not exposed, no grants.';

-- Manual rollback (only with explicit user approval):
--   drop schema fitness_review; drop schema fitness_private; drop schema fitness;
