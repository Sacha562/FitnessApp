-- fitness_007_jsonschema_extension (M0) — DECISIONS.md F5 (approved by the user 2026-10-08), DATA_MODEL.md 13.2
--
-- Installs pg_jsonschema (0.3.3, available but not installed) in the existing `extensions` schema. It
-- only adds functions such as extensions.jsonb_matches_schema(); nothing in the chess schemas changes.
-- The CHECK constraint that uses it on fitness.challenge_definitions needs that table (M1) and the CDS
-- v1 JSON Schema (M2), so it ships later as fitness_018_jsonschema_constraint.

create extension if not exists pg_jsonschema with schema extensions;

-- Manual rollback (only with explicit user approval):
--   drop extension pg_jsonschema;
