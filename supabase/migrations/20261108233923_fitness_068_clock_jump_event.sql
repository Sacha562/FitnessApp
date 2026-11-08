-- fitness_068_clock_jump_event (reference data): the session clock guard - DECISIONS D108; ANDROID 12.8;
-- DATA_MODEL 9.8.1
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Registers `clock_jump_detected` (lifecycle, payload `wall_ms`, `trusted_ms`, `offset_ms`): during a session the
-- device wall clock moved more than `clock.jumpToleranceS` away from the elapsed-time anchor, so the app keeps
-- writing the anchored time until the session ends.
-- Manual rollback (only with explicit user approval): set `active = false` on the row.

insert into fitness.session_event_types (event_type, category, payload_keys, introduced_in, description, active, sort)
values ('clock_jump_detected', 'lifecycle', array['wall_ms', 'trusted_ms', 'offset_ms']::text[], '1.0.0',
        'Emitted by ANDROID 12.9 (the session clock guard, D108)', true, 1620)
on conflict (event_type) do nothing;

do $$
begin
  if not exists (select 1 from fitness.session_event_types
                  where event_type = 'clock_jump_detected'
                    and payload_keys = array['wall_ms', 'trusted_ms', 'offset_ms']::text[] and active) then
    raise exception '068: clock_jump_detected missing';
  end if;
end;
$$;
