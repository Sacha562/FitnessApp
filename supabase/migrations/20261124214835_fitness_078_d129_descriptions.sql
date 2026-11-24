-- fitness_078_d129_descriptions (data): DECISIONS D129
--
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- D129 grew before any phone ran ENGINE_VERSION 1.30.0: RX-1 also plans later sets under the range at a lighter load
-- (trace rx.backoff) and DOUBLE climbs on the top-load sets of a mixed exposure (rx.double.top_sets); the rest refit
-- also brings a back-off set back up to the top load. Only the descriptions of rx@1.2.0 and autoreg@1.4.0 change.

update fitness.algorithm_versions
   set description = 'D129: later sets under the range are planned lighter; DOUBLE climbs on its top-load sets and never advances from a mixed exposure.'
 where module_id = 'rx' and version = '1.2.0';

update fitness.algorithm_versions
   set description = 'D129: the rest refit goes lighter when even r_lo falls under the aim, and brings a back-off set back up to the top load.'
 where module_id = 'autoreg' and version = '1.4.0';

do $$
begin
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.30.0' and description like 'D129:%') <> 2 then
    raise exception '078: expected 2 D129 module rows for engine 1.30.0';
  end if;
end;
$$;
