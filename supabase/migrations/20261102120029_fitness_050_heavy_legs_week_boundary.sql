-- fitness_050_heavy_legs_week_boundary (M4.5, data): H6 and H1 across the week boundary - DECISIONS D74;
-- ALGORITHMS_PLANNING 8.2, 8.5
--
-- No DDL, no user rows. Registers `weekplan@1.1.0` and `today@1.2.0` (ENGINE_VERSION 1.15.0): the previous ISO week's
-- logged general sessions seed H6 (heavy-lower sessions at least HEAVY_LOWER_MIN_H apart) and H1's pairwise gap check.
-- constants_hash = the `weekplan` and `today` slices of default params 1.0.3 (ParamsHashes.slices), equal to the
-- earlier rows; no params change.
-- Manual rollback (only with explicit user approval): delete both rows before any recommendation references them.

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'weekplan', '1.1.0', layer, '1.15.0', 'active', '1.0.3', '0ab0826bc8d4205a168413d923d7c3c520c43ac0b4739421d5ab4fe4727f1794',
       'docs/ALGORITHMS_PLANNING.md section 8', 'D74: H6 and H1''s pairwise gap check also see the previous ISO week''s logged general sessions.'
  from fitness.algorithm_versions
 where module_id = 'weekplan' and version = '1.0.0'
on conflict (module_id, version) do nothing;

insert into fitness.algorithm_versions (module_id, version, layer, engine_version, status, default_params_version, constants_hash, spec_ref, description)
select 'today', '1.2.0', layer, '1.15.0', 'active', '1.0.3', 'cf9d37fc4e94c01b6f0a5c3cf60acf7b4fe2c21ef7fbe2b6c74a5b610e8c9a2e',
       'docs/ALGORITHMS_PLANNING.md section 9', 'D74: options that break H6 against the previous week''s sessions are excluded (HEAVY_LOWER_GAP).'
  from fitness.algorithm_versions
 where module_id = 'today' and version = '1.0.0'
on conflict (module_id, version) do nothing;

do $$
begin
  if (select constants_hash from fitness.algorithm_versions where module_id = 'weekplan' and version = '1.1.0')
     <> '0ab0826bc8d4205a168413d923d7c3c520c43ac0b4739421d5ab4fe4727f1794' then
    raise exception '050: weekplan@1.1.0 missing or with another constants_hash';
  end if;
  if (select constants_hash from fitness.algorithm_versions where module_id = 'today' and version = '1.2.0')
     <> 'cf9d37fc4e94c01b6f0a5c3cf60acf7b4fe2c21ef7fbe2b6c74a5b610e8c9a2e' then
    raise exception '050: today@1.2.0 missing or with another constants_hash';
  end if;
  if (select count(*) from fitness.algorithm_versions where engine_version = '1.15.0') <> 2 then
    raise exception '050: expected 2 module versions for engine 1.15.0';
  end if;
end;
$$;
