-- M0 RLS / grant / LWW behaviour test (DATA_MODEL.md 17.6, 19.3, 19.4; C61). Runs entirely inside a
-- transaction that is rolled back; nothing persists. Run through the Supabase MCP execute_sql.
-- The non-allowlisted user is a synthetic UUID; the owner is the allowlisted owner.
-- Expected (2026-10-08, after migrations 001-004, 006, 007):
--   stranger_sees_groups 0 | stranger_insert rejected 42501 | owner_sees_groups >= 1
--   after_stale <null>     | after_newer newer              | owner_writes_ref rejected 42501
--   owner_delete rejected 42501 | anon_read rejected 42501

begin;
create temp table rls_results (k text, v text) on commit drop;
grant all on rls_results to authenticated, anon;
insert into fitness.muscle_groups (id, family, label_en) values ('rlstest_group', 'other', 'RLS test')
on conflict (id) do nothing;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-0000000000aa","role":"authenticated"}', true);
insert into rls_results select 'stranger_sees_groups', count(*)::text from fitness.muscle_groups;
do $$ begin
  insert into fitness.profiles (id, user_id, client_updated_at, device_id, app_version)
  values ('00000000-0000-4000-8000-0000000000aa', '00000000-0000-4000-8000-0000000000aa', now(), gen_random_uuid(), 'rlstest');
  insert into rls_results values ('stranger_insert', 'ALLOWED (bad)');
exception when others then insert into rls_results values ('stranger_insert', 'rejected ' || sqlstate); end $$;

select set_config('request.jwt.claims', '{"sub":"39fca078-e316-4932-9012-b4ae72ec79c2","role":"authenticated"}', true);
insert into rls_results select 'owner_sees_groups', count(*)::text from fitness.muscle_groups;
insert into fitness.profiles (id, user_id, client_updated_at, device_id, app_version)
values ('39fca078-e316-4932-9012-b4ae72ec79c2', '39fca078-e316-4932-9012-b4ae72ec79c2', '2026-10-08T12:00:00Z',
        'bbbbbbbb-0000-4000-8000-000000000000', 'rlstest')
on conflict (id) do nothing;
insert into fitness.profiles (id, user_id, client_updated_at, device_id, app_version, display_name)
values ('39fca078-e316-4932-9012-b4ae72ec79c2', '39fca078-e316-4932-9012-b4ae72ec79c2', '2026-10-08T11:00:00Z',
        'bbbbbbbb-0000-4000-8000-000000000000', 'rlstest', 'stale')
on conflict (id) do update set client_updated_at = excluded.client_updated_at, display_name = excluded.display_name;
insert into rls_results select 'after_stale', coalesce(display_name, '<null>') from fitness.profiles;
insert into fitness.profiles (id, user_id, client_updated_at, device_id, app_version, display_name)
values ('39fca078-e316-4932-9012-b4ae72ec79c2', '39fca078-e316-4932-9012-b4ae72ec79c2', '2099-10-08T13:00:00Z',
        'bbbbbbbb-0000-4000-8000-000000000000', 'rlstest', 'newer')
on conflict (id) do update set client_updated_at = excluded.client_updated_at, display_name = excluded.display_name;
insert into rls_results select 'after_newer', coalesce(display_name, '<null>') from fitness.profiles;
do $$ begin
  insert into fitness.muscle_groups (id, family, label_en) values ('rlstest_x', 'other', 'x');
  insert into rls_results values ('owner_writes_ref', 'ALLOWED (bad)');
exception when others then insert into rls_results values ('owner_writes_ref', 'rejected ' || sqlstate); end $$;
do $$ begin
  delete from fitness.profiles where true;
  insert into rls_results values ('owner_delete', 'ALLOWED (bad)');
exception when others then insert into rls_results values ('owner_delete', 'rejected ' || sqlstate); end $$;

set local role anon;
do $$ begin
  perform 1 from fitness.muscle_groups;
  insert into rls_results values ('anon_read', 'ALLOWED (bad)');
exception when others then insert into rls_results values ('anon_read', 'rejected ' || sqlstate); end $$;
reset role;

select * from rls_results;
rollback;
