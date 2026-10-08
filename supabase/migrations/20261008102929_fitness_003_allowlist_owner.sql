-- fitness_003_allowlist_owner (M0, data) — DATA_MODEL.md 5.2, 13.2
--
-- Allowlists the owner. The UUID was resolved once with a read-only lookup of the owner's auth.users row
-- (2026-10-08); no email address appears in the repository. Idempotent.

insert into fitness_private.allowed_users (user_id, role, note)
values ('39fca078-e316-4932-9012-b4ae72ec79c2', 'owner', 'owner')
on conflict (user_id) do nothing;

-- Manual rollback (only with explicit user approval):
--   delete from fitness_private.allowed_users where user_id = '39fca078-e316-4932-9012-b4ae72ec79c2';
