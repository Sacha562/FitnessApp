-- fitness_056_warmup_switched_event (M4.5, reference data): the sticky warm-up switch - DECISIONS D81; UX 11.5;
-- DATA_MODEL 9.8.1
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Registers `warmup_switched` (logging, payload `on`): the live card's "Log working sets" / "Log a warm-up set" choice,
-- which the live screen reads back so it holds for the rest of the session.
-- Manual rollback (only with explicit user approval): set `active = false` on the row.

insert into fitness.session_event_types (event_type, category, payload_keys, introduced_in, description, active, sort)
values ('warmup_switched', 'logging', array['on']::text[], '1.0.0',
        'Emitted by UX 11.5 (the live card''s warm-up switch, kept for the session, D81)', true, 1535)
on conflict (event_type) do nothing;

do $$
begin
  if not exists (select 1 from fitness.session_event_types
                  where event_type = 'warmup_switched' and payload_keys = array['on']::text[] and active) then
    raise exception '056: warmup_switched missing';
  end if;
end;
$$;
