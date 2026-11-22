-- fitness_072_dead_letter_resolved_event (reference data): dead letters clear once the server has the row -
-- DECISIONS D120; ANDROID 9.5; DATA_MODEL 9.8.1
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Registers `sync_dead_letter_resolved` (sync, payload `table`, `row_id`): after the pull, the phone found that the
-- server holds a dead-lettered row at the phone's version or a newer one, and removed the dead letter.
-- Manual rollback (only with explicit user approval): set `active = false` on the row.

insert into fitness.session_event_types (event_type, category, payload_keys, introduced_in, description, active, sort)
values ('sync_dead_letter_resolved', 'sync', array['table', 'row_id']::text[], '1.0.0',
        'Emitted by ANDROID 9.5 (a dead letter whose row the server holds, D120)', true, 1630)
on conflict (event_type) do nothing;

do $$
begin
  if not exists (select 1 from fitness.session_event_types
                  where event_type = 'sync_dead_letter_resolved'
                    and payload_keys = array['table', 'row_id']::text[] and active) then
    raise exception '072: sync_dead_letter_resolved missing';
  end if;
end;
$$;
