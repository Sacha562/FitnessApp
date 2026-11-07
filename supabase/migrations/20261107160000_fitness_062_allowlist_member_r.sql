-- fitness_062_allowlist_member_r (data) — DATA_MODEL.md 5.2, D3
--
-- Allowlists a second user (role member) at the owner's request (2026-11-07), after the app showed
-- "This account can't use the app yet" for r***@g***.com. The UUID was resolved with a read-only lookup of
-- that exact email in auth.users; no email address appears in the repository. Idempotent.

insert into fitness_private.allowed_users (user_id, role, note)
values ('222ce484-2b7a-465a-b3fd-af26032f4cd7', 'member', 'friend, added 2026-11-07')
on conflict (user_id) do nothing;

-- Manual rollback (only with explicit user approval):
--   delete from fitness_private.allowed_users where user_id = '222ce484-2b7a-465a-b3fd-af26032f4cd7';
