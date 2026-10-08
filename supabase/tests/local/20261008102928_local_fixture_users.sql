-- LOCAL DOCKER STACK ONLY (DATA_MODEL 17.6, 19.6). Replaces fitness_003_allowlist_owner on the local test
-- stack: creates the three fixture users in the local auth.users and allowlists the fixture owner. Never
-- applied to the shared project (C61: the live auth.users is never written).
insert into auth.users (id, aud, role, email, created_at, updated_at)
values
  ('63750a4b-f9b8-5269-a927-91957167fe62', 'authenticated', 'authenticated', 'owner@fixture.invalid', now(), now()),
  ('2c87d2df-3ae7-5148-b047-860b3515151d', 'authenticated', 'authenticated', 'member@fixture.invalid', now(), now()),
  ('22499716-1c0e-538f-b5db-120450ae2d61', 'authenticated', 'authenticated', 'outsider@fixture.invalid', now(), now());
insert into fitness_private.allowed_users (user_id, role, note)
values ('63750a4b-f9b8-5269-a927-91957167fe62', 'owner', 'fixture owner (local stack)');
