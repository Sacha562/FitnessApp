-- fitness_042_warnings_1_1_0_engine_1_10_0 (D61, data): warnings@1.1.0 of ENGINE_VERSION 1.10.0 - DATA_MODEL.md 9.1.9; D61
--
-- (1) warnings@1.1.0: WE-V1 (improve_weekly_low) is not raised while the week's DeloadPolicy is active. Its params
--     slice (`warnings.*`, 31 keys) is the same in params 1.0.0 to 1.0.3, so the row keeps the constants_hash of
--     warnings@1.0.0, read from that row.
-- Registered before the APK that uses it is installed (9.1.9 release rule).
-- No DDL, grant, policy, function, trigger, role or extension. Idempotent.
-- Manual rollback (only with explicit user approval): delete the warnings 1.1.0 row once no recommendation refers to it.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'warnings', '1.1.0', 'prescription', '1.10.0', 'active', '1.0.2', v.constants_hash,
       'docs/ALGORITHMS.md section B15', 'D61: WE-V1 is not raised in a deload week.'
  from fitness.algorithm_versions v
 where v.module_id = 'warnings' and v.version = '1.0.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.10.0') <> 1 then
    raise exception '042: expected 1 module version for engine 1.10.0';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'warnings' and version = '1.1.0')
     is distinct from '7349a6024559d60895054825f32a09eb926006af24686c568b1e6ad9fafc675f' then
    raise exception '042: warnings 1.1.0 constants_hash differs from the 1.0.0 slice hash';
  end if;
end;
$$;
