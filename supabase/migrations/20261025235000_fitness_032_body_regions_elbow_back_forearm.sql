-- fitness_032_body_regions_elbow_back_forearm (M2b.6, data): two pain regions — DATA_MODEL.md 9.1.5, D45
--
-- D45: the Pain sheet had no place for pain at the back of the elbow or in the forearm (the 2026-10-23 report went to
-- `other`). `elbow_posterior` and `forearm` join fitness.body_regions with conservative INJ-1 joint keys: the back of
-- the elbow flags both elbow keys (like the crease), the forearm both elbow keys and the wrist. wellness_logs
-- .pain_body_region references this table, so the rows must exist before the app sends such a report. Equal to
-- algo/src/main/resources/fitness/reference/body_regions.json. Manual rollback (only with explicit user approval and
-- before any wellness_logs row uses them): delete the two rows.

insert into fitness.body_regions (code, joint_keys, side_applicable, label_en, label_nl, active, sort)
values
  ('elbow_posterior', array['elbow_medial', 'elbow_lateral']::text[], true, 'Elbow, back', 'Elleboog, achterkant', true, 32),
  ('forearm', array['elbow_medial', 'elbow_lateral', 'wrist']::text[], true, 'Forearm', 'Onderarm', true, 35)
on conflict (code) do nothing;

do $$
begin
  if (select count(*) from fitness.body_regions where code in ('elbow_posterior', 'forearm') and active) <> 2 then
    raise exception '032: expected the two new body regions';
  end if;
end;
$$;
